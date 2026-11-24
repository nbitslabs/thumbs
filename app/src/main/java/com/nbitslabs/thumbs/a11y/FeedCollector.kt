package com.nbitslabs.thumbs.a11y

import android.graphics.PointF
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.nbitslabs.thumbs.mcp.ToolException
import kotlinx.coroutines.delay
import java.util.Locale

data class FeedScreen(
    val lines: List<String>,
    val urls: List<String> = emptyList(),
)

data class CollectedFeed(
    val screensCaptured: Int,
    val items: List<String>,
    val urls: List<String>,
    val reachedEnd: Boolean,
)

/**
 * Pure-Kotlin deduplicator and formatter for multi-screen scrolled social feeds.
 * Keeps zero Android runtime dependencies so JVM unit tests run directly.
 */
object FeedDeduplicator {
    const val SUBSTANTIVE_MIN_LENGTH = 12
    private const val MAX_STICKY_PREFIX = 3

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val URL_REGEX = Regex("https?://[^\\s<>\"\u201c\u201d]+", RegexOption.IGNORE_CASE)

    private val CHROME_LINES = setOf(
        "reply",
        "repost",
        "like",
        "impressions",
        "bookmark",
        "share",
        "verified",
        "verified business",
        "post options",
        "explain this post with grok",
        "home",
        "explore",
        "grok",
        "notifications tab",
        "messages",
        "post",
        "scroll to top",
        "show navigation drawer",
        "thumbs remote control",
        "tap a feature to start using it:",
        "image",
        "video",
        "play",
        "navigate up",
        "more options",
    )

    fun normalize(line: String): String = line.replace(WHITESPACE_REGEX, " ").trim()

    fun isChrome(line: String): Boolean {
        val key = normalize(line).lowercase(Locale.ROOT)
        return key.isEmpty() || key in CHROME_LINES
    }

    /** Extracts unique http(s) URLs from [text], stripping trailing sentence/wrapper punctuation. */
    fun extractUrls(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return URL_REGEX.findAll(text)
            .map { trimTrailingPunctuation(it.value) }
            .filter { it.substringAfter("://").isNotEmpty() }
            .distinct()
            .toList()
    }

    /** Normalizes lines and removes blank and UI-chrome entries. */
    fun cleanLines(lines: List<String>): List<String> = buildList(lines.size) {
        for (raw in lines) {
            val cleaned = normalize(raw)
            if (cleaned.isNotEmpty() && cleaned.lowercase(Locale.ROOT) !in CHROME_LINES) {
                add(cleaned)
            }
        }
    }

    /**
     * Merges consecutive scrolled [screens] in order of first appearance:
     * - strips social UI chrome lines
     * - skips overlapping prefixes matching the previous screen's tail (including a short sticky top header)
     * - deduplicates substantive lines (>= [SUBSTANTIVE_MIN_LENGTH] chars) globally while preserving
     *   short distinct metrics (like "123" or "42") belonging to new posts
     * - collects distinct http(s) URLs across lines and explicit [FeedScreen.urls]
     */
    fun deduplicate(screens: List<FeedScreen>, reachedEnd: Boolean = false): CollectedFeed {
        if (screens.isEmpty()) {
            return CollectedFeed(
                screensCaptured = 0,
                items = emptyList(),
                urls = emptyList(),
                reachedEnd = reachedEnd,
            )
        }

        val items = ArrayList<String>()
        val seenSubstantive = HashSet<String>()
        val urls = LinkedHashSet<String>()
        var prevKeys: List<String>? = null
        var detectedEnd = reachedEnd

        for (screen in screens) {
            val cleaned = cleanLines(screen.lines)
            val keys = cleaned.map { it.lowercase(Locale.ROOT) }

            for (rawUrl in screen.urls) {
                for (u in extractUrls(rawUrl)) urls.add(u)
            }
            for (line in cleaned) {
                for (u in extractUrls(line)) urls.add(u)
            }

            val previous = prevKeys
            if (previous != null && keys == previous) {
                detectedEnd = true
                continue
            }

            val skipPrefix = if (previous == null) 0 else computePrefixSkip(previous, keys)
            for (i in skipPrefix until cleaned.size) {
                val line = cleaned[i]
                val key = keys[i]
                if (key.length >= SUBSTANTIVE_MIN_LENGTH) {
                    if (!seenSubstantive.add(key)) continue
                }
                items.add(line)
            }

            prevKeys = keys
        }

        return CollectedFeed(
            screensCaptured = screens.size,
            items = items,
            urls = urls.toList(),
            reachedEnd = detectedEnd,
        )
    }

