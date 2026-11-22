package com.Lia.assistant.voice

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One voice returned by GET /v1/voices. */
data class ElevenLabsVoice(val id: String, val name: String)

/** One ElevenLabs text-to-speech model. Ids are the real ElevenLabs model ids. */
data class ElevenLabsModel(val id: String, val displayName: String)

/** The five models the official LIA v1.12 UI offers. */
object ElevenLabsModels {
    val ALL = listOf(
        ElevenLabsModel("eleven_v4", "Eleven v4"),
        ElevenLabsModel("eleven_turbo_v2_5", "Eleven v4 Turbo"),
        ElevenLabsModel("eleven_multilingual_v2", "Eleven Multilingual v2"),
        ElevenLabsModel("eleven_flash_v2_5", "Eleven Flash v2.5"),
        ElevenLabsModel("eleven_turbo_v2", "Eleven Turbo v2")
    )

    val DEFAULT = ALL.first()

    fun displayName(modelId: String): String =
        ALL.firstOrNull { it.id == modelId }?.displayName ?: modelId
}

/**
 * ElevenLabs' built-in "picks" shown in the official voice picker
 * (Sarah, Laura, Charlie, George, Callum).
 *
 * The picker resolves their ids from the GET /v1/voices response by name
 * first. The [KNOWN_IDS] below are ElevenLabs' public default voice ids,
 * used only as a fallback when the API list does not contain a pick by name
 * (e.g. an account whose voice list is filtered) — so the pick is still
 * selectable instead of disappearing.
 */
object ElevenLabsBuiltInPicks {
    val NAMES = listOf("Sarah", "Laura", "Charlie", "George", "Callum")

    val KNOWN_IDS = mapOf(
        "Sarah" to "21m00Tcm4TlvDq8ikWAM",
        "Laura" to "FGY2WhTYpPnrIDTdsKH5X",
        "Charlie" to "IKne3meq5aSn9KrM0sBv",
        "George" to "JBFqnCBsd6RMkjVDRZzb",
        "Callum" to "N2lVS1w4EtoT3dr4eOWF"
    )

    fun isPick(name: String): Boolean = NAMES.any { it.equals(name, ignoreCase = true) }

    fun fallbackVoice(name: String): ElevenLabsVoice? =
        KNOWN_IDS[name]?.let { ElevenLabsVoice(id = it, name = name) }
}

sealed class ElevenLabsResult<out T> {
    data class Success<T>(val data: T) : ElevenLabsResult<T>()
    data class Error(val message: String) : ElevenLabsResult<Nothing>()
}

/**
 * Plain OkHttp REST client for ElevenLabs (v10).
 *
 * Reuses the OkHttp dependency the repo already has (same as
 * [GeminiTextClient]); no new Gradle dependencies were added.
 * No API key is stored or logged here — the caller passes the user's own
 * key from [com.Lia.assistant.data.ElevenLabsStore] on every call.
 * Errors are mapped to short user-readable messages, never raw stack traces.
 */
object ElevenLabsClient {

