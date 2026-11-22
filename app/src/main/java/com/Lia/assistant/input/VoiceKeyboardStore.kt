package com.Lia.assistant.input

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * Small preference store for the Lia Voice Keyboard.
 *
 * Deliberately separate from [com.Lia.assistant.data.NovaPreferences] and
 * [com.Lia.assistant.data.ApiKeyStore]: the keyboard toggle lives in its own
 * SharedPreferences file ("lia_voice_keyboard_prefs") so keyboard work never
 * touches the assistant's main preferences.
 *
 * IMPORTANT — what the toggle does and does not do: Android does not let an
 * app enable or select its own input method programmatically. The toggle here
 * only records that the user wants to use the voice keyboard. The user must
 * still enable "Lia Voice Keyboard" in the system Settings and then switch to
 * it with the keyboard-switcher. [VoiceKeyboardSettingsScreen] walks them
 * through both steps and shows the live system status (enabled / selected)
 * via [isEnabledInSystem] and [isCurrentInputMethod].
 */
object VoiceKeyboardStore {

    private const val PREF_NAME = "lia_voice_keyboard_prefs"
    private const val KEY_ENABLED = "voice_keyboard_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** The user's own "I want to use the voice keyboard" preference. Default false. */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Component of [LiaKeyboardService] as declared in the manifest. */
    fun serviceComponentName(context: Context): ComponentName =
        ComponentName(context, LiaKeyboardService::class.java)

    /** True when "Lia Voice Keyboard" appears in the system's enabled-IME list. */
    fun isEnabledInSystem(context: Context): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return false
        val component = serviceComponentName(context)
        return imm.enabledInputMethodList.any { info ->
            info.serviceInfo.packageName == component.packageName &&
                info.serviceInfo.name == component.className
        }
    }

    /** True when "Lia Voice Keyboard" is the keyboard currently selected by the system. */
    fun isCurrentInputMethod(context: Context): Boolean {
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ) ?: return false
        val component = serviceComponentName(context)
        // DEFAULT_INPUT_METHOD is stored as "package/class" (flattened component).
        return current == component.flattenToString() ||
            current == "${component.packageName}/${component.className}" ||
            (current.startsWith("${component.packageName}/") &&
                current.substringAfter("/").endsWith(".input.LiaKeyboardService"))
    }

    /** Opens the system "Languages & input / On-screen keyboard" settings. */
    fun openInputMethodSettings(context: Context) {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /** Opens the system keyboard-switcher so the user can pick "Lia Voice Keyboard". */
    fun showInputMethodPicker(context: Context) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        imm.showInputMethodPicker()
    }
}
