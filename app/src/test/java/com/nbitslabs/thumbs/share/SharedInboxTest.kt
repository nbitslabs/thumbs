package com.nbitslabs.thumbs.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SharedInboxTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun inbox(
        file: File = File(tmp.root, "shared_inbox.json"),
        maxItems: Int = 50,
        clock: () -> Long = System::currentTimeMillis,
    ) = SharedInbox(file, maxItems = maxItems, clock = clock)

    @Test fun `add extracts TikTok and X URLs and strips trailing punctuation`() {
        val s = inbox()
        val item = s.add(
            text = "Watch this clip (https://vm.tiktok.com/ZM8abc123/), and thread https://x.com/user/status/1823456789012345678?s=46.",
            subject = "Cool video",
            sourceApp = "com.zhiliaoapp.musically",
        )
        assertEquals(
            listOf(
                "https://vm.tiktok.com/ZM8abc123/",
                "https://x.com/user/status/1823456789012345678?s=46",
            ),
            item.urls,
        )
        assertEquals("Cool video", item.subject)
        assertEquals("com.zhiliaoapp.musically", item.sourceApp)
    }

    @Test fun `extractUrls preserves balanced parentheses and deduplicates`() {
        val urls = SharedInbox.extractUrls(
            "See (https://en.wikipedia.org/wiki/Android_(operating_system)). " +
                "Again: https://en.wikipedia.org/wiki/Android_(operating_system), " +
                "and http://example.com/path!",
        )
        assertEquals(
            listOf(
                "https://en.wikipedia.org/wiki/Android_(operating_system)",
                "http://example.com/path",
            ),
            urls,
        )
    }

    @Test fun `list and StateFlow return items newest first with limit`() {
        var now = 1_000L
        val s = inbox(clock = { now })
        val first = s.add("first")
        now = 2_000L
        val second = s.add("second")
        now = 3_000L
        val third = s.add("third")

        assertEquals(listOf(third.id, second.id, first.id), s.items.value.map { it.id })
        assertEquals(listOf(third.id, second.id), s.list(limit = 2).map { it.id })
        assertEquals(emptyList<SharedItem>(), s.list(limit = 0))
    }

    @Test fun `ring buffer evicts oldest items past maxItems`() {
        var now = 100L
        val file = File(tmp.root, "ring.json")
        val s = inbox(file = file, maxItems = 3, clock = { now++ })
        repeat(5) { i -> s.add("item-$i") }

        assertEquals(listOf("item-4", "item-3", "item-2"), s.list(10).map { it.text })
        val reloaded = inbox(file = file, maxItems = 3)
        assertEquals(listOf("item-4", "item-3", "item-2"), reloaded.list(10).map { it.text })
    }

    @Test fun `items survive reload from disk`() {
        val file = File(tmp.root, "persist.json")
        var now = 42_000L
        val first = inbox(file = file, clock = { now }).add(
            text = "https://x.com/dev/status/999",
            subject = "Post",
            sourceApp = "com.twitter.android",
        )
        now = 43_000L
        val second = inbox(file = file, clock = { now }).add("Second note")

        val reloaded = inbox(file = file)
        val items = reloaded.list()
        assertEquals(2, items.size)
        assertEquals(second, items[0])
        assertEquals(first, items[1])
        assertEquals(listOf("https://x.com/dev/status/999"), items[1].urls)
    }

    @Test fun `delete removes matching item and persists across reload`() {
        val file = File(tmp.root, "delete.json")
        val s = inbox(file = file)
        val a = s.add("alpha")
        val b = s.add("beta")

        assertFalse(s.delete("non-existent-id"))
        assertTrue(s.delete(a.id))
        assertFalse(s.delete(a.id))
        assertEquals(listOf(b.id), s.list().map { it.id })
        assertEquals(listOf(b.id), inbox(file = file).list().map { it.id })
    }

    @Test fun `clear removes all items and returns removed count`() {
        val file = File(tmp.root, "clear.json")
        val s = inbox(file = file)
        assertEquals(0, s.clear())
        s.add("one")
        s.add("two")

        assertEquals(2, s.clear())
        assertEquals(0, s.clear())
        assertTrue(s.items.value.isEmpty())
        assertTrue(inbox(file = file).list().isEmpty())
    }

    @Test fun `caps text at 8000 chars and subject at 500 chars`() {
        val s = inbox()
        val item = s.add(
            text = "a".repeat(12_000),
            subject = "s".repeat(900),
        )
        assertEquals(8_000, item.text.length)
        assertEquals(500, item.subject?.length)

        val blankSubject = s.add(text = "hello", subject = "   ")
        assertNull(blankSubject.subject)
    }

    @Test fun `rejects blank text and recovers cleanly from corrupt json`() {
        val file = File(tmp.root, "corrupt.json")
        file.writeText("{not valid json")
        val s = inbox(file = file)
        assertTrue(s.list().isEmpty())

        var threw = false
        try {
            s.add("   ")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        assertTrue(s.list().isEmpty())
    }

    @Test fun `extracts URLs from subject and deduplicates across text and subject`() {
        val s = inbox()
        val item = s.add(
            text = "Check https://x.com/user/status/123 and https://example.com/a.",
            subject = "Re: https://x.com/user/status/123 + https://vm.tiktok.com/ZM999/!",
        )
        assertEquals(
            listOf(
                "https://x.com/user/status/123",
                "https://example.com/a",
                "https://vm.tiktok.com/ZM999/",
            ),
            item.urls,
        )
    }

    @Test fun `clamps non-positive list limit and maxItems`() {
        val zeroCap = inbox(file = File(tmp.root, "zero.json"), maxItems = 0)
        zeroCap.add("first")
        zeroCap.add("second")
        assertEquals(listOf("second"), zeroCap.list(10).map { it.text })
        assertEquals(emptyList<SharedItem>(), zeroCap.list(-1))

        val negCap = inbox(file = File(tmp.root, "neg.json"), maxItems = -5)
        negCap.add("a")
        negCap.add("b")
        assertEquals(listOf("b"), negCap.items.value.map { it.text })
    }

    @Test fun `ignores bare schemes after punctuation stripping`() {
        val urls = SharedInbox.extractUrls("Broken (https://) and http://. and https://, plus https://ok.dev/1.")
        assertEquals(listOf("https://ok.dev/1"), urls)
    }

    @Test fun `concurrent add delete and list stay thread-safe and valid on disk`() {
        val file = File(tmp.root, "concurrent.json")
        val s = inbox(file = file, maxItems = 20)
        val threads = 4
        val iterations = 25
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val error = AtomicReference<Throwable?>(null)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) { t ->
                pool.execute {
                    try {
                        start.await(5, TimeUnit.SECONDS)
                        repeat(iterations) { i ->
                            val added = s.add("t$t-$i https://example.com/$t/$i")
                            s.list(10)
                            if (i % 3 == 0) s.delete(added.id)
                        }
                    } catch (e: Throwable) {
                        error.compareAndSet(null, e)
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(15, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertNull(error.get())
        val current = s.list(50)
        assertTrue(current.size <= 20)
        assertEquals(current.map { it.id }, inbox(file = file, maxItems = 20).list(50).map { it.id })
    }
}
