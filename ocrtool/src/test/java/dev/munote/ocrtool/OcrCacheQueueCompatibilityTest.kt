package dev.munote.ocrtool

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OcrCacheQueueCompatibilityTest {
    @Test fun oldPausedTaskCanChangeTextStyleWithoutLosingOcrProgress() {
        val dir = Files.createTempDirectory("muocr-mode-switch").toFile()
        try {
            val file = File(dir, "queue.json")
            val store = OcrQueueLedger(file)
            val task = store.add(listOf("content://old.pdf" to "old.pdf"), "content://folder", 2200).single()
            store.claimNext()
            store.progress(task.id, "识别", 293, 395, 70)
            assertThrows(IllegalStateException::class.java) { store.setSimplifyChinese(task.id, true) }
            store.pause(task.id)
            store.finish(task.id, OcrQueueLedger.Status.PAUSED)
            store.setSimplifyChinese(task.id, true)
            val changed = OcrQueueLedger(file).snapshot().entries.single()
            assertTrue(changed.simplifyChinese)
            assertEquals(293, changed.page)
            assertEquals(395, changed.total)
            store.withCleanup(task.id, setOf(OcrQueueLedger.Status.PAUSED)) {
                assertThrows(java.io.IOException::class.java) { store.setSimplifyChinese(task.id, false) }
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun pendingOutputMustBeReconciledBeforeStyleChanges() {
        val dir = Files.createTempDirectory("muocr-mode-output").toFile()
        try {
            val store = OcrQueueLedger(File(dir, "queue.json"))
            val task = store.add(listOf("content://old.pdf" to "old.pdf"), "content://folder", 2200).single()
            store.claimNext()
            store.setOutput(task.id, "content://possibly-complete.pdf")
            store.deferOutputCompletion(task.id, "commit failed")
            assertThrows(IllegalStateException::class.java) { store.setSimplifyChinese(task.id, true) }
            assertFalse(store.snapshot().entries.single().simplifyChinese)
            assertEquals("content://possibly-complete.pdf", store.snapshot().entries.single().outputUri)
        } finally { dir.deleteRecursively() }
    }

    @Test fun oldQueueDefaultsToRawAndNewChoicePersistsAcrossRestart() {
        val dir = Files.createTempDirectory("muocr-style-queue").toFile()
        try {
            val file = File(dir, "queue.json")
            val store = OcrQueueLedger(file)
            store.add(listOf("content://old.pdf" to "old.pdf"), "content://folder", 2800)
            val old = JsonParser.parseString(file.readText()).asJsonObject
            old.getAsJsonArray("entries")[0].asJsonObject.remove("simplifyChinese")
            old.getAsJsonArray("entries")[0].asJsonObject.remove("discardLegacyCache")
            file.writeText(old.toString())
            val upgraded = OcrQueueLedger(file)
            assertFalse(upgraded.snapshot().entries.single().simplifyChinese)
            assertFalse(upgraded.snapshot().entries.single().discardLegacyCache)
            upgraded.add(listOf("content://new.pdf" to "new.pdf"), "content://folder", 2200, true)
            val reopened = OcrQueueLedger(file).snapshot().entries
            assertFalse(reopened[0].simplifyChinese)
            assertTrue(reopened[1].simplifyChinese)
            assertEquals(2800, reopened[0].quality)
        } finally { dir.deleteRecursively() }
    }

    @Test fun clearKeepsTaskPausedAndRequiresExclusiveCleanupLease() {
        val dir = Files.createTempDirectory("muocr-clear-queue").toFile()
        try {
            val store = OcrQueueLedger(File(dir, "queue.json"))
            val task = store.add(listOf("content://old.pdf" to "old.pdf"), "content://folder", 2200).single()
            store.claimNext()
            store.progress(task.id, "识别", 293, 395, 70)
            store.pause(task.id)
            store.finish(task.id, OcrQueueLedger.Status.PAUSED)
            assertThrows(IllegalStateException::class.java) { store.noteCacheCleared(task.id) }
            store.withCleanup(task.id, setOf(OcrQueueLedger.Status.PAUSED)) {
                assertThrows(java.io.IOException::class.java) { store.resume(task.id) }
                store.prepareCacheClear(task.id)
                store.noteCacheCleared(task.id)
            }
            val cleared = store.snapshot().entries.single()
            assertEquals(OcrQueueLedger.Status.PAUSED, cleared.status)
            assertEquals(0, cleared.page)
            assertEquals(0, cleared.total)
            assertEquals(0, cleared.progress)
            assertTrue(OcrQueueLedger(File(dir, "queue.json")).snapshot().entries.single().discardLegacyCache)
            store.resume(task.id)
            assertEquals(task.id, store.claimNext()!!.id)
        } finally { dir.deleteRecursively() }
    }
}
