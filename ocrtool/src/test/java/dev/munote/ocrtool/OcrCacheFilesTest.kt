package dev.munote.ocrtool

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class OcrCacheFilesTest {
    @Test fun legacy013IdentityStillMatchesTheDeployedDirectory() {
        assertEquals("c72e68bbbe8fbf1d2833cbfc932952ba", OcrCacheFiles.legacyId(
            "content://com.android.externalstorage.documents/document/primary%3ADocuments%2FMuOCR-Acceptance-20261104%2Finput%2Fmuocr-small-3pages-scanned.pdf",
            896209, 1793193758000))
    }
    private fun fixture(action: (File, File) -> Unit) {
        val base = Files.createTempDirectory("muocr-cache-").toFile()
        val root = File(base, "muocr-work").apply { mkdirs() }
        val job = File(root, "0123456789abcdef0123456789abcdef").apply { mkdirs() }
        try { action(root, job) } finally { base.deleteRecursively() }
    }

    @Test fun statisticsAndDeletionLeaveInputOutputAndFontUntouched() = fixture { root, job ->
        val source = File(root.parentFile, "source.pdf").apply { writeText("source") }
        val output = File(root.parentFile, "output.pdf").apply { writeText("output") }
        val font = File(root.parentFile, "muocr-font-v1/font.ttf").apply { checkNotNull(parentFile).mkdirs(); writeText("font") }
        val pages = File(job, "pages").apply { mkdir() }
        File(pages, "page_000001.json").writeText("one")
        File(pages, "page_000293.json").writeText("two")
        File(pages, "page_000294.json.tmp").writeText("partial")
        File(job, "original.pdf").writeText("copy")
        val row = OcrCacheFiles.scan(listOf(root)).single()
        assertEquals(2, row.pages)
        assertEquals(17L, row.bytes)
        OcrCacheFiles.clear(listOf(root), job)
        assertFalse(job.exists())
        assertEquals("source", source.readText())
        assertEquals("output", output.readText())
        assertEquals("font", font.readText())
    }

    @Test fun internalStorageCacheIsFoundAfterExternalStorageReturns() = fixture { root, job ->
        val external = File(root.parentFile, "external").apply { mkdir() }
        assertEquals(job, OcrCacheFiles.directory(listOf(external, root), job.name))
    }

    @Test fun ambiguousRootsAreReportedInsteadOfGuessing() = fixture { root, job ->
        val external = File(root.parentFile, "external").apply { mkdir() }
        File(external, job.name).mkdir()
        assertThrows(IOException::class.java) { OcrCacheFiles.directory(listOf(external, root), job.name) }
    }

    @Test fun interruptedImportMergesRemainingPagesAndPreservesBothCopies() = fixture { root, old ->
        val job = File(root, "queue-12345678-1234-1234-1234-123456789abc").apply { mkdir() }
        val sourcePages = File(old, "pages").apply { mkdir() }
        val newPages = File(job, "pages").apply { mkdir() }
        for (index in 1..3) File(sourcePages, "page_%06d.json".format(index)).writeText("raw-$index")
        File(newPages, "page_000001.json").writeText("raw-1")
        val valid: (File, Int) -> Boolean = { dir, index -> File(dir, "page_%06d.json".format(index + 1)).isFile }
        assertEquals(2, OcrCacheFiles.importPages(old, job, valid))
        assertEquals(0, OcrCacheFiles.importPages(old, job, valid))
        assertEquals("raw-3", File(newPages, "page_000003.json").readText())
        OcrCacheFiles.clear(listOf(root), old)
        assertEquals("raw-1", File(newPages, "page_000001.json").readText())
    }

    @Test fun damagedLegacyPageIsAnExplicitError() = fixture { root, old ->
        File(old, "pages").mkdir()
        File(old, "pages/page_000001.json").writeText("broken")
        val target = File(root, "queue-12345678-1234-1234-1234-123456789abc").apply { mkdir() }
        assertThrows(IOException::class.java) { OcrCacheFiles.importPages(old, target) { _, _ -> false } }
        assertTrue(File(old, "pages/page_000001.json").exists())
    }

    @Test fun oversizedLegacyPageIsRejectedBeforeParsing() = fixture { root, old ->
        File(old, "pages").mkdir()
        java.io.RandomAccessFile(File(old, "pages/page_000001.json"), "rw").use {
            it.setLength(8L * 1024 * 1024 + 1)
        }
        val target = File(root, "queue-12345678-1234-1234-1234-123456789abc").apply { mkdir() }
        var parsed = false
        assertThrows(IOException::class.java) {
            OcrCacheFiles.importPages(old, target) { _, _ -> parsed = true; false }
        }
        assertFalse("Oversized OCR data must not be read into memory", parsed)
        assertTrue(File(old, "pages/page_000001.json").exists())
    }

    @Test fun pendingCleanupCannotBeImportedAndCanBeRetried() = fixture { root, old ->
        File(old, OcrCacheFiles.CLEANUP_MARKER).writeText("muocr-cleanup-v1")
        File(old, "original.pdf").writeText("partial cleanup")
        val target = File(root, "queue-12345678-1234-1234-1234-123456789abc").apply { mkdir() }
        assertThrows(IOException::class.java) { OcrCacheFiles.importPages(old, target) { _, _ -> true } }
        assertTrue(OcrCacheFiles.scan(listOf(root)).first { it.directory == old }.cleanupPending)
        OcrCacheFiles.clear(listOf(root), old)
        assertFalse(old.exists())
    }

    @Test fun rejectsRootUnknownAndOutsideDirectories() = fixture { root, job ->
        assertThrows(IllegalArgumentException::class.java) { OcrCacheFiles.clear(listOf(root), root) }
        val other = File(root.parentFile, job.name).apply { mkdir() }
        assertThrows(IOException::class.java) { OcrCacheFiles.clear(listOf(root), other) }
        assertThrows(IllegalArgumentException::class.java) { OcrCacheFiles.directory(listOf(root), "../source.pdf") }
        assertTrue(job.exists())
    }

    @Test fun deletionFailureLeavesDurableMarkerAndRetryCompletes() = fixture { root, job ->
        val page = File(job, "original.pdf").apply { writeText("retained") }
        assertThrows(IOException::class.java) {
            OcrCacheFiles.clear(listOf(root), job) { file -> file != page && file.delete() }
        }
        assertTrue(page.exists())
        assertTrue(File(job, OcrCacheFiles.CLEANUP_MARKER).isFile)
        assertTrue(OcrCacheFiles.scan(listOf(root)).single().cleanupPending)
        OcrCacheFiles.clear(listOf(root), job)
        assertFalse(job.exists())
    }

    @Test fun maintenanceAndWorkersCannotOwnCacheTogether() {
        assertTrue(OcrCacheAccess.beginWorker())
        try {
            assertFalse(OcrCacheAccess.beginWorker())
            assertThrows(IOException::class.java) { OcrCacheAccess.maintain {} }
        } finally { OcrCacheAccess.endWorker() }
        assertThrows(IOException::class.java) {
            OcrCacheAccess.maintain {
                assertFalse(OcrCacheAccess.beginWorker())
                throw IOException("simulated deletion failure")
            }
        }
        assertTrue(OcrCacheAccess.beginWorker())
        OcrCacheAccess.endWorker()
    }
}
