package dev.munote.ocrtool

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

class OcrCacheCleanupTest {
    private class Fixture(val base: File, statuses: List<String>) {
        val root = File(base, "muocr-work").apply { mkdir() }
        val file = File(base, "queue.json")
        val tasks = statuses.mapIndexed { index, status -> OcrQueueLedger.Entry(
            id = UUID(0, index.toLong() + 1).toString(), name = "task-$index",
            inputUri = "content://input-$index", treeUri = "content://output", status = status,
            page = 73, total = 395, progress = 20) }
        val store: OcrQueueLedger
        init {
            file.writeText(Gson().toJson(OcrQueueLedger.Snapshot(entries = tasks.toMutableList())))
            store = OcrQueueLedger(file)
            tasks.forEach { add("queue-${it.id}") }
        }
        fun add(name: String): File = File(root, name).apply {
            mkdir()
            File(this, "original.pdf").writeText("private workcopy")
            File(this, "pages").mkdir()
            File(this, "pages/page_000001.json").writeText("raw OCR")
        }
        fun rows() = OcrCacheFiles.scan(listOf(root)).sortedBy { it.directory.name }
    }

    private fun fixture(statuses: List<String>, action: (Fixture) -> Unit) {
        val base = Files.createTempDirectory("muocr-batch-clear-").toFile()
        try { action(Fixture(base, statuses)) } finally { base.deleteRecursively() }
    }

    @Test fun selectionSeparatesFinishedTasksAndProtectsWaitingRunningAndPendingOutput() {
        fixture(listOf("done", "cancelled", "paused", "failed", "waiting", "running", "failed")) { f ->
            f.store.setOutput(f.tasks.last().id, "content://possibly-complete.pdf")
            f.add("0123456789abcdef0123456789abcdef")
            val completed = OcrCacheCleanup.select(f.rows(), f.store.snapshot().entries, true)
            val all = OcrCacheCleanup.select(f.rows(), f.store.snapshot().entries, false)
            assertEquals(2, completed.size)
            assertEquals(5, all.size)
            assertTrue(all.any { it.taskId == null })
            assertFalse(all.any { it.taskId == f.tasks[4].id || it.taskId == f.tasks[5].id || it.taskId == f.tasks[6].id })
        }
    }

    @Test fun wholeBatchHoldsEveryLeaseAndLeavesSourceOutputFontAndNewCacheUntouched() {
        fixture(listOf("paused", "paused")) { f ->
            val selected = f.rows()
            val addedLater = f.add("abcdef0123456789abcdef0123456789")
            val preserved = listOf("source.pdf", "export.pdf", "muocr-font-v1/font.ttf").map { name ->
                File(f.base, name).apply { checkNotNull(parentFile).mkdirs(); writeText(name) }
            }
            var checked = false
            val count = OcrCacheCleanup.clear(listOf(f.root), f.store, selected, ensureIdle = {}, delete = { file ->
                if (!checked) {
                    assertThrows(IOException::class.java) { f.store.resume(f.tasks.last().id) }
                    assertFalse(OcrCacheAccess.beginWorker())
                    checked = true
                }
                file.delete()
            })
            assertTrue(checked)
            assertEquals(2, count)
            assertTrue(addedLater.exists())
            preserved.forEach { assertEquals(it.relativeTo(f.base).invariantSeparatorsPath, it.readText()) }
            val reopened = OcrQueueLedger(f.file)
            reopened.snapshot().entries.forEach {
                assertEquals("paused", it.status)
                assertEquals(0, it.page)
                assertTrue(it.discardLegacyCache)
            }
            f.store.resume(f.tasks.last().id)
            assertEquals(f.tasks.last().id, f.store.claimNext()!!.id)
        }
    }

    @Test fun staleConfirmationCannotPartlyDeleteAfterOneTaskResumes() {
        fixture(listOf("paused", "paused")) { f ->
            val selected = f.rows()
            f.store.resume(f.tasks.last().id)
            assertThrows(IOException::class.java) { OcrCacheCleanup.clear(listOf(f.root), f.store, selected, {}) }
            assertEquals(2, f.rows().size)
            assertTrue(f.store.snapshot().entries.none { it.discardLegacyCache })
            f.store.resume(f.tasks.first().id) // No partial lease survives failed authorization.
        }
    }

    @Test fun pendingOutputBlocksTheEntireBatchBeforeDeletion() {
        fixture(listOf("paused", "failed")) { f ->
            val selected = f.rows()
            f.store.setOutput(f.tasks.last().id, "content://possibly-complete.pdf")
            assertThrows(IOException::class.java) { OcrCacheCleanup.clear(listOf(f.root), f.store, selected, {}) }
            assertEquals(2, f.rows().size)
            assertTrue(f.store.snapshot().entries.none { it.discardLegacyCache })
        }
    }

    @Test fun failedDeletionReportsPartialCountAndCanResumeAfterLedgerReopen() {
        fixture(listOf("paused", "paused", "paused")) { f ->
            val selected = f.rows()
            val failed = File(selected[1].directory, "original.pdf")
            val error = assertThrows(IOException::class.java) {
                OcrCacheCleanup.clear(listOf(f.root), f.store, selected, ensureIdle = {},
                    delete = { it != failed && it.delete() })
            }
            assertTrue(error.message!!.contains("1/3"))
            assertTrue(error.message!!.contains(selected[1].directory.absolutePath))
            assertFalse(selected[0].directory.exists())
            assertTrue(File(selected[1].directory, OcrCacheFiles.CLEANUP_MARKER).isFile)
            assertTrue(selected[2].directory.exists())
            val reopened = OcrQueueLedger(f.file)
            assertTrue(reopened.snapshot().entries[1].discardLegacyCache)
            assertFalse(reopened.snapshot().entries[2].discardLegacyCache)
            assertEquals(2, OcrCacheCleanup.clear(listOf(f.root), reopened, f.rows(), {}))
            assertTrue(f.rows().isEmpty())
            reopened.snapshot().entries.forEach { assertEquals(0, it.page); assertTrue(it.discardLegacyCache) }
        }
    }

    @Test fun unfinishedWorkAndExistingWorkerPreventAnyDeletionAndReleaseMaintenance() {
        fixture(listOf("paused")) { f ->
            assertThrows(IOException::class.java) {
                OcrCacheCleanup.clear(listOf(f.root), f.store, f.rows(), { throw IOException("pending WorkManager request") })
            }
            assertEquals(1, f.rows().size)
            assertTrue(OcrCacheAccess.beginWorker())
            try {
                assertThrows(IOException::class.java) { OcrCacheCleanup.clear(listOf(f.root), f.store, f.rows(), {}) }
            } finally { OcrCacheAccess.endWorker() }
            assertEquals(1, OcrCacheCleanup.clear(listOf(f.root), f.store, f.rows(), {}))
        }
    }
}
