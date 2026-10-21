package com.nbitslabs.thumbs.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.nbitslabs.thumbs.mcp.ToolException

/**
 * Hands out short element refs ("e12") for nodes. AccessibilityNodeInfo equality is based on the
 * underlying view and window ids, so the same on-screen element keeps its ref across snapshots
 * for as long as it exists, and concurrent clients don't invalidate each other's refs.
 */
object RefRegistry {
    private const val MAX = 5000
    private var counter = 0
    private val byNode = object : LinkedHashMap<AccessibilityNodeInfo, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AccessibilityNodeInfo, String>): Boolean {
            if (size > MAX) {
                byRef.remove(eldest.value)
                return true
            }
            return false
        }
    }
    private val byRef = HashMap<String, AccessibilityNodeInfo>()

    @Synchronized
    fun refFor(node: AccessibilityNodeInfo): String = byNode.getOrPut(node) {
        "e${++counter}".also { byRef[it] = node }
    }.also {
        // Keep the freshest copy so actions see current state
        byRef[it] = node
    }

    @Synchronized
    fun lookup(ref: String): AccessibilityNodeInfo? = byRef[ref.trim().removePrefix("[").removeSuffix("]")]
}

class TreeOptions(
    val includeInvisible: Boolean = false,
    val interestingOnly: Boolean = true,
    val maxNodes: Int = 600,
    val maxTextLength: Int = 160,
    val includeSystemWindows: Boolean = true,
)

class UiSnapshot(val text: String, val nodeCount: Int, val truncated: Boolean)

