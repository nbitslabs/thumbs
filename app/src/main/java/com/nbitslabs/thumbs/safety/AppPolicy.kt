package com.nbitslabs.thumbs.safety

import com.nbitslabs.thumbs.auth.ApiKey
import com.nbitslabs.thumbs.auth.Scope

/**
 * Pure-Kotlin enforcement rules for per-key app allow-lists and [Scope.RESEARCH] safety guards.
 *
 * Every check returns `null` when allowed, or a plain-English denial reason suitable for returning
 * directly as an MCP tool error.
 */
object AppPolicy {
    /** System navigation / wake keys that remain usable to leave a blocked app or the Thumbs UI. */
    val LAUNCHER_ESCAPE_KEYS: Set<String> = setOf("back", "home", "recents", "wake_screen")

    /** High-privilege tools hard-blocked for [Scope.RESEARCH] keys. */
    val RESEARCH_BLOCKED_TOOLS: Set<String> = setOf(
        "start_intent",
        "notification_action",
        "dismiss_notification",
        "open_notification",
    )

    /** Built-in observation tools allowed for [Scope.READ] keys when tool scope isn't passed separately. */
    val READ_TOOLS: Set<String> = setOf(
        "get_device_state",
        "screenshot",
        "get_ui_tree",
        "find_elements",
        "read_screen_text",
        "get_view_link",
        "wait_for",
        "wait_for_idle",
        "list_apps",
        "list_notifications",
        "get_shared_items",
        "capture_burst",
        "list_recordings",
        "get_recording",
        "list_clips",
    )

    /** Common Android browser packages recognized for `open_url` under [Scope.RESEARCH]. */
    val BROWSER_PACKAGES: Set<String> = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.google.android.apps.chrome",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.focus",
        "com.brave.browser",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.sec.android.app.sbrowser",
        "com.duckduckgo.mobile.android",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "app.vanadium.browser",
    )

    fun isEscapeKey(key: String): Boolean {
        val normalized = key.trim().lowercase().replace('-', '_').replace(' ', '_')
        return normalized in LAUNCHER_ESCAPE_KEYS
    }

    /**
     * Checks whether [key] may operate on [foregroundPackage].
     * Always blocks [selfPackage] (Thumbs itself), even if listed in [ApiKey.allowedPackages].
     */
    fun checkForegroundApp(key: ApiKey, foregroundPackage: String?, selfPackage: String): String? {
        val self = selfPackage.trim()
        val fg = foregroundPackage?.trim()?.takeIf { it.isNotEmpty() }
        if (fg != null && self.isNotEmpty() && fg.equals(self, ignoreCase = true)) {
            return "The Thumbs app ($self) is in front. For security, agents can't operate it; press_key home/back or open another app first."
        }
        if (key.allowedPackages.isNotEmpty()) {
            if (fg == null) {
                return "Blocked by key '${key.name}': foreground app is unknown, and this key only allows: ${key.allowedPackages.joinToString(", ")}. Open an allowed app first."
            }
            if (key.allowedPackages.none { it.equals(fg, ignoreCase = true) }) {
                return "Blocked by key '${key.name}': foreground app '$fg' is not in this key's allowed apps (${key.allowedPackages.joinToString(", ")}). Open an allowed app first."
            }
        }
        return null
    }

    /**
     * Checks whether [key] may launch [targetPackage].
     * Always blocks [selfPackage] (Thumbs itself).
     */
    fun checkLaunchPackage(key: ApiKey, targetPackage: String, selfPackage: String): String? {
        val self = selfPackage.trim()
        val target = targetPackage.trim()
        if (target.isEmpty()) {
            return "Target package name is empty."
        }
        if (self.isNotEmpty() && target.equals(self, ignoreCase = true)) {
            return "Agents can't open the Thumbs app ($self)."
        }
        if (key.allowedPackages.isNotEmpty() && key.allowedPackages.none { it.equals(target, ignoreCase = true) }) {
            return "Blocked by key '${key.name}': launching '$target' is not permitted. Allowed apps: ${key.allowedPackages.joinToString(", ")}."
        }
        return null
    }

    /**
     * Checks whether [key] may call `open_url` (and optionally validates [url] scheme for [Scope.RESEARCH]).
     * In [Scope.RESEARCH], `open_url` is allowed only if [ApiKey.allowedPackages] is empty or includes
     * a recognized browser package, and (when [url] is supplied) only for `http://` or `https://` URIs.
     */
    fun checkOpenUrl(key: ApiKey, url: String? = null): String? {
        if (key.scope == Scope.READ) {
            return "The API key '${key.name}' is read-only and can't call 'open_url'."
        }
        if (key.scope == Scope.RESEARCH) {
            if (url != null) {
                val lower = url.trim().lowercase()
                if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                    return "Blocked by research-only key '${key.name}': open_url only permits http:// and https:// URLs."
                }
            }
            if (key.allowedPackages.isNotEmpty() && key.allowedPackages.none { it.trim().lowercase() in BROWSER_PACKAGES }) {
                return "Blocked by research-only key '${key.name}': open_url requires a browser package in allowed apps (${key.allowedPackages.joinToString(", ")})."
            }
        }
        return null
    }

    /**
     * Checks whether [key] is allowed to invoke [toolName] based on its [Scope].
     * For [Scope.READ], fails closed against anything outside [READ_TOOLS].
     */
    fun checkToolForScope(key: ApiKey, toolName: String): String? {
        val name = toolName.trim().lowercase()
        return when (key.scope) {
            Scope.CONTROL -> null
            Scope.READ -> if (name in READ_TOOLS) null else "The API key '${key.name}' is read-only and can't call '$toolName'."
            Scope.RESEARCH -> checkResearchTool(key, toolName, name)
        }
    }

    /**
     * Checks whether [key] is allowed to invoke [toolName] whose declared scope is [toolScope].
     * Preferred when the caller has the `Tool` descriptor (e.g. `McpServer`) so new `Scope.READ`
     * tools work automatically without maintaining a hardcoded allow-list.
     */
    fun checkToolForScope(key: ApiKey, toolName: String, toolScope: Scope): String? {
        val name = toolName.trim().lowercase()
        return when (key.scope) {
            Scope.CONTROL -> null
            Scope.READ -> if (toolScope == Scope.READ) null else "The API key '${key.name}' is read-only and can't call '$toolName'."
            Scope.RESEARCH -> checkResearchTool(key, toolName, name)
        }
    }

    /**
     * Hard-blocks risky UI actions for [Scope.RESEARCH] keys without showing interactive prompts.
     * Returns `null` for non-research keys or when none of [labels] match [RiskyActions].
     */
    fun checkRiskyActionForResearch(key: ApiKey, actionDescription: String, labels: Iterable<String?>): String? {
        if (key.scope != Scope.RESEARCH) return null
        val phrase = RiskyActions.match(labels) ?: return null
        return "Blocked by research-only key '${key.name}': risky action '$phrase' ($actionDescription) is not permitted."
    }

    private fun checkResearchTool(key: ApiKey, rawName: String, normalizedName: String): String? = when {
        normalizedName in RESEARCH_BLOCKED_TOOLS ->
            "Blocked by research-only key '${key.name}': tool '$rawName' is not permitted."
        normalizedName == "open_url" ->
            checkOpenUrl(key)
        else -> null
    }
}