    private const val BASE_URL = "https://api.elevenlabs.io/v1"

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * GET /v1/voices — the user's voices (own + shared + built-in library).
     * The settings screen splits these into "Your voices" and
     * "ElevenLabs picks" by name.
     */
    fun fetchVoices(apiKey: String): ElevenLabsResult<List<ElevenLabsVoice>> {
        if (apiKey.isBlank()) {
            return ElevenLabsResult.Error("Add your ElevenLabs API key first.")
        }
        val request = Request.Builder()
            .url("$BASE_URL/voices")
            .header("xi-api-key", apiKey)
            .get()
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return ElevenLabsResult.Error(friendlyError(response.code))
                }
                val body = response.body?.string() ?: ""
                val voicesArray = runCatching {
                    JSONObject(body).optJSONArray("voices") ?: JSONArray()
                }.getOrDefault(JSONArray())
                val voices = ArrayList<ElevenLabsVoice>()
                for (i in 0 until voicesArray.length()) {
                    val obj = voicesArray.getJSONObject(i)
                    val id = obj.optString("voice_id", "").trim()
                    val name = obj.optString("name", "").trim()
                    if (id.isNotEmpty() && name.isNotEmpty()) {
                        voices.add(ElevenLabsVoice(id = id, name = name))
                    }
                }
                ElevenLabsResult.Success(voices)
            }
        } catch (_: IOException) {
            ElevenLabsResult.Error("Couldn't reach ElevenLabs. Check your internet connection.")
        } catch (_: Exception) {
            ElevenLabsResult.Error("Couldn't load voices from ElevenLabs. Please try again.")
        }
    }

    /**
     * POST /v1/text-to-speech/{voice_id} — returns the spoken reply as MP3 bytes.
     * [text] is capped by the caller; the API itself rejects over-long text.
     */
    fun synthesize(
        apiKey: String,
        voiceId: String,
        modelId: String,
        text: String
    ): ElevenLabsResult<ByteArray> {
        if (apiKey.isBlank()) {
            return ElevenLabsResult.Error("Add your ElevenLabs API key first.")
        }
        if (voiceId.isBlank()) {
            return ElevenLabsResult.Error("Pick an ElevenLabs voice first.")
        }
        if (text.isBlank()) {
            return ElevenLabsResult.Error("Nothing to speak.")
        }
        val bodyJson = JSONObject().apply {
            put("text", text)
            put("model_id", modelId.ifBlank { ElevenLabsModels.DEFAULT.id })
            put(
                "voice_settings", JSONObject().apply {
                    put("stability", 0.5)
                    put("similarity_boost", 0.75)
                }
            )
        }
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url("$BASE_URL/text-to-speech/$voiceId")
            .header("xi-api-key", apiKey)
            .header("Accept", "audio/mpeg")
            .post(bodyJson.toString().toRequestBody(mediaType))
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return ElevenLabsResult.Error(friendlyError(response.code))
                }
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (bytes.isEmpty()) {
                    ElevenLabsResult.Error("ElevenLabs returned empty audio. Please try again.")
                } else {
                    ElevenLabsResult.Success(bytes)
                }
            }
        } catch (_: IOException) {
            ElevenLabsResult.Error("Couldn't reach ElevenLabs. Check your internet connection.")
        } catch (_: Exception) {
            ElevenLabsResult.Error("Couldn't speak with ElevenLabs. Please try again.")
        }
    }

    /** Short, user-readable message for an ElevenLabs HTTP status code. */
    private fun friendlyError(code: Int): String = when (code) {
        401 -> "Invalid ElevenLabs API key. Check the key in Settings — Connections — ElevenLabs."
        402 -> "ElevenLabs quota used up. Your free credits are finished — top up on elevenlabs.io or switch back to Gemini."
        422 -> "ElevenLabs can't speak with this voice and model. Try another voice or model."
        429 -> "ElevenLabs is busy right now. Please wait a moment and try again."
        404 -> "That ElevenLabs voice was not found. Pick another voice."
        else -> "ElevenLabs returned an error (code $code). Please try again."
    }
}

/**
 * Plays ElevenLabs MP3 bytes with android.media.MediaPlayer (framework only —
 * the repo has no ExoPlayer dependency, and none was added).
 *
 * MediaPlayer cannot play raw bytes, so the audio is written to a temp file
 * in the app cache dir first; the file is deleted when playback is stopped
 * or replaced. This player is for ElevenLabs audio only — it never drives
 * lip sync: ElevenLabs audio does not animate Lia's 3D character (the
 * official app has the same limitation), so callers must not wire it to
 * the orb state.
 */
object ElevenLabsAudioPlayer {

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    var isPlaying: Boolean = false
        private set

    /**
     * Called on the main thread when playback finishes on its own (not when
     * [stop] is called). UI owners set this to clear their "now playing"
     * state, e.g. the chat speaker toggle.
     */
    @Volatile
    var onPlaybackFinished: (() -> Unit)? = null

    /**
     * Play [mp3Bytes]. Stops any audio already playing first.
     * Returns true if playback started, false if the audio could not be played.
     * Must be called from a coroutine (file I/O + prepare run on Dispatchers.IO).
     */
    suspend fun play(context: Context, mp3Bytes: ByteArray): Boolean {
        stop()
        return withContext(Dispatchers.IO) {
            try {
                val file = File(context.cacheDir, "elevenlabs_reply.mp3")
                FileOutputStream(file).use { it.write(mp3Bytes) }
                val mediaPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener {
                        this@ElevenLabsAudioPlayer.isPlaying = false
                        releasePlayer()
                        onPlaybackFinished?.invoke()
                    }
                    setOnErrorListener { _, _, _ ->
                        this@ElevenLabsAudioPlayer.isPlaying = false
                        releasePlayer()
                        onPlaybackFinished?.invoke()
                        true
                    }
                    prepare()
                }
                player = mediaPlayer
                mediaPlayer.start()
                isPlaying = true
                true
            } catch (_: Exception) {
                isPlaying = false
                releasePlayer()
                false
            }
        }
    }

    /** Stop playback immediately (also used by the chat speaker toggle). */
    fun stop() {
        try {
            player?.stop()
        } catch (_: Exception) {
            // Already stopped / not prepared — nothing to do.
        } finally {
            isPlaying = false
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
