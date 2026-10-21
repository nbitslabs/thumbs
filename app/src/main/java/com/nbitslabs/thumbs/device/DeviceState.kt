package com.nbitslabs.thumbs.device

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Surface
import android.view.WindowManager
import android.hardware.display.DisplayManager
import android.view.Display
import kotlinx.coroutines.delay

object DeviceState {
    fun isScreenOn(context: Context) = context.getSystemService(PowerManager::class.java).isInteractive

    fun isLocked(context: Context) = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    fun isSecure(context: Context) = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    fun battery(context: Context): Pair<Int, Boolean> {
        val bm = context.getSystemService(BatteryManager::class.java)
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) to bm.isCharging
    }

    fun rotation(context: Context): Int {
        val d = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return when (d.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    fun model() = "${Build.MANUFACTURER} ${Build.MODEL}"

    /**
     * Turns the screen on. If the keyguard isn't secure (swipe/none) it is dismissed too; a
     * secure lock screen stays up and must be unlocked by the user.
     */
    suspend fun wake(context: Context): Boolean {
        if (!isScreenOn(context)) {
            context.startActivity(Intent(context, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            for (i in 0 until 20) {
                if (isScreenOn(context)) break
                delay(100)
            }
            delay(300)
        }
        return isScreenOn(context)
    }
}

/** Invisible activity that turns the screen on and, for insecure keyguards, dismisses it. */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isDeviceSecure) km.requestDismissKeyguard(this, null)
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1200)
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0) else @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
