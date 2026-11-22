package com.Lia.assistant.action

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * V16 (APK-delta addition) — Lia's action list as DATA, not hardcoded
 * rows. The Actions screen reads `assets/lia_actions.json` at runtime
 * and renders whatever it finds; adding a new action is a JSON edit,
 * not a code change.
 *
 * The pattern is the macro-engine idea from the Maya APK study
 * (actions described as data — a command id plus parameters — with a
 * small driver that executes them), adapted to Lia's honest limits:
 * the catalogue can only name commands Lia really has
 * ([DeviceCommand]); the driver below maps each `command` string to
 * its real constructor, and an unknown command simply fails honestly
 * instead of pretending to work. There is no AccessibilityService
 * macro execution here — that stays out of Lia (Play-policy risk).
 *
 * JSON schema (`format: "lia-actions/1"`):
 * ```
 * {
 *   "format": "lia-actions/1", "version": 1,
 *   "sections": [{
 *     "id": "phone", "title": "Phone controls",
 *     "actions": [{
 *       "id": "flashlight_on", "name": "Flashlight on",
 *       "example": "\"Flashlight on karo\"",
 *       "needs": "Camera permission",        // optional, one-line why
 *       "input_hint": "Kitni %? (0–100)",    // optional: ask first
 *       "button": "Open",                    // optional, default "Run"
 *       "command": "flashlight",             // see toDeviceCommand()
 *       "params": { "on": "true" }           // optional, strings only
 *     }]
 *   }]
 * }
 * ```
 * `command` values are the fixed vocabulary in [toDeviceCommand];
 * `params` are plain strings so the file stays simple JSON.
 */
data class CatalogueAction(
    val id: String,
    val name: String,
    val example: String,
    val needs: String? = null,
    val inputHint: String? = null,
    val button: String = "Run",
    val command: String,
    val params: Map<String, String> = emptyMap()
)

data class CatalogueSection(
    val id: String,
    val title: String,
    val actions: List<CatalogueAction>
)

object ActionCatalogue {
    private const val TAG = "ActionCatalogue"
    private const val ASSET_NAME = "lia_actions.json"

    /** What [load] returned, and whether it is the honest fallback. */
    data class LoadResult(
        val sections: List<CatalogueSection>,
        val fromFallback: Boolean
    )

