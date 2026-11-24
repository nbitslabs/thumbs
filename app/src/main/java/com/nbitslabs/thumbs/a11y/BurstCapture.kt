package com.nbitslabs.thumbs.a11y

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Captures a timed burst of screenshots and stitches them into a single JPEG contact sheet
 * with timestamp badges (`t+0.0s`, `t+0.6s`, ...) for analysing animations and video motion.
 */
object BurstCapture {

    data class BurstResult(
        val jpeg: ByteArray,
        val framesCaptured: Int,
        val columns: Int,
        val rows: Int,
        val durationMs: Long,
        val timestampsMs: List<Long>,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BurstResult) return false
            return jpeg.contentEquals(other.jpeg) &&
                framesCaptured == other.framesCaptured &&
                columns == other.columns &&
                rows == other.rows &&
                durationMs == other.durationMs &&
                timestampsMs == other.timestampsMs
        }

        override fun hashCode(): Int {
            var result = jpeg.contentHashCode()
            result = 31 * result + framesCaptured
            result = 31 * result + columns
            result = 31 * result + rows
            result = 31 * result + durationMs.hashCode()
            result = 31 * result + timestampsMs.hashCode()
            return result
        }
    }

    private const val MIN_FRAMES = 2
    private const val MAX_FRAMES = 12
    private const val MIN_INTERVAL_MS = 400L
    private const val MAX_INTERVAL_MS = 3000L
    private const val MIN_QUALITY = 30
    private const val MAX_QUALITY = 95
    private const val MIN_TILE_WIDTH = 180
    private const val MAX_TILE_WIDTH = 480
    private const val PADDING_PX = 8

    suspend fun captureStoryboard(
        svc: ThumbsAccessibilityService,
        frames: Int = 6,
        intervalMs: Long = 600L,
        quality: Int = 70,
        maxTileWidth: Int = 360,
    ): BurstResult {
        val count = frames.coerceIn(MIN_FRAMES, MAX_FRAMES)
        val stepMs = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        val jpegQuality = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)
        val tileMaxWidth = maxTileWidth.coerceIn(MIN_TILE_WIDTH, MAX_TILE_WIDTH)

        val tiles = ArrayList<Bitmap>(count)
        val timestamps = ArrayList<Long>(count)
        var sheet: Bitmap? = null

        try {
            var firstFrameAt = 0L
            for (i in 0 until count) {
                if (i > 0) {
                    val targetAt = firstFrameAt + i * stepMs
                    val waitMs = targetAt - SystemClock.uptimeMillis()
                    if (waitMs > 0) delay(waitMs)
                }

                val raw = svc.screenshot()
                var scaled: Bitmap? = null
                try {
                    val capturedAt = SystemClock.uptimeMillis()
                    if (i == 0) firstFrameAt = capturedAt
                    timestamps += (capturedAt - firstFrameAt).coerceAtLeast(0L)
                    withContext(Dispatchers.Default) {
                        scaled = downscale(raw, tileMaxWidth)
                    }
                    scaled?.let {
                        tiles += it
                        scaled = null
                    }
                } finally {
                    if (!raw.isRecycled) raw.recycle()
                    scaled?.let { if (!it.isRecycled) it.recycle() }
                }
            }

            val cols = if (count <= 4) 2 else 3
            val rows = (count + cols - 1) / cols
            val jpeg = withContext(Dispatchers.Default) {
                val stitched = stitchStoryboard(tiles, timestamps, cols, rows)
                sheet = stitched
                val out = ByteArrayOutputStream()
                stitched.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
                out.toByteArray()
            }

            return BurstResult(
                jpeg = jpeg,
                framesCaptured = tiles.size,
                columns = cols,
                rows = rows,
                durationMs = timestamps.lastOrNull() ?: 0L,
                timestampsMs = timestamps.toList(),
            )
        } finally {
            sheet?.let { if (!it.isRecycled) it.recycle() }
            for (tile in tiles) {
                if (!tile.isRecycled) tile.recycle()
            }
        }
    }

    private fun downscale(raw: Bitmap, maxWidth: Int): Bitmap {
        if (raw.width <= maxWidth) {
            return Bitmap.createBitmap(raw)
        }
        val targetH = ((raw.height.toLong() * maxWidth) / raw.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(raw, maxWidth, targetH, true)
        return if (scaled === raw) Bitmap.createBitmap(raw) else scaled
    }

    private fun stitchStoryboard(
        tiles: List<Bitmap>,
        timestamps: List<Long>,
        columns: Int,
        rows: Int,
    ): Bitmap {
        val cellW = tiles.maxOf { it.width }.coerceAtLeast(1)
        val cellH = tiles.maxOf { it.height }.coerceAtLeast(1)
        val sheetW = PADDING_PX + columns * (cellW + PADDING_PX)
        val sheetH = PADDING_PX + rows * (cellH + PADDING_PX)

        val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(18, 18, 22))

        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(56, 56, 64)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        val textSize = (cellW * 0.065f).coerceIn(14f, 28f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.textSize = textSize
        }
        val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(196, 12, 12, 16)
            style = Paint.Style.FILL
        }

        for (i in tiles.indices) {
            val col = i % columns
            val row = i / columns
            val cellLeft = (PADDING_PX + col * (cellW + PADDING_PX)).toFloat()
            val cellTop = (PADDING_PX + row * (cellH + PADDING_PX)).toFloat()
            val tile = tiles[i]

            val dest = fitRect(tile.width, tile.height, cellLeft, cellTop, cellW.toFloat(), cellH.toFloat())
            canvas.drawBitmap(tile, null, dest, bitmapPaint)
            canvas.drawRect(cellLeft, cellTop, cellLeft + cellW, cellTop + cellH, borderPaint)

            val label = String.format(Locale.US, "t+%.1fs", timestamps[i] / 1000.0)
            drawTimestampBadge(canvas, label, cellLeft, cellTop, textPaint, badgeBgPaint)
        }

        return sheet
    }

    private fun fitRect(srcW: Int, srcH: Int, left: Float, top: Float, cellW: Float, cellH: Float): RectF {
        if (srcW <= 0 || srcH <= 0) return RectF(left, top, left + cellW, top + cellH)
        val scale = minOf(cellW / srcW.toFloat(), cellH / srcH.toFloat())
        val drawW = srcW * scale
        val drawH = srcH * scale
        val dx = left + (cellW - drawW) / 2f
        val dy = top + (cellH - drawH) / 2f
        return RectF(dx, dy, dx + drawW, dy + drawH)
    }

    private fun drawTimestampBadge(
        canvas: Canvas,
        label: String,
        cellLeft: Float,
        cellTop: Float,
        textPaint: Paint,
        badgeBgPaint: Paint,
    ) {
        val inset = 6f
        val hPad = textPaint.textSize * 0.45f
        val vPad = textPaint.textSize * 0.25f
        val radius = textPaint.textSize * 0.25f
        val fm = textPaint.fontMetrics
        val textW = textPaint.measureText(label)
        val textH = fm.descent - fm.ascent

        val badgeRect = RectF(
            cellLeft + inset,
            cellTop + inset,
            cellLeft + inset + textW + hPad * 2f,
            cellTop + inset + textH + vPad * 2f,
        )
        canvas.drawRoundRect(badgeRect, radius, radius, badgeBgPaint)
        canvas.drawText(label, badgeRect.left + hPad, badgeRect.top + vPad - fm.ascent, textPaint)
    }
}
