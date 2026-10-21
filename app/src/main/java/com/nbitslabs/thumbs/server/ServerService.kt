package com.nbitslabs.thumbs.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nbitslabs.thumbs.BuildConfig
import com.nbitslabs.thumbs.R
import com.nbitslabs.thumbs.ThumbsApp
import com.nbitslabs.thumbs.mcp.McpServer
import com.nbitslabs.thumbs.mcp.PhoneTools
import com.nbitslabs.thumbs.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service hosting the MCP HTTP server. The persistent notification doubles as a
 * security indicator: it shows when an agent is actively using the phone and offers Stop.
 */
class ServerService : LifecycleService() {
    private var server: HttpServer? = null
    private var activeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_server), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_server_description)
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val app = ThumbsApp.get(this)
        if (intent?.action == ACTION_STOP) {
            app.stopServer()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (intent?.action == ACTION_RESTART) {
            server?.stop()
            server = null
        }
        if (server == null) startServer(app)
        return START_STICKY
    }

    private fun startServer(app: ThumbsApp) {
        val port = app.settings.port.value
        val tools = PhoneTools(applicationContext) { app.settings.autoWake.value }
        val toolList = tools.all()
        val mcp = McpServer(
            tools = { toolList },
            serverVersion = BuildConfig.VERSION_NAME,
            instructions = tools.instructions,
            onAudit = app::recordAudit,
        )
        val http = HttpServer(port, { app.settings.networkMode.value }, app.keys, mcp, ::onClientRequest)
        try {
            http.start()
            server = http
            app.updateStatus { it.copy(running = true, port = port, error = null) }
        } catch (e: Exception) {
            Log.e(TAG, "Server failed to start", e)
            app.updateStatus { it.copy(running = false, error = "Couldn't listen on port $port: ${e.message}") }
            stopSelf()
        }
    }

    private fun onClientRequest(keyName: String) {
        val app = ThumbsApp.get(this)
        val now = System.currentTimeMillis()
        val wasActive = now - app.status.value.lastRequestAt < ACTIVE_WINDOW_MS
        app.updateStatus { it.copy(lastClient = keyName, lastRequestAt = now) }
        if (!wasActive) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(keyName))
        activeJob?.cancel()
        activeJob = lifecycleScope.launch {
            delay(ACTIVE_WINDOW_MS)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(null))
        }
    }

    private fun notification(activeClient: String?): Notification {
        val port = ThumbsApp.get(this).settings.port.value
        val address = AccessPolicy.tailscaleAddresses().firstOrNull()?.hostAddress
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (activeClient != null) {
            getString(R.string.notification_active, activeClient)
        } else if (address != null) {
            getString(R.string.notification_listening, "$address:$port")
        } else {
            getString(R.string.notification_listening_no_tailscale, port)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(if (activeClient != null) R.string.notification_title_active else R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        ThumbsApp.get(this).updateStatus { it.copy(running = false) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ThumbsServer"
        private const val CHANNEL = "server"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.nbitslabs.thumbs.STOP"
        const val ACTION_RESTART = "com.nbitslabs.thumbs.RESTART"
        private const val ACTIVE_WINDOW_MS = 15_000L
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ThumbsApp.get(context).startServerIfEnabled()
        }
    }
}