    /**
     * Read the catalogue from assets. On ANY failure (missing file,
     * bad JSON, empty catalogue) the screen still works: it gets a
     * tiny built-in fallback (3 core actions) and `fromFallback` is
     * true, so the UI can say so plainly instead of showing a blank
     * screen. The fallback is deliberately small — it is a safety
     * net, not a second copy of the catalogue.
     */
    fun load(context: Context): LoadResult {
        return try {
            val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            val sections = parse(text)
            if (sections.isEmpty() || sections.all { it.actions.isEmpty() }) {
                Log.w(TAG, "Catalogue parsed but empty — using fallback")
                LoadResult(fallbackSections(), fromFallback = true)
            } else {
                LoadResult(sections, fromFallback = false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load $ASSET_NAME — using fallback", e)
            LoadResult(fallbackSections(), fromFallback = true)
        }
    }

    private fun parse(text: String): List<CatalogueSection> {
        val root = JSONObject(text)
        val sectionsJson = root.optJSONArray("sections") ?: JSONArray()
        val sections = mutableListOf<CatalogueSection>()
        for (i in 0 until sectionsJson.length()) {
            val sectionJson = sectionsJson.optJSONObject(i) ?: continue
            val actionsJson = sectionJson.optJSONArray("actions") ?: JSONArray()
            val actions = mutableListOf<CatalogueAction>()
            for (j in 0 until actionsJson.length()) {
                val a = actionsJson.optJSONObject(j) ?: continue
                val id = a.optString("id").trim()
                val name = a.optString("name").trim()
                val command = a.optString("command").trim()
                if (id.isEmpty() || name.isEmpty() || command.isEmpty()) continue
                val params = mutableMapOf<String, String>()
                val paramsJson = a.optJSONObject("params")
                if (paramsJson != null) {
                    val keys = paramsJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        params[key] = paramsJson.optString(key)
                    }
                }
                actions.add(
                    CatalogueAction(
                        id = id,
                        name = name,
                        example = a.optString("example"),
                        needs = a.optString("needs").takeIf { it.isNotBlank() },
                        inputHint = a.optString("input_hint").takeIf { it.isNotBlank() },
                        button = a.optString("button").takeIf { it.isNotBlank() } ?: "Run",
                        command = command,
                        params = params
                    )
                )
            }
            val title = sectionJson.optString("title").trim()
            if (title.isNotEmpty() && actions.isNotEmpty()) {
                sections.add(
                    CatalogueSection(
                        id = sectionJson.optString("id").trim(),
                        title = title,
                        actions = actions
                    )
                )
            }
        }
        return sections
    }

    private fun fallbackSections(): List<CatalogueSection> {
        return listOf(
            CatalogueSection(
                id = "fallback",
                title = "Basic actions",
                actions = listOf(
                    CatalogueAction(
                        id = "youtube_search",
                        name = "YouTube search",
                        example = "\"YouTube par music search karo\"",
                        inputHint = "Kya search karun? (e.g. music)",
                        command = "youtube_search"
                    ),
                    CatalogueAction(
                        id = "flashlight_on",
                        name = "Flashlight on",
                        example = "\"Flashlight on karo\"",
                        needs = "Camera permission",
                        command = "flashlight",
                        params = mapOf("on" to "true")
                    ),
                    CatalogueAction(
                        id = "read_notifications",
                        name = "Read notifications",
                        example = "\"Mere notifications parho\"",
                        needs = "Notification access",
                        command = "read_notifications"
                    )
                )
            )
        )
    }

    /**
     * The driver: turn one catalogue action (+ the user's typed input,
     * if the action asked for one) into the real [DeviceCommand].
     * Returns null for an unknown `command` — the caller must say so
     * honestly instead of running something else. `input` is the raw
     * dialog text; each branch decides what a blank input means.
     */
    fun toDeviceCommand(action: CatalogueAction, input: String): DeviceCommand? {
        return when (action.command) {
            "youtube_search" -> DeviceCommand.YouTubeSearch(input.ifBlank { "music" })
            "open_app" -> DeviceCommand.OpenApp(input.ifBlank { "YouTube" })
            "dial" -> DeviceCommand.Dial(input)
            "direct_call" -> DeviceCommand.DirectCall(input)
            "whatsapp_message" -> DeviceCommand.WhatsAppMessage(input)
            "flashlight" -> DeviceCommand.Flashlight(action.params["on"] == "true")
            "volume_up" -> DeviceCommand.VolumeUp
            "volume_down" -> DeviceCommand.VolumeDown
            "brightness" -> DeviceCommand.Brightness(level = input.trim().toIntOrNull())
            "dnd" -> DeviceCommand.DoNotDisturb(action.params["on"] == "true")
            "internet_panel" -> DeviceCommand.InternetPanel
            "bluetooth_toggle" -> DeviceCommand.BluetoothToggle(action.params["on"] == "true")
            "set_alarm" -> {
                val parts = input.trim().split(":")
                val hour = parts.getOrNull(0)?.toIntOrNull()
                val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
                if (hour != null) DeviceCommand.SetAlarm(hour, minute, true)
                else DeviceCommand.SetAlarm(0, 0, false)
            }
            "read_notifications" -> DeviceCommand.ReadNotifications
            "reply_notification" -> DeviceCommand.ReplyNotification(null, input)
            "current_app" -> DeviceCommand.CurrentApp
            "top_apps" -> DeviceCommand.TopApps
            "open_settings" -> {
                val screen = runCatching {
                    SettingsScreen.valueOf(action.params["screen"] ?: "MAIN")
                }.getOrDefault(SettingsScreen.MAIN)
                DeviceCommand.OpenSettings(screen)
            }
            else -> null
        }
    }
}
