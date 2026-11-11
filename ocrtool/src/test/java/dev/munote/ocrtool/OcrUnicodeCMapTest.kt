package dev.munote.ocrtool

import org.apache.fontbox.cmap.CMapParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.charset.StandardCharsets

class OcrUnicodeCMapTest {
    @Test
    fun glyphCollectionIncludesSpacesAndPreservesNormalHanOverRadicalAliases() {
        val mapping = linkedMapOf<Int, Int>()
        val glyphs = mapOf(' '.code to 1, '\u3000'.code to 2,
            '\u2f00'.code to 3, '一'.code to 3, '丁'.code to 3)

        OcrUnicodeCMap.rememberUnicode(mapping, " \u3000\u2f00一\u2f00丁\n") {
            glyphs[it] ?: 0
        }

        val parsed = CMapParser().parse(OcrUnicodeCMap.encode(mapping).inputStream())
        assertEquals(" ", parsed.toUnicode(1))
        assertEquals("\u3000", parsed.toUnicode(2))
        assertEquals("一", parsed.toUnicode(3))
        assertEquals(3, mapping.size)
    }

    @Test
    fun emptyMappingProducesAValidCMapWithoutCharacterBlocks() {
        val encoded = OcrUnicodeCMap.encode(emptyMap())
        val parsed = CMapParser().parse(encoded.inputStream())

        assertEquals("MuOCR-ExactUnicode", parsed.name)
        assertFalse(parsed.hasUnicodeMappings())
        assertFalse(String(encoded, StandardCharsets.US_ASCII).contains("beginbfchar"))
    }

    @Test
    fun mappingOrderIsDeterministicAndBmpValuesRoundTrip() {
        val mapping = linkedMapOf(0xffff to 0xffff, 17 to 0x4e2d, 1 to 0)
        val encoded = OcrUnicodeCMap.encode(mapping)
        assertArrayEquals(encoded, OcrUnicodeCMap.encode(mapping.toSortedMap()))

        val parsed = CMapParser().parse(encoded.inputStream())
        assertEquals("\u0000", parsed.toUnicode(1))
        assertEquals("中", parsed.toUnicode(17))
        assertEquals("\uffff", parsed.toUnicode(0xffff))
    }

    @Test
    fun supplementaryCodePointsUseUtf16SurrogatePairs() {
        val mapping = mapOf(1 to 0x20000, 2 to 0x10ffff)
        val encoded = OcrUnicodeCMap.encode(mapping)
        val text = String(encoded, StandardCharsets.US_ASCII)
        assertTrue(text.contains("<0001> <D840DC00>"))
        assertTrue(text.contains("<0002> <DBFFDFFF>"))

        val parsed = CMapParser().parse(encoded.inputStream())
        mapping.forEach { (cid, codePoint) ->
            assertEquals(String(Character.toChars(codePoint)), parsed.toUnicode(cid))
        }
    }

    @Test
    fun characterBlocksStayWithinOneHundredEntriesAcrossBoundaries() {
        for (size in listOf(1, 99, 100, 101, 200, 201)) {
            val mapping = (1..size).associateWith { 0x4e00 + it }
            val encoded = OcrUnicodeCMap.encode(mapping)
            val blocks = String(encoded, StandardCharsets.US_ASCII).lineSequence()
                .filter { it.endsWith(" beginbfchar") }
                .map { it.substringBefore(' ').toInt() }
                .toList()
            assertEquals((size + 99) / 100, blocks.size)
            assertEquals(size, blocks.sum())
            assertTrue(blocks.all { it in 1..100 })

            val parsed = CMapParser().parse(encoded.inputStream())
            mapping.forEach { (cid, codePoint) ->
                assertEquals("Mapping lost at size $size, CID $cid",
                    String(Character.toChars(codePoint)), parsed.toUnicode(cid))
            }
        }
    }

    @Test
    fun invalidCidsAreRejectedWithTheOffendingMapping() {
        for (cid in listOf(-1, 0, 0x10000)) {
            val error = assertThrows(IOException::class.java) {
                OcrUnicodeCMap.encode(mapOf(cid to 0x4e2d))
            }
            assertEquals("Invalid OCR ToUnicode mapping CID=$cid codepoint=20013",
                error.message)
        }
    }

    @Test
    fun invalidCodePointsAndUnpairedSurrogatesAreRejected() {
        for (codePoint in listOf(-1, 0x110000, 0xd800, 0xdfff)) {
            val error = assertThrows(IOException::class.java) {
                OcrUnicodeCMap.encode(mapOf(1 to codePoint))
            }
            assertEquals("Invalid OCR ToUnicode mapping CID=1 codepoint=$codePoint",
                error.message)
        }
    }
}
