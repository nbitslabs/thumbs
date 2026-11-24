package com.nbitslabs.thumbs.record

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Prompts the phone owner for Android's MediaProjection screen-capture consent and hands the
 * granted token to [ClipRecordingService] so clip recording can run on demand.
 */
class ClipConsentActivity : ComponentActivity() {
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            startForegroundService(ClipRecordingService.startIntent(this, result.resultCode, data))
            Toast.makeText(this, "Clip recording enabled", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Clip recording cancelled", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            if (mpm == null) {
                Toast.makeText(this, "Screen capture unavailable on this device", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            runCatching { captureLauncher.launch(mpm.createScreenCaptureIntent()) }
                .onFailure {
                    Toast.makeText(this, "Unable to request screen recording: ${it.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
        }
    }
}
