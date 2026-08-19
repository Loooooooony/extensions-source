package keiyoushi.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Best-effort reporter that notifies a local Windows bridge when a chapter is opened,
 * so the bridge can update Discord Rich Presence.
 *
 * The report is fire-and-forget: it never blocks page loading and swallows every error.
 *
 * The bridge is located automatically via UDP broadcast discovery (survives the
 * laptop's IP changing via DHCP). [BRIDGE_URL] is only a fallback; [BRIDGE_TOKEN]
 * must match the bridge's config.json.
 */
object DiscordBridgeReporter {

    // ====== USER SETTINGS (token must match the bridge config.json) ======
    private const val BRIDGE_URL = "http://192.168.0.101:8765" // fallback only
    private const val BRIDGE_TOKEN = "vH_KuoMuT0UyhrXq6G1EnqG-DRoTIIU8xlFIyvC3qao"
    // ==================================================================

    private const val DISCOVERY_PORT = 8766
    private const val DISCOVERY_MAGIC = "TACHIMANGA_DISCOVER"

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

    // ====== bridge auto-discovery ======
    // The laptop's LAN IP changes whenever the router reassigns DHCP leases, so a
    // hardcoded address breaks silently. Instead we broadcast "where is the bridge?"
    // and the bridge answers with its port; the sender's IP is the address we need.

    @Volatile
    private var cachedBridgeUrl: String? = null

    private fun resolveBridgeUrl(): String {
        cachedBridgeUrl?.let { return it }
        discoverBridge()?.let {
            cachedBridgeUrl = it
            return it
        }
        return BRIDGE_URL
    }

    private fun discoverBridge(): String? = runCatching {
        val socket = DatagramSocket()
        socket.broadcast = true
        socket.soTimeout = 800
        try {
            val msg = DISCOVERY_MAGIC.toByteArray()
            socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
            val buf = ByteArray(256)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            val text = String(resp.data, 0, resp.length)
            if (text.startsWith("TACHIMANGA_BRIDGE:")) {
                "http://${resp.address.hostAddress}:${text.substringAfter(':').trim()}"
            } else {
                null
            }
        } finally {
            runCatching { socket.close() }
        }
    }.getOrNull()

    private fun postToBridge(payload: String) {
        val url = resolveBridgeUrl()
        if (tryPost(url, payload)) return
        // The cached address went stale (laptop got a new IP) - rediscover and retry once.
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
