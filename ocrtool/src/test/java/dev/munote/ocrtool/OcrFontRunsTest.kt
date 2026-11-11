package dev.munote.ocrtool

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OcrFontRunsTest {
    @Test fun mixedScriptsKeepEveryCodePointAndMergeAdjacentSharedFonts() {
        val latin = Any()
        val chinese = Any()
        val math = Any()
        val calls = mutableListOf<String>()
        val selector = OcrFontRuns { text: String ->
            calls.add(text)
            when (text.codePointAt(0)) {
                in 0..127 -> latin
                0x1D465 -> math
                else -> chinese
            }
        }
        val text = "选择题 AB 𝑥中文"
        val runs = selector.split(text)
        assertEquals(listOf("选择题", " AB ", "𝑥", "中文"), runs.map { it.text })
        assertEquals(text, runs.joinToString("") { it.text })
        assertSame(latin, runs[1].font)
        assertSame(math, runs[2].font)
        assertSame(chinese, runs[3].font)
        assertEquals(1, calls.count { it == " " })
        assertTrue(calls.contains("𝑥"))
        assertFalse(calls.any { it.length == 1 && it[0].isSurrogate() })
        val count = calls.size
        assertEquals("𝑥题A", selector.split("𝑥题A").joinToString("") { it.text })
        assertEquals("Repeated code points must not reopen fonts", count, calls.size)
    }

    @Test fun equalButDifferentFontInstancesAreNeverMerged() {
        data class Font(val name: String)
        val first = Font("same name")
        val second = Font("same name")
        val runs = OcrFontRuns { text: String -> if (text == "A") first else second }.split("AB")
        assertEquals(2, runs.size)
        assertSame(first, runs[0].font)
        assertSame(second, runs[1].font)
    }

    @Test fun missingGlyphReportsExactCodePointAndRetainsOriginalCause() {
        val original = IOException("font cannot encode")
        val runs = OcrFontRuns { text: String ->
            if (text == "𝑥") throw original
            Any()
        }
        val error = assertThrows(IOException::class.java) { runs.split("选择题𝑥") }
        assertTrue(error.message!!.contains("𝑥"))
        assertTrue(error.message!!.contains("U+1D465"))
        assertTrue(error.message!!.contains("缓存已保留"))
        assertSame(original, error.cause)
    }

    @Test fun invalidSurrogateIsRejectedBeforeCallingFontResolver() {
        var called = false
        val runs = OcrFontRuns { _: String -> called = true; Any() }
        val error = assertThrows(IOException::class.java) { runs.split("\uD800") }
        assertTrue(error.message!!.contains("U+D800"))
        assertFalse(called)
    }

    @Test fun fontsAreCachedOnlyForTheOwningBatch() {
        var calls = 0
        fun selector() = OcrFontRuns { _: String -> calls++; Any() }
        val first = selector()
        assertTrue(first.split("").isEmpty())
        first.split("AA")
        first.split("A")
        assertEquals(1, calls)
        selector().split("A")
        assertEquals(2, calls)
    }
}
