package com.Lia.assistant.data

import android.content.Context

/**
 * V17 — voice output preferences, in the shared NovaPreferences file
 * (no new files, no new dependencies). Same pattern as V16.
 *
 * V18 diet: the on-device voice engine is gone (Pocket TTS, Moonshine
 * STT, speaker voice-match, laugh detection, memory embeddings and the
 * VAD gate were all removed). What is left:
 * - ONLINE: internet voice (Kokoro via mesmer.tools) — human-like,
 *   no key, ~20 generations/hour, needs internet. Default.
 * - SYSTEM: device TTS (cute voice) — last resort, always works.
 * A stored "POCKET" value from a V17 install maps to ONLINE via the
 * migration in [VoiceOutputPreferences.getEngine].
 *
 * V19: Pocket TTS is BACK (on-device Lia voice, ~191 MB model,
 * Wi-Fi download from Settings → On-device voice). The chain is
 * POCKET → ONLINE → SYSTEM, and POCKET is the default for fresh
 * installs (see the V19 migration below).
 *
 * V21: Hindi TTS is the voice fix (Supertonic 3, ~139 MB, native
 * Hindi voice). The chain is now HINDI → POCKET → ONLINE → SYSTEM,
 * and HINDI is the default when its model is downloaded (see the
 * V21 migration below). POCKET stays as the English on-device
 * fallback; ONLINE (Kokoro) and SYSTEM are unchanged.
 */

/**
 * Which voice speaks Lia's no-key replies:
 * - HINDI: on-device Hindi voice (Supertonic 3) — the V21 fix for
 *   the foreign accent: speaks Roman Urdu transliterated to
 *   Devanagari with a native Hindi voice. V21 default when its
 *   ~139 MB model is downloaded; the chain skips it instantly when
 *   not downloaded.
 * - ONLINE: internet voice (Kokoro via mesmer.tools) — human-like,
 *   no key, ~20 generations/hour, needs internet.
 * - POCKET: on-device Lia voice — offline, always available once
 *   its ~191 MB model is downloaded. V19 default when downloaded.
 * - SYSTEM: device TTS (cute voice) — last resort, always works.
 */
enum class VoiceOutputEngine {
    HINDI,
    ONLINE,
    POCKET,
    SYSTEM
}

object VoiceOutputPreferences {

    fun getEngine(context: Context): VoiceOutputEngine {
        migrateVoiceDefault(context)
        val stored = NovaPreferences.getString(
            context,
            NovaPreferences.Keys.VOICE_OUTPUT_ENGINE,
            VoiceOutputEngine.HINDI.name
        )
        return try {
            VoiceOutputEngine.valueOf(stored)
        } catch (_: Exception) {
            VoiceOutputEngine.HINDI
        }
    }

    fun setEngine(context: Context, engine: VoiceOutputEngine) {
        NovaPreferences.putString(
            context,
            NovaPreferences.Keys.VOICE_OUTPUT_ENGINE,
            engine.name
        )
    }

    /**
     * One-time migration (V21, replaces the V19 migration — the V19
     * flag is still marked done so it can never re-run and clobber
     * a HINDI value back to POCKET):
     * - A stored "POCKET" (the V19/V20 default — the English voice
     *   ayan complained sounds foreign) becomes HINDI: that is
     *   exactly the voice V21 fixes, and the chain still falls
     *   back to POCKET when the Hindi model is not downloaded yet.
     * - A stored ONLINE / SYSTEM (an explicit choice the user
     *   actually made) is kept — V21 never overrides a choice.
     * - Anything else / nothing stored (fresh install, or a value
     *   from before any voice choice existed) becomes HINDI — the
     *   V21 default. When the model is not downloaded yet the chain
     *   skips Hindi instantly and POCKET/ONLINE speaks, so a fresh
     *   install still talks on first launch.
     * After this runs, the stored value IS the explicit choice.
     */
    private fun migrateVoiceDefault(context: Context) {
        if (NovaPreferences.getBoolean(
                context, NovaPreferences.Keys.VOICE_V21_MIGRATED, false
            )
        ) return
        val stored = NovaPreferences.getString(
            context, NovaPreferences.Keys.VOICE_OUTPUT_ENGINE, ""
        )
        when (stored) {
            VoiceOutputEngine.POCKET.name,
            VoiceOutputEngine.ONLINE.name,
            VoiceOutputEngine.SYSTEM.name -> {
                // POCKET (the old default) becomes the Hindi voice;
                // an explicit ONLINE / SYSTEM choice is kept as-is.
                if (stored == VoiceOutputEngine.POCKET.name) {
                    NovaPreferences.putString(
                        context,
                        NovaPreferences.Keys.VOICE_OUTPUT_ENGINE,
                        VoiceOutputEngine.HINDI.name
                    )
                }
            }
            else -> {
                NovaPreferences.putString(
                    context,
                    NovaPreferences.Keys.VOICE_OUTPUT_ENGINE,
                    VoiceOutputEngine.HINDI.name
                )
            }
        }
        NovaPreferences.putBoolean(
            context, NovaPreferences.Keys.VOICE_V21_MIGRATED, true
        )
        NovaPreferences.putBoolean(
            context, NovaPreferences.Keys.VOICE_V19_MIGRATED, true
        )
        NovaPreferences.putBoolean(
            context, NovaPreferences.Keys.ONLINE_VOICE_MIGRATED, true
        )
    }