object UiTree {
    fun snapshot(service: ThumbsAccessibilityService, space: CoordinateSpace, options: TreeOptions = TreeOptions()): UiSnapshot {
        val sb = StringBuilder()
        var count = 0
        var truncated = false
        val windows = service.orderedWindows()
        val rect = Rect()

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (count >= options.maxNodes) {
                truncated = true
                return
            }
            if (!options.includeInvisible && !node.isVisibleToUser) return
            val printed = !options.interestingOnly || isInteresting(node)
            if (printed) {
                count++
                repeat(depth) { sb.append("  ") }
                node.getBoundsInScreen(rect)
                sb.append(describe(node, space, rect, options.maxTextLength)).append('\n')
            }
            val childDepth = if (printed) depth + 1 else depth
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, childDepth)
            }
        }

        if (windows.isEmpty()) {
            service.rootInActiveWindow?.let {
                sb.append("## window app=${it.packageName}\n")
                walk(it, 0)
            }
        } else {
            for (w in windows) {
                if (!options.includeSystemWindows && w.type != AccessibilityWindowInfo.TYPE_APPLICATION && w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val root = w.root ?: continue
                sb.append("## window ").append(windowType(w.type))
                root.packageName?.let { sb.append(" app=").append(it) }
                w.title?.takeIf { it.isNotBlank() }?.let { sb.append(" title=\"").append(clip(it.toString(), 60)).append('"') }
                if (w.isActive) sb.append(" active")
                if (w.isFocused) sb.append(" focused")
                sb.append('\n')
                walk(root, 1)
            }
        }
        if (truncated) sb.append("… truncated at ${options.maxNodes} elements; pass max_nodes to see more\n")
        return UiSnapshot(sb.toString(), count, truncated)
    }

    fun isInteresting(n: AccessibilityNodeInfo): Boolean =
        !n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank() || !n.hintText.isNullOrBlank() ||
            n.isClickable || n.isLongClickable || n.isEditable || n.isScrollable || n.isCheckable ||
            n.isFocused || n.isAccessibilityFocused || (n.isFocusable && n.viewIdResourceName != null) ||
            n.paneTitle != null

    fun describe(n: AccessibilityNodeInfo, space: CoordinateSpace, screenRect: Rect, maxText: Int): String {
        val sb = StringBuilder()
        sb.append('[').append(RefRegistry.refFor(n)).append("] ")
        sb.append(n.className?.toString()?.substringAfterLast('.') ?: "View")
        val text = n.text?.toString()
        val desc = n.contentDescription?.toString()
        if (n.isPassword) {
            sb.append(" \"•••\"")
        } else if (!text.isNullOrBlank() && !n.isShowingHintText) {
            sb.append(" \"").append(clip(text, maxText)).append('"')
        }
        if (!desc.isNullOrBlank() && desc != text) sb.append(" desc=\"").append(clip(desc, maxText)).append('"')
        n.hintText?.toString()?.takeIf { it.isNotBlank() }?.let { sb.append(" hint=\"").append(clip(it, 60)).append('"') }
        n.paneTitle?.toString()?.takeIf { it.isNotBlank() }?.let { sb.append(" pane=\"").append(clip(it, 60)).append('"') }
        n.viewIdResourceName?.let { sb.append(" id=").append(it.substringAfter(":id/")) }
        val flags = buildList {
            if (n.isClickable) add("clickable")
            if (n.isLongClickable) add("long-clickable")
            if (n.isEditable) add("editable")
            if (n.isScrollable) add("scrollable")
            if (n.isCheckable) add(if (n.isChecked) "checked" else "unchecked")
            if (n.isSelected) add("selected")
            if (n.isFocused) add("focused")
            if (!n.isEnabled) add("disabled")
            if (n.isPassword) add("password")
            if (!n.isVisibleToUser) add("offscreen")
        }
        if (flags.isNotEmpty()) sb.append(" {").append(flags.joinToString(",")).append('}')
        n.stateDescription?.toString()?.takeIf { it.isNotBlank() }?.let { sb.append(" state=\"").append(clip(it, 60)).append('"') }
        n.rangeInfo?.let { sb.append(" range=").append(it.current).append('/').append(it.min).append("..").append(it.max) }
        val b = space.fromScreen(screenRect)
        sb.append(" @").append(b.left).append(',').append(b.top).append(',').append(b.right).append(',').append(b.bottom)
        return sb.toString()
    }

    /** All visible nodes across windows, top-most window first. */
    fun allNodes(service: ThumbsAccessibilityService, includeInvisible: Boolean = false): Sequence<AccessibilityNodeInfo> = sequence {
        for (root in service.roots()) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                if (!includeInvisible && !n.isVisibleToUser) continue
                yield(n)
                for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let(stack::addLast)
            }
        }
    }

    private fun labelsOf(n: AccessibilityNodeInfo): List<String> = listOfNotNull(
        n.text?.toString()?.takeUnless { n.isShowingHintText },
        n.contentDescription?.toString(),
        n.hintText?.toString(),
        n.viewIdResourceName?.substringAfter(":id/"),
    ).filter { it.isNotBlank() }

    /**
     * Finds nodes whose text, description, hint or resource id matches [query]
     * (case-insensitive). Exact matches rank first, then prefix, then substring.
     */
    fun find(service: ThumbsAccessibilityService, query: String, clickableOnly: Boolean = false): List<AccessibilityNodeInfo> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return allNodes(service)
            .mapNotNull { n ->
                if (clickableOnly && clickableAncestor(n) == null) return@mapNotNull null
                val labels = labelsOf(n).map { it.lowercase() }
                val rank = when {
                    labels.any { it == q } -> 0
                    labels.any { it.startsWith(q) } -> 1
                    labels.any { it.contains(q) } -> 2
                    else -> return@mapNotNull null
                }
                rank to n
            }
            .sortedBy { it.first }
            .map { it.second }
            .toList()
    }

    /** Short list of visible labels, used in "not found" errors so the agent can correct itself. */
    fun visibleLabels(service: ThumbsAccessibilityService, limit: Int = 30): List<String> =
        allNodes(service).flatMap { labelsOf(it).take(1) }.map { clip(it, 40) }.distinct().take(limit).toList()

    fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 30) {
            if (n.isClickable) return n
            n = n.parent
            depth++
        }
        return null
    }

    fun scrollableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 30) {
            if (n.isScrollable) return n
            n = n.parent
            depth++
        }
        return null
    }

    /** Resolves a ref and makes sure it still refers to something on screen. */
    fun resolveRef(ref: String): AccessibilityNodeInfo {
        val node = RefRegistry.lookup(ref) ?: throw ToolException("Unknown element ref '$ref'. Call get_ui_tree or find_elements to get current refs.")
        if (!node.refresh()) throw ToolException("Element '$ref' is no longer on screen. Call get_ui_tree again.")
        return node
    }

    fun windowType(type: Int) = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "keyboard"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "divider"
        AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> "magnifier"
        else -> "window($type)"
    }

    private fun clip(s: String, max: Int): String {
        val oneLine = s.replace('\n', ' ').replace("\"", "\\\"")
        return if (oneLine.length <= max) oneLine else oneLine.take(max - 1) + "…"
    }
}
