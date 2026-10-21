package com.nbitslabs.thumbs.mcp

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.nbitslabs.thumbs.auth.Scope
import com.nbitslabs.thumbs.a11y.CoordinateSpace
import com.nbitslabs.thumbs.a11y.Gestures
import com.nbitslabs.thumbs.a11y.TextInput
import com.nbitslabs.thumbs.a11y.ThumbsAccessibilityService
import com.nbitslabs.thumbs.a11y.TreeOptions
import com.nbitslabs.thumbs.a11y.TypeMethod
import com.nbitslabs.thumbs.a11y.UiTree
import com.nbitslabs.thumbs.device.Apps
import com.nbitslabs.thumbs.device.DeviceState
import com.nbitslabs.thumbs.device.NotificationListener
import com.nbitslabs.thumbs.server.AccessPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The MCP tools exposed to agents.
 *
 * Conventions: every x/y and element bound is in the [CoordinateSpace] (the screenshot's pixel
 * grid). Elements can be targeted by `ref` (from get_ui_tree / find_elements), by `text`, or by
 * coordinates. Control tools wait for the UI to settle before returning, and accept `observe`
 * to return a fresh UI tree and/or screenshot in the same round trip.
 */
class PhoneTools(private val context: Context, private val autoWake: () -> Boolean) {

    val instructions = """
        Thumbs controls an Android phone through its accessibility service.

        Workflow: call get_ui_tree (cheap, text) or screenshot (visual) to see the screen, act with tap/type_text/scroll/press_key/open_app, then verify. Taps on empty space "succeed" silently, so always check the result; control tools accept observe="tree"|"screenshot"|"both" to get the new state back immediately.

        Targeting: prefer element refs like "e12" from get_ui_tree/find_elements; they stay valid while the element is on screen. You can also pass text="Label" or x/y coordinates. All coordinates use the screenshot's pixel grid (see get_device_state), so a point in a screenshot can be tapped directly.

        Text: tap a field (or pass its ref to type_text) before typing. scroll direction is the content you want to reveal ("down" shows what's below); swipe direction is finger motion.

        Safety: if the phone is locked with a PIN/password, ask the user to unlock it; never enter their PIN, passwords or 2FA codes unless they explicitly gave them to you for that purpose. Confirm with the user before sending messages, purchases, deleting data or changing security settings. Some screens (banking, password prompts) block screenshots; use get_ui_tree there.
    """.trimIndent()

    private fun space() = CoordinateSpace.current(context)