    fun format(feed: CollectedFeed, foregroundLabel: String? = null): String = buildString {
        val header = buildList {
            foregroundLabel?.takeIf { it.isNotBlank() }?.let { add("Foreground: $it") }
            add("Screens: ${feed.screensCaptured}")
            add("Items: ${feed.items.size}")
            if (feed.urls.isNotEmpty()) add("URLs: ${feed.urls.size}")
            if (feed.reachedEnd) add("reached end")
        }.joinToString(" · ")
        append(header)

        if (feed.items.isEmpty()) {
            append("\n\n(no feed text collected)")
        } else {
            append("\n\n")
            feed.items.forEachIndexed { index, item ->
                if (index > 0) append('\n')
                append(item)
            }
        }

        if (feed.urls.isNotEmpty()) {
            append("\n\nURLs:\n")
            feed.urls.forEachIndexed { index, url ->
                if (index > 0) append('\n')
                append("- ").append(url)
            }
        }
    }

    private fun computePrefixSkip(previous: List<String>, current: List<String>): Int {
        if (previous.isEmpty() || current.isEmpty()) return 0

        val direct = findTailPrefixOverlap(previous, current)
        if (direct > 0) return direct

        // Handle a short sticky header (e.g. "Search X" / tab bar) pinned at the top of both screens.
        val maxSticky = minOf(MAX_STICKY_PREFIX, previous.size, current.size - 1)
        var sticky = 0
        while (sticky < maxSticky && previous[sticky] == current[sticky]) {
            sticky++
            val restOverlap = findTailPrefixOverlap(previous, current.subList(sticky, current.size))
            if (restOverlap > 0) return sticky + restOverlap
        }
        return 0
    }

    /** Returns the largest k in 1..min(prev.size, curr.size) where prev's last k items equal curr's first k items. */
    private fun findTailPrefixOverlap(previous: List<String>, current: List<String>): Int {
        val maxK = minOf(previous.size, current.size)
        for (k in maxK downTo 1) {
            var match = true
            val offset = previous.size - k
            for (i in 0 until k) {
                if (previous[offset + i] != current[i]) {
                    match = false
                    break
                }
            }
            if (match) return k
        }
        return 0
    }

    private fun trimTrailingPunctuation(raw: String): String {
        var s = raw
        while (s.isNotEmpty()) {
            val last = s.last()
            val strip = when (last) {
                '.', ',', ';', ':', '!', '?', '\'', '"', '\u2019', '\u201d', '>', ']', '}' -> true
                ')' -> s.count { it == ')' } > s.count { it == '(' }
                else -> false
            }
            if (!strip) break
            s = s.dropLast(1)
        }
        return s
    }
}

/**
 * Scrolls the active app feed for [steps] pages, capturing visible non-password text/descriptions
 * in reading order and returning a deduplicated [CollectedFeed].
 */
object FeedCollector {
    private const val MIN_STEPS = 1
    private const val MAX_STEPS = 12
    private const val MIN_AMOUNT = 0.3
    private const val MAX_AMOUNT = 0.9
    private const val MIN_SETTLE_MS = 250L
    private const val MAX_SETTLE_MS = 2500L
    private const val SCROLL_DURATION_MS = 450L
    private const val MAX_NODES_PER_SCREEN = 1500
    private const val MAX_LINE_CHARS = 4000

