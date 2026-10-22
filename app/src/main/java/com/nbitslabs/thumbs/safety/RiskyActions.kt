package com.nbitslabs.thumbs.safety

/**
 * Recognises controls whose label suggests an action that is hard to undo or affects other
 * people: sending, paying, deleting, posting. Used by the opt-in "ask before risky actions" mode.
 *
 * This is a heuristic over what the control says, so it errs towards asking. It can't see
 * icon-only buttons without a label, which is why there is also an "ask before every action" mode.
 */
object RiskyActions {
    private val phrases = listOf(
        // Messages and posts
        "send", "send now", "reply", "post", "publish", "tweet", "share now", "submit",
        // Money
        "pay", "pay now", "buy", "buy now", "purchase", "place order", "checkout", "check out", "order now",
        "transfer", "withdraw", "donate", "subscribe", "confirm payment", "proceed to pay", "book now",
        // Destruction
        "delete", "delete all", "remove", "erase", "wipe", "uninstall", "reset", "factory reset", "clear data",
        "clear storage", "empty trash", "discard",
        // Accounts and security
        "sign out", "log out", "logout", "deactivate", "close account", "change password", "turn off 2fa",
        // Calls and confirmations
        "call", "accept", "approve", "confirm", "allow", "grant", "install", "block", "report",
        // Common non-English labels (Chinese, Hindi)
        "发送", "付款", "支付", "删除", "确认", "转账", "提交", "भेजें", "भुगतान", "हटाएं",
    )

    // Labels longer than this are content (a message mentioning "send"), not a control
    private const val MAX_LABEL_LENGTH = 40

    // Longest first, so the most specific phrase is reported ("pay now" rather than "pay")
    private val wordPatterns = phrases.filter { p -> p.all { it.code < 128 } }
        .sortedByDescending { it.length }
        .map { p -> p to Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(p) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE) }
    private val scriptPhrases = phrases.filter { p -> p.any { it.code >= 128 } }

    /** The risky phrase in [label], or null. */
    fun match(label: String?): String? {
        val text = label?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LABEL_LENGTH } ?: return null
        wordPatterns.firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.let { return it.first }
        return scriptPhrases.firstOrNull { text.contains(it) }
    }

    /** The first risky phrase in any of [labels], or null. */
    fun match(labels: Iterable<String?>): String? = labels.firstNotNullOfOrNull { match(it) }
}
