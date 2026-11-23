package com.Lia.assistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * V15 (security P0) — Keystore-backed encrypted storage for the user's
 * own API credentials (Gemini key).
 *
 * Before V15 the keys sat as plain text in ordinary SharedPreferences
 * ("nova_prefs") — readable on a rooted phone or
 * via a backup restore. Now they live in EncryptedSharedPreferences
 * (AES-256-GCM, key in the Android Keystore, TEE-backed on this phone).
 *
 * One-time migration: on first read, if the encrypted store has no
 * value but the old plain store still has one, the value is moved over
 * and deleted from the old store. Migration runs once per key and never
 * logs the value. If encrypted storage cannot be created at all
 * (Keystore broken on some devices), the store falls back to the old
 * plain prefs rather than breaking the app — the key still works, just
 * unencrypted, exactly as before V15.
 *
 * The encrypted file ("lia_secure_prefs") is excluded from auto-backup
 * (see res/xml/backup_rules.xml) — history and settings still back up.
 */
object SecureCredentialStore {

    private const val ENC_PREF_NAME = "lia_secure_prefs"

    private const val KEY_GEMINI = "gemini_api_key"

    @Volatile
    private var encryptedPrefs: SharedPreferences? = null

    @Volatile
    private var encryptedUnavailable = false

    private fun encrypted(context: Context): SharedPreferences? {
        encryptedPrefs?.let { return it }
        if (encryptedUnavailable) return null
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                ENC_PREF_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { encryptedPrefs = it }
        }.getOrElse {
            // Keystore broken on this device: fall back to plain prefs
            // (V14 behaviour) instead of breaking the app.
            encryptedUnavailable = true
            null
        }
    }

    private fun legacyPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)

    /**
     * Read a key: encrypted store first; if empty, migrate once from
     * the old plain store (read -> write encrypted -> delete old).
     */
    private fun getMigrated(
        context: Context,
        key: String,
        legacy: SharedPreferences
    ): String {
        encrypted(context)?.let { prefs ->
            val value = prefs.getString(key, "")?.trim().orEmpty()
            if (value.isNotEmpty()) return value
        }
        // Not in the encrypted store yet: try the old plain store once.
        val oldValue = legacy.getString(key, "")?.trim().orEmpty()
        if (oldValue.isEmpty()) return ""
        // Move it over (best effort) and remove the plain copy.
        runCatching {
            encrypted(context)?.edit()?.putString(key, oldValue)?.apply()
            legacy.edit().remove(key).apply()
        }
        return oldValue
    }

    private fun putKey(context: Context, key: String, value: String) {
        val trimmed = value.trim()
        val prefs = encrypted(context)
        if (prefs != null) {
            if (trimmed.isEmpty()) {
                prefs.edit().remove(key).apply()
            } else {
                prefs.edit().putString(key, trimmed).apply()
            }
            return
        }
        // Encrypted store unavailable: plain fallback (V14 behaviour).
        val legacy = legacyPrefs(context)
        if (trimmed.isEmpty()) {
            legacy.edit().remove(key).apply()
        } else {
            legacy.edit().putString(key, trimmed).apply()
        }
    }

    // ---- Gemini key ------------------------------------------------------

    fun getGeminiKey(context: Context): String =
        getMigrated(context, KEY_GEMINI, legacyPrefs(context))

    fun saveGeminiKey(context: Context, key: String) = putKey(context, KEY_GEMINI, key)

    fun hasGeminiKey(context: Context): Boolean = getGeminiKey(context).isNotEmpty()

}
