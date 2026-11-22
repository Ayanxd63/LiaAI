package com.Lia.assistant.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Own persistence for the ElevenLabs integration (v10).
 *
 * Stored in a dedicated SharedPreferences file ("elevenlabs_prefs"), separate
 * from the Gemini key store. The API key is the user's own key — it is never
 * logged, never hardcoded, and never leaves the device except in the
 * xi-api-key header of ElevenLabs API calls made by [com.Lia.assistant.voice.ElevenLabsClient].
 *
 * ElevenLabs is a paid service (free tier available). Lia never pays for it
 * and never embeds any key: everything here only works with a key the user
 * creates on elevenlabs.io and pastes in themselves.
 */
object ElevenLabsStore {

    private const val PREF_NAME = "elevenlabs_prefs"

    // NOTE: the API key itself no longer lives here — V15 moved it to
    // Keystore-backed encrypted storage (SecureCredentialStore). Only
    // the voice/model/custom-voice prefs remain in this file.
    private const val KEY_VOICE_ID = "elevenlabs_voice_id"
    private const val KEY_VOICE_NAME = "elevenlabs_voice_name"
    private const val KEY_MODEL_ID = "elevenlabs_model_id"
    private const val KEY_CUSTOM_VOICES = "elevenlabs_custom_voices"

    /** Default model when the user has not picked one: Eleven v4. */
    const val DEFAULT_MODEL_ID = "eleven_v4"

    /** One voice the user cloned on ElevenLabs' site and added here by ID + name. */
    data class CustomVoice(val voiceId: String, val name: String)

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // ---- API key -----------------------------------------------------------
    // V15: the key lives in Keystore-backed encrypted storage
    // (SecureCredentialStore) with a one-time migration from the old
    // plain-text prefs. The API here is unchanged.

    fun saveKey(context: Context, key: String) {
        SecureCredentialStore.saveElevenLabsKey(context, key)
    }

    fun getKey(context: Context): String {
        return SecureCredentialStore.getElevenLabsKey(context)
    }

    fun hasKey(context: Context): Boolean = SecureCredentialStore.hasElevenLabsKey(context)

    fun clearKey(context: Context) {
        SecureCredentialStore.clearElevenLabsKey(context)
    }

    // ---- Selected voice ----------------------------------------------------
    // Empty voice id = no ElevenLabs voice selected = use Gemini (Sulafat).

    fun saveSelectedVoice(context: Context, voiceId: String, voiceName: String) {
        getPrefs(context).edit()
            .putString(KEY_VOICE_ID, voiceId.trim())
            .putString(KEY_VOICE_NAME, voiceName.trim())
            .apply()
    }

    /** The selected ElevenLabs voice id, or "" when Gemini is in use. */
    fun selectedVoiceId(context: Context): String {
        return getPrefs(context).getString(KEY_VOICE_ID, "")?.trim() ?: ""
    }

    /** Display name of the selected ElevenLabs voice, or "" when none. */
    fun selectedVoiceName(context: Context): String {
        return getPrefs(context).getString(KEY_VOICE_NAME, "")?.trim() ?: ""
    }

    fun hasSelectedVoice(context: Context): Boolean = selectedVoiceId(context).isNotEmpty()

    /** "Use Gemini instead": forget the ElevenLabs voice (key and models stay). */
    fun clearSelectedVoice(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_VOICE_ID)
            .remove(KEY_VOICE_NAME)
            .apply()
    }

    // ---- Selected model ----------------------------------------------------

    fun saveSelectedModel(context: Context, modelId: String) {
        getPrefs(context).edit().putString(KEY_MODEL_ID, modelId.trim()).apply()
    }

    /** Selected ElevenLabs model id, defaulting to Eleven v4. */
    fun selectedModel(context: Context): String {
        val saved = getPrefs(context).getString(KEY_MODEL_ID, "")?.trim() ?: ""
        return saved.ifEmpty { DEFAULT_MODEL_ID }
    }

    // ---- Custom (cloned) voices --------------------------------------------
    // Cloning itself happens on ElevenLabs' site. In-app we only store the
    // Voice ID + name the user enters, as a JSON array string.

    fun getCustomVoices(context: Context): List<CustomVoice> {
        val raw = getPrefs(context).getString(KEY_CUSTOM_VOICES, "")?.trim() ?: ""
        if (raw.isEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<CustomVoice>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("voiceId", "").trim()
                val name = obj.optString("name", "").trim()
                if (id.isNotEmpty() && name.isNotEmpty()) {
                    out.add(CustomVoice(voiceId = id, name = name))
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addCustomVoice(context: Context, voiceId: String, name: String): Boolean {
        val id = voiceId.trim()
        val displayName = name.trim()
        if (id.isEmpty() || displayName.isEmpty()) return false
        val current = getCustomVoices(context).toMutableList()
        // Re-adding the same Voice ID updates its name instead of duplicating.
        current.removeAll { it.voiceId == id }
        current.add(CustomVoice(voiceId = id, name = displayName))
        persistCustomVoices(context, current)
        return true
    }

    fun removeCustomVoice(context: Context, voiceId: String) {
        val current = getCustomVoices(context).filter { it.voiceId != voiceId.trim() }
        persistCustomVoices(context, current)
    }

    private fun persistCustomVoices(context: Context, voices: List<CustomVoice>) {
        val array = JSONArray()
        for (voice in voices) {
            array.put(
                JSONObject().apply {
                    put("voiceId", voice.voiceId)
                    put("name", voice.name)
                }
            )
        }
        getPrefs(context).edit().putString(KEY_CUSTOM_VOICES, array.toString()).apply()
    }
}
