package com.nbitslabs.thumbs.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.nbitslabs.thumbs.ThumbsApp
import com.nbitslabs.thumbs.mcp.ToolException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class ThumbsAccessibilityService : AccessibilityService() {

    /** Uptime of the last event that indicates the UI changed; used to wait for the screen to settle. */
    @Volatile var lastUiEventAt: Long = 0
        private set

    @Volatile var foregroundPackage: String? = null
        private set

    @Volatile var foregroundClass: String? = null
        private set

    private val screenshotLock = Mutex()
    private var lastScreenshotAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
        // The server is useless without this service, so make sure it's running now that we are
        ThumbsApp.get(this).startServerIfEnabled()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastUiEventAt = SystemClock.uptimeMillis()
                val pkg = event.packageName?.toString()
                val cls = event.className?.toString()
                // Ignore transient system UI (toasts, IME) so "current app" means the app in front
                if (pkg != null && pkg != packageName && cls != null && !cls.startsWith("android.widget.") &&
                    !cls.startsWith("android.inputmethodservice.")
                ) {
                    foregroundPackage = pkg
                    foregroundClass = cls
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            -> lastUiEventAt = SystemClock.uptimeMillis()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        clearInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearInstance()
        super.onDestroy()
    }

    private fun clearInstance() {
        if (instance === this) {
            instance = null
            _connected.value = false
        }
    }

    // --- Windows ---------------------------------------------------------------------------

    /** Windows ordered top-most first, with a fallback for when window retrieval is unavailable. */
    fun orderedWindows(): List<AccessibilityWindowInfo> = runCatching { windows.sortedByDescending { it.layer } }.getOrDefault(emptyList())

    fun roots(): List<AccessibilityNodeInfo> {
        val fromWindows = orderedWindows().mapNotNull { it.root }
        return fromWindows.ifEmpty { listOfNotNull(rootInActiveWindow) }
    }

    fun isKeyboardVisible(): Boolean = orderedWindows().any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    /** The input-focused node, searching every window (the focused field may not be in the active one). */
    fun focusedInput(): AccessibilityNodeInfo? {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        return roots().firstNotNullOfOrNull { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
    }

    /** Package of the app window in front, falling back to the last window-state event. */
    fun currentPackage(): String? {
        val appWindow = orderedWindows().firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root != null }
        return appWindow?.root?.packageName?.toString() ?: foregroundPackage
    }

    /** Waits until no UI-change events arrive for [quietMs], up to [timeoutMs]. Returns true if it settled. */
    suspend fun waitForIdle(quietMs: Long = 400, timeoutMs: Long = 2000): Boolean {
        val start = SystemClock.uptimeMillis()
        val deadline = start + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            // Measure from the later of the last event and the start of the wait, so a screen
            // that was quiet *before* an action doesn't count as settled right after it
            val since = SystemClock.uptimeMillis() - maxOf(lastUiEventAt, start)
            if (since >= quietMs) return true
            delay((quietMs - since).coerceIn(20, 100))
        }
        return false
    }

    // --- Gestures --------------------------------------------------------------------------

    suspend fun dispatch(gesture: GestureDescription, timeoutMs: Long = 15_000): Boolean {
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }, null)
                if (!accepted && cont.isActive) cont.resume(false)
            }
        }
        return result == true
    }

    fun global(action: Int): Boolean = performGlobalAction(action)

    // --- Screenshots -----------------------------------------------------------------------

    /**
     * Captures the default display. The platform rejects requests closer than ~333ms apart, so
     * calls are serialised and spaced out, retrying once if we still hit the interval limit.
     */
    suspend fun screenshot(): Bitmap = screenshotLock.withLock {
        repeat(3) { attempt ->
            val wait = MIN_SCREENSHOT_INTERVAL_MS - (SystemClock.uptimeMillis() - lastScreenshotAt)
            if (wait > 0) delay(wait)
            lastScreenshotAt = SystemClock.uptimeMillis()
            when (val r = captureOnce()) {
                is Capture.Ok -> return@withLock r.bitmap
                is Capture.Failed -> {
                    if (r.code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempt < 2) {
                        delay(MIN_SCREENSHOT_INTERVAL_MS)
                    } else {
                        throw ToolException(screenshotError(r.code))
                    }
                }
            }
        }
        throw ToolException("Screenshot failed after retries")
    }

    private sealed interface Capture {
        class Ok(val bitmap: Bitmap) : Capture
        class Failed(val code: Int) : Capture
    }

    private suspend fun captureOnce(): Capture = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val capture = try {
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    // Hardware bitmaps can't be scaled/compressed in software; copy out first
                    hw?.copy(Bitmap.Config.ARGB_8888, false)?.also { hw.recycle() }
                } finally {
                    buffer.close()
                }
                if (cont.isActive) cont.resume(if (capture != null) Capture.Ok(capture) else Capture.Failed(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR))
            }

            override fun onFailure(errorCode: Int) {
                if (cont.isActive) cont.resume(Capture.Failed(errorCode))
            }
        })
    }

    private fun screenshotError(code: Int) = when (code) {
        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
            "The screen shows a secure window (FLAG_SECURE, e.g. a banking app or password prompt) which Android won't let us capture. Use get_ui_tree instead."
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "Screenshot permission is missing from the accessibility service."
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Screenshots requested too quickly; try again."
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "Invalid display."
        else -> "Screenshot failed (error $code). The screen may be off; try wake_screen."
    }

    companion object {
        private const val MIN_SCREENSHOT_INTERVAL_MS = 350L

        @Volatile var instance: ThumbsAccessibilityService? = null
            private set

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        fun require(): ThumbsAccessibilityService = instance ?: throw ToolException(
            "The Thumbs accessibility service isn't running. On the phone, open Settings → Accessibility → Thumbs and turn it on.",
        )
    }
}
