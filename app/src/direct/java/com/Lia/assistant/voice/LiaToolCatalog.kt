package com.Lia.assistant.voice

import org.json.JSONArray

object LiaToolCatalog {
    fun declarations(): JSONArray {
        val array = JSONArray()

        // Base tools
        array.put(
            GeminiToolUtils.functionDecl(
                name = "open_app",
                description = "Opens an installed application on the phone by its name.",
                params = mapOf("app_name" to "The name of the app to launch (e.g. YouTube, WhatsApp, Instagram)"),
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
                description = "Prepares and sends a message to a contact in SMS or WhatsApp.",
                params = mapOf(
                    "contact_name" to "Name or phone number of the recipient",
                    "message" to "Text body of the message",
                    "app" to "Target app: 'whatsapp' or 'sms' (defaults to 'sms')"
                ),
                required = listOf("contact_name", "message")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "build_website",
                description = "Launches the Website Forge to design, code, and animate a complete website based on a description.",
                params = mapOf("prompt" to "Detailed description of the website to build"),
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

        // Screen accessibility tools
        array.put(
            GeminiToolUtils.functionDecl(
                name = "read_screen",
                description = "Inspects and returns visible elements, labels, and text on the current screen."
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "tap_text",
                description = "Taps a visible UI element containing the specified text or label.",
                params = mapOf("text" to "Text or label of the button or element to tap"),
                required = listOf("text")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "type_text",
                description = "Types text into the currently focused or active input field.",
                params = mapOf("text" to "Text to type into the active field"),
                required = listOf("text")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "scroll_screen",
                description = "Scrolls the current screen forward or backward.",
                params = mapOf("direction" to "Direction to scroll: 'forward' (down) or 'backward' (up)"),
                required = listOf("direction")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "go_back",
                description = "Performs the system Back action on the phone."
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "go_home",
                description = "Performs the system Home action on the phone."
            )
        )

        // Visual Agent Tools
        array.put(
            GeminiToolUtils.functionDecl(
                name = "execute_social_media_task",
                description = "Navigates and executes actions on Instagram or Facebook. Always requests confirmation before publishing irreversible content.",
                params = mapOf(
                    "platform" to "Platform: 'INSTAGRAM' or 'FACEBOOK'",
                    "action" to "Action: 'CREATE_POST', 'CREATE_REEL', 'CREATE_STORY', or 'TEXT_POST'",
                    "media_uri" to "Optional local content:// URI of the media to share",
                    "caption" to "Caption or text content for the post",
                    "mode" to "'DRAFT' (prepare only) or 'PUBLISH' (requires explicit confirmation before final publish)",
                    "target_account" to "Optional account handle or name"
                ),
                required = listOf("platform", "action")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "social_media_task_control",
                description = "Controls an active social media task. Only call command 'CONFIRM' after the user explicitly said yes.",
                params = mapOf(
                    "command" to "Control command: 'CONFIRM', 'REJECT', 'RESUME', 'CANCEL', or 'STATUS'",
                    "task_id" to "Task ID returned from execute_social_media_task"
                ),
                required = listOf("command", "task_id")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "execute_whatsapp_task",
                description = "Performs WhatsApp tasks such as reading chats, sending messages, or muting notifications.",
                params = mapOf(
                    "action" to "Action: 'READ_UNREAD', 'SEND_MESSAGE', 'READ_CHAT', 'SEARCH_CHAT', 'MUTE_CHAT', 'UNMUTE_CHAT', or 'MARK_READ'",
                    "contact_name" to "Name of contact",
                    "phone_number" to "Optional phone number",
                    "message" to "Message body if sending",
                    "query" to "Search query if searching"
                ),
                required = listOf("action")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "whatsapp_task_control",
                description = "Controls or confirms an active WhatsApp task.",
                params = mapOf(
                    "command" to "Control command: 'CONFIRM', 'REJECT', 'CANCEL', or 'STATUS'",
                    "task_id" to "Task ID"
                ),
                required = listOf("command", "task_id")
            )
        )

        return array
    }
}
