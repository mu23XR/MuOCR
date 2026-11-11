package dev.munote.ocrtool

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files

class DurableFileTest {
    private fun withCheckpoint(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("muocr-durable").toFile()
        try {
            block(File(directory, "checkpoint"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun successiveWritesReplaceAnExistingCheckpoint() = withCheckpoint { file ->
        val writer = DurableFile()
        for (content in listOf("first", "第二次提交", "third")) {
            writer.write(file, content.toByteArray(Charsets.UTF_8))
            assertEquals(content, file.readText(Charsets.UTF_8))
            assertFalse(File(file.parentFile, file.name + ".new").exists())
        }
    }

    @Test
    fun failedReplacementPreservesCommittedBytesAndReportsDestination() =
        withCheckpoint { file ->
            file.writeText("committed")
            val injected = IOException("Injected replacement failure")
            val writer = DurableFile { temporary, destination ->
                assertEquals(file, destination)
                assertEquals("candidate", temporary.readText())
                assertEquals("committed", destination.readText())
                throw injected
            }
            try {
                writer.write(file, "candidate".toByteArray())
                fail("Expected failed atomic replacement")
            } catch (error: IOException) {
                assertTrue(error.message!!.contains(file.absolutePath))
                assertSame(injected, error.cause)
            }
            assertEquals("committed", file.readText())
            assertFalse(File(file.parentFile, file.name + ".new").exists())
        }

    @Test
    fun unsupportedAtomicReplacementNeverDeletesThePreviousCheckpoint() =
        withCheckpoint { file ->
            file.writeText("committed")
            var attempts = 0
            val writer = DurableFile { temporary, destination ->
                attempts++
                throw AtomicMoveNotSupportedException(
                    temporary.path, destination.path, "Injected unsupported atomic move")
            }
            try {
                writer.write(file, "candidate".toByteArray())
                fail("Atomic move support must not silently fall back")
            } catch (error: IOException) {
                assertTrue(error.cause is AtomicMoveNotSupportedException)
            }
            assertEquals(1, attempts)
            assertEquals("committed", file.readText())
        }

    @Test
    fun failedInitialCommitDoesNotExposeAnUncommittedCheckpoint() =
        withCheckpoint { file ->
            val writer = DurableFile { _, _ -> throw IOException("Injected initial failure") }
            try {
                writer.write(file, "candidate".toByteArray())
                fail("Expected failed initial commit")
            } catch (_: IOException) {
                assertFalse(file.exists())
                assertFalse(File(file.parentFile, file.name + ".new").exists())
            }
        }
}
