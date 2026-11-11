package dev.munote.ocrtool

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class PdfOutputFinalizationTest {
    @Test
    fun failedCompletionCommitKeepsOutputAndWorkForAReopenedLedger() {
        val dir = Files.createTempDirectory("muocr-finalization").toFile()
        try {
            val manifest = File(dir, "queue.json")
            val queue = OcrQueueLedger(manifest)
            val entry = queue.add(listOf("content://input/book" to "book.pdf"),
                "content://tree/output", 2200)[0]
            queue.claimNext()
            val bytes = "%PDF-1.7\ncomplete searchable document\n%%EOF\n".toByteArray()
            val output = File(dir, "exported.pdf").apply { writeBytes(bytes) }
            val uri = output.toURI().toString()
            queue.setOutput(entry.id, uri)
            queue.progress(entry.id, "将完整可搜索 PDF 写入目标文件", 3, 3, 99)
            val manifestBefore = manifest.readBytes()

            val job = File(dir, "task").apply { mkdir() }
            val working = File(job, "writing.pdf").apply { writeBytes(bytes) }
            val journal = PdfExportJournal(job)
            val initial = journal.initialize(bytes.size.toLong(), 3)
            journal.advanceWithoutChanges(initial, 3)
            val injected = IOException("Injected completion commit failure")
            val failing = OcrQueueLedger(manifest, DurableFile { temporary, destination ->
                if (destination == manifest) throw injected
                Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            })

            val failure = assertThrows(IOException::class.java) { failing.complete(entry.id, uri, 3) }
            assertSame(injected, failure.cause)
            assertArrayEquals(manifestBefore, manifest.readBytes())
            val reopened = OcrQueueLedger(manifest)
            val pending = reopened.snapshot().entries[0]
            assertEquals(OcrQueueLedger.Status.RUNNING, pending.status)
            assertEquals(uri, pending.outputUri)
            assertEquals(99, pending.progress)
            assertArrayEquals(bytes, output.readBytes())
            assertArrayEquals(bytes, working.readBytes())

            val pages = PdfOutputRecovery.completedPages(job) { output.inputStream() }
            assertEquals(3, pages)
            reopened.complete(entry.id, uri, pages!!)
            val completed = OcrQueueLedger(manifest).snapshot().entries[0]
            assertEquals(OcrQueueLedger.Status.DONE, completed.status)
            assertEquals(uri, completed.outputUri)
            assertEquals(100, completed.progress)
            assertArrayEquals(bytes, output.readBytes())
            assertArrayEquals(bytes, working.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }
}
