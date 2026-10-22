package com.nbitslabs.thumbs.safety

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.nbitslabs.thumbs.a11y.ThumbsAccessibilityService
import com.nbitslabs.thumbs.auth.ApiKey
import com.nbitslabs.thumbs.device.DeviceState
import com.nbitslabs.thumbs.mcp.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

enum class ConfirmMode {
    /** Agents act without asking (default). */
    OFF,

    /** Ask before actions that look like sending, paying, deleting and similar. */
    RISKY,

    /** Ask before every control action. */
    ALL,
}

/**
 * Opt-in approval of agent actions by the person holding the phone.
 *
 * The prompt is an accessibility overlay drawn by our own service. Agents can't approve it
 * themselves: control tools run one at a time, so no agent gesture can happen while a prompt
 * is waiting, and our windows are hidden from the UI tree and screenshots.
 */
class Confirmations(
    private val context: Context,
    private val mode: () -> ConfirmMode,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private enum class Answer { ALLOW, ALLOW_FOR_A_WHILE, DENY }

    /** Key id → time until which that key may act without asking. */
    private val grants = ConcurrentHashMap<String, Long>()
    private val promptLock = Mutex()

    fun needsAsking(key: ApiKey, risky: Boolean): Boolean {
        val m = mode()
        if (m == ConfirmMode.OFF || (m == ConfirmMode.RISKY && !risky)) return false
        return (grants[key.id] ?: 0) <= clock()
    }

    /** Whether the current mode cares about [risky] actions' labels at all (so callers can skip the work). */
    val active: Boolean get() = mode() != ConfirmMode.OFF

    /**
     * Returns when the action may go ahead; throws a [ToolException] (reported to the agent)
     * when the user declines or doesn't answer in time.
     */
    suspend fun check(svc: ThumbsAccessibilityService, key: ApiKey, action: String, risky: Boolean) {
        if (!needsAsking(key, risky)) return
        promptLock.withLock {
            // Another prompt may have granted this key a while in the meantime
            if (!needsAsking(key, risky)) return
            if (!DeviceState.isScreenOn(context)) DeviceState.wake(svc)
            svc.keepScreenOn(TIMEOUT_MS + 5_000)
            val answer = withTimeoutOrNull(TIMEOUT_MS) { prompt(svc, key.name, action, risky) }
            awaitPromptGone(svc)
            when (answer) {
                Answer.ALLOW -> Unit
                Answer.ALLOW_FOR_A_WHILE -> grants[key.id] = clock() + GRANT_MS
                Answer.DENY -> throw ToolException(
                    "The person holding the phone declined: $action. Don't retry this unless they ask you to.",
                )
                null -> throw ToolException(
                    "This needs approval on the phone and nobody answered within ${TIMEOUT_MS / 1000}s, so it wasn't done: " +
                        "$action. Ask the user to watch the phone, then retry.",
                )
            }
        }
    }

    /**
     * Removing a window is asynchronous; until it's gone it would swallow the gesture the user
     * just approved, so wait for it to leave the window list.
     */
    private suspend fun awaitPromptGone(svc: ThumbsAccessibilityService) {
        val deadline = SystemClock.uptimeMillis() + 1500
        val bounds = Rect()
        while (SystemClock.uptimeMillis() < deadline) {
            val showing = svc.orderedWindows().any { w ->
                w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    w.root?.packageName?.toString() == svc.packageName &&
                    run { w.getBoundsInScreen(bounds); bounds.width() > 1 && bounds.height() > 1 }
            }
            if (!showing) break
            delay(50)
        }
        delay(150)
    }

    private suspend fun prompt(svc: ThumbsAccessibilityService, keyName: String, action: String, risky: Boolean): Answer =
        withContext(Dispatchers.Main) {
            coroutineScope {
                val shownAt = SystemClock.uptimeMillis()
                var view: View? = null
                try {
                    suspendCancellableCoroutine { cont ->
                        val answer = { a: Answer ->
                            // Ignore taps in the first moments, which were meant for the screen underneath
                            if (SystemClock.uptimeMillis() - shownAt > ARM_DELAY_MS && cont.isActive) cont.resume(a)
                        }
                        val (root, countdown) = buildPrompt(svc, keyName, action, risky, answer)
                        view = root
                        svc.getSystemService(WindowManager::class.java).addView(root, layoutParams())
                        vibrate()
                        launch {
                            val deadline = shownAt + TIMEOUT_MS
                            while (true) {
                                val left = (deadline - SystemClock.uptimeMillis()) / 1000
                                countdown.text = "Declines automatically in ${left.coerceAtLeast(0)}s"
                                delay(1000)
                            }
                        }
                    }
                } finally {
                    view?.let { runCatching { svc.getSystemService(WindowManager::class.java).removeView(it) } }
                    coroutineContext.cancelChildren()
                }
            }
        }

    private fun layoutParams() = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Secure: agents' screenshots fail instead of seeing the prompt
        WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        PixelFormat.TRANSLUCENT,
    )

    private fun buildPrompt(
        svc: ThumbsAccessibilityService,
        keyName: String,
        action: String,
        risky: Boolean,
        answer: (Answer) -> Unit,
    ): Pair<View, TextView> {
        val ctx = ContextThemeWrapper(svc, android.R.style.Theme_DeviceDefault_DayNight)
        val dark = (svc.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val onSurface = if (dark) 0xFFE6E0E9.toInt() else 0xFF1D1B20.toInt()
        val muted = if (dark) 0xFFCAC4D0.toInt() else 0xFF49454F.toInt()
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), svc.resources.displayMetrics).toInt()
        fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(if (dark) 0xFF2B2930.toInt() else 0xFFF7F2FA.toInt())
            }
            isClickable = true
        }
        card.addView(text("Allow “$keyName” to do this?", 20f, onSurface, bold = true))
        card.addView(text(action, 16f, onSurface).apply { setPadding(0, dp(16), 0, dp(8)); maxLines = 10 })
        if (risky) card.addView(text("This looks like sending, paying, deleting or a similar action.", 13f, muted))
        val countdown = text("", 13f, muted).apply { setPadding(0, dp(4), 0, dp(16)) }
        card.addView(countdown)
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Deny"
            setOnClickListener { answer(Answer.DENY) }
        })
        buttons.addView(Button(ctx).apply {
            text = "Allow"
            setOnClickListener { answer(Answer.ALLOW) }
        })
        card.addView(buttons)
        card.addView(Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Allow everything from “$keyName” for ${GRANT_MS / 60_000} min"
            isAllCaps = false
            setOnClickListener { answer(Answer.ALLOW_FOR_A_WHILE) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val root = FrameLayout(ctx).apply {
            setBackgroundColor(0x99000000.toInt())
            // Swallow touches so nothing underneath is tapped by accident
            isClickable = true
        }
        val width = minOf(svc.resources.displayMetrics.widthPixels - dp(32), dp(440))
        root.addView(card, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return root to countdown
    }

    private fun vibrate() {
        runCatching {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
                .vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
        }
    }

    companion object {
        /** Under common MCP client tool timeouts (Codex defaults to 60s). */
        const val TIMEOUT_MS = 45_000L
        const val GRANT_MS = 10 * 60_000L
        private const val ARM_DELAY_MS = 600L
    }
}
