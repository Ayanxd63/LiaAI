package com.Lia.assistant.action

import android.content.Context
import com.Lia.assistant.notification.NotificationStore
import org.json.JSONArray
import org.json.JSONObject

object ActionExecutor {

    suspend fun execute(context: Context, toolName: String, args: JSONObject): JSONObject {
        val result = JSONObject()

        when (toolName) {
            "open_app" -> {
                val appName = args.optString("app_name", "").trim()
                if (appName.isEmpty()) {
                    result.put("status", "error")
                    result.put("message", "App name cannot be empty")
                    return result
                }

                val apps = InstalledAppLabelCache.get(context)
                val packageName = AppLauncher.findApp(appName, apps)

                if (packageName != null) {
                    val launched = AppLauncher.launch(context, packageName)
                    if (launched) {
                        result.put("status", "success")
                        result.put("action", "app_opened")
                        result.put("package", packageName)
                    } else {
                        result.put("status", "error")
                        result.put("action", "launch_failed")
                    }
                } else {
                    result.put("status", "error")
                    result.put("action", "app_not_found")
                    result.put("query", appName)
                }
            }

            "call_contact" -> {
                val query = args.optString("contact_name", "").trim()
                if (query.isEmpty()) {
                    result.put("status", "error")
                    result.put("message", "Contact query is empty")
                    return result
                }

                when (val lookup = ContactResolver.lookup(context, query)) {
                    is ContactLookupResult.Found -> {
                        val callStatus = CommIntents.placeCall(context, lookup.phoneNumber)
                        result.put("status", "success")
                        result.put("action", callStatus)
                        result.put("contact", lookup.displayName)
                        result.put("number", lookup.phoneNumber)
                    }
                    is ContactLookupResult.NoPermission -> {
                        result.put("status", "error")
                        result.put("action", "no_contacts_permission")
                        result.put("message", "Please grant Contacts permission in Settings.")
                    }
                    is ContactLookupResult.NotFound -> {
                        result.put("status", "error")
                        result.put("action", "contact_not_found")
                        result.put("query", query)
                    }
                }
            }

            "message_contact" -> {
                val contactName = args.optString("contact_name", "").trim()
                val messageText = args.optString("message", "").trim()
                val targetApp = args.optString("app", "sms").lowercase().trim()

                if (contactName.isEmpty() || messageText.isEmpty()) {
                    result.put("status", "error")
                    result.put("message", "Recipient and message body are required")
                    return result
                }

                val lookup = ContactResolver.lookup(context, contactName)
                val phoneNumber = when (lookup) {
                    is ContactLookupResult.Found -> lookup.phoneNumber
                    else -> contactName
                }

                val status = if (targetApp == "whatsapp") {
                    CommIntents.openWhatsAppComposer(context, phoneNumber, messageText)
                } else {
                    CommIntents.openSmsComposer(context, phoneNumber, messageText)
                }

                result.put("status", "success")
                result.put("action", status)
                result.put("recipient", phoneNumber)
            }

            // V14 — device control tools (delegated to DeviceActions so the
            // local voice/text path and the Gemini path run the same code).
            "set_brightness" -> {
                val level = args.optInt("level", -1)
                if (level !in 0..100) {
                    result.put("status", "error")
                    result.put("message", "Brightness level must be 0-100")
                    return result
                }
                val reply = DeviceActions.execute(
                    context, DeviceCommand.Brightness(level = level)
                )
                result.put("status", "success")
                result.put("action", "brightness_set")
                result.put("message", reply)
            }

            "set_dnd" -> {
                val enabled = args.optBoolean("enabled", true)
                val reply = DeviceActions.execute(
                    context, DeviceCommand.DoNotDisturb(on = enabled)
                )
                result.put("status", "success")
                result.put("action", "dnd_set")
                result.put("message", reply)
            }

            "read_notifications" -> {
                if (!NotificationStore.isEnabled(context)) {
                    result.put("status", "error")
                    result.put("action", "notification_access_missing")
                    result.put("message", "Please grant notification access in Settings.")
                    return result
                }
                val array = JSONArray()
                NotificationStore.recent(5).forEach { item ->
                    array.put(JSONObject().apply {
                        put("app", item.appLabel)
                        put("title", item.title)
                        put("text", item.text)
                    })
                }
                result.put("status", "success")
                result.put("action", "notifications_read")
                result.put("notifications", array)
            }

            "reply_notification" -> {
                val app = args.optString("app", "").trim().ifEmpty { null }
                val message = args.optString("message", "").trim()
                if (message.isEmpty()) {
                    result.put("status", "error")
                    result.put("message", "Reply message cannot be empty")
                    return result
                }
                if (!NotificationStore.isEnabled(context)) {
                    result.put("status", "error")
                    result.put("action", "notification_access_missing")
                    result.put("message", "Please grant notification access in Settings.")
                    return result
                }
                val target = NotificationStore.latestWithReply(app).firstOrNull()
                if (target == null) {
                    result.put("status", "error")
                    result.put("action", "no_replyable_notification")
                    result.put("message", "No notification found that can be replied to.")
                    return result
                }
                if (NotificationStore.sendReply(context, target, message)) {
                    result.put("status", "success")
                    result.put("action", "notification_replied")
                    result.put("app", target.appLabel)
                } else {
                    result.put("status", "error")
                    result.put("action", "reply_failed")
                    result.put("message", "The reply could not be sent.")
                }
            }

            else -> {
                result.put("error", "Unknown tool: $toolName")
            }
        }

        return result
    }
}
