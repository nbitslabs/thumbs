package com.nbitslabs.thumbs.safety

import com.nbitslabs.thumbs.auth.ApiKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LeaseInfo(
    val keyId: String,
    val keyName: String,
    val acquiredAt: Long,
    val lastActivityAt: Long,
    val expiresAt: Long,
)

/**
 * Cooperative single-agent lease preventing multiple API keys from interleaving UI control actions
 * on the phone at the same time.
 *
 * Leases expire automatically after [defaultIdleTtlMs] of inactivity or immediately if the holding
 * key is revoked/expired (checked via [isKeyActive]).
 */
class PhoneLease(
    private val defaultIdleTtlMs: Long = DEFAULT_IDLE_TTL_MS,
    private val isKeyActive: (String) -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var holder: LeaseInfo? = null

    private val _state = MutableStateFlow<LeaseInfo?>(null)
    /** Observable lease snapshot for UI display; call [current] for an expiry-checked read. */
    val state: StateFlow<LeaseInfo?> = _state.asStateFlow()

    /** Returns the active lease, or `null` if free, expired, or its holding key was revoked. */
    fun current(): LeaseInfo? = synchronized(lock) { activeHolderLocked(clock()) }

    /**
     * Acquires or renews the lease for [key] with [ttlMs] clamped to `10_000L..600_000L`.
     * Fails with a plain-English busy message if another active key currently holds the phone.
     */
    fun acquire(key: ApiKey, ttlMs: Long? = null): Result<LeaseInfo> {
        val ttl = (ttlMs ?: defaultIdleTtlMs).coerceIn(MIN_TTL_MS, MAX_TTL_MS)
        return synchronized(lock) {
            val now = clock()
            val active = activeHolderLocked(now)
            if (active != null && active.keyId != key.id) {
                return Result.failure(IllegalStateException(busyMessage(active, now)))
            }
            val acquiredAt = if (active != null && active.keyId == key.id) active.acquiredAt else now
            val updated = LeaseInfo(
                keyId = key.id,
                keyName = key.name,
                acquiredAt = acquiredAt,
                lastActivityAt = now,
                expiresAt = now + ttl,
            )
            holder = updated
            _state.value = updated
            Result.success(updated)
        }
    }

    /**
     * Called before any control tool: acquires the lease if free or extends it if owned by [key],
     * returning `null`. Returns a plain-English busy error message if held by another active key.
     */
    fun touchOrAcquire(key: ApiKey): String? {
        val idleTtl = defaultIdleTtlMs.coerceAtLeast(1L)
        return synchronized(lock) {
            val now = clock()
            val active = activeHolderLocked(now)
            if (active != null && active.keyId != key.id) {
                return busyMessage(active, now)
            }
            val acquiredAt = if (active != null) active.acquiredAt else now
            val nextExpiry = if (active != null) maxOf(active.expiresAt, now + idleTtl) else now + idleTtl
            val updated = LeaseInfo(
                keyId = key.id,
                keyName = key.name,
                acquiredAt = acquiredAt,
                lastActivityAt = now,
                expiresAt = nextExpiry,
            )
            holder = updated
            _state.value = updated
            null
        }
    }

    /** Releases the lease only if currently held by [key]. Returns `true` if released. */
    fun release(key: ApiKey): Boolean {
        return synchronized(lock) {
            val active = activeHolderLocked(clock()) ?: return false
            if (active.keyId != key.id) return false
            holder = null
            _state.value = null
            true
        }
    }

    /** Unconditionally clears any active lease (e.g. when the server stops or owner overrides in UI). */
    fun forceReleaseAll() = synchronized(lock) {
        holder = null
        _state.value = null
    }

    private fun activeHolderLocked(now: Long): LeaseInfo? {
        val current = holder ?: return null
        if (now >= current.expiresAt || !isKeyActive(current.keyId)) {
            holder = null
            _state.value = null
            return null
        }
        return current
    }

    private fun busyMessage(active: LeaseInfo, now: Long): String {
        val remainingMs = (active.expiresAt - now).coerceAtLeast(1L)
        val remainingSec = (remainingMs + 999L) / 1000L
        return "Phone is busy: held by '${active.keyName}' (expires in ${remainingSec}s). Wait or ask them to call release_phone."
    }

    companion object {
        const val DEFAULT_IDLE_TTL_MS = 90_000L
        const val MIN_TTL_MS = 10_000L
        const val MAX_TTL_MS = 600_000L
    }
}
