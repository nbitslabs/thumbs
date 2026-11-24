package com.nbitslabs.thumbs.record

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkflowRecorderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun workflowDir(): File = File(tmp.root, "workflows")
    private fun clipsDir(): File = File(tmp.root, "clips")

    @Test fun `start and stop lifecycle tracks state and persists to disk`() {
        var now = 10_000L
        val recorder = WorkflowRecorder(workflowDir(), clock = { now })
        assertFalse(recorder.recording.value)
        assertNull(recorder.stop())

        val id = recorder.start("TikTok bookmark")
        assertTrue(recorder.recording.value)
        assertEquals("TikTok bookmark", recorder.activeName.value)
        assertEquals(0, recorder.activeStepCount.value)

        var threw = false
        try {
            recorder.start("Another")
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)

        now = 10_500L
        recorder.recordRaw(RawUiEvent("app_switch", "com.zhiliaoapp.musically"))
        now = 11_200L
        recorder.recordRaw(RawUiEvent("tap", "com.zhiliaoapp.musically", label = "Share", viewId = "com.zhiliaoapp.musically:id/share_btn"))
        assertEquals(2, recorder.activeStepCount.value)

        now = 12_000L
        val saved = recorder.stop()
        assertNotNull(saved)
        assertEquals(id, saved!!.id)
        assertEquals(2, saved.steps.size)
        assertEquals(500L, saved.steps[0].offsetMs)
        assertEquals(1200L, saved.steps[1].offsetMs)
        assertFalse(recorder.recording.value)
        assertNull(recorder.activeName.value)
        assertEquals(0, recorder.activeStepCount.value)

        // Reload from disk in a fresh instance
        val reloaded = WorkflowRecorder(workflowDir())
        assertEquals(1, reloaded.list().size)
        val loaded = reloaded.get(id)
        assertNotNull(loaded)
        assertEquals("TikTok bookmark", loaded!!.name)
        assertEquals(2, loaded.steps.size)

        assertTrue(reloaded.delete(id))
        assertNull(reloaded.get(id))
        assertEquals(0, reloaded.list().size)
    }

    @Test fun `ignores selfPackage and events while not recording`() {
        var now = 1_000L
        val recorder = WorkflowRecorder(workflowDir(), selfPackage = "com.nbitslabs.thumbs", clock = { now })
        recorder.recordRaw(RawUiEvent("tap", "com.example.app", label = "Before start"))

        recorder.start("Self filter")
        recorder.recordRaw(RawUiEvent("app_switch", "com.nbitslabs.thumbs"))
        recorder.recordRaw(RawUiEvent("tap", "com.nbitslabs.thumbs", label = "Enable clip recording"))
        recorder.recordRaw(RawUiEvent("tap", "com.example.app", label = "Search"))

        val wf = recorder.stop()!!
        assertEquals(1, wf.steps.size)
        assertEquals("com.example.app", wf.steps[0].packageName)
        assertEquals("Search", wf.steps[0].label)
    }

    @Test fun `redacts password flags and sensitive viewIds or labels without false positives`() {
        var now = 1_000L
        val recorder = WorkflowRecorder(workflowDir(), clock = { now })
        val id = recorder.start("Login flow")

        recorder.recordRaw(RawUiEvent("text_input", "com.bank.app", label = "hunter2", viewId = "com.bank.app:id/username_field", textLength = 7))
        now += 2000L
        recorder.recordRaw(RawUiEvent("text_input", "com.bank.app", label = "SuperSecret123", viewId = "com.bank.app:id/input", isPassword = true, textLength = 14))
        now += 2000L
        recorder.recordRaw(RawUiEvent("tap", "com.bank.app", label = "1234", contentDescription = "Enter PIN", viewId = "com.bank.app:id/enterPinField"))
        now += 2000L
        recorder.recordRaw(RawUiEvent("text_input", "com.bank.app", label = "998877", viewId = "com.bank.app:id/otp_code", textLength = 6))
        now += 2000L
        recorder.recordRaw(RawUiEvent("tap", "com.shop.app", label = "Shopping", contentDescription = "Pinterest board", viewId = "com.shop.app:id/shopping_tab"))

        val wf = recorder.stop()!!
        assertEquals(5, wf.steps.size)

        // Normal text_input never stores raw text, only length
        assertEquals("[text input]", wf.steps[0].label)
        assertEquals(7, wf.steps[0].textLength)
        assertFalse(wf.steps[0].redacted)

        // isPassword = true redacted
        assertTrue(wf.steps[1].redacted)
        assertEquals("[redacted]", wf.steps[1].label)
        assertNull(wf.steps[1].contentDescription)
        assertNull(wf.steps[1].textLength)

        // PIN viewId/description redacted
        assertTrue(wf.steps[2].redacted)
        assertEquals("[redacted]", wf.steps[2].label)
        assertNull(wf.steps[2].contentDescription)

        // OTP viewId redacted
        assertTrue(wf.steps[3].redacted)
        assertEquals("[redacted]", wf.steps[3].label)
        assertNull(wf.steps[3].textLength)

        // "Shopping" and "Pinterest" must NOT trigger "pin" false positive
        assertFalse(wf.steps[4].redacted)
        assertEquals("Shopping", wf.steps[4].label)

        // Ensure raw secret text never hits disk
        val diskJson = File(workflowDir(), "$id.json").readText()
        assertFalse(diskJson.contains("hunter2"))
        assertFalse(diskJson.contains("SuperSecret123"))
        assertFalse(diskJson.contains("998877"))
        assertFalse(diskJson.contains("1234"))
    }

    @Test fun `deduplicates rapid scroll, text_input, and app_switch within 1500ms`() {
        var now = 1_000L
        val recorder = WorkflowRecorder(workflowDir(), clock = { now })
        recorder.start("Dedup test")

        // App switches within 1500ms deduplicated
        recorder.recordRaw(RawUiEvent("app_switch", "com.example.feed"))
        now += 400L
        recorder.recordRaw(RawUiEvent("app_switch", "com.example.feed"))

        // Scrolls on same view within 1500ms deduplicated
        now += 200L
        recorder.recordRaw(RawUiEvent("scroll", "com.example.feed", viewId = "list", className = "RecyclerView"))
        now += 500L
        recorder.recordRaw(RawUiEvent("scroll", "com.example.feed", viewId = "list", className = "RecyclerView"))
        // Scroll after >1500ms kept
        now += 1600L
        recorder.recordRaw(RawUiEvent("scroll", "com.example.feed", viewId = "list", className = "RecyclerView"))

        // Typing progression within 1500ms coalesces into single step with latest length
        now += 200L
        recorder.recordRaw(RawUiEvent("text_input", "com.example.feed", viewId = "search_box", textLength = 1))
        now += 300L
        recorder.recordRaw(RawUiEvent("text_input", "com.example.feed", viewId = "search_box", textLength = 5))

        // Consecutive taps are NOT deduplicated
        now += 100L
        recorder.recordRaw(RawUiEvent("tap", "com.example.feed", label = "Like"))
        now += 100L
        recorder.recordRaw(RawUiEvent("tap", "com.example.feed", label = "Like"))

        val wf = recorder.stop()!!
        assertEquals(6, wf.steps.size)
        assertEquals("app_switch", wf.steps[0].type)
        assertEquals("scroll", wf.steps[1].type)
        assertEquals("scroll", wf.steps[2].type)
        assertEquals("text_input", wf.steps[3].type)
        assertEquals(5, wf.steps[3].textLength)
        assertEquals("tap", wf.steps[4].type)
        assertEquals("tap", wf.steps[5].type)
    }

    @Test fun `enforces maxSteps and maxWorkflows caps and formats markdown`() {
        var now = 1_000L
        val recorder = WorkflowRecorder(workflowDir(), maxSteps = 3, maxWorkflows = 2, clock = { now })

        val id1 = recorder.start("First")
        now += 100L
        recorder.recordRaw(RawUiEvent("tap", "com.a", label = "1"))
        recorder.recordRaw(RawUiEvent("tap", "com.a", label = "2"))
        recorder.recordRaw(RawUiEvent("tap", "com.a", label = "3"))
        recorder.recordRaw(RawUiEvent("tap", "com.a", label = "4"))
        val wf1 = recorder.stop()!!
        assertEquals(3, wf1.steps.size)

        now += 1000L
        val id2 = recorder.start("Second")
        now += 100L
        recorder.recordRaw(RawUiEvent("tap", "com.b", label = "B"))
        recorder.stop()

        now += 1000L
        val id3 = recorder.start("Third")
        now += 100L
        recorder.recordRaw(RawUiEvent("tap", "com.c", label = "C"))
        val wf3 = recorder.stop()!!

        val all = recorder.list()
        assertEquals(2, all.size)
        assertEquals(listOf(id3, id2), all.map { it.id })
        assertNull(recorder.get(id1))

        val md = recorder.formatAsMarkdown(wf1)
        assertTrue(md.contains("# Workflow: First"))
        assertTrue(md.contains("Steps: 3"))
        assertTrue(md.contains("**tap** in `com.a` — \"1\""))
    }

    @Test fun `clipRecorder enforces strict id validation and manages metadata`() {
        val dir = clipsDir()
        dir.mkdirs()

        val json = Json { prettyPrint = true }
        val meta1 = ClipMetadata("clip_1001_ab12", 1000L, 5000L, 720, 1600, 24, false, 12345L)
        val meta2 = ClipMetadata("clip_2002_cd34", 2000L, 8000L, 720, 1600, 24, false, 23456L)

        File(dir, "${meta1.id}.mp4").writeBytes(ByteArray(16) { 1 })
        File(dir, "${meta1.id}.json").writeText(json.encodeToString(ClipMetadata.serializer(), meta1))
        File(dir, "${meta2.id}.mp4").writeBytes(ByteArray(32) { 2 })
        File(dir, "${meta2.id}.json").writeText(json.encodeToString(ClipMetadata.serializer(), meta2))

        val recorder = ClipRecorder(dir)
        assertFalse(recorder.sessionReady.value)
        assertFalse(recorder.isRecording.value)

        val list = recorder.list()
        assertEquals(2, list.size)
        assertEquals(meta2.id, list[0].id)
        assertEquals(meta1.id, list[1].id)

        assertNotNull(recorder.getFile(meta1.id))
        assertNull(recorder.getFile("../etc/passwd"))
        assertNull(recorder.getFile("..\\etc\\passwd"))
        assertNull(recorder.getFile("abc"))
        assertNull(recorder.getFile("clip/sub"))
        assertNull(recorder.getFile("bad id"))
        assertNull(recorder.getFile(""))
        assertNull(recorder.getFile("nonexistent_clip"))

        assertFalse(recorder.delete("../etc/passwd"))
        assertTrue(recorder.delete(meta1.id))
        assertNull(recorder.getFile(meta1.id))
        assertFalse(File(dir, "${meta1.id}.json").exists())
        assertEquals(1, recorder.list().size)
    }

    @Test fun `redacts uppercase acronym fields and escalates sensitivity when coalescing text_input`() {
        var now = 1_000L
        val recorder = WorkflowRecorder(workflowDir(), clock = { now })
        recorder.start("Acronym and escalation")

        recorder.recordRaw(RawUiEvent("tap", "com.bank.app", label = "9911", viewId = "com.bank.app:id/OTPField"))
        now += 2000L
        recorder.recordRaw(RawUiEvent("tap", "com.bank.app", label = "4242", viewId = "com.bank.app:id/PINCode"))
        now += 2000L
        recorder.recordRaw(RawUiEvent("tap", "com.bank.app", label = "777", viewId = "com.bank.app:id/CVVInput"))
        now += 2000L
        // Coalescing text_input where second event escalates sensitivity to true
        recorder.recordRaw(
            RawUiEvent(
                type = "text_input",
                packageName = "com.bank.app",
                contentDescription = "Field",
                viewId = "com.bank.app:id/shared_input",
                textLength = 3,
                isPassword = false,
            ),
        )
        now += 300L
        recorder.recordRaw(
            RawUiEvent(
                type = "text_input",
                packageName = "com.bank.app",
                contentDescription = "Secret",
                viewId = "com.bank.app:id/shared_input",
                textLength = 6,
                isPassword = true,
            ),
        )

        val wf = recorder.stop()!!
        assertEquals(4, wf.steps.size)
        for (i in 0..2) {
            assertTrue("Expected step $i to be redacted", wf.steps[i].redacted)
            assertEquals("[redacted]", wf.steps[i].label)
            assertNull(wf.steps[i].contentDescription)
        }
        val merged = wf.steps[3]
        assertTrue(merged.redacted)
        assertEquals("[redacted]", merged.label)
        assertNull(merged.contentDescription)
        assertNull(merged.textLength)

        val md = recorder.formatAsMarkdown(wf)
        assertTrue(md.contains("— [redacted]"))
    }

    @Test fun `workflowRecorder rejects invalid ids and formats empty workflows`() {
        val recorder = WorkflowRecorder(workflowDir())
        assertNull(recorder.get("../etc/passwd"))
        assertNull(recorder.get("..\\secret"))
        assertNull(recorder.get("abc"))
        assertNull(recorder.get("a/b"))
        assertFalse(recorder.delete("../etc/passwd"))
        assertFalse(recorder.delete("..\\secret"))
        assertFalse(recorder.delete("abc"))
        assertFalse(recorder.delete("a/b"))

        recorder.start("Empty run")
        val emptyWf = recorder.stop()!!
        val md = recorder.formatAsMarkdown(emptyWf)
        assertTrue(md.contains("# Workflow: Empty run"))
        assertTrue(md.contains("(No steps recorded)"))
    }

    @Test fun `clipRecorder ignores orphan json metadata without matching mp4`() {
        val dir = clipsDir()
        dir.mkdirs()
        val json = Json { prettyPrint = true }
        val orphan = ClipMetadata("clip_orphan_1234", 5000L, 4000L, 720, 1600, 24, false, 999L)
        File(dir, "${orphan.id}.json").writeText(json.encodeToString(ClipMetadata.serializer(), orphan))

        val recorder = ClipRecorder(dir)
        assertTrue(recorder.list().isEmpty())
        assertNull(recorder.getFile(orphan.id))
    }
}
