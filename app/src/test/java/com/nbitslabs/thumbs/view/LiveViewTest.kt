package com.nbitslabs.thumbs.view

import com.nbitslabs.thumbs.auth.ApiKeyStore
import com.nbitslabs.thumbs.auth.Scope
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LiveViewTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private val keys by lazy { ApiKeyStore(File(tmp.root, "keys.json")) { now } }
    private val live by lazy { LiveView(keys) { now } }

    @Test fun `links work until they expire`() {
        val (token, _) = live.createLink(null, "app", 60_000)
        assertTrue(token.startsWith(LiveView.TOKEN_PREFIX))
        assertNotNull(live.validate(token))
        now += 60_000
        assertNull(live.validate(token))
    }

    @Test fun `links die with the key that created them`() {
        val key = keys.create("agent", Scope.READ)
        val (token, _) = live.createLink(key.key.id, "agent", 3_600_000)
        assertNotNull(live.validate(token))
        keys.delete(key.key.id)
        assertNull(live.validate(token))
    }

    @Test fun `links die when their key expires`() {
        val key = keys.create("temp", Scope.READ, validForMs = 1000)
        val (token, _) = live.createLink(key.key.id, "temp", 3_600_000)
        now += 1000
        assertNull(live.validate(token))
    }

    @Test fun `ending all views invalidates every link and unknown tokens fail`() {
        val (a, _) = live.createLink(null, "a", 60_000)
        val (b, _) = live.createLink(null, "b", 60_000)
        live.endAll()
        assertNull(live.validate(a))
        assertNull(live.validate(b))
        assertNull(live.validate("view_unknown"))
        assertNull(live.validate(null))
        assertNull(live.validate("thumbs_notaviewtoken"))
    }
}
