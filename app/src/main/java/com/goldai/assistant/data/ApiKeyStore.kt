package com.goldai.assistant.data

import android.content.Context

/**
 * Gemini API key storage.
 *
 * V15: keys live in Keystore-backed encrypted storage
 * ([SecureCredentialStore]) with a one-time migration from the old
 * plain-text prefs. The API of this object is unchanged — every caller
 * keeps working exactly as before.
 */
object ApiKeyStore {

    fun getKey(context: Context): String {
        return SecureCredentialStore.getGeminiKey(context)
    }

    fun saveKey(context: Context, key: String) {
        SecureCredentialStore.saveGeminiKey(context, key)
    }

    fun hasKey(context: Context): Boolean {
        return SecureCredentialStore.hasGeminiKey(context)
    }
}