    /**
     * Voice engines to try, in order, for one reply. V21 chain:
     * HINDI (once its model is downloaded) → POCKET (English
     * on-device) → ONLINE (Kokoro) → SYSTEM (always last, and
     * alone when explicitly chosen — it never fails). The caller's
     * HINDI/POCKET steps skip instantly when their model is not
     * downloaded, so the chain never waits on a missing engine.
     * The caller tries each in turn and moves on only on failure,
     * so a reply is never lost to one dead engine.
     */
    fun resolveOrder(context: Context): List<VoiceOutputEngine> {
        return when (getEngine(context)) {
            VoiceOutputEngine.HINDI ->
                listOf(
                    VoiceOutputEngine.HINDI,
                    VoiceOutputEngine.POCKET,
                    VoiceOutputEngine.ONLINE,
                    VoiceOutputEngine.SYSTEM
                )
            VoiceOutputEngine.POCKET ->
                listOf(
                    VoiceOutputEngine.POCKET,
                    VoiceOutputEngine.HINDI,
                    VoiceOutputEngine.ONLINE,
                    VoiceOutputEngine.SYSTEM
                )
            VoiceOutputEngine.ONLINE ->
                listOf(
                    VoiceOutputEngine.ONLINE,
                    VoiceOutputEngine.HINDI,
                    VoiceOutputEngine.POCKET,
                    VoiceOutputEngine.SYSTEM
                )
            VoiceOutputEngine.SYSTEM ->
                listOf(VoiceOutputEngine.SYSTEM)
        }
    }
}

/**
 * Online voice quality: LOW (Kokoro-82M, default — fast, reliable)
 * or HIGH (Azure GPT-audio narrator — better when it works, but it
 * fails often and the service then falls back to Kokoro on its own;
 * the response says so via fellBack, and the app shows it).
 */
enum class OnlineTtsQuality(val apiValue: String) {
    LOW("low"),
    HIGH("high")
}

object OnlineTtsQualityPreferences {

    fun getQuality(context: Context): String {
        val stored = NovaPreferences.getString(
            context,
            NovaPreferences.Keys.ONLINE_TTS_QUALITY,
            OnlineTtsQuality.LOW.apiValue
        )
        return if (stored == OnlineTtsQuality.HIGH.apiValue) {
            OnlineTtsQuality.HIGH.apiValue
        } else {
            OnlineTtsQuality.LOW.apiValue
        }
    }

    fun setQuality(context: Context, quality: String) {
        NovaPreferences.putString(
            context,
            NovaPreferences.Keys.ONLINE_TTS_QUALITY,
            if (quality == OnlineTtsQuality.HIGH.apiValue) {
                OnlineTtsQuality.HIGH.apiValue
            } else {
                OnlineTtsQuality.LOW.apiValue
            }
        )
    }
}

/**
 * Online voice choice: Bella (af_bella, warm American female —
 * default) or Hindi (hf_alpha, Kokoro Hindi female). The Hindi voice
 * is also auto-used for any Devanagari reply text, whatever is
 * chosen here (see OnlineTtsEngine.voiceForText).
 */
object OnlineTtsVoicePreferences {

    const val VOICE_BELLA = "af_bella"
    const val VOICE_HINDI = "hf_alpha"

    fun getVoiceId(context: Context): String {
        val stored = NovaPreferences.getString(
            context,
            NovaPreferences.Keys.ONLINE_TTS_VOICE,
            VOICE_BELLA
        )
        return if (stored == VOICE_HINDI) VOICE_HINDI else VOICE_BELLA
    }

    fun setVoiceId(context: Context, voiceId: String) {
        NovaPreferences.putString(
            context,
            NovaPreferences.Keys.ONLINE_TTS_VOICE,
            if (voiceId == VOICE_HINDI) VOICE_HINDI else VOICE_BELLA
        )
    }
}

/**
 * V19 (restored from V17) — Pocket TTS voice reference: "lia"
 * (bria.wav, the default Lia voice) or "loona" (loona.wav, the
 * second voice). These are the reference recordings from the public
 * sherpa Pocket TTS model repo — Lia's own voice character, not any
 * vendor's voice.
 */
object PocketTtsVoicePreferences {

    const val VOICE_LIA = "lia"
    const val VOICE_LOONA = "loona"

    fun getVoiceId(context: Context): String {
        val stored = NovaPreferences.getString(
            context,
            NovaPreferences.Keys.POCKET_TTS_VOICE,
            VOICE_LIA
        )
        return if (stored == VOICE_LOONA) VOICE_LOONA else VOICE_LIA
    }

    fun setVoiceId(context: Context, voiceId: String) {
        NovaPreferences.putString(
            context,
            NovaPreferences.Keys.POCKET_TTS_VOICE,
            if (voiceId == VOICE_LOONA) VOICE_LOONA else VOICE_LIA
        )
    }
}
