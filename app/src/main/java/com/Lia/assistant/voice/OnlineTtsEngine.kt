package com.Lia.assistant.voice

import android.content.Context
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.data.OnlineTtsQualityPreferences
import com.Lia.assistant.data.OnlineTtsVoicePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * V17 (online voice) — internet TTS: Kokoro-82M via mesmer.tools.
 * Free, no key, no signup. This is the human-like voice ayan asked
 * for: the online voice speaks Lia's replies when internet is
 * available, and the device voice (system TTS) is the fallback —
 * never the other way round.
 *
 * Endpoint (verified live 2026-11-22, and re-verified before this
 * file was written):
 *   GET https://mesmer.tools/api/v1/tts?text=...&quality=low&voice=af_bella
 *   → {"url": "https://cdn.mesmer.tools/tts/...mp3", "hash", "cached",
 *      "quality", "voice", "provider", "fellBack"}
 * Docs: https://mesmer.tools/api-tools/tts
 *
 * Verified live 2026-11-22 ~17:31 PKT:
 * - quality=low + voice=af_bella → real CDN MP3 (provider kokoro,
 *   fellBack false). The MP3 downloads and plays: MPEG layer III,
 *   24 kHz mono, 2.25 s for "Hello jan, kya haal hai?".
 * - quality=low + voice=hf_alpha + Hindi (Devanagari) text → real
 *   CDN MP3 (provider kokoro, fellBack false). Hindi voice WORKS.
 * - quality=high + voice=hq_female → worked for a cached text
 *   (provider openai, cached true, fellBack false). A fresh high
 *   render failed earlier the same day with an Azure OpenAI 530 and
 *   the service fell back to Kokoro on its own (fellBack true) — so
 *   high quality is offered as an option, never the default, and the
 *   response's fellBack flag is always honoured and shown.
 *
 * Honest limits (shown in Settings → Lia's voice, not hidden):
 * - 20 generations per hour per IP (server-enforced). A local
 *   counter fails fast at 20 so the app falls back instantly
 *   instead of waiting on a 429. Cached repeats (same text + voice)
 *   do NOT count — the server returns cached:true and no generation
 *   happens, and the app's own disk cache skips the call entirely.
 * - Text max 2,000 chars per request — longer replies are split at
 *   sentence boundaries and spoken chunk by chunk (each chunk is one
 *   generation when it is not cached).
 * - mesmer.tools is a small third-party service: it can be slow or
 *   down. It is NEVER the only voice — the V19 chain is
 *   POCKET → ONLINE → SYSTEM, and a failure falls back at once (see
 *   VoiceSessionManager.speakVoiceChain).
 *
 * FEELINGS / HUMAN EMOTIONS: the API takes text + voice id only —
 * there is NO emotion, pitch, speed or SSML knob to invent. The
 * emotion lives in Lia's reply text itself: the Girlfriend system
 * instruction (Personality.kt) already rewrites all 12 feelings as
 * voice-acting direction (cry / laugh / happy / sad / angry /
 * afraid / surprised / romantic / shy / tired / hurt / peace — "feel
 * it in the voice instead" of fake sound-effect words), and V16's
 * personality beats shape the same text. This engine speaks that
 * exact text, unchanged, in Kokoro's natural voice — that is where
 * the human sound comes from. No fake sound-effect words ("ahh"
 * etc.) are ever added: they were removed in V9/V13 and stay out.
 * The one "uff" left in LiaVoiceBeats is a real Roman Urdu word
 * inside a spoken apology sentence, not a sound effect.
 *
 * No new permissions (INTERNET + ACCESS_NETWORK_STATE were already
 * declared) and no new dependencies (OkHttp + MediaPlayer were
 * already in the app — the player follows ElevenLabsAudioPlayer's
 * temp-file pattern).
 */
object OnlineTtsEngine {

    private const val TAG = "OnlineTts"

    private const val BASE_URL = "https://mesmer.tools/api/v1/tts"

    /** Server limit: 2,000 chars per request — stay safely under it. */
    private const val MAX_CHARS_PER_REQUEST = 1900

    /** Server limit: 20 generations per hour per IP. */
    const val HOURLY_LIMIT = 20

    /** V19: hard deadline for one online-voice synthesis (see speak). */
    private const val ONLINE_TTS_TIMEOUT_MS = 20_000L

    private const val QUOTA_WINDOW_MILLIS = 60L * 60L * 1000L

    private const val CACHE_DIR_NAME = "online_tts"

    /** Disk cache cap — oldest MP3s are deleted past this. */
    private const val CACHE_MAX_BYTES = 20L * 1024L * 1024L

    /** Default voice: warm American female (Kokoro af_bella). */
    const val VOICE_BELLA = "af_bella"

    /** Hindi voice (Kokoro hf_alpha) — auto-used for Devanagari text. */
    const val VOICE_HINDI = "hf_alpha"

    private val mainHandler = Handler(Looper.getMainLooper())

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Why the last attempt did not speak in the requested quality. */
    @Volatile
    var lastFallback: String? = null
        private set

    @Volatile
    var isSpeaking: Boolean = false
        private set

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var speakJob: Job? = null

    /** Typed failure — the caller decides the fallback from [kind]. */
    class OnlineTtsException(val kind: Kind, message: String) : Exception(message) {
        enum class Kind { NO_INTERNET, QUOTA, NETWORK, SERVICE }
    }

    /** True when the phone has any working internet connection. */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Which Kokoro voice speaks [text]: Hindi text (Devanagari
     * script) gets the Hindi voice, everything else the user's
     * chosen voice (Bella by default).
     */
    fun voiceForText(text: String, preferredVoice: String): String {
        return if (text.any { it in '\u0900'..'\u097F' }) VOICE_HINDI else preferredVoice
    }

    /** Generations used in the current rolling hour (local estimate). */
    fun quotaUsed(context: Context): Int {
        val now = System.currentTimeMillis()
        val windowStart = NovaPreferences.getString(
            context, NovaPreferences.Keys.ONLINE_TTS_QUOTA_WINDOW_START, "0"
        ).toLongOrNull() ?: 0L
        if (now - windowStart >= QUOTA_WINDOW_MILLIS) return 0
        return NovaPreferences.getString(
            context, NovaPreferences.Keys.ONLINE_TTS_QUOTA_COUNT, "0"
        ).toIntOrNull() ?: 0
    }

    private fun noteGeneration(context: Context, cached: Boolean) {
        if (cached) return // a cached repeat costs no quota anywhere
        val now = System.currentTimeMillis()
        val windowStart = NovaPreferences.getString(
            context, NovaPreferences.Keys.ONLINE_TTS_QUOTA_WINDOW_START, "0"
        ).toLongOrNull() ?: 0L
        val (start, count) = if (now - windowStart >= QUOTA_WINDOW_MILLIS) {
            now to 0
        } else {
            windowStart to (
                NovaPreferences.getString(
                    context, NovaPreferences.Keys.ONLINE_TTS_QUOTA_COUNT, "0"
                ).toIntOrNull() ?: 0
            )
        }
        NovaPreferences.putString(
            context, NovaPreferences.Keys.ONLINE_TTS_QUOTA_WINDOW_START, start.toString()
        )
        NovaPreferences.putString(
            context, NovaPreferences.Keys.ONLINE_TTS_QUOTA_COUNT, (count + 1).toString()
        )
    }

    /** Split [text] into ≤[MAX_CHARS_PER_REQUEST] chunks at sentences. */
    fun splitText(text: String): List<String> {
        val chunks = mutableListOf<String>()
        var rest = text.trim()
        while (rest.isNotEmpty()) {
            if (rest.length <= MAX_CHARS_PER_REQUEST) {
                chunks += rest
                break
            }
            val window = rest.substring(0, MAX_CHARS_PER_REQUEST)
            // Prefer a sentence end, else a space, else a hard cut.
            val cut = window.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
                .takeIf { it > MAX_CHARS_PER_REQUEST / 2 }
                ?: window.lastIndexOf(' ').takeIf { it > MAX_CHARS_PER_REQUEST / 2 }
                ?: MAX_CHARS_PER_REQUEST
            chunks += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1).trim()
        }
        return chunks.ifEmpty { listOf("") }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Synthesize [text] and return the MP3 files to play, in order.
     * Cached chunks are reused from disk (no quota, no network).
     * Throws [OnlineTtsException] on quota / network / service
     * failure — the caller falls back to the next voice engine.
     */
    suspend fun synthesize(
        context: Context,
        text: String,
        quality: String,
        voiceId: String
    ): List<File> = withContext(Dispatchers.IO) {
        if (!isOnline(context)) {
            throw OnlineTtsException(
                OnlineTtsException.Kind.NO_INTERNET,
                "Internet nahi hai — online voice ke liye internet chahiye."
            )
        }
        val dir = File(File(context.cacheDir, CACHE_DIR_NAME), voiceId).apply { mkdirs() }
        val files = mutableListOf<File>()
        for (chunk in splitText(text)) {
            if (chunk.isBlank()) continue
            val key = sha256Hex("$voiceId|$quality|$chunk")
            val cached = File(dir, "$key.mp3")
            if (cached.exists() && cached.length() > 0) {
                files += cached
                continue
            }
            if (quotaUsed(context) >= HOURLY_LIMIT) {
                throw OnlineTtsException(
                    OnlineTtsException.Kind.QUOTA,
                    "Online voice ki limit khatam (20 ghantay mein) — system voice bol rahi hai."
                )
            }
            files += fetchChunk(context, dir, key, chunk, quality, voiceId)
        }
        pruneCache(File(context.cacheDir, CACHE_DIR_NAME))
        files
    }

    /** One API call + MP3 download for a single chunk (one retry). */
    private fun fetchChunk(
        context: Context,
        dir: File,
        key: String,
        chunk: String,
        quality: String,
        voiceId: String
    ): File {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val url = "$BASE_URL?text=${URLEncoder.encode(chunk, "UTF-8")}" +
                    "&quality=$quality&voice=$voiceId"
                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.code == 429) {
                        throw OnlineTtsException(
                            OnlineTtsException.Kind.QUOTA,
                            "Online voice ki limit khatam (20 ghantay mein) — system voice bol rahi hai."
                        )
                    }
                    if (!response.isSuccessful) {
                        throw OnlineTtsException(
                            OnlineTtsException.Kind.SERVICE,
                            "Online voice service ne jawab nahi diya (HTTP ${response.code})."
                        )
                    }
                    val json = JSONObject(body)
                    val mp3Url = json.optString("url", "")
                    if (mp3Url.isBlank()) {
                        throw OnlineTtsException(
                            OnlineTtsException.Kind.SERVICE,
                            "Online voice service se MP3 link nahi mila."
                        )
                    }
                    if (json.optBoolean("fellBack", false)) {
                        val reason = json.optString("fallbackReason", "")
                            .takeIf { it.isNotBlank() }
                            ?: "high quality nahi mili, Kokoro voice use hui"
                        lastFallback = reason
                        Log.i(TAG, "Online voice fell back: $reason")
                    }
                    noteGeneration(context, json.optBoolean("cached", false))
                    val mp3Request = Request.Builder().url(mp3Url).get().build()
                    httpClient.newCall(mp3Request).execute().use { mp3Response ->
                        if (!mp3Response.isSuccessful || mp3Response.body == null) {
                            throw OnlineTtsException(
                                OnlineTtsException.Kind.NETWORK,
                                "Voice audio download nahi ho saka."
                            )
                        }
                        val tmp = File(dir, "$key.tmp")
                        FileOutputStream(tmp).use { out ->
                            mp3Response.body!!.byteStream().use { it.copyTo(out) }
                        }
                        val out = File(dir, "$key.mp3")
                        if (!tmp.renameTo(out)) {
                            tmp.delete()
                            throw OnlineTtsException(
                                OnlineTtsException.Kind.SERVICE,
                                "Voice audio save nahi ho saka."
                            )
                        }
                        return out
                    }
                }
            } catch (e: OnlineTtsException) {
                throw e // quota/service errors are final — no retry
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Online voice attempt ${attempt + 1} failed", e)
            }
        }
        throw OnlineTtsException(
            OnlineTtsException.Kind.NETWORK,
            "Online voice se rabta nahi ho saka (${lastError?.localizedMessage ?: "network error"})."
        )
    }

    private fun pruneCache(root: File) {
        try {
            val all = root.walkTopDown().filter { it.isFile }.toList()
            var total = all.sumOf { it.length() }
            if (total <= CACHE_MAX_BYTES) return
            for (file in all.sortedBy { it.lastModified() }) {
                if (total <= CACHE_MAX_BYTES) break
                total -= file.length()
                file.delete()
            }
        } catch (_: Exception) {
            // Cache pruning is best-effort only.
        }
    }

    /**
     * Speak [text] with the online voice, then [onDone]. Same contract
     * as the system-TTS path: [onDone] fires when playback ends,
     * [onError] fires with the typed failure (the caller falls back
     * to the next voice engine). Callbacks run on the main
     * thread. [stop] cancels playback.
     *
     * V19 (S7 fix): synthesis has a hard 20s deadline. The old code
     * bounded one OkHttp call at 30s but retried twice per chunk and
     * walked chunks one by one — a slow service held the overlay on
     * "Lia bol rahi hai…" for up to ~60s per chunk. Now the whole
     * synthesis dies at 20s and the chain moves on. Playback itself
     * needs no deadline: it plays local files, and MediaPlayer's
     * completion/error callbacks always fire (prepare() on a local
     * file either succeeds at once or throws).
     */
    fun speak(
        context: Context,
        scope: CoroutineScope,
        text: String,
        onDone: (() -> Unit)? = null,
        onError: ((OnlineTtsException) -> Unit)? = null
    ) {
        stop()
        isSpeaking = true
        speakJob = scope.launch {
            try {
                val quality = OnlineTtsQualityPreferences.getQuality(context)
                val preferred = OnlineTtsVoicePreferences.getVoiceId(context)
                val voiceId = voiceForText(text, preferred)
                val files = withTimeoutOrNull(ONLINE_TTS_TIMEOUT_MS) {
                    synthesize(context, text, quality, voiceId)
                } ?: throw OnlineTtsException(
                    OnlineTtsException.Kind.SERVICE,
                    "Online voice slow hai — device voice bol rahi hai."
                )
                if (files.isEmpty()) throw OnlineTtsException(
                    OnlineTtsException.Kind.SERVICE,
                    "Bolne ke liye kuch nahi mila."
                )
                withContext(Dispatchers.IO) { playFiles(files, onDone, onError) }
            } catch (e: CancellationException) {
                // V19 (S11 fix): a cancelled speak (user stopped the
                // session, or a new speak started) must stay silent —
                // the old code caught this as a normal error and fired
                // onError, so the fallback chain spoke a reply into a
                // dead session. Re-raise; nothing downstream runs.
                isSpeaking = false
                throw e
            } catch (e: OnlineTtsException) {
                isSpeaking = false
                mainHandler.post { onError?.invoke(e) }
            } catch (e: Exception) {
                isSpeaking = false
                Log.w(TAG, "Online voice speak failed", e)
                mainHandler.post {
                    onError?.invoke(
                        OnlineTtsException(
                            OnlineTtsException.Kind.SERVICE,
                            e.localizedMessage ?: "Online voice nahi bol saki."
                        )
                    )
                }
            }
        }
    }

    /** Play MP3 files one after another (MediaPlayer, ElevenLabs pattern). */
    private fun playFiles(
        files: List<File>,
        onDone: (() -> Unit)?,
        onError: ((OnlineTtsException) -> Unit)?
    ) {
        fun playNext(index: Int) {
            if (index >= files.size) {
                isSpeaking = false
                releasePlayer()
                mainHandler.post { onDone?.invoke() }
                return
            }
            try {
                val mediaPlayer = MediaPlayer().apply {
                    setDataSource(files[index].absolutePath)
                    setOnCompletionListener {
                        releasePlayer()
                        playNext(index + 1)
                    }
                    setOnErrorListener { _, _, _ ->
                        releasePlayer()
                        isSpeaking = false
                        mainHandler.post {
                            onError?.invoke(
                                OnlineTtsException(
                                    OnlineTtsException.Kind.SERVICE,
                                    "Voice audio play nahi ho saka."
                                )
                            )
                        }
                        true
                    }
                    prepare()
                }
                player = mediaPlayer
                mediaPlayer.start()
            } catch (_: Exception) {
                releasePlayer()
                isSpeaking = false
                mainHandler.post {
                    onError?.invoke(
                        OnlineTtsException(
                            OnlineTtsException.Kind.SERVICE,
                            "Voice audio play nahi ho saka."
                        )
                    )
                }
            }
        }
        playNext(0)
    }

    /** Stop playback immediately (session end / next reply). */
    fun stop() {
        try {
            speakJob?.cancel()
        } catch (_: Exception) {
        } finally {
            speakJob = null
        }
        try {
            player?.stop()
        } catch (_: Exception) {
            // Already stopped / not prepared — nothing to do.
        } finally {
            isSpeaking = false
            releasePlayer()
        }
    }

    private fun releasePlayer() {
        try {
            player?.release()
        } catch (_: Exception) {
            // Already released — nothing to do.
        } finally {
            player = null
        }
    }
}
