package com.Lia.assistant.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Home-character settings. Kept in its own SharedPreferences file —
 * deliberately separate from [NovaPreferences] so character choices
 * never mix with the main app settings.
 *
 * The character is a 2D illustration (closed/open mouth drawings),
 * not a 3D model; nothing here claims otherwise.
 */
data class CharacterSettings(
    // V15: the headphones girl is the default now (ayan's order
    // 2026-11-22 — he does not like the red-saree one). Fresh installs
    // open with headphones; see the one-time migration below for
    // existing installs that still have "saree" stored.
    val characterId: String = CharacterPreferences.CHARACTER_HEADPHONES,
    val homeCharacterEnabled: Boolean = true,
    val showEverywhere: Boolean = false
)

object CharacterPreferences {
    const val CHARACTER_SAREE = "saree"
    const val CHARACTER_HEADPHONES = "headphones"

    /** All selectable character ids, in display order (default first). */
    val ALL_CHARACTER_IDS = listOf(CHARACTER_HEADPHONES, CHARACTER_SAREE)

    private const val PREF_NAME = "lia_character_prefs"
    private const val KEY_CHARACTER_ID = "character_id"
    private const val KEY_HOME_ENABLED = "home_character_enabled"
    private const val KEY_SHOW_EVERYWHERE = "show_everywhere"
    // V15: one-time default migration flag (see migrateDefaultToHeadphones).
    private const val KEY_DEFAULT_MIGRATED_V15 = "default_migrated_v15"

    private val _settings = MutableStateFlow(CharacterSettings())
    val settings: StateFlow<CharacterSettings> = _settings.asStateFlow()

    // SharedPreferences holds change listeners weakly: keep a strong
    // reference here or updates silently stop arriving.
    private var changeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Volatile
    private var listenerRegistered = false

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun read(prefs: SharedPreferences): CharacterSettings {
        val rawId = prefs.getString(KEY_CHARACTER_ID, CHARACTER_HEADPHONES) ?: CHARACTER_HEADPHONES
        return CharacterSettings(
            characterId = if (rawId in ALL_CHARACTER_IDS) rawId else CHARACTER_HEADPHONES,
            homeCharacterEnabled = prefs.getBoolean(KEY_HOME_ENABLED, true),
            showEverywhere = prefs.getBoolean(KEY_SHOW_EVERYWHERE, false)
        )
    }

    /**
     * V15 one-time migration: before V15 the default was the red-saree
     * girl, so existing installs already have "saree" stored — a code
     * default alone would never change them. On the first run of V15,
     * a stored "saree" (or nothing stored) is switched to "headphones"
     * once, then the flag stops it ever running again.
     *
     * Honest limit: there is no "explicitly picked" flag in V14, so a
     * user who deliberately picked saree in Settings cannot be told
     * apart from one who never touched the picker — both migrate.
     * The picker in Settings still offers saree, so anyone who wants
     * it back can re-pick it in one tap.
     */
    private fun migrateDefaultToHeadphones(sharedPrefs: SharedPreferences) {
        if (sharedPrefs.getBoolean(KEY_DEFAULT_MIGRATED_V15, false)) return
        sharedPrefs.edit().putBoolean(KEY_DEFAULT_MIGRATED_V15, true).apply()
        val stored = sharedPrefs.getString(KEY_CHARACTER_ID, null)
        if (stored == null || stored == CHARACTER_SAREE) {
            sharedPrefs.edit().putString(KEY_CHARACTER_ID, CHARACTER_HEADPHONES).apply()
        }
    }

    /**
     * Start observing. Safe to call from any screen or service; the
     * change listener is registered once per process and [settings]
     * always reflects the latest stored values.
     */
    @Synchronized
    fun observe(context: Context): StateFlow<CharacterSettings> {
        val appContext = context.applicationContext
        val sharedPrefs = prefs(appContext)
        migrateDefaultToHeadphones(sharedPrefs)
        _settings.value = read(sharedPrefs)
        if (!listenerRegistered) {
            listenerRegistered = true
            changeListener = SharedPreferences.OnSharedPreferenceChangeListener { shared, _ ->
                _settings.value = read(shared)
            }
            sharedPrefs.registerOnSharedPreferenceChangeListener(changeListener)
        }
        return settings
    }

    fun getSelectedCharacterId(context: Context): String {
        val sharedPrefs = prefs(context.applicationContext)
        migrateDefaultToHeadphones(sharedPrefs)
        return read(sharedPrefs).characterId
    }

    fun setSelectedCharacterId(context: Context, characterId: String) {
        val id = if (characterId in ALL_CHARACTER_IDS) characterId else CHARACTER_HEADPHONES
        prefs(context.applicationContext).edit().putString(KEY_CHARACTER_ID, id).apply()
    }

    fun isHomeCharacterEnabled(context: Context): Boolean =
        read(prefs(context.applicationContext)).homeCharacterEnabled

    fun setHomeCharacterEnabled(context: Context, enabled: Boolean) {
        prefs(context.applicationContext).edit().putBoolean(KEY_HOME_ENABLED, enabled).apply()
    }

    fun isShowEverywhere(context: Context): Boolean =
        read(prefs(context.applicationContext)).showEverywhere

    fun setShowEverywhere(context: Context, enabled: Boolean) {
        prefs(context.applicationContext).edit().putBoolean(KEY_SHOW_EVERYWHERE, enabled).apply()
    }
}
