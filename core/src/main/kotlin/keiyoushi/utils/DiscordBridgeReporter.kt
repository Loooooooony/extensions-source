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
import java.util.concurrent.TimeUnit

/**
 * Best-effort reporter that notifies a local Windows bridge when a chapter is opened,
 * so the bridge can update Discord Rich Presence.
 *
 * The report is fire-and-forget: it never blocks page loading and swallows every error.
 *
 * SETUP: edit [BRIDGE_URL] and [BRIDGE_TOKEN] before building, or leave [BRIDGE_TOKEN]
 * as-is to keep reporting disabled.
 */
object DiscordBridgeReporter {

    // ====== USER SETTINGS (edit these two lines before building) ======
    private const val BRIDGE_URL = "http://192.168.0.107:8765"
    private const val BRIDGE_TOKEN = "vH_KuoMuT0UyhrXq6G1EnqG-DRoTIIU8xlFIyvC3qao"
    // ==================================================================

    private val enabled: Boolean
        get() = BRIDGE_TOKEN != "CHANGE_ME_LONG_RANDOM_TOKEN" && BRIDGE_URL.isNotBlank()

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
                    runCatching {
                        val payload = """{"event":"heartbeat","timestamp":${now / 1000}}"""
                        val hbRequest = Request.Builder()
                            .url("$BRIDGE_URL/api/reading")
                            .header("X-Bridge-Token", BRIDGE_TOKEN)
                            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                            .build()
                        client.newCall(hbRequest).execute().close()
                    }
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
            runCatching {
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

                val request = Request.Builder()
                    .url("$BRIDGE_URL/api/reading")
                    .header("X-Bridge-Token", BRIDGE_TOKEN)
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                client.newCall(request).execute().close()
            }
        }
    }
}
