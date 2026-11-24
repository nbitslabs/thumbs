package com.nbitslabs.thumbs.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class SharedItem(
    val id: String,
    val receivedAt: Long,
    val text: String,
    val subject: String? = null,
    val urls: List<String> = emptyList(),
    val sourceApp: String? = null,
)

/**
 * Thread-safe persisted JSON ring buffer of items shared to Thumbs from Android's share sheet.
 * Stored newest-first so UI and MCP consumers see the latest shared links immediately.
 */
class SharedInbox(
    private val file: File,
    private val maxItems: Int = 50,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val capacity = maxItems.coerceAtLeast(1)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()
    private val _items = MutableStateFlow(load())

    /** Shared items, newest first. */
    val items: StateFlow<List<SharedItem>> = _items.asStateFlow()

    fun add(text: String, subject: String? = null, sourceApp: String? = null): SharedItem {
        val cleanText = text.trim().take(MAX_TEXT_LENGTH)
        require(cleanText.isNotEmpty()) { "Shared text must not be blank" }
        val cleanSubject = subject?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_SUBJECT_LENGTH)
        val cleanSource = sourceApp?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_SOURCE_APP_LENGTH)
        val urls = (extractUrls(cleanText) + (cleanSubject?.let(::extractUrls) ?: emptyList())).distinct()
        val item = SharedItem(
            id = UUID.randomUUID().toString(),
            receivedAt = clock(),
            text = cleanText,
            subject = cleanSubject,
            urls = urls,
            sourceApp = cleanSource,
        )
        synchronized(lock) {
            _items.value = (listOf(item) + _items.value).take(capacity)
            save()
        }
        return item
    }

    /** Returns up to [limit] shared items, newest first. */
    fun list(limit: Int = 20): List<SharedItem> {
        if (limit <= 0) return emptyList()
        return _items.value.take(limit)
    }

    /** Removes the item with [id], returning true if an item was removed. */
    fun delete(id: String): Boolean = synchronized(lock) {
        val current = _items.value
        val updated = current.filterNot { it.id == id }
        if (updated.size == current.size) return false
        _items.value = updated
        save()
        true
    }

    /** Removes all items, returning how many were cleared. */
    fun clear(): Int = synchronized(lock) {
        val count = _items.value.size
        if (count == 0) return 0
        _items.value = emptyList()
        save()
        count
    }

    private fun load(): List<SharedItem> = runCatching {
        if (file.exists()) json.decodeFromString<List<SharedItem>>(file.readText()).take(capacity) else emptyList()
    }.getOrDefault(emptyList())

    private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val encoded = json.encodeToString(_items.value)
        tmp.writeText(encoded)
        if (!tmp.renameTo(file)) {
            file.writeText(encoded)
            tmp.delete()
        }
    }

    companion object {
        const val MAX_TEXT_LENGTH = 8_000
        const val MAX_SUBJECT_LENGTH = 500
        private const val MAX_SOURCE_APP_LENGTH = 200
        private val URL_REGEX = Regex("https?://[^\\s<>\"\u201c\u201d]+", RegexOption.IGNORE_CASE)

        /** Extracts unique http(s) URLs, stripping trailing sentence/wrapper punctuation. */
        fun extractUrls(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            return URL_REGEX.findAll(text)
                .map { trimTrailingPunctuation(it.value) }
                .filter { it.substringAfter("://").isNotEmpty() }
                .distinct()
                .toList()
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
}
