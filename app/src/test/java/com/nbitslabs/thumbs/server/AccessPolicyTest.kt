package com.nbitslabs.thumbs.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessPolicyTest {
    @Test fun `tailscale only mode accepts tailnet and loopback`() {
        val m = NetworkMode.TAILSCALE_ONLY
        assertTrue(AccessPolicy.isAllowed("100.64.0.1", m))
        assertTrue(AccessPolicy.isAllowed("100.101.102.103", m))
        assertTrue(AccessPolicy.isAllowed("100.127.255.254", m))
        assertTrue(AccessPolicy.isAllowed("127.0.0.1", m))
        assertTrue(AccessPolicy.isAllowed("::1", m))
        assertTrue(AccessPolicy.isAllowed("fd7a:115c:a1e0::1234", m))
        assertTrue(AccessPolicy.isAllowed("/100.90.1.2", m))
    }

    @Test fun `tailscale only mode rejects everything else`() {
        val m = NetworkMode.TAILSCALE_ONLY
        assertFalse(AccessPolicy.isAllowed("100.63.255.255", m))
        assertFalse(AccessPolicy.isAllowed("100.128.0.1", m))
        assertFalse(AccessPolicy.isAllowed("192.168.1.10", m))
        assertFalse(AccessPolicy.isAllowed("10.0.2.2", m))
        assertFalse(AccessPolicy.isAllowed("fd7a:115c:a1e1::1", m))
        assertFalse(AccessPolicy.isAllowed("2001:db8::1", m))
        assertFalse(AccessPolicy.isAllowed("example.com", m))
        assertFalse(AccessPolicy.isAllowed("", m))
    }

    @Test fun `any mode accepts everything`() {
        assertTrue(AccessPolicy.isAllowed("192.168.1.10", NetworkMode.ANY))
    }

    @Test fun `limiter locks out after repeated failures and expires`() {
        var now = 0L
        val l = AuthFailureLimiter(maxFailures = 3, windowMs = 1000, lockoutMs = 5000) { now }
        repeat(2) { l.recordFailure("1.2.3.4") }
        assertFalse(l.isLockedOut("1.2.3.4"))
        l.recordFailure("1.2.3.4")
        assertTrue(l.isLockedOut("1.2.3.4"))
        assertFalse(l.isLockedOut("5.6.7.8"))
        now = 6000
        assertFalse(l.isLockedOut("1.2.3.4"))
    }

    @Test fun `limiter window resets and success clears`() {
        var now = 0L
        val l = AuthFailureLimiter(maxFailures = 3, windowMs = 1000, lockoutMs = 5000) { now }
        repeat(2) { l.recordFailure("a") }
        now = 2000
        l.recordFailure("a")
        assertFalse(l.isLockedOut("a"))
        l.recordFailure("a")
        l.recordSuccess("a")
        l.recordFailure("a")
        assertFalse(l.isLockedOut("a"))
    }
}
