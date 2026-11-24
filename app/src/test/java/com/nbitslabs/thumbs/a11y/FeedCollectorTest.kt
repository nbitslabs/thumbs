package com.nbitslabs.thumbs.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedCollectorTest {

    @Test fun `strips social UI chrome lines case-insensitively`() {
        val screen = FeedScreen(
            lines = listOf(
                "Show navigation drawer",
                "Home",
                "Explore",
                "Grok",
                "Notifications tab",
                "Messages",
                "Scroll to top",
                "Thumbs remote control",
                "Tap a feature to start using it:",
                "  @alice · 2h  ",
                "Verified",
                "Verified Business",
                "Shipping a faster local inference runtime today.",
                "Post options",
                "Explain this post with Grok",
                "Image",
                "Video",
                "PLAY",
                "Reply",
                "42",
                "REPOST",
                "19",
                "like",
                "310",
                "Impressions",
                "Bookmark",
                "Share",
                "Post",
                "Reply to this thread if you want the benchmark script.",
            ),
        )

        val collected = FeedDeduplicator.deduplicate(listOf(screen))

        assertEquals(1, collected.screensCaptured)
        assertFalse(collected.reachedEnd)
        assertEquals(
            listOf(
                "@alice · 2h",
                "Shipping a faster local inference runtime today.",
                "42",
                "19",
                "310",
                "Reply to this thread if you want the benchmark script.",
            ),
            collected.items,
        )
    }

    @Test fun `deduplicates overlapping multi-screen feed while preserving distinct short metrics`() {
        val screen0 = FeedScreen(
            lines = listOf(
                "@alice · 1h",
                "Just ran Android 17 accessibility benchmarks on Pixel 8 Pro.",
                "Reply",
                "42",
                "Like",
                "123",
                "@bob · 2h",
                "Compact UI tree mode cuts social feed noise roughly in half.",
                "15",
                "88",
            ),
        )
        val screen1 = FeedScreen(
            lines = listOf(
                "@bob · 2h",
                "Compact UI tree mode cuts social feed noise roughly in half.",
                "15",
                "88",
                "@carol · 3h",
                "Here is our breakdown of TikTok motion pacing and hook cuts.",
                "42",
                "123",
            ),
        )
        val screen2 = FeedScreen(
            lines = listOf(
                "@carol · 3h",
                "Here is our breakdown of TikTok motion pacing and hook cuts.",
                "42",
                "123",
                "@dave · 4h",
                "Compact UI tree mode cuts social feed noise roughly in half.",
                "New take on gesture navigation cropping in storyboards.",
                "9",
            ),
        )

        val collected = FeedDeduplicator.deduplicate(listOf(screen0, screen1, screen2))

        assertEquals(3, collected.screensCaptured)
        assertFalse(collected.reachedEnd)
        assertEquals(
            listOf(
                "@alice · 1h",
                "Just ran Android 17 accessibility benchmarks on Pixel 8 Pro.",
                "42",
                "123",
                "@bob · 2h",
                "Compact UI tree mode cuts social feed noise roughly in half.",
                "15",
                "88",
                "@carol · 3h",
                "Here is our breakdown of TikTok motion pacing and hook cuts.",
                "42",
                "123",
                "@dave · 4h",
                "New take on gesture navigation cropping in storyboards.",
                "9",
            ),
            collected.items,
        )
    }

    @Test fun `skips sticky header plus overlapping tail across scrolled screens`() {
        val screen0 = FeedScreen(
            lines = listOf(
                "For You",
                "@creator1",
                "First video caption with camera pan timing notes.",
                "1.2K",
                "@creator2",
                "Second video caption with split-screen comparison.",
                "540",
            ),
        )
        val screen1 = FeedScreen(
            lines = listOf(
                "For You",
                "@creator2",
                "Second video caption with split-screen comparison.",
                "540",
                "@creator3",
                "Third video caption showcasing match cuts.",
                "540",
            ),
        )

        val collected = FeedDeduplicator.deduplicate(listOf(screen0, screen1))

        assertEquals(
            listOf(
                "For You",
                "@creator1",
                "First video caption with camera pan timing notes.",
                "1.2K",
                "@creator2",
                "Second video caption with split-screen comparison.",
                "540",
                "@creator3",
                "Third video caption showcasing match cuts.",
                "540",
            ),
            collected.items,
        )
    }

    @Test fun `extracts distinct http and https urls trimming trailing punctuation`() {
        val screen0 = FeedScreen(
            lines = listOf(
                "Read the paper at https://example.com/paper.pdf, and code at (https://github.com/org/repo).",
                "Wiki ref: https://en.wikipedia.org/wiki/Android_(operating_system)!",
            ),
            urls = listOf("http://news.example.org/item?id=7"),
        )
        val screen1 = FeedScreen(
            lines = listOf(
                "Mirror: https://github.com/org/repo and https://x.com/user/status/1234567890?",
            ),
        )

        val collected = FeedDeduplicator.deduplicate(listOf(screen0, screen1))

        assertEquals(
            listOf(
                "http://news.example.org/item?id=7",
                "https://example.com/paper.pdf",
                "https://github.com/org/repo",
                "https://en.wikipedia.org/wiki/Android_(operating_system)",
                "https://x.com/user/status/1234567890",
            ),
            collected.urls,
        )
    }

    @Test fun `handles empty and identical screens cleanly`() {
        val empty = FeedDeduplicator.deduplicate(emptyList())
        assertEquals(0, empty.screensCaptured)
        assertTrue(empty.items.isEmpty())
        assertTrue(empty.urls.isEmpty())
        assertFalse(empty.reachedEnd)

        val onlyChrome = FeedScreen(listOf("Reply", "Like", "Share"))
        val identicalEmpty = FeedDeduplicator.deduplicate(listOf(onlyChrome, onlyChrome))
        assertEquals(2, identicalEmpty.screensCaptured)
        assertTrue(identicalEmpty.items.isEmpty())
        assertTrue(identicalEmpty.reachedEnd)

        val page = FeedScreen(listOf("@alice · 5m", "Bottom of the feed has been reached now.", "7"))
        val identical = FeedDeduplicator.deduplicate(listOf(page, page))
        assertEquals(2, identical.screensCaptured)
        assertTrue(identical.reachedEnd)
        assertEquals(listOf("@alice · 5m", "Bottom of the feed has been reached now.", "7"), identical.items)
    }

    @Test fun `formats collected feed with header items and urls`() {
        val feed = CollectedFeed(
            screensCaptured = 3,
            items = listOf("@alice · 1h", "Full release notes at https://example.com/notes"),
            urls = listOf("https://example.com/notes"),
            reachedEnd = true,
        )

        val formatted = FeedDeduplicator.format(feed, foregroundLabel = "X (com.twitter.android)")

        assertTrue(formatted.startsWith("Foreground: X (com.twitter.android) · Screens: 3 · Items: 2 · URLs: 1 · reached end"))
        assertTrue(formatted.contains("@alice · 1h\nFull release notes at https://example.com/notes"))
        assertTrue(formatted.contains("\n\nURLs:\n- https://example.com/notes"))

        val emptyFormatted = FeedDeduplicator.format(CollectedFeed(1, emptyList(), emptyList(), false))
        assertEquals("Screens: 1 · Items: 0\n\n(no feed text collected)", emptyFormatted)
    }
}