    suspend fun scrollAndCollect(
        svc: ThumbsAccessibilityService,
        space: CoordinateSpace,
        steps: Int = 5,
        direction: String = "down",
        amount: Double = 0.72,
        settleMs: Long = 700L,
    ): CollectedFeed {
        val clampedSteps = steps.coerceIn(MIN_STEPS, MAX_STEPS)
        val clampedAmount = amount.coerceIn(MIN_AMOUNT, MAX_AMOUNT)
        val clampedSettleMs = settleMs.coerceIn(MIN_SETTLE_MS, MAX_SETTLE_MS)
        val dir = direction.trim().lowercase(Locale.ROOT)
        val (from, to) = scrollPath(space, dir, clampedAmount)

        val screens = ArrayList<FeedScreen>(clampedSteps + 1)
        val first = captureScreen(svc)
        screens.add(first)
        var prevKeys = FeedDeduplicator.cleanLines(first.lines).map { it.lowercase(Locale.ROOT) }
        var reachedEnd = false

        val gestures = Gestures(svc)
        val minWaitMs = (clampedSettleMs / 2L).coerceAtLeast(150L)
        val idleTimeoutMs = (clampedSettleMs - minWaitMs).coerceAtLeast(250L)

        for (step in 1..clampedSteps) {
            gestures.swipe(from, to, SCROLL_DURATION_MS)
            delay(minWaitMs)
            svc.waitForIdle(quietMs = 250L, timeoutMs = idleTimeoutMs)

            val next = captureScreen(svc)
            val nextKeys = FeedDeduplicator.cleanLines(next.lines).map { it.lowercase(Locale.ROOT) }
            if (nextKeys == prevKeys) {
                reachedEnd = true
                break
            }
            screens.add(next)
            prevKeys = nextKeys
        }

        return FeedDeduplicator.deduplicate(screens, reachedEnd = reachedEnd)
    }

    private fun captureScreen(svc: ThumbsAccessibilityService): FeedScreen {
        val windows = svc.orderedWindows()
        val appRoot = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .firstOrNull { w ->
                val root = w.root
                root != null && root.packageName?.toString() != svc.packageName
            }?.root
            ?: svc.rootInActiveWindow?.takeIf { it.packageName?.toString() != svc.packageName }
            ?: return FeedScreen(emptyList())

        val lines = ArrayList<String>()
        var visited = 0

        fun walk(node: AccessibilityNodeInfo) {
            if (visited >= MAX_NODES_PER_SCREEN) return
            if (!node.isVisibleToUser) return
            if (node.isPassword) return
            if (UiTree.isAccessibilityShortcutNode(node)) return
            visited++

            val text = node.text?.toString()
                ?.takeUnless { node.isShowingHintText }
                ?.let { FeedDeduplicator.normalize(it) }
                ?.takeIf { it.isNotEmpty() }
                ?.take(MAX_LINE_CHARS)

            val desc = node.contentDescription?.toString()
                ?.let { FeedDeduplicator.normalize(it) }
                ?.takeIf { it.isNotEmpty() }
                ?.take(MAX_LINE_CHARS)

            if (text != null) lines.add(text)
            if (desc != null && !desc.equals(text, ignoreCase = true)) lines.add(desc)

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)
            }
        }

        walk(appRoot)
        val urls = lines.flatMap { FeedDeduplicator.extractUrls(it) }.distinct()
        return FeedScreen(lines = lines, urls = urls)
    }

    /** Same safe central scroll geometry as PhoneTools.scroll (avoids status and gesture bars). */
    private fun scrollPath(space: CoordinateSpace, direction: String, amount: Double): Pair<PointF, PointF> {
        val region = Rect(
            (space.screenWidth * 0.1).toInt(),
            (space.screenHeight * 0.2).toInt(),
            (space.screenWidth * 0.9).toInt(),
            (space.screenHeight * 0.8).toInt(),
        )
        val cx = region.exactCenterX()
        val cy = region.exactCenterY()
        val halfH = (region.height() * amount / 2.0).toFloat()
        val halfW = (region.width() * amount / 2.0).toFloat()
        return when (direction) {
            "down" -> PointF(cx, cy + halfH) to PointF(cx, cy - halfH)
            "up" -> PointF(cx, cy - halfH) to PointF(cx, cy + halfH)
            "left" -> PointF(cx - halfW, cy) to PointF(cx + halfW, cy)
            "right" -> PointF(cx + halfW, cy) to PointF(cx - halfW, cy)
            else -> throw ToolException("direction must be up, down, left or right")
        }
    }
}
