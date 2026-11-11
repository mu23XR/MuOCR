package dev.munote.ocrtool

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A failed replacement must leave the previously committed checkpoint intact. */
internal class DurableFile(
    private val replace: (File, File) -> Unit = ::atomicReplace
) {
    fun write(destination: File, bytes: ByteArray) {
        val temporary = File(destination.parentFile, destination.name + ".new")
        var failure: Throwable? = null
        try {
            try {
                FileOutputStream(temporary).use { stream ->
                    stream.write(bytes)
                    stream.fd.sync()
                }
                replace(temporary, destination)
            } catch (cause: IOException) {
                throw IOException("Cannot atomically commit checkpoint '${destination.absolutePath}'", cause)
            } catch (cause: SecurityException) {
                throw IOException("Cannot access checkpoint '${destination.absolutePath}'", cause)
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                if (temporary.exists() && !temporary.delete()) {
                    throw IOException("Cannot remove checkpoint temporary file '${temporary.absolutePath}'")
                }
            } catch (cleanup: Exception) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }
}

private fun atomicReplace(source: File, destination: File) {
    // Both paths share a directory. An unsupported atomic move is an error;
    // deleting the old checkpoint first would open a data-loss window.
    Files.move(source.toPath(), destination.toPath(),
        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
