package com.nbitslabs.thumbs.device

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.nbitslabs.thumbs.mcp.ToolException

class NotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun list(): List<StatusBarNotification> = activeNotifications?.sortedByDescending { it.postTime } ?: emptyList()

    fun find(key: String): StatusBarNotification =
        list().firstOrNull { it.key == key } ?: throw ToolException("No active notification with key '$key'. Call list_notifications again.")

    fun open(key: String) {
        val sbn = find(key)
        val intent = sbn.notification.contentIntent ?: throw ToolException("That notification has no tap action.")
        send(intent, null)
        if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) cancelNotification(key)
    }

    fun action(key: String, index: Int?, title: String?, reply: String?): String {
        val sbn = find(key)
        val actions = sbn.notification.actions ?: emptyArray()
        val action = when {
            index != null -> actions.getOrNull(index)
            title != null -> actions.firstOrNull { it.title?.toString().equals(title, ignoreCase = true) }
                ?: actions.firstOrNull { it.title?.toString()?.contains(title, ignoreCase = true) == true }
            else -> null
        } ?: throw ToolException("No such action. Available: ${actions.mapIndexed { i, a -> "$i:${a.title}" }}")
        val inputs = action.remoteInputs
        if (reply != null) {
            if (inputs.isNullOrEmpty()) throw ToolException("Action '${action.title}' doesn't accept a reply.")
            val fill = Intent()
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, reply) } }
            RemoteInput.addResultsToIntent(inputs, fill, results)
            send(action.actionIntent, fill)
        } else {
            send(action.actionIntent, null)
        }
        return action.title?.toString() ?: "action"
    }

    private fun send(pi: PendingIntent, fill: Intent?) {
        // Lend our background-activity-start privilege (we're bound by the system) so tapping a
        // notification can actually bring its app to the front.
        val options = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= 36) {
                setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
        }.toBundle()
        try {
            pi.send(this, 0, fill, null, null, null, options)
        } catch (e: PendingIntent.CanceledException) {
            throw ToolException("The notification's action is no longer valid.")
        }
    }

    companion object {
        @Volatile var instance: NotificationListener? = null
            private set

        fun require(): NotificationListener = instance ?: throw ToolException(
            "Notification access isn't granted. In the Thumbs app, tap 'Grant notification access'.",
        )
    }
}
