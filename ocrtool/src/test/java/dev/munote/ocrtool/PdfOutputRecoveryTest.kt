package dev.munote.ocrtool

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class PdfOutputRecoveryTest {
    @Test
    fun missingJournalDoesNotOpenTheDestination() = withDirectory { dir ->
        File(dir, "writing.pdf").writeText("completed-looking bytes")
        assertNull(PdfOutputRecovery.completedPages(dir) {
            throw AssertionError("Destination must not be opened without a journal")
        })
    }

    @Test
    fun unfinishedJournalDoesNotOpenTheDestination() = withWork(completed = false) { dir, _ ->
        assertNull(PdfOutputRecovery.completedPages(dir) {
            throw AssertionError("Destination must not be opened before all pages commit")
        })
    }

    @Test
    fun invalidWorkingLengthIsProtectedAsAnAmbiguousOutput() = withWork { dir, _ ->
        val working = File(dir, "writing.pdf").apply { appendText("uncommitted") }
        val before = working.readBytes()
        assertThrows(IOException::class.java) {
            PdfOutputRecovery.completedPages(dir) {
                throw AssertionError("Destination must not be opened with an invalid work copy")
            }
        }
        assertArrayEquals(before, working.readBytes())
    }

    @Test
    fun corruptCheckpointDoesNotPermitDestinationDeletion() = withWork { dir, bytes ->
        val checkpoint = File(dir, "export.state").apply { writeText("corrupt checkpoint") }
        assertThrows(IOException::class.java) {
            PdfOutputRecovery.completedPages(dir) {
                throw AssertionError("Destination must not be opened with a corrupt checkpoint")
            }
        }
        assertEquals("corrupt checkpoint", checkpoint.readText())
        assertArrayEquals(bytes, File(dir, "writing.pdf").readBytes())
    }

    @Test
    fun corruptPendingCheckpointKeepsTheWorkCopy() = withWork { dir, bytes ->
        val pending = File(dir, "export.pending").apply { writeText("broken pending state") }
        assertThrows(IOException::class.java) {
            PdfOutputRecovery.completedPages(dir) {
                throw AssertionError("Destination must not be opened with a corrupt pending checkpoint")
            }
        }
        assertEquals("broken pending state", pending.readText())
        assertArrayEquals(bytes, File(dir, "writing.pdf").readBytes())
    }

    @Test
    fun identicalFilesRecoverThePageCountWithoutChangingFiles() = withWork { dir, bytes ->
        val destination = File(dir, "exported.pdf").apply { writeBytes(bytes) }
        val journalBytes = File(dir, "export.state").readBytes()
        assertEquals(3, PdfOutputRecovery.completedPages(dir) { destination.inputStream() })
        assertArrayEquals(bytes, destination.readBytes())
        assertArrayEquals(bytes, File(dir, "writing.pdf").readBytes())
        assertArrayEquals(journalBytes, File(dir, "export.state").readBytes())
    }

    @Test
    fun sameLengthWithDifferentBytesIsNotComplete() = withWork { dir, bytes ->
        val changed = bytes.copyOf()
        val difference = 128 * 1024 + 17
        changed[difference] = (changed[difference] + 1).toByte()
        val destination = File(dir, "exported.pdf").apply { writeBytes(changed) }
        assertNull(PdfOutputRecovery.completedPages(dir) { destination.inputStream() })
        assertArrayEquals(changed, destination.readBytes())
    }

    @Test
    fun truncatedDestinationIsNotComplete() = withWork { dir, bytes ->
        val destination = File(dir, "exported.pdf").apply { writeBytes(bytes.copyOf(bytes.size - 1)) }
        assertNull(PdfOutputRecovery.completedPages(dir) { destination.inputStream() })
    }

    @Test
    fun trailingDestinationBytesAreNotComplete() = withWork { dir, bytes ->
        val destination = File(dir, "exported.pdf").apply { writeBytes(bytes + byteArrayOf(7)) }
        assertNull(PdfOutputRecovery.completedPages(dir) { destination.inputStream() })
    }

    @Test
    fun splitAndZeroLengthReadsDoNotLoseBytesAndTheStreamIsClosed() = withWork { dir, bytes ->
        var position = 0
        var calls = 0
        var closed = false
        val split = object : InputStream() {
            override fun read(): Int =
                if (position == bytes.size) -1 else bytes[position++].toInt() and 0xff

            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                assertTrue(length <= 128 * 1024)
                if (position == bytes.size) return -1
                if (++calls % 3 == 0) return 0
                val count = minOf(7, length, bytes.size - position)
                bytes.copyInto(target, offset, position, position + count)
                position += count
                return count
            }

            override fun close() { closed = true }
        }
        assertEquals(3, PdfOutputRecovery.completedPages(dir) { split })
        assertTrue(closed)
    }

    @Test
    fun readFailureKeepsRecoveryFilesAndPreservesItsCause() = withWork { dir, bytes ->
        val original = IOException("provider disconnected")
        val journalBytes = File(dir, "export.state").readBytes()
        var closed = false
        val broken = object : InputStream() {
            override fun read(): Int = throw original
            override fun close() { closed = true }
        }
        val error = assertThrows(IOException::class.java) {
            PdfOutputRecovery.completedPages(dir) { broken }
        }
        assertSame(original, error.cause)
        assertTrue(error.message!!.contains(File(dir, "writing.pdf").absolutePath))
        assertTrue(closed)
        assertArrayEquals(bytes, File(dir, "writing.pdf").readBytes())
        assertArrayEquals(journalBytes, File(dir, "export.state").readBytes())
    }

    @Test
    fun openingFailureIsNotMisreportedAsAnIncompleteFile() = withWork { dir, _ ->
        val original = IOException("cannot open provider")
        val error = assertThrows(IOException::class.java) {
            PdfOutputRecovery.completedPages(dir) { throw original }
        }
        assertSame(original, error.cause)
    }

    private fun withWork(completed: Boolean = true, block: (File, ByteArray) -> Unit) =
        withDirectory { dir ->
            val bytes = ByteArray(2 * 128 * 1024 + 37) { (it * 31).toByte() }
            File(dir, "writing.pdf").writeBytes(bytes)
            val journal = PdfExportJournal(dir)
            val state = journal.initialize(bytes.size.toLong(), 3)
            journal.advanceWithoutChanges(state, if (completed) 3 else 2)
            block(dir, bytes)
        }

    private fun withDirectory(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("muocr-output-recovery").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
