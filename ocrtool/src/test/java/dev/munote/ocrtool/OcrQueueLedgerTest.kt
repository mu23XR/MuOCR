package dev.munote.ocrtool

import org.junit.Assert.*
import com.google.gson.JsonParser
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OcrQueueLedgerTest {
    @Test
    fun interruptedCancellationWithAnOutputWaitsForWorkerVerification() = withLedger { queue, _ ->
        val entries = queue.three()
        queue.claimNext()
        val uri = "content://output/close-may-have-completed"
        queue.setOutput(entries[0].id, uri)
        queue.cancel(entries[0].id)

        queue.recoverAfterWorkerRestart()
        assertEquals(OcrQueueLedger.Status.CANCELLING, queue.stateOf(entries[0].id))
        assertEquals(uri, queue.snapshot().entries[0].outputUri)
        assertNull("Cleanup/next task must wait for output verification", queue.claimNext())

        queue.complete(entries[0].id, uri, 3)
        assertEquals(OcrQueueLedger.Status.DONE, queue.stateOf(entries[0].id))
        assertEquals(entries[1].id, queue.claimNext()!!.id)
    }

    private fun withLedger(block: (OcrQueueLedger, File) -> Unit) {
        val dir = Files.createTempDirectory("muocr-queue-jvm").toFile()
        try {
            block(OcrQueueLedger(File(dir, "queue.json")), dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun OcrQueueLedger.three(): List<OcrQueueLedger.Entry> =
        add(
            listOf(
                "content://input/A" to "教材A.pdf",
                "content://input/B" to "教材B.pdf",
                "content://input/C" to "教材C.pdf"
            ),
            "content://tree/output", 2200
        )

    @Test
    fun mutatingSnapshotDoesNotAffectOtherSnapshotsOrPersistedQueue() =
        withLedger { queue, dir ->
            val entries = queue.three()
            val manifest = File(dir, "queue.json")
            val persisted = manifest.readText(Charsets.UTF_8)
            val first = queue.snapshot()
            val second = queue.snapshot()
            assertNotSame(first.entries, second.entries)
            assertNotSame(first.entries[0], second.entries[0])

            first.pausedAll = true
            first.entries[0].name = "Changed by caller"
            first.entries[0].status = OcrQueueLedger.Status.DONE
            first.entries.clear()

            for (snapshot in listOf(second, queue.snapshot())) {
                assertFalse(snapshot.pausedAll)
                assertEquals(entries, snapshot.entries)
            }
            assertEquals(persisted, manifest.readText(Charsets.UTF_8))
        }

    @Test
    fun subsequentSnapshotReadsDiskChangesWithoutChangingPreviousSnapshot() =
        withLedger { queue, dir ->
            val entries = queue.three()
            val before = queue.snapshot()
            val manifest = File(dir, "queue.json")
            val json = JsonParser.parseString(manifest.readText(Charsets.UTF_8))
                .asJsonObject
            json.addProperty("pausedAll", true)
            json.getAsJsonArray("entries")[0].asJsonObject.apply {
                addProperty("name", "Changed on disk")
                addProperty("status", OcrQueueLedger.Status.PAUSED)
            }
            manifest.writeText(json.toString(), Charsets.UTF_8)

            val after = queue.snapshot()
            assertTrue(after.pausedAll)
            assertEquals("Changed on disk", after.entries[0].name)
            assertEquals(OcrQueueLedger.Status.PAUSED, after.entries[0].status)
            assertFalse(before.pausedAll)
            assertEquals(entries, before.entries)
        }

    @Test
    fun threeEntriesRunStrictlyOneAtATimeAndInOrder() = withLedger { queue, _ ->
        val added = queue.three()
        assertEquals(3, added.size)
        assertEquals(3, added.map { it.id }.toSet().size)
        assertEquals(added[0].id, queue.claimNext()!!.id)
        assertNull("Another entry started before active completed", queue.claimNext())
        queue.progress(added[0].id, "OCR", 40, 100, 35)
        assertEquals(35, queue.snapshot().entries.first().progress)
        queue.finish(added[0].id, OcrQueueLedger.Status.DONE)
        assertEquals(added[1].id, queue.claimNext()!!.id)
        queue.finish(added[1].id, OcrQueueLedger.Status.FAILED, "Damaged source")
        assertEquals(added[2].id, queue.claimNext()!!.id)
        queue.finish(added[2].id, OcrQueueLedger.Status.DONE)
        assertNull(queue.claimNext())
        assertEquals(
            listOf("done", "failed", "done"),
            queue.snapshot().entries.map { it.status }
        )
    }

    @Test
    fun pauseActiveTaskDoesNotPauseNextTask() = withLedger { queue, _ ->
        val entries = queue.three()
        assertEquals(entries[0].id, queue.claimNext()!!.id)
        queue.progress(entries[0].id, "已识别", 303, 583, 55)
        queue.pause(entries[0].id)
        assertEquals("pausing", queue.stateOf(entries[0].id))
        assertNull(queue.claimNext())
        queue.finish(entries[0].id, OcrQueueLedger.Status.PAUSED)
        assertEquals("paused", queue.stateOf(entries[0].id))
        assertEquals(303, queue.snapshot().entries.first().page)
        assertEquals(entries[1].id, queue.claimNext()!!.id)
        queue.finish(entries[1].id, OcrQueueLedger.Status.DONE)
        queue.resume(entries[0].id)
        queue.finish(entries[2].id, OcrQueueLedger.Status.WAITING)
        assertEquals(entries[0].id, queue.claimNext()!!.id)
    }

    @Test
    fun pauseAllThenImmediatelyResumeDoesNotStrandRunningTask() = withLedger { queue, _ ->
        val entries = queue.three()
        queue.claimNext()
        queue.pauseAll()
        assertEquals("pausing", queue.stateOf(entries[0].id))
        queue.resumeAll()
        assertEquals("running", queue.stateOf(entries[0].id))
        // If the worker already noticed pause before resume-all was clicked,
        // it should be requeued to continue, not left paused forever.
        queue.finish(entries[0].id, OcrQueueLedger.Status.PAUSED)
        assertEquals("waiting", queue.stateOf(entries[0].id))
        assertEquals(entries[0].id, queue.claimNext()!!.id)
    }

    @Test
    fun resumeAllMustNotResumeIndividuallyPausedPdf() = withLedger { queue, dir ->
        val entries = queue.three()
        val active = queue.claimNext()!!
        assertEquals(entries[0].id, active.id)

        // Individually pause B, then suspend the entire queue while A runs.
        queue.pause(entries[1].id)
        assertEquals(OcrQueueLedger.Status.PAUSED, queue.stateOf(entries[1].id))
        queue.pauseAll()
        assertEquals(OcrQueueLedger.Status.PAUSING, queue.stateOf(entries[0].id))
        queue.finish(entries[0].id, OcrQueueLedger.Status.PAUSED)

        // Simulate app process restart before global resume.
        val reopened = OcrQueueLedger(File(dir, "queue.json"))
        reopened.resumeAll()
        assertEquals(OcrQueueLedger.Status.WAITING, reopened.stateOf(entries[0].id))
        assertEquals(OcrQueueLedger.Status.PAUSED, reopened.stateOf(entries[1].id))
        assertEquals(OcrQueueLedger.Status.WAITING, reopened.stateOf(entries[2].id))

        assertEquals(entries[0].id, reopened.claimNext()!!.id)
        reopened.finish(entries[0].id, OcrQueueLedger.Status.DONE)
        assertEquals(entries[2].id, reopened.claimNext()!!.id)
        reopened.finish(entries[2].id, OcrQueueLedger.Status.DONE)
        assertNull(reopened.claimNext())

        // The explicit resume action, and only that action, can resume B.
        reopened.resume(entries[1].id)
        assertEquals(entries[1].id, reopened.claimNext()!!.id)
    }

    @Test
    fun resumeAllDuringActivePauseHonorsExplicitPerFilePause() =
        withLedger { queue, _ ->
            val entries = queue.three()
            queue.claimNext()
            queue.pauseAll()
            // A was pausing from a global request, but user explicitly
            // pauses that PDF before pressing Resume All.
            queue.pause(entries[0].id)
            queue.resumeAll()
            assertEquals(OcrQueueLedger.Status.PAUSING, queue.stateOf(entries[0].id))
            queue.finish(entries[0].id, OcrQueueLedger.Status.PAUSED)
            assertEquals(OcrQueueLedger.Status.PAUSED, queue.stateOf(entries[0].id))
            assertEquals(entries[1].id, queue.claimNext()!!.id)
        }

    @Test
    fun globalPausePendingAcrossWorkerRestartDoesNotUnpauseManualPause() =
        withLedger { queue, dir ->
            val entries = queue.three()
            queue.pause(entries[1].id)
            queue.claimNext()
            queue.pauseAll()
            val reopened = OcrQueueLedger(File(dir, "queue.json"))
            reopened.recoverAfterWorkerRestart()
            assertEquals(OcrQueueLedger.Status.PAUSED, reopened.stateOf(entries[0].id))
            reopened.resumeAll()
            assertEquals(OcrQueueLedger.Status.WAITING, reopened.stateOf(entries[0].id))
            assertEquals(OcrQueueLedger.Status.PAUSED, reopened.stateOf(entries[1].id))
        }

    @Test
    fun cancellingOneTaskDoesNotCancelOtherTasks() = withLedger { queue, _ ->
        val entries = queue.three()
        queue.claimNext()
        queue.cancel(entries[0].id)
        queue.cancel(entries[1].id)
        assertEquals("cancelling", queue.stateOf(entries[0].id))
        assertEquals("cancelled", queue.stateOf(entries[1].id))
        queue.finish(entries[0].id, OcrQueueLedger.Status.CANCELLED)
        assertEquals(entries[2].id, queue.claimNext()!!.id)
    }

    /**
     * Cancelling a task being claimed by the supervisor must tell the UI
     * whether it can safely reclaim files immediately. The old UI first
     * read WAITING, then called cancel(): if the worker claimed the task in
     * between, the UI would delete its live 1GB working directory.
     */
    @Test
    fun cancellationReturnsActualCommittedStateForSafeIndependentCleanup() =
        withLedger { queue, _ ->
            val entries = queue.three()
            assertEquals(
                OcrQueueLedger.Status.CANCELLED,
                queue.cancel(entries[2].id)
            )
            val claimed = queue.claimNext()!!
            assertEquals(entries[0].id, claimed.id)
            assertEquals(
                OcrQueueLedger.Status.CANCELLING,
                queue.cancel(entries[0].id)
            )
            assertNull("Running cancellation must finish before next claim", queue.claimNext())
            queue.finish(entries[0].id, OcrQueueLedger.Status.CANCELLED)
            assertEquals(entries[1].id, queue.claimNext()!!.id)
            // Cancelling a finished task must never delete its output.
            queue.finish(entries[1].id, OcrQueueLedger.Status.DONE)
            assertEquals(OcrQueueLedger.Status.DONE, queue.cancel(entries[1].id))
            assertNull(queue.cancel("missing-id"))
        }

    @Test
    fun completedOutputWinsOverALatePauseAndPersistsItsLocation() = withLedger { queue, dir ->
        val entries = queue.three()
        queue.claimNext()
        queue.pauseAll()
        assertEquals(OcrQueueLedger.Status.PAUSING, queue.stateOf(entries[0].id))
        val output = "content://output/completed-after-pause"

        queue.complete(entries[0].id, output, 583)
        val restored = OcrQueueLedger(File(dir, "queue.json")).snapshot()
        val completed = restored.entries[0]
        assertEquals(OcrQueueLedger.Status.DONE, completed.status)
        assertEquals(output, completed.outputUri)
        assertEquals(100, completed.progress)
        assertEquals(583, completed.page)
        assertEquals(583, completed.total)
        assertEquals("完成", completed.stage)
        assertEquals("", completed.error)
        assertFalse(completed.pausedByGlobal)
        assertEquals(0, completed.unexpectedRestarts)
        assertEquals(0L, completed.lastUnexpectedRestartAt)
        assertTrue("Completing one file must not resume the global queue", restored.pausedAll)
    }

    @Test
    fun completedOutputWinsOverALateCancelWithoutBlockingTheNextTask() =
        withLedger { queue, dir ->
            val entries = queue.three()
            queue.claimNext()
            queue.cancel(entries[0].id)
            assertEquals(OcrQueueLedger.Status.CANCELLING, queue.stateOf(entries[0].id))
            val output = "content://output/completed-after-cancel"

            queue.complete(entries[0].id, output, 42)
            val reopened = OcrQueueLedger(File(dir, "queue.json"))
            val completed = reopened.snapshot().entries[0]
            assertEquals(OcrQueueLedger.Status.DONE, completed.status)
            assertEquals(output, completed.outputUri)
            assertEquals(42, completed.page)
            assertEquals(42, completed.total)
            assertEquals(entries[1].id, reopened.claimNext()!!.id)
        }

    @Test
    fun lateCancellationOfACopiedOutputCanBeDeferredRetriedAndCompleted() =
        withLedger { queue, dir ->
            val entry = queue.three()[0]
            val output = "content://output/copied-before-late-cancel"
            queue.claimNext()
            queue.setOutput(entry.id, output)
            queue.cancel(entry.id)
            assertEquals(OcrQueueLedger.Status.CANCELLING, queue.stateOf(entry.id))

            queue.deferOutputCompletion(entry.id, "Completion could not be committed")
            val reopened = OcrQueueLedger(File(dir, "queue.json"))
            val deferred = reopened.snapshot().entries[0]
            assertEquals(OcrQueueLedger.Status.FAILED, deferred.status)
            assertEquals(output, deferred.outputUri)
            assertEquals("Completion could not be committed", deferred.error)

            reopened.retry(entry.id)
            assertEquals(output, reopened.snapshot().entries[0].outputUri)
            reopened.complete(entry.id, output, 583)
            val completed = OcrQueueLedger(File(dir, "queue.json")).snapshot().entries[0]
            assertEquals(OcrQueueLedger.Status.DONE, completed.status)
            assertEquals(output, completed.outputUri)
            assertEquals(583, completed.total)
            assertEquals("", completed.error)
        }

    @Test
    fun completingAnUnknownTaskDoesNotRewriteTheQueue() = withLedger { queue, dir ->
        queue.three()
        val manifest = File(dir, "queue.json")
        val before = manifest.readBytes()
        try {
            queue.complete("missing", "content://output/untracked", 1)
            fail("An untracked completion must be reported")
        } catch (error: IOException) {
            assertTrue(error.message!!.contains("missing"))
        }
        assertArrayEquals(before, manifest.readBytes())
    }

    @Test
    fun globalPauseAndResumeAreDurable() = withLedger { queue, dir ->
        val entries = queue.three()
        queue.claimNext()
        queue.pauseAll()
        assertTrue(queue.snapshot().pausedAll)
        assertEquals("pausing", queue.stateOf(entries[0].id))
        queue.finish(entries[0].id, OcrQueueLedger.Status.PAUSED)
        assertNull(queue.claimNext())

        val reopened = OcrQueueLedger(File(dir, "queue.json"))
        assertTrue(reopened.snapshot().pausedAll)
        reopened.resumeAll()
        assertEquals(entries[0].id, reopened.claimNext()!!.id)
    }

    @Test
    fun workerRestartResumesIncompleteEntriesButNotCancelled() =
        withLedger { queue, dir ->
            val entries = queue.three()
            queue.claimNext()
            queue.progress(entries[0].id, "写入文字层", 16, 583, 92)
            queue.cancel(entries[1].id)
            val afterRestart = OcrQueueLedger(File(dir, "queue.json"))
            afterRestart.recoverAfterWorkerRestart()
            assertEquals("waiting", afterRestart.stateOf(entries[0].id))
            assertEquals("cancelled", afterRestart.stateOf(entries[1].id))
            val resumed = afterRestart.claimNext()!!
            assertEquals(entries[0].id, resumed.id)
            assertEquals(16, resumed.page)
            assertEquals(92, resumed.progress)
        }

    @Test
    fun duplicatePdfDocumentsRemainSeparateQueueEntries() = withLedger { queue, _ ->
        val tasks = queue.add(
            listOf("content://same" to "same.pdf", "content://same" to "same.pdf"),
            "content://tree/out", 2800
        )
        assertNotEquals(tasks[0].id, tasks[1].id)
        queue.claimNext()
        queue.setOutput(tasks[0].id, "content://output/first")
        queue.finish(tasks[0].id, OcrQueueLedger.Status.DONE)
        queue.claimNext()
        queue.setOutput(tasks[1].id, "content://output/second")
        assertNotEquals(
            queue.snapshot().entries[0].outputUri,
            queue.snapshot().entries[1].outputUri
        )
        assertEquals(2800, tasks[1].quality)
    }

    @Test
    fun failedEntryCanRetryWithoutResettingItsProgress() = withLedger { queue, _ ->
        val entry = queue.add(listOf("content://input/X" to "X.pdf"), "tree", 2200)[0]
        queue.claimNext()
        queue.progress(entry.id, "OCR", 150, 200, 70)
        queue.finish(entry.id, OcrQueueLedger.Status.FAILED, "Too little disk")
        queue.resume(entry.id)
        assertEquals(entry.id, queue.claimNext()!!.id)
        assertEquals(150, queue.snapshot().entries.first().page)
    }

    @Test
    fun retryRetainsTheOutputUriForInterruptedCompletionReconciliation() =
        withLedger { queue, dir ->
            val entry = queue.three()[0]
            queue.claimNext()
            val output = "content://output/awaiting-queue-commit"
            queue.setOutput(entry.id, output)
            queue.progress(entry.id, "将完整可搜索 PDF 写入目标文件", 583, 583, 99)
            queue.finish(entry.id, OcrQueueLedger.Status.FAILED, "Queue commit interrupted")

            queue.retry(entry.id)
            val retried = OcrQueueLedger(File(dir, "queue.json")).snapshot().entries[0]
            assertEquals(OcrQueueLedger.Status.WAITING, retried.status)
            assertEquals(output, retried.outputUri)
            assertEquals(583, retried.page)
            assertEquals(99, retried.progress)
            assertEquals("", retried.error)
        }

    @Test
    fun repeatedlyKilledPdfIsSkippedWithoutBlockingOtherFiles() =
        withLedger { queue, dir ->
            val entries = queue.three()
            val first = entries[0]
            val originalAt = 10_000_000L

            assertEquals(first.id, queue.claimNext()!!.id)
            queue.progress(first.id, "第 303 页，写入文字层", 303, 583, 95)

            queue.recoverAfterWorkerRestart(originalAt)
            assertEquals(OcrQueueLedger.Status.WAITING, queue.stateOf(first.id))
            assertEquals(1, queue.snapshot().entries[0].unexpectedRestarts)
            assertEquals(first.id, queue.claimNext()!!.id)

            queue.recoverAfterWorkerRestart(originalAt + 3_000)
            assertEquals(2, queue.snapshot().entries[0].unexpectedRestarts)
            assertEquals(first.id, queue.claimNext()!!.id)

            // Third abnormal shutdown affects only document A.
            queue.recoverAfterWorkerRestart(originalAt + 6_000)
            val failed = queue.snapshot().entries[0]
            assertEquals(OcrQueueLedger.Status.FAILED, failed.status)
            assertEquals(303, failed.page)
            assertEquals(583, failed.total)
            assertTrue(failed.error.contains("继续处理队列"))
            assertEquals(entries[1].id, queue.claimNext()!!.id)
            queue.finish(entries[1].id, OcrQueueLedger.Status.DONE)
            assertEquals(entries[2].id, queue.claimNext()!!.id)
            queue.finish(entries[2].id, OcrQueueLedger.Status.DONE)

            // The manifest (including error and OCR progress) persists.
            val restored = OcrQueueLedger(File(dir, "queue.json"))
            assertEquals(OcrQueueLedger.Status.FAILED, restored.stateOf(first.id))
            assertEquals(303, restored.snapshot().entries[0].page)
            assertNull(restored.claimNext())

            // User can retry this one file later; it is not permanently banned.
            restored.retry(first.id)
            val retried = restored.snapshot().entries[0]
            assertEquals(0, retried.unexpectedRestarts)
            assertEquals("", retried.error)
            assertEquals(first.id, restored.claimNext()!!.id)
        }

    @Test
    fun crashRecoveryCountExpiresAfterAnHourAndManualPauseIsNotADeath() =
        withLedger { queue, _ ->
            val entries = queue.three()
            val t0 = 4_000_000L
            queue.claimNext()
            queue.recoverAfterWorkerRestart(t0)
            assertEquals(1, queue.snapshot().entries[0].unexpectedRestarts)

            queue.claimNext()
            queue.recoverAfterWorkerRestart(t0 + 2L * 60L * 60L * 1000L)
            assertEquals(1, queue.snapshot().entries[0].unexpectedRestarts)

            // A deliberate user pause resets the automatic-recovery counter.
            queue.claimNext()
            queue.pause(entries[0].id)
            queue.finish(entries[0].id, OcrQueueLedger.Status.PAUSED)
            assertEquals(0, queue.snapshot().entries[0].unexpectedRestarts)
            queue.resume(entries[0].id)
            assertEquals(entries[0].id, queue.claimNext()!!.id)
        }

    @Test
    fun queueManifestFromPreviousVersionLoadsWithoutRestartMetadata() =
        withLedger { queue, dir ->
            val entries = queue.three()
            val manifest = File(dir, "queue.json")
            val json = JsonParser.parseString(manifest.readText(Charsets.UTF_8))
                .asJsonObject
            // Mimic pre-v1.1.3 JSON exactly: the two new fields are absent.
            json.getAsJsonArray("entries").forEach { item ->
                item.asJsonObject.remove("unexpectedRestarts")
                item.asJsonObject.remove("lastUnexpectedRestartAt")
            }
            manifest.writeText(json.toString(), Charsets.UTF_8)
            assertFalse(manifest.readText().contains("unexpectedRestarts"))
            val oldQueue = OcrQueueLedger(manifest)
            assertEquals(3, oldQueue.snapshot().entries.size)
            assertEquals(0, oldQueue.snapshot().entries[0].unexpectedRestarts)
            assertEquals(entries[0].id, oldQueue.claimNext()!!.id)
        }

    @Test
    fun queueRecoversFromCorruptedPrimaryManifest() = withLedger { queue, dir ->
        val entries = queue.three()
        queue.claimNext()
        assertTrue(File(dir, "queue.json.bak").exists())
        File(dir, "queue.json").writeText("{CORRUPT")
        val restored = OcrQueueLedger(File(dir, "queue.json")).snapshot()
        assertEquals(entries.size, restored.entries.size)
    }

    @Test
    fun failedCommitAfterBackupRecoveryPreservesTheValidBackup() = withLedger { queue, dir ->
        val entries = queue.three()
        queue.claimNext()
        val manifest = File(dir, "queue.json")
        val backup = File(dir, "queue.json.bak")
        val validBackup = backup.readBytes()
        manifest.writeText("{CORRUPT")
        val attempted = mutableListOf<File>()
        val recovered = OcrQueueLedger(manifest, DurableFile { _, destination ->
            attempted.add(destination)
            throw IOException("Injected commit failure")
        })

        try {
            recovered.pauseAll()
            fail("Expected failed recovered-queue commit")
        } catch (_: IOException) {
            assertEquals(listOf(manifest), attempted)
        }
        assertArrayEquals(validBackup, backup.readBytes())
        assertEquals("{CORRUPT", manifest.readText())
        val restored = OcrQueueLedger(manifest).snapshot()
        assertFalse(restored.pausedAll)
        assertEquals(entries, restored.entries)
    }

    @Test
    fun successfulCommitAfterBackupRecoveryDoesNotBackUpCorruptPrimary() =
        withLedger { queue, dir ->
            val entries = queue.three()
            queue.claimNext()
            val manifest = File(dir, "queue.json")
            val backup = File(dir, "queue.json.bak")
            val validBackup = backup.readBytes()
            manifest.writeText("{CORRUPT")

            OcrQueueLedger(manifest).pauseAll()
            assertTrue(OcrQueueLedger(manifest).snapshot().pausedAll)
            assertArrayEquals(validBackup, backup.readBytes())
            manifest.writeText("{CORRUPT AGAIN")
            assertEquals(entries, OcrQueueLedger(manifest).snapshot().entries)
        }

    @Test
    fun failedBackupReplacementLeavesBothCommittedCopiesIntact() = withLedger { queue, dir ->
        queue.three()
        queue.claimNext()
        val manifest = File(dir, "queue.json")
        val backup = File(dir, "queue.json.bak")
        val mainBefore = manifest.readBytes()
        val backupBefore = backup.readBytes()
        val attempted = mutableListOf<File>()
        val failing = OcrQueueLedger(manifest, DurableFile { _, destination ->
            attempted.add(destination)
            throw IOException("Injected backup replacement failure")
        })

        try {
            failing.pauseAll()
            fail("Expected failed backup replacement")
        } catch (_: IOException) {
            assertEquals(listOf(backup), attempted)
        }
        assertArrayEquals(mainBefore, manifest.readBytes())
        assertArrayEquals(backupBefore, backup.readBytes())
        assertFalse(OcrQueueLedger(manifest).snapshot().pausedAll)
    }

    @Test
    fun failedPrimaryReplacementKeepsTheLastValidPrimaryAndBackup() =
        withLedger { queue, dir ->
            queue.three()
            queue.claimNext()
            val manifest = File(dir, "queue.json")
            val backup = File(dir, "queue.json.bak")
            val mainBefore = manifest.readBytes()
            val failing = OcrQueueLedger(manifest, DurableFile { temporary, destination ->
                if (destination == manifest) throw IOException("Injected primary failure")
                Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            })

            try {
                failing.pauseAll()
                fail("Expected failed primary replacement")
            } catch (_: IOException) {
                assertArrayEquals(mainBefore, manifest.readBytes())
                assertArrayEquals(mainBefore, backup.readBytes())
            }
            assertFalse(OcrQueueLedger(manifest).snapshot().pausedAll)
        }

    @Test
    fun removingPausedItemDoesNotTouchOtherRecords() = withLedger { queue, _ ->
        val entries = queue.three()
        queue.pause(entries[1].id)
        assertTrue(queue.remove(entries[1].id))
        assertEquals(listOf(entries[0].id, entries[2].id),
            queue.snapshot().entries.map { it.id })
        assertFalse(queue.remove("missing"))
    }

    @Test
    fun cleanupLeaseBlocksRetryAndRemovalWithoutBlockingQueueReads() = withLedger { queue, _ ->
        val entry = queue.add(listOf("content://input/A" to "A.pdf"), "tree", 2200)[0]
        queue.cancel(entry.id)
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val cleanup = threads.submit<Boolean> {
                queue.withCleanup(entry.id, setOf(OcrQueueLedger.Status.CANCELLED)) { current ->
                    assertEquals(entry.id, current.id)
                    acquired.countDown()
                    assertTrue(release.await(20, TimeUnit.SECONDS))
                    true
                } ?: false
            }
            assertTrue(acquired.await(10, TimeUnit.SECONDS))
            val interactions = threads.submit {
                assertThrows(IOException::class.java) { queue.retry(entry.id) }
                assertThrows(IOException::class.java) { queue.resume(entry.id) }
                assertFalse(queue.remove(entry.id))
                assertNull(queue.withCleanup(entry.id, setOf(OcrQueueLedger.Status.CANCELLED)) {
                    fail("A second cleanup must not acquire the same task")
                })
                assertEquals(OcrQueueLedger.Status.CANCELLED, queue.stateOf(entry.id))
            }
            // A held queue monitor would deadlock this operation against release.
            interactions.get(10, TimeUnit.SECONDS)
            release.countDown()
            assertTrue(cleanup.get(10, TimeUnit.SECONDS))
            queue.retry(entry.id)
            assertEquals(entry.id, queue.claimNext()!!.id)
        } finally {
            release.countDown()
            threads.shutdownNow()
            assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun failedCleanupReleasesItsLeaseAndLeasedWaitingTaskCannotBeClaimed() =
        withLedger { queue, _ ->
            val entry = queue.add(listOf("content://input/A" to "A.pdf"), "tree", 2200)[0]
            val injected = IOException("Injected cache deletion failure")
            val error = assertThrows(IOException::class.java) {
                queue.withCleanup(entry.id, setOf(OcrQueueLedger.Status.WAITING)) {
                    assertNull(queue.claimNext())
                    throw injected
                }
            }
            assertSame(injected, error)
            assertEquals("released", queue.withCleanup(entry.id,
                setOf(OcrQueueLedger.Status.WAITING)) { "released" })
            assertEquals(entry.id, queue.claimNext()!!.id)
        }

    @Test
    fun cleanupCannotUseACancelledSnapshotAfterRetryWinsTheLeaseRace() =
        withLedger { queue, _ ->
            val entry = queue.add(listOf("content://input/A" to "A.pdf"), "tree", 2200)[0]
            queue.cancel(entry.id)
            val staleSnapshot = queue.snapshot().entries[0]
            queue.retry(entry.id)

            assertNull(queue.withCleanup(staleSnapshot.id,
                setOf(OcrQueueLedger.Status.CANCELLED)) {
                fail("A stale cancelled entry must not permit deleting a retried task")
            })
            assertEquals(entry.id, queue.claimNext()!!.id)
        }

    @Test
    fun cancellingIdleTasksWithSavedOutputRequiresWorkerVerification() =
        withLedger { queue, _ ->
            val entries = queue.three()
            queue.pause(entries[1].id)
            queue.finish(entries[2].id, OcrQueueLedger.Status.FAILED)
            for (entry in entries) {
                val uri = "content://output/${entry.id}"
                queue.setOutput(entry.id, uri)
                assertEquals(OcrQueueLedger.Status.CANCELLING, queue.cancel(entry.id))
                assertEquals(uri, queue.snapshot().entries.find { it.id == entry.id }!!.outputUri)
                assertNull(queue.withCleanup(entry.id, setOf(OcrQueueLedger.Status.CANCELLED)) {
                    fail("An unverified output must not enter cancelled-task cleanup")
                })
            }
        }
}
