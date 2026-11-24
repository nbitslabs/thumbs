package com.nbitslabs.thumbs.safety

import com.nbitslabs.thumbs.auth.ApiKey
import com.nbitslabs.thumbs.auth.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPolicyAndLeaseTest {
    private val selfPkg = "com.nbitslabs.thumbs"

    private fun key(
        id: String = "k1",
        name: String = "agent",
        scope: Scope = Scope.CONTROL,
        allowedPackages: List<String> = emptyList(),
    ) = ApiKey(
        id = id,
        name = name,
        prefix = "thumbs_test",
        hash = "hash_$id",
        scope = scope,
        createdAt = 0L,
        allowedPackages = allowedPackages,
    )

    // --- AppPolicy: foreground & launch checks --------------------------------------------

    @Test fun `selfPackage is always blocked even for unrestricted or explicitly listed keys`() {
        val unrestricted = key(name = "ctl", scope = Scope.CONTROL)
        val sneaky = key(name = "sneaky", scope = Scope.CONTROL, allowedPackages = listOf(selfPkg))

        assertNotNull(AppPolicy.checkForegroundApp(unrestricted, selfPkg, selfPkg))
        assertNotNull(AppPolicy.checkForegroundApp(sneaky, selfPkg.uppercase(), selfPkg))
        assertNotNull(AppPolicy.checkLaunchPackage(unrestricted, selfPkg, selfPkg))
        assertNotNull(AppPolicy.checkLaunchPackage(sneaky, selfPkg.uppercase(), selfPkg))
    }

    @Test fun `empty allowedPackages permits non-self apps and unknown foreground`() {
        val unrestricted = key(scope = Scope.RESEARCH, allowedPackages = emptyList())
        assertNull(AppPolicy.checkForegroundApp(unrestricted, "com.twitter.android", selfPkg))
        assertNull(AppPolicy.checkForegroundApp(unrestricted, null, selfPkg))
        assertNull(AppPolicy.checkLaunchPackage(unrestricted, "com.twitter.android", selfPkg))
    }

    @Test fun `non-empty allowedPackages allows listed apps case-insensitively and blocks others or null`() {
        val scoped = key(
            name = "tiktok-research",
            scope = Scope.RESEARCH,
            allowedPackages = listOf("com.zhiliaoapp.musically", "com.twitter.android"),
        )

        assertNull(AppPolicy.checkForegroundApp(scoped, "com.zhiliaoapp.musically", selfPkg))
        assertNull(AppPolicy.checkForegroundApp(scoped, "COM.TWITTER.ANDROID", selfPkg))
        assertNull(AppPolicy.checkLaunchPackage(scoped, "com.twitter.android", selfPkg))

        val wrongFg = AppPolicy.checkForegroundApp(scoped, "com.whatsapp", selfPkg)
        assertNotNull(wrongFg)
        assertTrue(wrongFg!!.contains("com.whatsapp"))
        assertTrue(wrongFg.contains("com.zhiliaoapp.musically"))

        val nullFg = AppPolicy.checkForegroundApp(scoped, null, selfPkg)
        assertNotNull(nullFg)
        assertTrue(nullFg!!.contains("com.zhiliaoapp.musically"))

        val blankFg = AppPolicy.checkForegroundApp(scoped, "   ", selfPkg)
        assertNotNull(blankFg)

        val wrongLaunch = AppPolicy.checkLaunchPackage(scoped, "com.android.settings", selfPkg)
        assertNotNull(wrongLaunch)
        assertTrue(wrongLaunch!!.contains("com.android.settings"))
    }

    @Test fun `launcher escape keys contain expected navigation keys`() {
        assertEquals(setOf("back", "home", "recents", "wake_screen"), AppPolicy.LAUNCHER_ESCAPE_KEYS)
        assertTrue(AppPolicy.isEscapeKey("back"))
        assertTrue(AppPolicy.isEscapeKey("wake-screen"))
        assertFalse(AppPolicy.isEscapeKey("enter"))
    }

    // --- AppPolicy: scope & open_url checks -----------------------------------------------

    @Test fun `READ scope blocks control tools and allows read tools`() {
        val reader = key(name = "observer", scope = Scope.READ)
        assertNull(AppPolicy.checkToolForScope(reader, "screenshot"))
        assertNull(AppPolicy.checkToolForScope(reader, "get_ui_tree"))
        assertNull(AppPolicy.checkToolForScope(reader, "get_shared_items"))
        assertNull(AppPolicy.checkToolForScope(reader, "capture_burst"))
        assertNull(AppPolicy.checkToolForScope(reader, "list_recordings"))
        assertNull(AppPolicy.checkToolForScope(reader, "get_recording"))
        assertNull(AppPolicy.checkToolForScope(reader, "list_clips"))
        assertNull(AppPolicy.checkToolForScope(reader, "custom_read_tool", Scope.READ))

        assertNotNull(AppPolicy.checkToolForScope(reader, "tap"))
        assertNotNull(AppPolicy.checkToolForScope(reader, "open_app"))
        assertNotNull(AppPolicy.checkToolForScope(reader, "open_url"))
        assertNotNull(AppPolicy.checkToolForScope(reader, "tap", Scope.CONTROL))
    }

    @Test fun `RESEARCH scope blocks high-privilege tools and allows browsing tools`() {
        val researcher = key(name = "scout", scope = Scope.RESEARCH)

        for (blocked in listOf("start_intent", "notification_action", "dismiss_notification", "open_notification")) {
            assertNotNull("Expected $blocked to be blocked", AppPolicy.checkToolForScope(researcher, blocked))
            assertNotNull("Expected $blocked to be blocked with toolScope", AppPolicy.checkToolForScope(researcher, blocked, Scope.CONTROL))
        }

        for (allowed in listOf("screenshot", "get_ui_tree", "tap", "scroll", "type_text", "open_app", "press_key", "capture_burst")) {
            assertNull("Expected $allowed to be allowed", AppPolicy.checkToolForScope(researcher, allowed))
        }
    }

    @Test fun `open_url in RESEARCH requires empty allow-list or browser package and http or https scheme`() {
        val openResearch = key(name = "open", scope = Scope.RESEARCH, allowedPackages = emptyList())
        val browserResearch = key(name = "browser", scope = Scope.RESEARCH, allowedPackages = listOf("com.twitter.android", "com.android.chrome"))
        val socialOnlyResearch = key(name = "social", scope = Scope.RESEARCH, allowedPackages = listOf("com.twitter.android"))

        assertNull(AppPolicy.checkToolForScope(openResearch, "open_url"))
        assertNull(AppPolicy.checkOpenUrl(openResearch, "https://example.com"))
        assertNull(AppPolicy.checkToolForScope(browserResearch, "open_url"))
        assertNull(AppPolicy.checkOpenUrl(browserResearch, "http://example.com"))

        assertNotNull(AppPolicy.checkToolForScope(socialOnlyResearch, "open_url"))
        assertNotNull(AppPolicy.checkOpenUrl(socialOnlyResearch))
        assertNotNull(AppPolicy.checkOpenUrl(openResearch, "tel:+15551234567"))
        assertNotNull(AppPolicy.checkOpenUrl(browserResearch, "mailto:test@example.com"))
    }

    // --- AppPolicy: risky action refusal for RESEARCH -------------------------------------

    @Test fun `RESEARCH hard-blocks risky actions and allows benign navigation labels`() {
        val researcher = key(name = "scout", scope = Scope.RESEARCH)
        val controller = key(name = "admin", scope = Scope.CONTROL)

        for (label in listOf("Post", "Send", "Pay", "删除")) {
            val denial = AppPolicy.checkRiskyActionForResearch(researcher, "Tap $label", listOf(null, label))
            assertNotNull("Expected '$label' to be blocked", denial)
            assertTrue(denial!!.contains("scout"))
            assertTrue(denial.contains("Tap $label"))
            assertNull("CONTROL keys should not be hard-blocked by research policy", AppPolicy.checkRiskyActionForResearch(controller, "Tap $label", listOf(label)))
        }

        for (benign in listOf("Search", "Share", "Copy link", "Following", "")) {
            assertNull("Expected '$benign' to be allowed", AppPolicy.checkRiskyActionForResearch(researcher, "Tap $benign", listOf(benign)))
        }
    }

    // --- PhoneLease -----------------------------------------------------------------------

    @Test fun `lease enforces mutual exclusion between two keys and formats busy error`() {
        var now = 1_000_000L
        val lease = PhoneLease(defaultIdleTtlMs = 90_000L, clock = { now })
        val keyA = key(id = "a", name = "Agent A")
        val keyB = key(id = "b", name = "Agent B")

        val acquired = lease.acquire(keyA)
        assertTrue(acquired.isSuccess)
        assertEquals("a", lease.current()?.keyId)
        assertEquals(now + 90_000L, acquired.getOrThrow().expiresAt)

        now += 30_000L
        val conflict = lease.acquire(keyB)
        assertTrue(conflict.isFailure)
        assertEquals(
            "Phone is busy: held by 'Agent A' (expires in 60s). Wait or ask them to call release_phone.",
            conflict.exceptionOrNull()?.message,
        )

        val touchErr = lease.touchOrAcquire(keyB)
        assertEquals(
            "Phone is busy: held by 'Agent A' (expires in 60s). Wait or ask them to call release_phone.",
            touchErr,
        )
    }

    @Test fun `same key renews lease and clamps explicit ttl`() {
        var now = 5_000L
        val lease = PhoneLease(defaultIdleTtlMs = 90_000L, clock = { now })
        val keyA = key(id = "a", name = "Agent A")

        val short = lease.acquire(keyA, ttlMs = 1_000L).getOrThrow()
        assertEquals(5_000L + PhoneLease.MIN_TTL_MS, short.expiresAt)

        val long = lease.acquire(keyA, ttlMs = 9_000_000L).getOrThrow()
        assertEquals(5_000L, long.acquiredAt)
        assertEquals(5_000L + PhoneLease.MAX_TTL_MS, long.expiresAt)

        // touchOrAcquire advances lastActivityAt without shrinking a longer explicit lease
        now += 20_000L
        assertNull(lease.touchOrAcquire(keyA))
        val afterTouch = lease.current()!!
        assertEquals(5_000L, afterTouch.acquiredAt)
        assertEquals(25_000L, afterTouch.lastActivityAt)
        assertEquals(5_000L + PhoneLease.MAX_TTL_MS, afterTouch.expiresAt)

        // Renewing near expiry extends by defaultIdleTtlMs
        now = afterTouch.expiresAt - 5_000L
        assertNull(lease.touchOrAcquire(keyA))
        assertEquals(now + 90_000L, lease.current()!!.expiresAt)
    }

    @Test fun `idle expiry frees lease automatically`() {
        var now = 10_000L
        val lease = PhoneLease(defaultIdleTtlMs = 90_000L, clock = { now })
        val keyA = key(id = "a", name = "Agent A")
        val keyB = key(id = "b", name = "Agent B")

        assertNull(lease.touchOrAcquire(keyA))
        assertNotNull(lease.current())

        now += 89_999L
        assertNotNull(lease.current())
        assertNotNull(lease.touchOrAcquire(keyB))

        now += 1L
        assertNull(lease.current())
        assertNull(lease.state.value)
        assertNull(lease.touchOrAcquire(keyB))
        assertEquals("b", lease.current()?.keyId)
    }

    @Test fun `revoking holding key frees lease immediately`() {
        val now = 10_000L
        val activeKeys = mutableSetOf("a", "b")
        val lease = PhoneLease(isKeyActive = { it in activeKeys }, clock = { now })
        val keyA = key(id = "a", name = "Agent A")
        val keyB = key(id = "b", name = "Agent B")

        assertTrue(lease.acquire(keyA, ttlMs = 300_000L).isSuccess)
        assertEquals("a", lease.current()?.keyId)

        activeKeys.remove("a")
        assertNull(lease.current())
        assertTrue(lease.acquire(keyB).isSuccess)
        assertEquals("b", lease.current()?.keyId)
    }

    @Test fun `only holding key can release and forceReleaseAll clears unconditionally`() {
        val lease = PhoneLease()
        val keyA = key(id = "a", name = "Agent A")
        val keyB = key(id = "b", name = "Agent B")

        assertFalse(lease.release(keyA))
        assertTrue(lease.acquire(keyA).isSuccess)
        assertFalse(lease.release(keyB))
        assertEquals("a", lease.current()?.keyId)

        assertTrue(lease.release(keyA))
        assertNull(lease.current())
        assertFalse(lease.release(keyA))

        assertTrue(lease.acquire(keyB).isSuccess)
        lease.forceReleaseAll()
        assertNull(lease.current())
        assertNull(lease.state.value)
    }
}
