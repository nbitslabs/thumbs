package com.nbitslabs.thumbs.a11y

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.nbitslabs.thumbs.mcp.ToolException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class TextScript { LATIN, CHINESE }

enum class TextGranularity { BLOCK, LINE, WORD }

/** A piece of recognised text with its bounds in screen pixels. */
class ScreenTextItem(val text: String, val bounds: Rect, val confidence: Float?)

/**
 * On-device OCR (ML Kit) for screens that don't expose their content to accessibility, such
 * as WeChat, games, WebView-rendered email bodies, or images of text.
 */
object ScreenText {
    private val latin: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // Also reads Latin script, so it suits mixed Chinese/English screens
    private val chinese: TextRecognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    /**
     * Recognises text in [bitmap]. Bounds are offset by [origin] so that a crop's results come
     * back in full-screen pixels. Results are in reading order (top to bottom, then left to right).
     */
    suspend fun recognize(
        bitmap: Bitmap,
        script: TextScript = TextScript.LATIN,
        granularity: TextGranularity = TextGranularity.LINE,
        origin: Rect? = null,
    ): List<ScreenTextItem> {
        val recognizer = if (script == TextScript.CHINESE) chinese else latin
        val result = suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(ToolException("Text recognition failed: ${it.message}")) }
        }
        val dx = origin?.left ?: 0
        val dy = origin?.top ?: 0
        return items(result, granularity)
            .mapNotNull { (text, box, confidence) ->
                if (box == null || text.isBlank()) null else ScreenTextItem(text, Rect(box).apply { offset(dx, dy) }, confidence)
            }
            .sortedWith(compareBy<ScreenTextItem> { it.bounds.centerY() / ROW_TOLERANCE_PX }.thenBy { it.bounds.left })
    }

    private fun items(text: Text, granularity: TextGranularity): List<Triple<String, Rect?, Float?>> = when (granularity) {
        TextGranularity.BLOCK -> text.textBlocks.map { Triple(it.text.replace('\n', ' '), it.boundingBox, null) }
        TextGranularity.LINE -> text.textBlocks.flatMap { b -> b.lines.map { Triple(it.text, it.boundingBox, it.confidence) } }
        TextGranularity.WORD -> text.textBlocks.flatMap { b -> b.lines.flatMap { l -> l.elements.map { Triple(it.text, it.boundingBox, it.confidence) } } }
    }

    /**
     * Best match for [query] (case-insensitive): exact, then prefix, then substring, preferring
     * shorter text so "Send" beats a message that mentions sending.
     */
    fun find(items: List<ScreenTextItem>, query: String): ScreenTextItem? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        return items
            .mapNotNull { item ->
                val t = item.text.trim().lowercase()
                val rank = when {
                    t == q -> 0
                    t.startsWith(q) -> 1
                    t.contains(q) -> 2
                    else -> return@mapNotNull null
                }
                Triple(rank, t.length, item)
            }
            .minWithOrNull(compareBy<Triple<Int, Int, ScreenTextItem>> { it.first }.thenBy { it.second })
            ?.third
    }

    /** Lines whose vertical centres are this close are treated as the same row when sorting. */
    private const val ROW_TOLERANCE_PX = 24
}
