package com.nbitslabs.thumbs.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApiKeyStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(file: File = File(tmp.root, "keys.json")) = ApiKeyStore(file)

    @Test fun `created token authenticates and only its hash is persisted`() {
        val file = File(tmp.root, "keys.json")
        val s = store(file)
        val created = s.create("laptop", Scope.CONTROL)
        assertTrue(created.token.startsWith("thumbs_"))
        assertEquals(50, created.token.length)
        assertEquals("laptop", s.authenticate(created.token)?.name)
        val onDisk = file.readText()
        assertFalse(onDisk.contains(created.token))
        assertTrue(onDisk.contains(ApiKeyStore.sha256(created.token)))
    }

    @Test fun `keys with a validity stop working when they expire`() {
        var now = 1_000_000L
        val s = ApiKeyStore(File(tmp.root, "keys.json")) { now }
        val temporary = s.create("temp", Scope.CONTROL, validForMs = 60_000)
        val permanent = s.create("perm", Scope.CONTROL)
        assertEquals(now + 60_000, temporary.key.expiresAt)
        assertNotNull(s.authenticate(temporary.token))
        now += 60_000
        assertNull(s.authenticate(temporary.token))
        assertNotNull(s.authenticate(permanent.token))
    }

    @Test fun `keys survive reload and can be revoked`() {
        val file = File(tmp.root, "keys.json")
        val a = store(file).create("a", Scope.READ)
        val b = store(file).create("b", Scope.CONTROL)
        val reloaded = store(file)
        assertEquals(Scope.READ, reloaded.authenticate(a.token)?.scope)
        assertNotNull(reloaded.authenticate(b.token))
        reloaded.delete(a.key.id)
        assertNull(store(file).authenticate(a.token))
        assertNotNull(store(file).authenticate(b.token))
    }

    @Test fun `rejects wrong, malformed and oversized tokens`() {
        val s = store()
        val created = s.create("x", Scope.CONTROL)
        assertNull(s.authenticate(created.token.dropLast(1) + "!"))
        assertNull(s.authenticate(""))
        assertNull(s.authenticate("Bearer " + created.token))
        assertNull(s.authenticate("thumbs_" + "a".repeat(500)))
    }

    @Test fun `authenticate records last use`() {
        var now = 1000L
        val s = ApiKeyStore(File(tmp.root, "k.json")) { now }
        val created = s.create("x", Scope.CONTROL)
        assertNull(s.keys.value.single().lastUsedAt)
        now = 5000L
        s.authenticate(created.token)
        assertEquals(5000L, s.keys.value.single().lastUsedAt)
    }

    @Test fun `tokens are unique`() {
        val tokens = (1..200).map { ApiKeyStore.generateToken() }.toSet()
        assertEquals(200, tokens.size)
    }
}
