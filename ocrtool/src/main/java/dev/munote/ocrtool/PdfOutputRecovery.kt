package dev.munote.ocrtool

import java.io.File
import java.io.IOException
import java.io.InputStream

/** Recognizes a finished SAF copy when the process died before queue completion. */
internal object PdfOutputRecovery {
    fun completedPages(jobDir: File, openOutput: () -> InputStream): Int? {
        val working = File(jobDir, "writing.pdf")
        return try {
            val checkpoint = File(jobDir, "export.state")
            if (!checkpoint.exists()) return null
            checkReadable(checkpoint)
            val pending = File(jobDir, "export.pending")
            if (pending.exists()) checkReadable(pending)
            val state = PdfExportJournal(jobDir).recover(working)
                ?: throw IOException("Cannot recover PDF export checkpoint '${checkpoint.absolutePath}'")
            if (state.nextPage != state.pageCount) return null

            working.inputStream().use { source ->
                openOutput().use { output ->
                    if (sameBytes(source, output)) state.pageCount else null
                }
            }
        } catch (cause: IOException) {
            throw IOException("Cannot verify PDF output against '${working.absolutePath}'; " +
                "keep the destination and recovery files", cause)
        } catch (cause: SecurityException) {
            throw IOException("Cannot access PDF output or '${working.absolutePath}'; " +
                "keep the destination and recovery files", cause)
        }
    }

    private fun checkReadable(file: File) {
        // Journal parsing tolerates invalid checkpoints. Check access separately
        // so an unreadable record remains an explicit I/O error with its cause.
        file.inputStream().use { it.read() }
    }

    private fun sameBytes(source: InputStream, output: InputStream): Boolean {
        val expected = ByteArray(128 * 1024)
        val actual = ByteArray(expected.size)
        while (true) {
            val expectedCount = readChunk(source, expected)
            val actualCount = readChunk(output, actual)
            if (expectedCount != actualCount) return false
            if (expectedCount < 0) return true
            for (index in 0 until expectedCount) {
                if (expected[index] != actual[index]) return false
            }
        }
    }

    private fun readChunk(input: InputStream, buffer: ByteArray): Int {
        var filled = 0
        while (filled < buffer.size) {
            val count = input.read(buffer, filled, buffer.size - filled)
            if (count < 0) return if (filled == 0) -1 else filled
            if (count == 0) {
                // Some document providers return zero without EOF. A single-byte
                // read makes progress instead of spinning or treating it as EOF.
                val next = input.read()
                if (next < 0) return if (filled == 0) -1 else filled
                buffer[filled++] = next.toByte()
            } else {
                filled += count
            }
        }
        return filled
    }
}
