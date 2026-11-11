package dev.munote.ocrtool

import org.apache.fontbox.ttf.TTFParser
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.LinkedHashSet

/**
 * Validates the same pinning and CJK glyph-subsetting path used by MuOCR.
 * The fallback font is prepared by Gradle before this test runs.
 */
class MuOcrChineseFontTest {
    private fun bundledFont(): File {
        val path = File("build/generated/muocrFonts/fonts/MuOCR-NotoSansSC.ttf")
        check(path.isFile) { "Missing bundled CJK font: ${path.absolutePath}" }
        assertEquals(17_772_300L, path.length())
        return path
    }

    @Test
    fun pinnedCjkFontHasTrueTypeGlyfAndLocaOutlines() {
        TTFParser().parse(bundledFont()).use { font ->
            assertTrue("Expected TrueType glyf outlines", font.tableMap.containsKey("glyf"))
            assertTrue("Expected TrueType loca table", font.tableMap.containsKey("loca"))
            assertTrue("CFF outlines are unsupported", !font.tableMap.containsKey("CFF "))
        }
    }

    @Test
    fun invisibleChineseGlyphLayerIsSearchableAfterIncrementalSave() {
        val dir = Files.createTempDirectory("muocr-cjk").toFile()
        val phrase = "高等数学 基础三十讲 中文文字层测试 选择题 选择題"
        try {
            val base = File(dir, "original.pdf")
            PDDocument().use { document ->
                document.addPage(PDPage())
                document.save(base)
            }
            val patch = File(dir, "patch.pdfdelta")
            val output = File(dir, "final.pdf")
            var spaceGlyphId = 0
            PDDocument.load(base).use { document ->
                val font = PDType0Font.load(document, bundledFont().inputStream(), true)
                val page = document.getPage(0)
                PDPageContentStream(document, page,
                    PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                    stream.beginText()
                    stream.setRenderingMode(RenderingMode.NEITHER)
                    stream.setFont(font, 12f)
                    stream.newLineAtOffset(24f, 600f)
                    stream.showText(phrase)
                    stream.endText()
                }
                // Mirror Android's explicit font subsetting. Desktop PDFBox
                // 2.0.31 would normally do this again during saveIncremental,
                // so clear its private pending-subset set in this regression
                // test only (not in the application).
                font.subset()
                val pending = PDDocument::class.java.getDeclaredField("fontsToSubset")
                pending.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (pending.get(document) as MutableCollection<Any>).clear()

                // Use the production resolver's original-codepoint collection;
                // a missing space entry extracts as its raw CID (often U+0001).
                val mapping = linkedMapOf<Int, Int>()
                TTFParser().parse(bundledFont()).use { ttf ->
                    val cmap = ttf.getUnicodeCmapLookup(false)
                    OcrUnicodeCMap.rememberUnicode(mapping, phrase, cmap::getGlyphId)
                    spaceGlyphId = cmap.getGlyphId(' '.code)
                }
                val unicodeStream = PDStream(document)
                unicodeStream.createOutputStream(COSName.FLATE_DECODE).use { out ->
                    out.write(OcrUnicodeCMap.encode(mapping))
                }
                font.cosObject.setItem(COSName.TO_UNICODE, unicodeStream.cosObject)
                font.cosObject.setNeedToBeUpdated(true)

                val touched = LinkedHashSet<COSDictionary>()
                touched.add(font.cosObject)
                page.cosObject.setNeedToBeUpdated(true)
                touched.add(page.cosObject)
                page.resources.cosObject.setNeedToBeUpdated(true)
                touched.add(page.resources.cosObject)
                val fonts = page.resources.cosObject.getCOSDictionary(
                    org.apache.pdfbox.cos.COSName.FONT
                )
                fonts?.setNeedToBeUpdated(true)
                if (fonts != null) touched.add(fonts)
                document.documentCatalog.cosObject.setNeedToBeUpdated(true)
                touched.add(document.documentCatalog.cosObject)
                document.documentCatalog.pages.cosObject.setNeedToBeUpdated(true)
                touched.add(document.documentCatalog.pages.cosObject)
                val writer = IncrementalPdfPatchOutputStream(patch, base.length())
                writer.use { document.saveIncremental(it, touched) }
                writer.verify()
            }
            base.copyTo(output)
            output.appendBytes(patch.readBytes())
            assertEquals(true, output.readBytes().copyOfRange(0, base.length().toInt())
                .contentEquals(base.readBytes()))
            PDDocument.load(output).use { document ->
                assertEquals(1, document.numberOfPages)
                val extracted = PDFTextStripper().getText(document)
                assertTrue("Chinese Unicode lost: wanted <${phrase}>, got <${extracted.take(240)}>",
                    extracted.contains(phrase))
                assertTrue("Unmapped space CID leaked into extracted text", '\u0001' !in extracted)
                val resources = document.getPage(0).resources
                val savedFont = resources.getFont(resources.fontNames.single())
                assertEquals("Space mapping missing from the saved PDF", " ",
                    savedFont.toUnicode(spaceGlyphId))
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
