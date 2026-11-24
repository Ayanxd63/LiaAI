package com.goldai.assistant.voice

import org.json.JSONArray

object LiaToolCatalog {
    fun declarations(): JSONArray {
        val array = JSONArray()

        array.put(
            GeminiToolUtils.functionDecl(
                name = "open_app",
                description = "Opens an installed application on the phone by its name.",
                params = mapOf("app_name" to "The name of the app to launch (e.g. YouTube, WhatsApp, Camera)"),
                required = listOf("app_name")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "call_contact",
                description = "Places a voice call to a phone contact by name or direct number.",
                params = mapOf("contact_name" to "The contact name or phone number to call"),
                required = listOf("contact_name")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "message_contact",
                description = "Prepares a message to a contact in SMS or WhatsApp. The composer opens for user review.",
                params = mapOf(
                    "contact_name" to "Name or phone number of the recipient",
                    "message" to "Text body of the message",
                    "app" to "Optional target app: 'whatsapp' or 'sms' (defaults to 'sms')"
                ),
                required = listOf("contact_name", "message")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "build_website",
                description = "Launches the Website Forge to design, code, and animate a complete website based on a description.",
                params = mapOf("prompt" to "Detailed description of the website to build (e.g., 'Modern cafe landing page with menu and 3D coffee cup')"),
                required = listOf("prompt")
            )
        )

        // V14 — device control tools (SAFE + permission-based only).
        array.put(
            GeminiToolUtils.functionDecl(
                name = "set_brightness",
                description = "Sets the screen brightness to a percentage (0-100). Needs the WRITE_SETTINGS permission; if it is not granted, tell the user to grant it in Settings.",
                params = mapOf("level" to "Brightness percentage from 0 to 100"),
                required = listOf("level")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "set_dnd",
                description = "Turns Do Not Disturb on or off. Needs Notification Policy Access; if it is not granted, tell the user to grant it in Settings.",
                params = mapOf("enabled" to "true to turn Do Not Disturb on, false to turn it off"),
                required = listOf("enabled")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "read_notifications",
                description = "Reads the user's recent notifications out loud (app, title, text). Needs notification access; if it is not granted, tell the user to grant it in Settings."
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "reply_notification",
                description = "Replies to the newest replyable notification, through the original app's own reply action. Never invent a recipient: reply only to a notification that actually arrived.",
                params = mapOf(
                    "app" to "Optional app name to reply to, e.g. WhatsApp (omit to reply to the newest notification)",
                    "message" to "The reply text to send"
                ),
                required = listOf("message")
            )
        )

        return array
    }
}
