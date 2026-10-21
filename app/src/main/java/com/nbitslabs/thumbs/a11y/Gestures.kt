package com.nbitslabs.thumbs.a11y

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.graphics.PointF
import com.nbitslabs.thumbs.mcp.ToolException

/** Gesture primitives. All coordinates here are physical screen pixels. */
class Gestures(private val service: ThumbsAccessibilityService) {

    suspend fun tap(p: PointF) = run(GestureDescription.Builder().addStroke(StrokeDescription(point(p), 0, TAP_MS)).build(), "tap")

    suspend fun longPress(p: PointF, durationMs: Long) =
        run(GestureDescription.Builder().addStroke(StrokeDescription(point(p), 0, durationMs.coerceIn(400, 10_000))).build(), "long press")

    suspend fun doubleTap(p: PointF) = run(
        GestureDescription.Builder()
            .addStroke(StrokeDescription(point(p), 0, TAP_MS))
            .addStroke(StrokeDescription(point(p), TAP_MS + 90, TAP_MS))
            .build(),
        "double tap",
    )

    /** A quick flick: the content keeps moving after release (page changes, "next video"). */
    suspend fun swipe(from: PointF, to: PointF, durationMs: Long) =
        run(GestureDescription.Builder().addStroke(StrokeDescription(line(from, to), 0, durationMs.coerceIn(20, 10_000))).build(), "swipe")

    /**
     * A controlled scroll: move, then hold still before lifting so there is no fling and the
     * content moves by about the distance travelled.
     */
    suspend fun scroll(from: PointF, to: PointF, durationMs: Long) {
        val move = StrokeDescription(line(from, to), 0, durationMs.coerceIn(100, 10_000), true)
        run(GestureDescription.Builder().addStroke(move).build(), "scroll")
        val hold = move.continueStroke(point(to), 0, HOLD_MS, false)
        run(GestureDescription.Builder().addStroke(hold).build(), "scroll release")
    }

    /** Press and hold, move, hold, release: drag-and-drop, reordering, sliders. */
    suspend fun drag(from: PointF, to: PointF, holdMs: Long, durationMs: Long) {
        val press = StrokeDescription(point(from), 0, holdMs.coerceIn(50, 5_000), true)
        run(GestureDescription.Builder().addStroke(press).build(), "drag press")
        val move = press.continueStroke(line(from, to), 0, durationMs.coerceIn(100, 10_000), true)
        run(GestureDescription.Builder().addStroke(move).build(), "drag move")
        val release = move.continueStroke(point(to), 0, HOLD_MS, false)
        run(GestureDescription.Builder().addStroke(release).build(), "drag release")
    }

    /** Two-finger pinch around [center]; [scale] > 1 zooms in, < 1 zooms out. */
    suspend fun pinch(center: PointF, startSpan: Float, scale: Float, durationMs: Long, maxX: Float, maxY: Float) {
        val endSpan = (startSpan * scale)
        fun clampX(v: Float) = v.coerceIn(0f, maxX)
        fun clampY(v: Float) = v.coerceIn(0f, maxY)
        val a1 = PointF(clampX(center.x - startSpan / 2), clampY(center.y - startSpan / 2))
        val a2 = PointF(clampX(center.x - endSpan / 2), clampY(center.y - endSpan / 2))
        val b1 = PointF(clampX(center.x + startSpan / 2), clampY(center.y + startSpan / 2))
        val b2 = PointF(clampX(center.x + endSpan / 2), clampY(center.y + endSpan / 2))
        val d = durationMs.coerceIn(100, 5_000)
        run(
            GestureDescription.Builder()
                .addStroke(StrokeDescription(line(a1, a2), 0, d))
                .addStroke(StrokeDescription(line(b1, b2), 0, d))
                .build(),
            "pinch",
        )
    }

    private suspend fun run(g: GestureDescription, what: String) {
        if (!service.dispatch(g)) {
            throw ToolException("The $what gesture was cancelled by the system (the screen may be off or another gesture interrupted it).")
        }
    }

    private fun point(p: PointF) = Path().apply { moveTo(p.x, p.y) }
    private fun line(a: PointF, b: PointF) = Path().apply {
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
    }

    companion object {
        private const val TAP_MS = 60L
        private const val HOLD_MS = 250L
    }
}
