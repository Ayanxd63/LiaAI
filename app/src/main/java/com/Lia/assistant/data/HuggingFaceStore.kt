package com.Lia.assistant.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Own persistence for the Hugging Face integration (v11).
 *
 * Stored in a dedicated SharedPreferences file ("huggingface_prefs"), separate
 * from the Gemini key store and the ElevenLabs store. The token is the user's
 * own free Hugging Face access token — it is never logged, never hardcoded,
 * and never leaves the device except in the Authorization header of
 * Hugging Face API calls made by
 * [com.Lia.assistant.voice.VideoSpacesClient].
 *
 * Why a token is needed at all (verified live 2026-11-22, NOT assumed):
 * the public CogVideoX Spaces are free to use, but Hugging Face's ZeroGPU
 * scheduler rejects video jobs from anonymous (no-token) callers — the
 * exact errors seen were "ZeroGPU illegal duration" and "You have exceeded
 * your ZeroGPU quota (300s requested vs. 0s left). Authenticate with a
 * Hugging Face token for more quota". A free account token raises the
 * daily ZeroGPU quota to about 5 GPU-minutes (roughly one 6-second video
 * a day on these Spaces). The token costs nothing and needs no payment:
 * huggingface.co → Settings → Access Tokens → New token (read).
 *
 * The token is REQUIRED for video generation, but OPTIONAL for the app as
 * a whole: every other Lia feature works without it.
 */
object HuggingFaceStore {

    private const val PREF_NAME = "huggingface_prefs"

    private const val KEY_TOKEN = "huggingface_token"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveToken(context: Context, token: String) {
        getPrefs(context).edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    fun getToken(context: Context): String {
        return getPrefs(context).getString(KEY_TOKEN, "")?.trim() ?: ""
    }

    fun hasToken(context: Context): Boolean = getToken(context).isNotEmpty()

    fun clearToken(context: Context) {
        getPrefs(context).edit().remove(KEY_TOKEN).apply()
    }
}
