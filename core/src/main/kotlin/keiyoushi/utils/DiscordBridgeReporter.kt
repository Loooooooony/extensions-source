package keiyoushi.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Best-effort reporter that notifies a local Windows bridge when a chapter is opened,
 * so the bridge can update Discord Rich Presence.
 *
 * The report is fire-and-forget: it never blocks page loading and swallows every error.
 *
 * The bridge's current LAN address is read from bridge.json at this repo's root,
 * which the bridge itself rewrites via the GitHub API when its DHCP lease changes
 * (UDP broadcast discovery does not work on iOS). [BRIDGE_URL] is
 * only a last-resort fallback; [BRIDGE_TOKEN] must match the bridge's config.json.
 */
object DiscordBridgeReporter {

    // ====== USER SETTINGS (token must match the bridge config.json) ======
    private const val BRIDGE_URL = "http://192.168.0.100:8765" // last-resort fallback
    private const val BRIDGE_TOKEN = "vH_KuoMuT0UyhrXq6G1EnqG-DRoTIIU8xlFIyvC3qao"
    private const val BRIDGE_CONFIG_URL =
        "https://raw.githubusercontent.com/Loooooooony/extensions-source/main/bridge.json"
    // ==================================================================

    private const val BRIDGE_PORT = 8765

    /**
     * Per-app-launch URL fragment appended to stub pages (Iken) so the reader's
     * page cache never matches a previous launch. This forces the app to call
     * getImageUrl() again - which is our "user is viewing this chapter" signal -
     * even when re-opening an already-read chapter from history. The fragment is
     * never sent over HTTP, and image URLs themselves are untouched.
     */
    val cacheBuster: String = "#dc-" + java.util.UUID.randomUUID().toString().substring(0, 8)

    private val enabled: Boolean
        get() = BRIDGE_TOKEN != "CHANGE_ME_LONG_RANDOM_TOKEN"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastChapterOpenAt = 0L

    private val lastHeartbeatAt = java.util.concurrent.atomic.AtomicLong(0)

    // ====== bridge address resolution ======
    // The laptop's LAN IP changes whenever the router reassigns DHCP leases, so a
    // hardcoded address breaks silently. The bridge commits its current address to
    // bridge.json in this repo; we fetch it over plain HTTPS (raw CDN caches ~5min,
    // so a per-minute cache buster keeps us fresh during a reading session).

    @Volatile
    private var cachedBridgeUrl: String? = null

    private fun resolveBridgeUrl(): String {
        cachedBridgeUrl?.let { return it }
        fetchBridgeAddress()?.let { (host, port) ->
            val url = "http://$host:$port"
            cachedBridgeUrl = url
            return url
        }
        return BRIDGE_URL
    }

    // raw.githubusercontent is served through a CDN that caches for ~5 minutes;
    // rotating the query string once per minute yields near-real-time config
    // without hammering the endpoint.
    private val configBuster: String
        get() = "?v=" + System.currentTimeMillis() / 60_000L

    private fun fetchBridgeAddress(): Pair<String, Int>? = runCatching {
        val req = Request.Builder().url(BRIDGE_CONFIG_URL + configBuster).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(
                resp.body.string(),
            ).jsonObject
            val host = obj["host"]?.jsonPrimitive?.content ?: return@runCatching null
            val port = obj["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: BRIDGE_PORT
            host to port
        }
    }.getOrNull()

    private fun postToBridge(payload: String) {
        val url = resolveBridgeUrl()
        if (tryPost(url, payload)) return
        // The cached address went stale (laptop got a new IP) - refetch and retry once.
        if (url == cachedBridgeUrl) cachedBridgeUrl = null
        val fresh = resolveBridgeUrl()
        if (fresh != url) tryPost(fresh, payload)
    }

    private fun tryPost(baseUrl: String, payload: String): Boolean = runCatching {
        val request = Request.Builder()
            .url("$baseUrl/api/reading")
            .header("X-Bridge-Token", BRIDGE_TOKEN)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().close()
    }.isSuccess

    // ====== view-time reporting ======
    // getPageList is called early (apps prefetch upcoming chapters), so it is not
    // a reliable "user is reading this" signal. Instead, sources hand us chapter
    // metadata here and report via reportChapterViewed() from getImageUrl(),
    // which the app calls lazily right before displaying each page.

    private class ChapterMeta(
        val source: String,
        val title: String?,
        val chapterName: String?,
        val chapterUrl: String?,
        val coverUrl: String?,
    )

    private val chapterMetaByKey = java.util.concurrent.ConcurrentHashMap<String, ChapterMeta>()

    @Volatile
    private var lastReportedViewKey: String? = null

    /** Register chapter metadata so view-time signals (getImageUrl) can report it. */
    fun registerChapterMeta(
        source: String,
        title: String?,
        chapterName: String?,
        chapterUrl: String?,
        coverUrl: String?,
        chapterKey: String,
    ) {
        if (!enabled) return
        chapterMetaByKey[chapterKey] = ChapterMeta(source, title, chapterName, chapterUrl, coverUrl)
        if (chapterMetaByKey.size > 50) chapterMetaByKey.clear()
    }

    /** Report a chapter when it becomes the actively viewed one (deduped). */
    fun reportChapterViewed(chapterKey: String) {
        if (!enabled) return
        if (chapterKey == lastReportedViewKey) return
        val meta = chapterMetaByKey[chapterKey] ?: return
        lastReportedViewKey = chapterKey
        reportChapterOpened(meta.source, meta.title, meta.chapterName, meta.chapterUrl, meta.coverUrl)
    }

    /**
     * Interceptor that piggybacks on the source's network activity (page/image loads)
     * to send a throttled "still reading" heartbeat to the bridge.
     *
     * Heartbeats are only active for 30 minutes after the last reported chapter,
     * so casual browsing does not keep the presence alive.
     */
    fun heartbeatInterceptor(): okhttp3.Interceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        if (enabled) {
            val now = System.currentTimeMillis()

            val prev = lastHeartbeatAt.get()
            if (now - lastChapterOpenAt < 30 * 60_000L &&
                now - prev > 20_000L &&
                lastHeartbeatAt.compareAndSet(prev, now)
            ) {
                scope.launch {
                    postToBridge("""{"event":"heartbeat","timestamp":${now / 1000}}""")
                }
            }
        }
        chain.proceed(request)
    }

    fun reportChapterOpened(
        source: String,
        title: String?,
        chapterName: String?,
        chapterUrl: String?,
        coverUrl: String? = null,
    ) {
        if (!enabled) return
        lastChapterOpenAt = System.currentTimeMillis()
        scope.launch {
            val payload = buildJsonObject {
                put("event", "chapter_opened")
                put("source", source)
                put("language", "ar")
                title?.let { put("title", it) }
                chapterName?.let { put("chapterName", it) }
                chapterUrl?.let { put("chapterUrl", it) }
                coverUrl?.let { put("coverUrl", it) }
                put("timestamp", System.currentTimeMillis() / 1000)
            }.toString()
            postToBridge(payload)
        }
    }
}
