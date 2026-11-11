package dev.munote.ocrtool

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class OcrTextStyleTest {
    @Test fun trimsLegacyUnicodeWhitespaceAndPreservesEnglishWordSpacing() {
        assertEquals("选择題", OcrTextStyle.clean("\u3000选择題\u00a0 \t"))
        assertEquals("The quick brown fox", OcrTextStyle.clean("The  quick\n brown\r\nfox"))
        assertEquals("0 O 选择择题", OcrTextStyle.clean("0 O 选择择题"))
    }

    @Test fun stripsControlsAndZeroWidthCharactersWithoutSplittingSupplementaryText() {
        assertEquals("选择題", OcrTextStyle.clean("\uFEFF选\u200B择\u2060題\u0000\u007F\u200E\u202E "))
        assertEquals("The quick brown fox", OcrTextStyle.clean("The\u00A0quick\u0085brown\tfox"))
        assertEquals("e\u0301𠮷", OcrTextStyle.clean("e\u0301𠮷"))
        assertEquals("", OcrTextStyle.clean("\u0000\u200B\uFEFF\u00A0\n"))
    }

    @Test fun modeChangeRestartsExportButKeepsSourceAndRawOcr() {
        val dir = Files.createTempDirectory("muocr-text-").toFile()
        try {
            val state = File(dir, "export.state").apply { writeText("old state") }
            File(dir, "export.pending").writeText("old pending")
            val source = File(dir, "original.pdf").apply { writeText("source") }
            val pages = File(dir, "pages").apply { mkdir() }
            File(pages, "page_000001.json").writeText("选择題 ")
            OcrTextStyle.prepareExport(dir, true)
            assertFalse(state.exists())
            assertFalse(File(dir, "export.pending").exists())
            assertEquals("source", source.readText())
            assertEquals("选择題 ", File(pages, "page_000001.json").readText())
            // Death before mode commit must still invalidate the new uncommitted export.
            state.writeText("new but mode not committed")
            OcrTextStyle.prepareExport(dir, true)
            assertFalse(state.exists())
            OcrTextStyle.commitMode(dir, true)
            state.writeText("simplified checkpoint")
            OcrTextStyle.prepareExport(dir, true)
            assertEquals("simplified checkpoint", state.readText())
            OcrTextStyle.prepareExport(dir, false)
            assertFalse(state.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun legacyRawExportRestartsOnceButKeepsOcrAndRejectsDamagedMarkers() {
        val dir = Files.createTempDirectory("muocr-raw-").toFile()
        try {
            val state = File(dir, "export.state").apply { writeText("legacy checkpoint") }
            val pages = File(dir, "pages").apply { mkdir() }
            File(pages, "page_000001.json").writeText("raw OCR\u200B")
            OcrTextStyle.prepareExport(dir, false)
            assertFalse("An untagged export uses the old cleaning rules", state.exists())
            assertEquals("raw OCR\u200B", File(pages, "page_000001.json").readText())
            OcrTextStyle.commitMode(dir, false)
            state.writeText("v2 checkpoint")
            OcrTextStyle.prepareExport(dir, false)
            assertEquals("v2 checkpoint", state.readText())
            File(dir, "text-style.state").writeText("broken")
            assertThrows(IOException::class.java) { OcrTextStyle.prepareExport(dir, true) }
            assertEquals("v2 checkpoint", state.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun bothExplicitV1PoliciesRestartAndThenResumeTheirV2Checkpoint() {
        for (simple in listOf(false, true)) {
            val dir = Files.createTempDirectory("muocr-v1-").toFile()
            try {
                File(dir, "text-style.state").writeText(if (simple) "simplified-v1\n" else "raw-v1\n")
                val state = File(dir, "export.state").apply { writeText("old checkpoint") }
                val pending = File(dir, "export.pending").apply { writeText("old pending") }
                OcrTextStyle.prepareExport(dir, simple)
                assertFalse(state.exists())
                assertFalse(pending.exists())
                // A crash before publishing the new marker must invalidate an uncommitted export again.
                state.writeText("uncommitted")
                OcrTextStyle.prepareExport(dir, simple)
                assertFalse(state.exists())
                OcrTextStyle.commitMode(dir, simple)
                state.writeText("new checkpoint")
                OcrTextStyle.prepareExport(dir, simple)
                assertEquals("new checkpoint", state.readText())
            } finally { dir.deleteRecursively() }
        }
    }
}
