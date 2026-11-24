package com.nbitslabs.thumbs.share

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.nbitslabs.thumbs.ThumbsApp

/**
 * Lightweight transparent receiver for Android's Share sheet (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`).
 * Saves shared text/links into [ThumbsApp.sharedInbox], shows a short toast, and finishes immediately.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare(intent)
        finish()
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun handleShare(intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            showToast("Nothing to save")
            return
        }
        val subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)
            ?.take(MAX_COLLECTED_CHARS)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val sharedText = when (action) {
            Intent.ACTION_SEND -> extractSingleText(intent)
            Intent.ACTION_SEND_MULTIPLE -> extractMultipleText(intent)
            else -> null
        }

        val effectiveText = sharedText?.takeIf { it.isNotBlank() } ?: subject
        if (effectiveText.isNullOrBlank()) {
            showToast("Nothing to save")
            return
        }
        val effectiveSubject = if (sharedText.isNullOrBlank()) null else subject
        val sourceApp = runCatching { referrer?.host?.takeIf { it.isNotBlank() } }.getOrNull()

        runCatching {
            ThumbsApp.get(this).sharedInbox.add(
                text = effectiveText,
                subject = effectiveSubject,
                sourceApp = sourceApp,
            )
        }.onSuccess {
            showToast("Saved to Thumbs")
        }.onFailure { e ->
            Log.w(TAG, "Failed to save shared item", e)
            showToast("Nothing to save")
        }
    }

    private fun extractSingleText(intent: Intent): String? {
        val extra = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.take(MAX_COLLECTED_CHARS)
            ?.toString()
            ?.trim()
        if (!extra.isNullOrEmpty()) return extra
        return extractClipTexts(intent)
    }

    private fun extractMultipleText(intent: Intent): String? {
        val fromChars = runCatching {
            intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.let(::joinBounded)
        }.getOrNull()
        if (!fromChars.isNullOrEmpty()) return fromChars

        val single = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.take(MAX_COLLECTED_CHARS)
            ?.toString()
            ?.trim()
        if (!single.isNullOrEmpty()) return single

        return extractClipTexts(intent)
    }

    private fun extractClipTexts(intent: Intent): String? {
        val clip = intent.clipData ?: return null
        val limit = minOf(clip.itemCount, MAX_PARTS)
        val parts = ArrayList<CharSequence?>(limit)
        for (i in 0 until limit) {
            parts += clip.getItemAt(i)?.text
        }
        return joinBounded(parts)
    }

    private fun joinBounded(items: List<CharSequence?>): String? {
        val out = StringBuilder()
        val limit = minOf(items.size, MAX_PARTS)
        for (i in 0 until limit) {
            if (out.length >= MAX_COLLECTED_CHARS) break
            val remaining = MAX_COLLECTED_CHARS - out.length
            val piece = items[i]?.take(remaining)?.toString()?.trim()
            if (piece.isNullOrEmpty()) continue
            if (out.isNotEmpty()) out.append('\n')
            out.append(piece)
        }
        return out.toString().takeIf { it.isNotEmpty() }
    }

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "ShareReceiver"
        const val MAX_PARTS = 50
        const val MAX_COLLECTED_CHARS = SharedInbox.MAX_TEXT_LENGTH * 2
    }
}
