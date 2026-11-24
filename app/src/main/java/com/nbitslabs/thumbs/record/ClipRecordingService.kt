package com.nbitslabs.thumbs.record

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.nbitslabs.thumbs.R
import com.nbitslabs.thumbs.ThumbsApp
import com.nbitslabs.thumbs.ui.MainActivity

/**
 * Foreground service holding the active `MediaProjection` token required on Android 14+ before
 * capturing bounded screen clips.
 */
class ClipRecordingService : Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Clip recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Active while Thumbs is permitted to record short screen clips"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ThumbsApp.get(this).clipRecorder.releaseProjection()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14+ requires startForeground with mediaProjection type BEFORE getMediaProjection().
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (intent != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA)
            }
        } else {
            null
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "Missing MediaProjection grant extras; stopping ClipRecordingService")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val projection = runCatching { mpm.getMediaProjection(resultCode, data) }
            .onFailure { Log.e(TAG, "Failed to obtain MediaProjection", it) }
            .getOrNull()

        if (projection == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        ThumbsApp.get(this).clipRecorder.attachProjection(projection) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ThumbsApp.get(this).clipRecorder.releaseProjection()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Clip recording ready")
            .setContentText("Thumbs can record short screen clips on request")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stopPending).build())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "ThumbsClipService"
        private const val CHANNEL_ID = "clip_recording"
        private const val NOTIFICATION_ID = 2

        const val ACTION_START = "com.nbitslabs.thumbs.record.START"
        const val ACTION_STOP = "com.nbitslabs.thumbs.record.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ClipRecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ClipRecordingService::class.java).apply {
                action = ACTION_STOP
            }
    }
}
