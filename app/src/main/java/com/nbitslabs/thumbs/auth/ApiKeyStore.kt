package com.nbitslabs.thumbs.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

enum class Scope {
    /** Screenshots, UI tree, device state, notifications listing. */
    READ,

    /** READ plus safe browsing and navigation within allowed apps; blocks risky actions and high-privilege tools. */
    RESEARCH,

    /** Everything in READ plus input, app launching, intents, notification actions. */
    CONTROL,
    ;

    val canControl: Boolean get() = this == RESEARCH || this == CONTROL
}

@Serializable
data class ApiKey(
    val id: String,
    val name: String,
    /** First characters of the token, shown in the UI to tell keys apart. Not secret. */
    val prefix: String,
    /** Hex SHA-256 of the full token. The token itself is never stored. */
    val hash: String,
    val scope: Scope,
    val createdAt: Long,
    val lastUsedAt: Long? = null,
    /** When set, the key stops working at this time. */
    val expiresAt: Long? = null,
    /** Optional package allow-list; empty means all apps (except Thumbs itself). */
    val allowedPackages: List<String> = emptyList(),
) {
    fun isExpired(now: Long) = expiresAt != null && now >= expiresAt
}

data class CreatedKey(val key: ApiKey, val token: String)

/**
 * Persists API keys as a JSON file in app-private storage. Tokens are 256-bit random values
 * and only their SHA-256 is stored, so a leaked file can't be used to authenticate.
 */
class ApiKeyStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()
    private val _keys = MutableStateFlow(load())
    val keys: StateFlow<List<ApiKey>> = _keys.asStateFlow()

    // lastUsedAt is updated in memory on every request and flushed at most this often
    private var lastFlush = 0L

    fun create(
        name: String,
        scope: Scope,
        validForMs: Long? = null,
        allowedPackages: List<String> = emptyList(),
    ): CreatedKey {
        val token = generateToken()
        val now = clock()
        val normalizedPackages = allowedPackages
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        val key = ApiKey(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Unnamed key" },
            prefix = token.take(TOKEN_PREFIX.length + 6),
            hash = sha256(token),
            scope = scope,
            createdAt = now,
            expiresAt = validForMs?.let { now + it },
            allowedPackages = normalizedPackages,
        )
        synchronized(lock) {
            _keys.value = _keys.value + key
            save()
        }
        return CreatedKey(key, token)
    }

    fun delete(id: String) = synchronized(lock) {
        _keys.value = _keys.value.filterNot { it.id == id }
        save()
    }

    /** Returns true when a key with [id] exists and has not expired. */
    fun isActive(id: String): Boolean {
        val now = clock()
        return synchronized(lock) { _keys.value.any { it.id == id && !it.isExpired(now) } }
    }

    /** Returns the key matching [token], or null (also when it has expired). Records usage time. */
    fun authenticate(token: String): ApiKey? {
        if (!token.startsWith(TOKEN_PREFIX) || token.length > 128) return null
        val hash = sha256(token).toByteArray()
        synchronized(lock) {
            val match = _keys.value.firstOrNull { MessageDigest.isEqual(it.hash.toByteArray(), hash) } ?: return null
            val now = clock()
            if (match.isExpired(now)) return null
            val updated = match.copy(lastUsedAt = now)
            _keys.value = _keys.value.map { if (it.id == match.id) updated else it }
            if (now - lastFlush > FLUSH_INTERVAL_MS) save()
            return updated
        }
    }

    fun flush() = synchronized(lock) { save() }

    private fun load(): List<ApiKey> = runCatching {
        if (file.exists()) json.decodeFromString<List<ApiKey>>(file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    private fun save() {
        lastFlush = clock()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(_keys.value))
        tmp.renameTo(file)
    }

    companion object {
        const val TOKEN_PREFIX = "thumbs_"
        private const val FLUSH_INTERVAL_MS = 60_000L
        private val random = SecureRandom()
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        fun generateToken(): String {
            // 43 base62 chars ≈ 256 bits
            val sb = StringBuilder(TOKEN_PREFIX)
            repeat(43) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
            return sb.toString()
        }

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
