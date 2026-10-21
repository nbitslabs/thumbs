package com.nbitslabs.thumbs.server

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

enum class NetworkMode {
    /** Accept connections only from Tailscale (CGNAT 100.64.0.0/10, fd7a:115c:a1e0::/48) and loopback. */
    TAILSCALE_ONLY,

    /** Accept connections from any address. Authentication is still required. */
    ANY,
}

object AccessPolicy {
    fun isTailscale(addr: InetAddress): Boolean = when (addr) {
        is Inet4Address -> {
            val b = addr.address
            // 100.64.0.0/10
            (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 0x40
        }
        is Inet6Address -> {
            val b = addr.address
            val prefix = byteArrayOf(0xfd.toByte(), 0x7a, 0x11, 0x5c, 0xa1.toByte(), 0xe0.toByte())
            prefix.indices.all { b[it] == prefix[it] }
        }
        else -> false
    }

    fun isAllowed(remote: String, mode: NetworkMode): Boolean {
        if (mode == NetworkMode.ANY) return true
        val addr = parse(remote) ?: return false
        // Loopback covers `adb forward` during development
        return addr.isLoopbackAddress || isTailscale(addr)
    }

    private fun parse(remote: String): InetAddress? {
        val host = remote.removePrefix("/").substringBefore('%').trim('[', ']')
        // Only accept numeric literals, never trigger a DNS lookup here
        if (host.isEmpty() || !(host.contains(':') || host.all { it.isDigit() || it == '.' })) return null
        return runCatching { InetAddress.getByName(host) }.getOrNull()
    }

    /** Local Tailscale addresses of this device, if Tailscale is connected. */
    fun tailscaleAddresses(): List<InetAddress> = localAddresses().filter(::isTailscale)

    fun localAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp }
            .flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
    }.getOrDefault(emptyList())
}

/**
 * Locks out a remote address after repeated authentication failures, so tokens can't be
 * brute-forced (not that 256 bits would allow it) and noisy scanners get cut off cheaply.
 */
class AuthFailureLimiter(
    private val maxFailures: Int = 10,
    private val windowMs: Long = 5 * 60_000L,
    private val lockoutMs: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(var windowStart: Long, var failures: Int, var lockedUntil: Long)

    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun isLockedOut(remote: String): Boolean {
        val e = entries[remote] ?: return false
        return clock() < e.lockedUntil
    }

    @Synchronized
    fun recordFailure(remote: String) {
        val now = clock()
        val e = entries.getOrPut(remote) { Entry(now, 0, 0) }
        if (now - e.windowStart > windowMs) {
            e.windowStart = now
            e.failures = 0
        }
        e.failures++
        if (e.failures >= maxFailures) e.lockedUntil = now + lockoutMs
        if (entries.size > 1000) entries.entries.removeIf { now - it.value.windowStart > windowMs && now > it.value.lockedUntil }
    }

    @Synchronized
    fun recordSuccess(remote: String) {
        entries.remove(remote)
    }
}
