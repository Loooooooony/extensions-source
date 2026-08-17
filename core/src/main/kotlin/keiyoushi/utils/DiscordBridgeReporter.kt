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

    fun reportChapterOpened(
        source: String,
        title: String?,
        chapterName: String?,
        chapterUrl: String?,
        coverUrl: String? = null,
    ) {
        if (!enabled) return
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