    fun all(): List<Tool> = listOf(
        // --- Observation ------------------------------------------------------------------
        Tool(
            name = "get_device_state",
            description = "Device overview: model, Android version, coordinate space/screen size, rotation, screen on/locked, battery, foreground app, keyboard visibility, and which Thumbs capabilities are available.",
            inputSchema = schema(),
            scope = Scope.READ,
        ) { deviceState() },

        Tool(
            name = "screenshot",
            description = "Capture the screen as an image in the coordinate space (points in it can be passed to tap). Secure screens (FLAG_SECURE) can't be captured; use get_ui_tree for those.",
            inputSchema = schema {
                string("format", "Image format (default jpeg).", enum = listOf("jpeg", "png"))
                integer("quality", "JPEG quality 10-100 (default 75).")
                boolean("include_tree", "Also return the UI tree as text (default false).")
            },
            scope = Scope.READ,
        ) { call ->
            val svc = ThumbsAccessibilityService.require()
            val content = mutableListOf<Content>()
            content += screenshotContent(svc, call.string("format") ?: "jpeg", call.int("quality") ?: 75)
            if (call.bool("include_tree") == true) content += Content.Text(UiTree.snapshot(svc, space()).text)
            ToolResult(content)
        },

        Tool(
            name = "get_ui_tree",
            description = "The on-screen UI as an indented text tree, one element per line: [ref] Class \"text\" desc=… id=… {flags} @left,top,right,bottom. Windows (app, keyboard, system bars, dialogs) are listed top-most first. Only meaningful elements (labelled or interactive) are shown by default; layout containers are folded away.",
            inputSchema = schema {
                boolean("all_nodes", "Include every node, not just labelled/interactive ones (default false).")
                boolean("include_invisible", "Include nodes not visible to the user (default false).")
                boolean("app_only", "Skip system windows such as the status bar (default false).")
                integer("max_nodes", "Maximum elements to return (default 600).")
            },
            scope = Scope.READ,
        ) { call ->
            val svc = ThumbsAccessibilityService.require()
            val snap = UiTree.snapshot(
                svc,
                space(),
                TreeOptions(
                    includeInvisible = call.bool("include_invisible") == true,
                    interestingOnly = call.bool("all_nodes") != true,
                    maxNodes = (call.int("max_nodes") ?: 600).coerceIn(10, 5000),
                    includeSystemWindows = call.bool("app_only") != true,
                ),
            )
            ToolResult.text(header(svc) + "\n" + snap.text.ifBlank { "(no accessible windows; the screen may be off or showing a secure surface)" })
        },

        Tool(
            name = "find_elements",
            description = "Find elements whose text, content description, hint or resource id matches a query (case-insensitive; exact matches first). Returns refs usable with tap/type_text/etc.",
            inputSchema = schema {
                string("query", "Text to look for.", required = true)
                boolean("clickable_only", "Only elements that are (or are inside) something clickable.")
                integer("limit", "Maximum results (default 20).")
            },
            scope = Scope.READ,
        ) { call ->
            val svc = ThumbsAccessibilityService.require()
            val query = call.requireString("query")
            val matches = UiTree.find(svc, query, call.bool("clickable_only") == true).take((call.int("limit") ?: 20).coerceIn(1, 200))
            if (matches.isEmpty()) {
                ToolResult.text("No elements match '$query'. Visible labels: ${UiTree.visibleLabels(svc)}")
            } else {
                val sp = space()
                val r = Rect()
                ToolResult.text(matches.joinToString("\n") { it.getBoundsInScreen(r); UiTree.describe(it, sp, r, 160) })
            }
        },

        Tool(
            name = "wait_for",
            description = "Wait until an element matching `text` appears (or disappears with gone=true). Use after actions that load content.",
            inputSchema = schema {
                string("text", "Text, description or id to wait for.", required = true)
                boolean("gone", "Wait for it to disappear instead (default false).")
                integer("timeout_ms", "Give up after this long (default 10000, max 60000).")
            },
            scope = Scope.READ,
        ) { call ->
            val svc = ThumbsAccessibilityService.require()
            val text = call.requireString("text")
            val gone = call.bool("gone") == true
            val timeout = (call.int("timeout_ms") ?: 10_000).coerceIn(100, 60_000)
            val deadline = SystemClock.uptimeMillis() + timeout
            while (true) {
                val found = UiTree.find(svc, text).firstOrNull()
                if ((found != null) != gone) {
                    val msg = if (gone) "'$text' is gone." else {
                        val r = Rect().also { found!!.getBoundsInScreen(it) }
                        "Found: " + UiTree.describe(found!!, space(), r, 160)
                    }
                    return@Tool ToolResult.text(msg)
                }
                if (SystemClock.uptimeMillis() > deadline) {
                    return@Tool ToolResult.error("Timed out after ${timeout}ms waiting for '$text' to ${if (gone) "disappear" else "appear"}.")
                }
                delay(300)
            }
            @Suppress("UNREACHABLE_CODE")
            ToolResult.text("")
        },

        Tool(
            name = "wait_for_idle",
            description = "Wait until the UI stops changing (no accessibility events for quiet_ms). Screens with continuous animation never go idle; the call then returns after timeout_ms.",
            inputSchema = schema {
                integer("quiet_ms", "How long the UI must be still (default 500).")
                integer("timeout_ms", "Maximum wait (default 5000, max 30000).")
            },
            scope = Scope.READ,
        ) { call ->
            val svc = ThumbsAccessibilityService.require()
            val idle = svc.waitForIdle((call.int("quiet_ms") ?: 500).toLong().coerceIn(50, 10_000), (call.int("timeout_ms") ?: 5000).toLong().coerceIn(100, 30_000))
            ToolResult.text(if (idle) "UI is idle." else "UI still changing at timeout (animation or live content).")
        },

        Tool(
            name = "list_apps",
            description = "Installed apps that have a launcher icon: label and package name.",
            inputSchema = schema { string("filter", "Only apps whose label or package contains this text.") },
            scope = Scope.READ,
        ) { call ->
            val f = call.string("filter")?.lowercase()
            val apps = Apps.launchable(context).filter { f == null || it.label.lowercase().contains(f) || it.packageName.contains(f) }
            ToolResult.text(apps.joinToString("\n") { "${it.label}\t${it.packageName}" }.ifEmpty { "No matching apps." })
        },

        Tool(
            name = "list_notifications",
            description = "Active notifications with their keys, app, title, text and actions (index:title, with (reply) when the action accepts text). Requires notification access.",
            inputSchema = schema {
                boolean("include_ongoing", "Include ongoing/persistent notifications like media or downloads (default true).")
                integer("limit", "Maximum notifications (default 50).")
            },
            scope = Scope.READ,
        ) { call -> listNotifications(call.bool("include_ongoing") != false, call.int("limit") ?: 50) },

        // --- Touch ------------------------------------------------------------------------
        Tool(
            name = "tap",
            description = "Tap an element (ref or text) or a point (x, y). Elements are tapped at the centre of their visible bounds with a real touch gesture; method=\"action\" instead sends an accessibility click to the element (works even if it's covered).",
            inputSchema = schema {
                targetProps()
                string("method", "gesture (default) or action.", enum = listOf("gesture", "action"))
                observeProp()
            },
            scope = Scope.CONTROL,
        ) { call ->
            val svc = ready()
            val target = resolveTarget(svc, call)
            val msg = if (call.string("method") == "action") {
                val node = (target as? Target.Element)?.node ?: throw ToolException("method=action needs a ref or text target.")
                val clickable = UiTree.clickableAncestor(node) ?: node
                if (!clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw ToolException("The element rejected the click action; try method=gesture.")
                "Clicked ${target.label}"
            } else {
                Gestures(svc).tap(target.screenPoint)
                "Tapped ${target.label}"
            }
            finish(svc, call, msg)
        },

        Tool(
            name = "long_press",
            description = "Press and hold an element or point (context menus, selection, drag handles).",
            inputSchema = schema {
                targetProps()
                integer("duration_ms", "How long to hold (default 800).")
                observeProp()
            },
            scope = Scope.CONTROL,
        ) { call ->
            val svc = ready()
            val target = resolveTarget(svc, call)
            Gestures(svc).longPress(target.screenPoint, (call.int("duration_ms") ?: 800).toLong())
            finish(svc, call, "Long-pressed ${target.label}")
        },

        Tool(
            name = "double_tap",
            description = "Double-tap an element or point (zoom in maps/photos, like a post).",
            inputSchema = schema {
                targetProps()
                observeProp()
            },
            scope = Scope.CONTROL,
        ) { call ->
            val svc = ready()
            val target = resolveTarget(svc, call)
            Gestures(svc).doubleTap(target.screenPoint)
            finish(svc, call, "Double-tapped ${target.label}")
        },

        Tool(
            name = "swipe",
            description = "A fast flick in a finger direction, e.g. next photo, dismiss a card, next short video. Give either x1,y1,x2,y2 or a direction (finger motion) optionally within an element's bounds. For scrolling lists by a controlled amount use scroll.",
            inputSchema = schema {
                number("x1", "Start x.")
                number("y1", "Start y.")
                number("x2", "End x.")
                number("y2", "End y.")
                string("direction", "Finger motion when not giving coordinates.", enum = listOf("up", "down", "left", "right"))
                string("ref", "Swipe within this element's bounds.")
                integer("duration_ms", "Gesture duration (default 150).")
                observeProp()
            },
            scope = Scope.CONTROL,
        ) { call ->
            val svc = ready()
            val sp = space()
            val (from, to) = if (call.has("x1")) {
                PointF(sp.toScreenX(call.requireDouble("x1")), sp.toScreenY(call.requireDouble("y1"))) to
                    PointF(sp.toScreenX(call.requireDouble("x2")), sp.toScreenY(call.requireDouble("y2")))
            } else {
                val dir = call.string("direction") ?: throw ToolException("Give x1,y1,x2,y2 or a direction.")
                val region = call.string("ref")?.let { boundsOf(UiTree.resolveRef(it)) } ?: Rect(0, 0, sp.screenWidth, sp.screenHeight)
                fingerPath(region, dir, 0.6)
            }
            Gestures(svc).swipe(from, to, (call.int("duration_ms") ?: 150).toLong())
            finish(svc, call, "Swiped (${sp.fromScreen(from.x.toInt())},${sp.fromScreen(from.y.toInt())}) → (${sp.fromScreen(to.x.toInt())},${sp.fromScreen(to.y.toInt())})")
        },

        Tool(
            name = "scroll",
            description = "Scroll content without flinging. direction is the content you want to reveal: \"down\" shows what's further down (finger moves up). Scrolls the element given by ref (or the scrollable container around it), else the area around x,y, else the main screen. Reports when nothing moved (end of list).",
            inputSchema = schema {
                string("direction", "Which content to reveal.", required = true, enum = listOf("up", "down", "left", "right"))
                string("ref", "Element to scroll (or an element inside the list).")
                number("x", "Scroll around this point instead.")
                number("y", "Scroll around this point instead.")
                number("amount", "Fraction of the region to scroll, 0.1-0.95 (default 0.6).")
                string("method", "gesture (default) or action (accessibility scroll; moves by a page, needs a ref).", enum = listOf("gesture", "action"))
                observeProp()
            },
            scope = Scope.CONTROL,
        ) { call -> scroll(call) },

        Tool(
            name = "drag",
            description = "Press, hold, and drag from one point/element to another (drag-and-drop, reorder, sliders, moving icons).",
            inputSchema = schema {
                number("x1", "Start x (or use from_ref).")
                number("y1", "Start y.")
                number("x2", "End x (or use to_ref).")
                number("y2", "End y.")
                string("from_ref", "Element to drag.")
                string("to_ref", "Element to drop onto.")
                integer("hold_ms", "Hold before moving (default 600; use ~100 for sliders).")
                integer("duration_ms", "Movement duration (default 800).")
                observeProp()
            },
            scope = Scope.CONTROL,
        ) { call ->
            val svc = ready()
            val sp = space()
            val from = call.string("from_ref")?.let { center(UiTree.resolveRef(it)) }
                ?: PointF(sp.toScreenX(call.requireDouble("x1")), sp.toScreenY(call.requireDouble("y1")))
            val to = call.string("to_ref")?.let { center(UiTree.resolveRef(it)) }
                ?: PointF(sp.toScreenX(call.requireDouble("x2")), sp.toScreenY(call.requireDouble("y2")))
            Gestures(svc).drag(from, to, (call.int("hold_ms") ?: 600).toLong(), (call.int("duration_ms") ?: 800).toLong())
            finish(svc, call, "Dragged (${sp.fromScreen(from.x.toInt())},${sp.fromScreen(from.y.toInt())}) → (${sp.fromScreen(to.x.toInt())},${sp.fromScreen(to.y.toInt())})")
        },

        Tool(
            name = "pinch",
            description = "Two-finger pinch centred on a point or element. scale > 1 zooms in, < 1 zooms out.",
            inputSchema = schema {
                targetProps()
                number("scale", "Zoom factor, e.g. 2 to zoom in, 0.5 to zoom out.", required = true)
                integer("duration_ms", "Gesture duration (default 400).")
                observeProp()
            },
            scope = Scope.CONTROL,
        ) { call ->
            val svc = ready()
            val sp = space()
            val target = resolveTarget(svc, call)
            val scale = call.requireDouble("scale").toFloat().coerceIn(0.1f, 10f)
            val base = minOf(sp.screenWidth, sp.screenHeight) * (if (scale >= 1) 0.15f else 0.6f)
            Gestures(svc).pinch(target.screenPoint, base, scale, (call.int("duration_ms") ?: 400).toLong(), sp.screenWidth - 1f, sp.screenHeight - 1f)
            finish(svc, call, "Pinched ×$scale at ${target.label}")
        },

        // --- Text and keys ----------------------------------------------------------------
        Tool(
            name = "type_text",
            description = "Type text into the focused field, or into the field given by ref/text (it's tapped first). Inserts at the cursor unless clear=true. Supports any unicode. Set submit=true to press the keyboard's action key (search/send/next) afterwards.",
            inputSchema = schema {
                string("text", "Text to type.", required = true)
                string("ref", "Field to type into.")
                string("target_text", "Find the field by its text/hint/id instead of ref.")
                boolean("clear", "Replace the field's existing contents (default false).")
                boolean("submit", "Press enter/the IME action after typing (default false).")
                string("method", "auto (default: IME on Android 13+, else set_text), ime, set_text, or paste.", enum = listOf("auto", "ime", "set_text", "paste"))
                observeProp()
            },
            scope = Scope.CONTROL,
            logArgs = false,
        ) { call -> typeText(call) },

        Tool(
            name = "press_key",
            description = "Press a key or system button. System: back, home, recents, notifications, quick_settings, dismiss_notifications, all_apps, power_dialog, lock_screen, take_screenshot, split_screen. Media: volume_up, volume_down, volume_mute, media_play_pause, media_next, media_previous, media_stop. Keyboard keys go to the focused field: enter, tab, backspace, forward_delete, escape, space, up/down/left/right, move_home, move_end, page_up, page_down, f1-f12, letters/digits, or any KEYCODE_* name, with modifiers like ctrl+a or shift+tab.",
            inputSchema = schema {
                string("key", "Key name, e.g. back, enter, ctrl+a.", required = true)
                integer("count", "Repeat count for keyboard keys (default 1).")
                observeProp()
            },
            scope = Scope.CONTROL,
        ) { call -> pressKey(call) },

        Tool(
            name = "set_clipboard",
            description = "Put text on the clipboard (marked sensitive so it isn't shown in clipboard previews).",
            inputSchema = schema { string("text", "Text to copy.", required = true) },
            scope = Scope.CONTROL,
            exclusive = false,
            logArgs = false,
        ) { call ->
            val svc = ThumbsAccessibilityService.require()
            withContext(Dispatchers.Main) { TextInput(svc).setClipboard(call.requireString("text"), sensitive = true) }
            ToolResult.text("Clipboard set.")
        },

        Tool(
            name = "get_clipboard",
            description = "Read the clipboard. Android only allows this while a text field is focused (our IME is then active) on Android 13+, so it may fail otherwise.",
            inputSchema = schema(),
            scope = Scope.CONTROL,
            exclusive = false,
        ) { _ ->
            val svc = ThumbsAccessibilityService.require()
            val text = withContext(Dispatchers.Main) { runCatching { TextInput(svc).getClipboard() }.getOrNull() }
            if (text == null) ToolResult.error("Clipboard is empty or Android denied access (focus a text field and retry).") else ToolResult.text(text)
        },

        // --- Element actions --------------------------------------------------------------
        Tool(
            name = "element_action",
            description = "Perform an accessibility action on an element: click, long_click, focus, clear_focus, select, clear_selection, scroll_forward, scroll_backward, expand, collapse, dismiss, copy, cut, paste, show_on_screen (scroll it into view), or set_progress (with value) for sliders.",
            inputSchema = schema {
                string("ref", "Element ref.", required = true)
                string(
                    "action",
                    "Action to perform.",
                    required = true,
                    enum = listOf(
                        "click", "long_click", "focus", "clear_focus", "select", "clear_selection", "scroll_forward",
                        "scroll_backward", "expand", "collapse", "dismiss", "copy", "cut", "paste", "show_on_screen", "set_progress",
                    ),
                )
                number("value", "Value for set_progress.")
                observeProp()
            },
            scope = Scope.CONTROL,
        ) { call -> elementAction(call) },

        // --- Apps -------------------------------------------------------------------------
        Tool(
            name = "open_app",
            description = "Launch an app by name (launcher label, e.g. \"Settings\") or package name and wait for it to come to the front.",
            inputSchema = schema {
                string("name", "App label or package.", required = true)
                boolean("fresh", "Clear the app's task so it starts from its home screen (default false).")
                observeProp()
            },
            scope = Scope.CONTROL,
        ) { call ->
            val svc = ready()
            val app = Apps.resolve(context, call.requireString("name"))
            withContext(Dispatchers.Main) { Apps.launch(svc, app, call.bool("fresh") == true) }
            val deadline = SystemClock.uptimeMillis() + 8000
            while (svc.currentPackage() != app.packageName && SystemClock.uptimeMillis() < deadline) delay(150)
            val inFront = svc.currentPackage() == app.packageName
            finish(svc, call, if (inFront) "Opened ${app.label} (${app.packageName})" else "Launched ${app.label} (${app.packageName}) but it isn't in front yet")
        },

        Tool(
            name = "open_url",
            description = "Open a URI with the default handler: https:// links, tel:, mailto:, geo:, market://, app deep links.",
            inputSchema = schema {
                string("url", "URI to open.", required = true)
                observeProp()
            },
            scope = Scope.CONTROL,
            logArgs = false,
        ) { call ->
            val svc = ready()
            withContext(Dispatchers.Main) { Apps.openUri(svc, call.requireString("url")) }
            delay(500)
            finish(svc, call, "Opened URL")
        },

        Tool(
            name = "start_intent",
            description = "Start an activity with an explicit intent (advanced). E.g. action=android.settings.WIFI_SETTINGS, or action=android.intent.action.SEND with type=text/plain and extras {\"android.intent.extra.TEXT\": \"hi\"}.",
            inputSchema = schema {
                string("action", "Intent action.")
                string("data", "Data URI.")
                string("type", "MIME type.")
                string("package", "Restrict to this package.")
                string("component", "Explicit component, e.g. com.android.settings/.Settings.")
                stringArray("categories", "Intent categories.")
                obj("extras", "String extras.")
                observeProp()
            },
            scope = Scope.CONTROL,
            destructive = true,
        ) { call ->
            val svc = ready()
            val extras = call.obj("extras")?.mapValues { (it.value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it.value.toString() } ?: emptyMap()
            val started = withContext(Dispatchers.Main) {
                Apps.startIntent(svc, call.string("action"), call.string("data"), call.string("type"), call.string("package"), call.string("component"), call.stringList("categories") ?: emptyList(), extras)
            }
            delay(500)
            finish(svc, call, "Started $started")
        },

        // --- Notifications ----------------------------------------------------------------
        Tool(
            name = "open_notification",
            description = "Tap a notification (runs its content action, usually opening the app). Get keys from list_notifications.",
            inputSchema = schema {
                string("key", "Notification key.", required = true)
                observeProp()
            },
            scope = Scope.CONTROL,
        ) { call ->
            val svc = ready()
            NotificationListener.require().open(call.requireString("key"))
            delay(500)
            finish(svc, call, "Opened notification")
        },

        Tool(
            name = "notification_action",
            description = "Run one of a notification's action buttons by index or title. Pass reply to answer inline (e.g. a message Reply action).",
            inputSchema = schema {
                string("key", "Notification key.", required = true)
                integer("index", "Action index from list_notifications.")
                string("title", "Action title, e.g. \"Reply\" or \"Mark as read\".")
                string("reply", "Text to send for reply actions.")
            },
            scope = Scope.CONTROL,
            destructive = true,
            logArgs = false,
        ) { call ->
            val title = NotificationListener.require().action(call.requireString("key"), call.int("index"), call.string("title"), call.string("reply"))
            ToolResult.text("Ran '$title'" + if (call.has("reply")) " with a reply." else ".")
        },

        Tool(
            name = "dismiss_notification",
            description = "Dismiss (clear) a notification by key.",
            inputSchema = schema { string("key", "Notification key.", required = true) },
            scope = Scope.CONTROL,
            destructive = true,
        ) { call ->
            NotificationListener.require().cancelNotification(call.requireString("key"))
            ToolResult.text("Dismissed.")
        },

        // --- Power ------------------------------------------------------------------------
        Tool(
            name = "wake_screen",
            description = "Turn the screen on (and dismiss a swipe-only lock screen). A PIN/pattern/password lock stays up: ask the user to unlock.",
            inputSchema = schema { observeProp() },
            scope = Scope.CONTROL,
        ) { call ->
            val svc = ThumbsAccessibilityService.require()
            val on = DeviceState.wake(svc)
            val locked = DeviceState.isLocked(context)
            val msg = when {
                !on -> "Couldn't turn the screen on."
                locked && DeviceState.isSecure(context) -> "Screen is on but the phone is locked (secure lock). Ask the user to unlock it."
                locked -> "Screen is on; lock screen is showing (swipe up to dismiss)."
                else -> "Screen is on and unlocked."
            }
            finish(svc, call, msg)
        },
    )

    // --- Shared helpers -------------------------------------------------------------------

    private fun SchemaBuilder.targetProps() {
        string("ref", "Element ref from get_ui_tree/find_elements, e.g. \"e12\".")
        string("text", "Target the best element matching this text/description/id instead.")
        number("x", "X coordinate (with y) when not using ref/text.")
        number("y", "Y coordinate (with x).")
    }

    private fun SchemaBuilder.observeProp() =
        string("observe", "Return the resulting state: none (default), tree, screenshot, or both.", enum = listOf("none", "tree", "screenshot", "both"))

    private sealed interface Target {
        val screenPoint: PointF
        val label: String

        class Element(val node: AccessibilityNodeInfo, override val screenPoint: PointF, override val label: String) : Target
        class Point(override val screenPoint: PointF, override val label: String) : Target
    }

    private fun resolveTarget(svc: ThumbsAccessibilityService, call: ToolCall): Target {
        val sp = space()
        val node = call.string("ref")?.let { UiTree.resolveRef(it) }
            ?: call.string("text")?.let { q ->
                UiTree.find(svc, q, clickableOnly = true).firstOrNull() ?: UiTree.find(svc, q).firstOrNull()
                    ?: throw ToolException("No element matches '$q'. Visible labels: ${UiTree.visibleLabels(svc)}")
            }
        if (node != null) {
            val r = boundsOf(node)
            if (r.isEmpty) throw ToolException("That element has no visible area; scroll it into view (element_action show_on_screen) first.")
            val label = UiTree.describe(node, sp, r, 60)
            return Target.Element(node, PointF(r.exactCenterX(), r.exactCenterY()), label)
        }
        if (call.has("x") && call.has("y")) {
            val x = call.requireDouble("x")
            val y = call.requireDouble("y")
            if (x < 0 || y < 0 || x > sp.width || y > sp.height) throw ToolException("($x, $y) is outside the ${sp.width}x${sp.height} coordinate space.")
            return Target.Point(PointF(sp.toScreenX(x), sp.toScreenY(y)), "(${x.toInt()}, ${y.toInt()})")
        }
        throw ToolException("Specify a target: ref, text, or x and y.")
    }

    private fun boundsOf(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }
    private fun center(node: AccessibilityNodeInfo): PointF = boundsOf(node).let { PointF(it.exactCenterX(), it.exactCenterY()) }

    /** Ensures the service is connected and the screen is on before driving the UI. */
    private suspend fun ready(): ThumbsAccessibilityService {
        val svc = ThumbsAccessibilityService.require()
        if (!DeviceState.isScreenOn(context)) {
            if (!autoWake()) throw ToolException("The screen is off. Call wake_screen first.")
            DeviceState.wake(svc)
            if (!DeviceState.isScreenOn(context)) throw ToolException("The screen is off and couldn't be woken.")
        }
        return svc
    }

    private fun header(svc: ThumbsAccessibilityService): String {
        val pkg = svc.currentPackage()
        val label = pkg?.let { Apps.label(context, it) }
        val locked = DeviceState.isLocked(context)
        return buildString {
            append("Foreground: ").append(label ?: "?").append(" (").append(pkg ?: "unknown").append(")")
            if (locked) append(" · LOCKED")
            if (svc.isKeyboardVisible()) append(" · keyboard shown")
            append(" · space ").append(space().let { "${it.width}x${it.height}" })
        }
    }

    private suspend fun finish(svc: ThumbsAccessibilityService, call: ToolCall, message: String): ToolResult {
        svc.waitForIdle(quietMs = 300, timeoutMs = 2000)
        val content = mutableListOf<Content>(Content.Text("$message\n${header(svc)}"))
        when (call.string("observe") ?: "none") {
            "tree" -> content += Content.Text(UiTree.snapshot(svc, space()).text)
            "screenshot" -> content += screenshotContent(svc, "jpeg", 75)
            "both" -> {
                content += Content.Text(UiTree.snapshot(svc, space()).text)
                content += screenshotContent(svc, "jpeg", 75)
            }
        }
        return ToolResult(content)
    }

    private suspend fun screenshotContent(svc: ThumbsAccessibilityService, format: String, quality: Int): Content.Image {
        val raw = svc.screenshot()
        return withContext(Dispatchers.Default) {
            val sp = CoordinateSpace.forScreen(raw.width, raw.height)
            val scaled = if (sp.scale < 1.0) Bitmap.createScaledBitmap(raw, sp.width, sp.height, true) else raw
            val out = ByteArrayOutputStream()
            val png = format == "png"
            scaled.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), out)
            if (scaled !== raw) scaled.recycle()
            raw.recycle()
            Content.Image(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), if (png) "image/png" else "image/jpeg")
        }
    }

    /** Start/end points for a finger moving in [dir] across [region] (screen px). */
    private fun fingerPath(region: Rect, dir: String, amount: Double): Pair<PointF, PointF> {
        val cx = region.exactCenterX()
        val cy = region.exactCenterY()
        val halfH = (region.height() * amount / 2).toFloat()
        val halfW = (region.width() * amount / 2).toFloat()
        return when (dir) {
            "up" -> PointF(cx, cy + halfH) to PointF(cx, cy - halfH)
            "down" -> PointF(cx, cy - halfH) to PointF(cx, cy + halfH)
            "left" -> PointF(cx + halfW, cy) to PointF(cx - halfW, cy)
            "right" -> PointF(cx - halfW, cy) to PointF(cx + halfW, cy)
            else -> throw ToolException("direction must be up, down, left or right")
        }
    }

    private fun screenFingerprint(svc: ThumbsAccessibilityService, within: Rect?): Int {
        val r = Rect()
        return UiTree.allNodes(svc).filter { n ->
            within == null || run { n.getBoundsInScreen(r); Rect.intersects(r, within) }
        }.map { n -> n.getBoundsInScreen(r); "${n.text}|${n.contentDescription}|${r.top}|${r.left}" }.toList().hashCode()
    }

    private suspend fun scroll(call: ToolCall): ToolResult {
        val svc = ready()
        val sp = space()
        val dir = call.requireString("direction")
        val amount = (call.double("amount") ?: 0.6).coerceIn(0.1, 0.95)
        val refNode = call.string("ref")?.let { UiTree.resolveRef(it) }
        val container = refNode?.let { UiTree.scrollableAncestor(it) ?: it }

        if (call.string("method") == "action") {
            val node = container ?: UiTree.allNodes(svc).firstOrNull { it.isScrollable }
                ?: throw ToolException("Nothing scrollable on screen.")
            val action = when (dir) {
                "down" -> AccessibilityAction.ACTION_SCROLL_DOWN
                "up" -> AccessibilityAction.ACTION_SCROLL_UP
                "left" -> AccessibilityAction.ACTION_SCROLL_LEFT
                else -> AccessibilityAction.ACTION_SCROLL_RIGHT
            }
            val ok = node.performAction(action.id) ||
                node.performAction(if (dir == "down" || dir == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            return finish(svc, call, if (ok) "Scrolled $dir" else "Couldn't scroll $dir (probably at the end)")
        }

        val region: Rect = when {
            container != null -> boundsOf(container)
            call.has("x") && call.has("y") -> {
                val px = sp.toScreenX(call.requireDouble("x"))
                val py = sp.toScreenY(call.requireDouble("y"))
                val hw = sp.screenWidth * 0.4f
                val hh = sp.screenHeight * 0.3f
                Rect((px - hw).toInt(), (py - hh).toInt(), (px + hw).toInt(), (py + hh).toInt()).apply {
                    intersect(0, 0, sp.screenWidth, sp.screenHeight)
                }
            }
            // Stay clear of the status bar and gesture navigation areas
            else -> Rect((sp.screenWidth * 0.1).toInt(), (sp.screenHeight * 0.2).toInt(), (sp.screenWidth * 0.9).toInt(), (sp.screenHeight * 0.8).toInt())
        }
        val fingerDir = when (dir) {
            "down" -> "up"
            "up" -> "down"
            "left" -> "right"
            "right" -> "left"
            else -> throw ToolException("direction must be up, down, left or right")
        }
        val before = screenFingerprint(svc, region)
        val (from, to) = fingerPath(region, fingerDir, amount)
        Gestures(svc).scroll(from, to, 450)
        svc.waitForIdle(quietMs = 250, timeoutMs = 1500)
        val moved = screenFingerprint(svc, region) != before
        return finish(svc, call, if (moved) "Scrolled $dir" else "Scrolled $dir but nothing moved; probably at the end.")
    }

    private suspend fun typeText(call: ToolCall): ToolResult {
        val svc = ready()
        val input = TextInput(svc)
        val fieldNode = call.string("ref")?.let { UiTree.resolveRef(it) }
            ?: call.string("target_text")?.let { q ->
                UiTree.find(svc, q).firstOrNull { it.isEditable } ?: UiTree.find(svc, q).firstOrNull()
                    ?: throw ToolException("No field matches '$q'.")
            }
        if (fieldNode != null && !fieldNode.isFocused) {
            // A real tap focuses and brings up the IME, which ACTION_FOCUS alone doesn't always do
            Gestures(svc).tap(center(fieldNode))
            val deadline = SystemClock.uptimeMillis() + 1500
            while (SystemClock.uptimeMillis() < deadline) {
                if (svc.focusedInput()?.isEditable == true && (Build.VERSION.SDK_INT < 33 || input.hasImeConnection)) break
                delay(100)
            }
        }
        val method = when (call.string("method")) {
            "ime" -> TypeMethod.IME
            "set_text" -> TypeMethod.SET_TEXT
            "paste" -> TypeMethod.PASTE
            else -> TypeMethod.AUTO
        }
        val text = call.requireString("text")
        val how = withContext(Dispatchers.Main) { input.type(text, call.bool("clear") == true, method) }
        var msg = "Typed ${text.length} chars via $how"
        if (call.bool("submit") == true) {
            delay(100)
            msg += if (withContext(Dispatchers.Main) { input.submit() }) " and submitted" else " (couldn't submit: no IME action available)"
        } else {
            delay(150)
            svc.focusedInput()?.takeIf { it.isEditable }?.let { f ->
                msg += if (f.isPassword) "; field is a password field" else "; field now: \"${f.text?.toString()?.take(200) ?: ""}\""
            }
        }
        return finish(svc, call, msg)
    }

    private suspend fun pressKey(call: ToolCall): ToolResult {
        val raw = call.requireString("key").trim()
        val key = raw.lowercase().replace('-', '_').replace(' ', '_')
        val count = (call.int("count") ?: 1).coerceIn(1, 200)
        val global = when (key) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents", "app_switch", "overview" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "dismiss_notifications", "dismiss_notification_shade" -> AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
            "all_apps", "app_drawer" -> AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS
            "power_dialog", "power" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "lock_screen", "lock" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "take_screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            "split_screen" -> AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            "headset_hook" -> AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK
            else -> null
        }
        val svc = if (key == "lock_screen" || key == "lock") ThumbsAccessibilityService.require() else ready()
        if (global != null) {
            repeat(if (key == "back") count else 1) {
                if (!svc.global(global)) throw ToolException("The system rejected '$raw'.")
                if (count > 1) delay(250)
            }
            return finish(svc, call, "Pressed $raw")
        }
        val audio = context.getSystemService(AudioManager::class.java)
        val volume = when (key) {
            "volume_up" -> AudioManager.ADJUST_RAISE
            "volume_down" -> AudioManager.ADJUST_LOWER
            "volume_mute" -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> null
        }
        if (volume != null) {
            repeat(count) { audio.adjustSuggestedStreamVolume(volume, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI) }
            return finish(svc, call, "Pressed $raw")
        }
        val media = when (key) {
            "media_play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "media_play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "media_pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "media_next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "media_previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "media_stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> null
        }
        if (media != null) {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, media))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, media))
            return finish(svc, call, "Pressed $raw")
        }
        val (code, meta) = TextInput.parseKey(raw) ?: throw ToolException("Unknown key '$raw'.")
        val input = TextInput(svc)
        val sent = withContext(Dispatchers.Main) { input.key(code, meta, count) }
        if (!sent) {
            // D-pad navigation still works without a text field via global actions (Android 13+)
            val dpad = if (Build.VERSION.SDK_INT >= 33 && meta == 0) when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> AccessibilityService.GLOBAL_ACTION_DPAD_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> AccessibilityService.GLOBAL_ACTION_DPAD_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> AccessibilityService.GLOBAL_ACTION_DPAD_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> AccessibilityService.GLOBAL_ACTION_DPAD_CENTER
                else -> null
            } else null
            if (dpad != null && svc.global(dpad)) return finish(svc, call, "Pressed $raw (d-pad)")
            throw ToolException("Keyboard keys need a focused text field; tap one first. For navigation use back/home/recents.")
        }
        return finish(svc, call, "Pressed $raw" + if (count > 1) " ×$count" else "")
    }

    private suspend fun elementAction(call: ToolCall): ToolResult {
        val svc = ready()
        val node = UiTree.resolveRef(call.requireString("ref"))
        val action = call.requireString("action")
        val ok = when (action) {
            "click" -> (UiTree.clickableAncestor(node) ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            "long_click" -> node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            "focus" -> node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            "clear_focus" -> node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
            "select" -> node.performAction(AccessibilityNodeInfo.ACTION_SELECT)
            "clear_selection" -> node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_SELECTION)
            "scroll_forward" -> (UiTree.scrollableAncestor(node) ?: node).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            "scroll_backward" -> (UiTree.scrollableAncestor(node) ?: node).performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            "expand" -> node.performAction(AccessibilityNodeInfo.ACTION_EXPAND)
            "collapse" -> node.performAction(AccessibilityNodeInfo.ACTION_COLLAPSE)
            "dismiss" -> node.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
            "copy" -> node.performAction(AccessibilityNodeInfo.ACTION_COPY)
            "cut" -> node.performAction(AccessibilityNodeInfo.ACTION_CUT)
            "paste" -> node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            "show_on_screen" -> node.performAction(AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            "set_progress" -> node.performAction(
                AccessibilityAction.ACTION_SET_PROGRESS.id,
                Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, call.requireDouble("value").toFloat()) },
            )
            else -> throw ToolException("Unknown action '$action'.")
        }
        if (!ok) {
            val available = node.actionList.mapNotNull { it.label?.toString() ?: actionName(it.id) }
            throw ToolException("The element rejected '$action'. It supports: $available")
        }
        return finish(svc, call, "Performed $action on ${call.requireString("ref")}")
    }

    private fun actionName(id: Int): String? = when (id) {
        AccessibilityNodeInfo.ACTION_CLICK -> "click"
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> "long_click"
        AccessibilityNodeInfo.ACTION_FOCUS -> "focus"
        AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "clear_focus"
        AccessibilityNodeInfo.ACTION_SELECT -> "select"
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll_forward"
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll_backward"
        AccessibilityNodeInfo.ACTION_EXPAND -> "expand"
        AccessibilityNodeInfo.ACTION_COLLAPSE -> "collapse"
        AccessibilityNodeInfo.ACTION_DISMISS -> "dismiss"
        AccessibilityNodeInfo.ACTION_COPY -> "copy"
        AccessibilityNodeInfo.ACTION_CUT -> "cut"
        AccessibilityNodeInfo.ACTION_PASTE -> "paste"
        AccessibilityNodeInfo.ACTION_SET_TEXT -> "set_text"
        AccessibilityAction.ACTION_SHOW_ON_SCREEN.id -> "show_on_screen"
        AccessibilityAction.ACTION_SET_PROGRESS.id -> "set_progress"
        else -> null
    }

    private fun deviceState(): ToolResult {
        val svc = ThumbsAccessibilityService.instance
        val sp = space()
        val (battery, charging) = DeviceState.battery(context)
        val pkg = svc?.currentPackage()
        val lines = buildList {
            add("Device: ${DeviceState.model()} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            add(sp.describe())
            add("Rotation: ${DeviceState.rotation(context)}°")
            add("Screen: ${if (DeviceState.isScreenOn(context)) "on" else "off"} · ${if (DeviceState.isLocked(context)) "locked" + if (DeviceState.isSecure(context)) " (secure: needs the user to unlock)" else " (swipe)" else "unlocked"}")
            add("Battery: $battery%${if (charging) " charging" else ""}")
            if (pkg != null) add("Foreground app: ${Apps.label(context, pkg) ?: pkg} ($pkg)${svc.foregroundClass?.let { " activity=$it" } ?: ""}")
            if (svc != null) add("Keyboard: ${if (svc.isKeyboardVisible()) "shown" else "hidden"}")
            add("Accessibility service: ${if (svc != null) "connected" else "NOT ENABLED (control tools unavailable)"}")
            add("Notification access: ${if (NotificationListener.instance != null) "granted" else "not granted (notification tools unavailable)"}")
            add("IME input (Android 13+): ${if (Build.VERSION.SDK_INT >= 33) "available" else "unavailable; typing uses ACTION_SET_TEXT"}")
            add("Tailscale addresses: ${AccessPolicy.tailscaleAddresses().joinToString { it.hostAddress ?: "" }.ifEmpty { "none" }}")
        }
        return ToolResult.text(lines.joinToString("\n"))
    }

    private fun listNotifications(includeOngoing: Boolean, limit: Int): ToolResult {
        val listener = NotificationListener.require()
        val now = System.currentTimeMillis()
        val items = listener.list()
            .filter { includeOngoing || !it.isOngoing }
            .take(limit.coerceIn(1, 500))
        if (items.isEmpty()) return ToolResult.text("No notifications.")
        val text = items.joinToString("\n\n") { sbn ->
            val n = sbn.notification
            val extras = n.extras
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)
            val body = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
            val sub = extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)
            val lines = extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)
            val ago = (now - sbn.postTime) / 1000
            buildString {
                append("key=").append(sbn.key).append('\n')
                append("  app: ").append(Apps.label(context, sbn.packageName) ?: sbn.packageName).append(" (").append(sbn.packageName).append(")")
                append(" · ").append(if (ago < 60) "${ago}s ago" else if (ago < 3600) "${ago / 60}m ago" else "${ago / 3600}h ago")
                if (sbn.isOngoing) append(" · ongoing")
                append('\n')
                title?.let { append("  title: ").append(it).append('\n') }
                body?.let { append("  text: ").append(it.toString().take(500)).append('\n') }
                lines?.takeIf { it.isNotEmpty() }?.let { append("  lines: ").append(it.joinToString(" | ").take(500)).append('\n') }
                sub?.let { append("  sub: ").append(it).append('\n') }
                n.actions?.takeIf { it.isNotEmpty() }?.let { actions ->
                    append("  actions: ").append(actions.mapIndexed { i, a -> "$i:${a.title}${if (!a.remoteInputs.isNullOrEmpty()) "(reply)" else ""}" }.joinToString(", "))
                }
            }.trimEnd()
        }
        return ToolResult.text(text)
    }
}
