package com.nbitslabs.thumbs.a11y

import android.content.Context
import android.graphics.Rect
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The coordinate system shared by every tool: screenshots are delivered at exactly this size,
 * and all x/y inputs and element bounds use it.
 *
 * Phone screens are larger than what vision models ingest (Claude downsamples anything over
 * ~1568px on the long edge or ~1.15MP), and a model's coordinates refer to the image it saw.
 * Scaling once, here, deterministically, means "a point in the screenshot" and "a point to tap"
 * are always the same thing, whatever client is on the other end.
 */
data class CoordinateSpace(val screenWidth: Int, val screenHeight: Int, val scale: Double) {
    val width: Int get() = (screenWidth * scale).roundToInt()
    val height: Int get() = (screenHeight * scale).roundToInt()

    fun toScreenX(x: Double): Float = (x / scale).toFloat().coerceIn(0f, screenWidth - 1f)
    fun toScreenY(y: Double): Float = (y / scale).toFloat().coerceIn(0f, screenHeight - 1f)

    fun fromScreen(v: Int): Int = (v * scale).roundToInt()
    fun fromScreen(r: Rect): Rect = Rect(fromScreen(r.left), fromScreen(r.top), fromScreen(r.right), fromScreen(r.bottom))

    fun describe(): String =
        "Coordinate space is ${width}x$height (screen ${screenWidth}x$screenHeight px, scale ${"%.4f".format(scale)}). " +
            "Screenshots are this size and all x/y values use it."

    companion object {
        const val MAX_LONG_EDGE = 1568
        const val MAX_PIXELS = 1_150_000.0

        fun forScreen(width: Int, height: Int): CoordinateSpace {
            val long = maxOf(width, height).toDouble()
            val scale = min(1.0, min(MAX_LONG_EDGE / long, sqrt(MAX_PIXELS / (width.toDouble() * height))))
            return CoordinateSpace(width, height, scale)
        }

        fun current(context: Context): CoordinateSpace {
            // DisplayManager works from any context (WindowManager needs a visual one). Real size
            // is the full panel in its current rotation, which is what screenshots capture.
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            return forScreen(size.x, size.y)
        }
    }
}
