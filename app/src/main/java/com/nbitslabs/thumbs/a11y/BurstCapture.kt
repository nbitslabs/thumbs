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
        val skippedStaticFrames: Int = 0,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BurstResult) return false
            return jpeg.contentEquals(other.jpeg) &&
                framesCaptured == other.framesCaptured &&
                columns == other.columns &&
                rows == other.rows &&
                durationMs == other.durationMs &&
                timestampsMs == other.timestampsMs &&
                skippedStaticFrames == other.skippedStaticFrames
        }

        override fun hashCode(): Int {
            var result = jpeg.contentHashCode()
            result = 31 * result + framesCaptured
            result = 31 * result + columns
            result = 31 * result + rows
            result = 31 * result + durationMs.hashCode()
            result = 31 * result + timestampsMs.hashCode()
            result = 31 * result + skippedStaticFrames
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
    private const val MIN_CROP_HEIGHT = 400
    private const val STATUS_BAR_CROP_RATIO = 0.045f
    private const val NAV_BAR_CROP_RATIO = 0.035f
    private const val STATIC_DIFF_THRESHOLD = 2.5

    suspend fun captureStoryboard(
        svc: ThumbsAccessibilityService,
        frames: Int = 6,
        intervalMs: Long = 600L,
        quality: Int = 70,
        maxTileWidth: Int = 360,
        cropBars: Boolean = true,
        skipStatic: Boolean = false,
    ): BurstResult {
        val count = frames.coerceIn(MIN_FRAMES, MAX_FRAMES)
        val stepMs = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        val jpegQuality = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)
        val tileMaxWidth = maxTileWidth.coerceIn(MIN_TILE_WIDTH, MAX_TILE_WIDTH)

        val tiles = ArrayList<Bitmap>(count)
        val timestamps = ArrayList<Long>(count)
        var skippedStaticFrames = 0
        var lastElapsedMs = 0L
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
                    val elapsedMs = (capturedAt - firstFrameAt).coerceAtLeast(0L)
                    lastElapsedMs = elapsedMs

                    val keep = withContext(Dispatchers.Default) {
                        val candidate = cropAndDownscale(raw, tileMaxWidth, cropBars)
                        scaled = candidate
                        if (!skipStatic || tiles.isEmpty()) {
                            true
                        } else {
                            meanChannelDiff(tiles.last(), candidate) >= STATIC_DIFF_THRESHOLD
                        }
                    }

                    if (keep) {
                        scaled?.let {
                            tiles += it
                            timestamps += elapsedMs
                            scaled = null
                        }
                    } else {
                        skippedStaticFrames++
                    }
                } finally {
                    if (!raw.isRecycled) raw.recycle()
                    scaled?.let { if (!it.isRecycled) it.recycle() }
                }
            }

            val kept = tiles.size
            val cols = when {
                kept <= 1 -> 1
                kept <= 4 -> 2
                else -> 3
            }
            val rows = (kept + cols - 1) / cols
            val jpeg = withContext(Dispatchers.Default) {
                val stitched = stitchStoryboard(tiles, timestamps, cols, rows)
                sheet = stitched
                val out = ByteArrayOutputStream()
                stitched.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
                out.toByteArray()
            }

            return BurstResult(
                jpeg = jpeg,
                framesCaptured = kept,
                columns = cols,
                rows = rows,
                durationMs = lastElapsedMs,
                timestampsMs = timestamps.toList(),
                skippedStaticFrames = skippedStaticFrames,
            )
        } finally {
            sheet?.let { if (!it.isRecycled) it.recycle() }
            for (tile in tiles) {
                if (!tile.isRecycled) tile.recycle()
            }
        }
    }

    private fun cropAndDownscale(raw: Bitmap, maxWidth: Int, cropBars: Boolean): Bitmap {
        var cropped: Bitmap? = null
        return try {
            val source = if (cropBars && raw.height > MIN_CROP_HEIGHT) {
                val top = (raw.height * STATUS_BAR_CROP_RATIO).toInt().coerceAtLeast(0)
                val bottom = (raw.height * NAV_BAR_CROP_RATIO).toInt().coerceAtLeast(0)
                val croppedH = (raw.height - top - bottom).coerceAtLeast(1)
                Bitmap.createBitmap(raw, 0, top, raw.width, croppedH).also { cropped = it }
            } else {
                raw
            }
            if (source.width <= maxWidth) {
                val existing = cropped
                if (existing != null) {
                    cropped = null
                    existing
                } else {
                    Bitmap.createBitmap(source)
                }
            } else {
                val targetH = ((source.height.toLong() * maxWidth) / source.width).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(source, maxWidth, targetH, true)
                if (scaled === source) Bitmap.createBitmap(source) else scaled
            }
        } finally {
            cropped?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    /**
     * Mean absolute per-channel RGB difference (0.0..255.0) across a fast grid sample.
     * Returns 255.0 when dimensions differ (e.g. mid-burst rotation).
     */
    private fun meanChannelDiff(a: Bitmap, b: Bitmap): Double {
        if (a.width != b.width || a.height != b.height || a.width <= 0 || a.height <= 0) return 255.0
        val stepX = maxOf(1, a.width / 16)
        val stepY = maxOf(1, a.height / 24)
        var sumDiff = 0L
        var samples = 0
        var y = stepY / 2
        while (y < a.height) {
            var x = stepX / 2
            while (x < a.width) {
                val pa = a.getPixel(x, y)
                val pb = b.getPixel(x, y)
                val dr = kotlin.math.abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF))
                val dg = kotlin.math.abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF))
                val db = kotlin.math.abs((pa and 0xFF) - (pb and 0xFF))
                sumDiff += (dr + dg + db)
                samples++
                x += stepX
            }
            y += stepY
        }
        if (samples == 0) return 255.0
        return sumDiff.toDouble() / (samples * 3.0)
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
            color = 0xFF121212.toInt()
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
