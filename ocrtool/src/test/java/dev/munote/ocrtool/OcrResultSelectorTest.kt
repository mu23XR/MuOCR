package dev.munote.ocrtool

import org.junit.Assert.*
import org.junit.Test

class OcrResultSelectorTest {
    private fun line(text: String = "原始文字", confidence: Float = 0.6f,
                     left: Int = 10, top: Int = 10, right: Int = 90, bottom: Int = 30) =
        OcrResultSelector.Line(text, left, top, right, bottom, confidence)

    private fun page(vararg lines: OcrResultSelector.Line, width: Int = 100, height: Int = 100) =
        OcrResultSelector.Page(width, height, lines.toList())

    @Test
    fun retriesOnlyPagesWithAvailableConfidenceAndUncertainText() {
        assertTrue(OcrResultSelector.shouldRetry(page(line(confidence = 0.8f))))
        assertFalse(OcrResultSelector.shouldRetry(page(line(confidence = 0.801f))))
        assertFalse(OcrResultSelector.shouldRetry(page(line(confidence = 0f))))
        assertTrue(OcrResultSelector.shouldRetry(page(line(confidence = 0.9f), line(confidence = 0f))))
        assertFalse(OcrResultSelector.shouldRetry(page()))
        assertFalse(OcrResultSelector.shouldRetry(page(line(text = " ", confidence = 0.1f))))
    }

    @Test
    fun usesTheEngineStringAndMapsBoundsIntoTheOriginalPageCoordinates() {
        val base = page(line(text = "反复复核", left = 10, top = 11, right = 90, bottom = 31))
        val candidate = page(line(text = "反复复复核", confidence = 0.8f,
            left = 21, top = 23, right = 179, bottom = 61), width = 200, height = 200)
        val selected = OcrResultSelector.improve(base, candidate)
        assertEquals(1, selected.replacements)
        assertEquals(100, selected.page.width)
        assertEquals(100, selected.page.height)
        assertEquals(line("反复复复核", 0.8f, 10, 11, 90, 31), selected.page.lines.single())
        assertEquals("反复复核", base.lines.single().text)
    }

    @Test
    fun retainsLegitimateRepeatsWhenNoEngineCandidateChangesThem() {
        val base = page(line("人人参与，反复复核"))
        val selected = OcrResultSelector.improve(base, page(line("人人参与，反复复核", 0.99f)))
        assertSame(base, selected.page)
        assertEquals(0, selected.replacements)
    }

    @Test
    fun identicalTextKeepsOriginalBoundsDespiteHigherConfidence() {
        val base = page(line())
        val candidate = page(line(confidence = 0.95f, left = 11, top = 11, right = 91, bottom = 31))
        assertSame(base, OcrResultSelector.improve(base, candidate).page)
    }

    @Test
    fun rejectsAHighConfidenceHalfLineInsteadOfDroppingItsOtherHalf() {
        val base = page(line("MuOCR Accuracy Benchmark - Page 2"))
        val candidate = page(line("MuOCR Accuracy Benchmark", 0.99f, right = 69))
        assertSame(base, OcrResultSelector.improve(base, candidate).page)
    }

    @Test
    fun rejectsCandidateThatContainsTooMuchExtraArea() {
        val base = page(line(left = 20, right = 80))
        val candidate = page(line("另一行", 0.99f, left = 10, right = 90))
        assertSame(base, OcrResultSelector.improve(base, candidate).page)
    }

    @Test
    fun rejectsAmbiguousCandidatePartners() {
        val base = page(line())
        val candidate = page(line("候选一", 0.9f), line("候选二", 0.95f))
        assertSame(base, OcrResultSelector.improve(base, candidate).page)
    }

    @Test
    fun rejectsCandidateThatEquallyMatchesTwoOriginalLines() {
        val base = page(line("原行一"), line("原行二"))
        assertSame(base, OcrResultSelector.improve(base, page(line("候选", 0.95f))).page)
    }

    @Test
    fun onlyMutuallyBestPartnersMayReplaceLines() {
        val first = line("原行一", top = 10, bottom = 30)
        val second = line("原行二", top = 12, bottom = 32)
        val base = page(first, second)
        val selected = OcrResultSelector.improve(base, page(line("候选", 0.9f, top = 11, bottom = 30)))
        assertEquals(1, selected.replacements)
        assertEquals("候选", selected.page.lines[0].text)
        assertSame(second, selected.page.lines[1])
    }

    @Test
    fun preservesUnmatchedOriginalLinesAndTheirReadingOrder() {
        val first = line("第一行", top = 40, bottom = 60)
        val second = line("第二行", top = 10, bottom = 30)
        val third = line("第三行", top = 70, bottom = 90)
        val base = page(first, second, third)
        val candidate = page(line("另一行", 0.9f, top = 10, bottom = 30))
        val selected = OcrResultSelector.improve(base, candidate)
        assertEquals(listOf("第一行", "另一行", "第三行"), selected.page.lines.map { it.text })
        assertSame(first, selected.page.lines[0])
        assertSame(third, selected.page.lines[2])
    }

    @Test
    fun keepsTheOriginalWhenTheGainIsBelowSixPercentagePoints() {
        val base = page(line(confidence = 0.6f))
        assertSame(base, OcrResultSelector.improve(base, page(line("候选", 0.6599f))).page)
        assertSame(base, OcrResultSelector.improve(base, page(line("候选", 0.6f))).page)
        assertEquals(1, OcrResultSelector.improve(base, page(line("候选", 0.66f))).replacements)
    }

    @Test
    fun neverChangesHighConfidenceOriginalLines() {
        val base = page(line(confidence = 0.801f))
        assertSame(base, OcrResultSelector.improve(base, page(line("候选", 0.99f))).page)
        assertEquals(1, OcrResultSelector.improve(page(line(confidence = 0.8f)),
            page(line("候选", 0.9f))).replacements)
    }

    @Test
    fun unavailableConfidenceKeepsTheOriginal() {
        val base = page(line(confidence = 0f))
        assertSame(base, OcrResultSelector.improve(base, page(line("候选", 0.9f))).page)
        val scoredBase = page(line())
        assertSame(scoredBase, OcrResultSelector.improve(scoredBase, page(line("候选", 0f))).page)
    }

    @Test
    fun emptyCandidateAndBlankCandidateKeepOriginalText() {
        val base = page(line())
        assertSame(base, OcrResultSelector.improve(base, page()).page)
        assertSame(base, OcrResultSelector.improve(base, page(line(" ", 0.99f))).page)
    }

    @Test
    fun rejectsInvalidConfidenceWithTheSourceAndLineNumber() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.01f, 1.01f)) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                OcrResultSelector.improve(page(line()), page(line("候选", invalid)))
            }
            assertTrue(error.message, error.message!!.contains("candidate line 1"))
            assertTrue(error.message, error.message!!.contains("confidence"))
        }
        val error = assertThrows(IllegalArgumentException::class.java) {
            OcrResultSelector.shouldRetry(page(line(confidence = Float.NaN)))
        }
        assertTrue(error.message, error.message!!.contains("base line 1"))
    }

    @Test
    fun rejectsInvalidPageDimensionsAndBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            OcrResultSelector.shouldRetry(page(line(), width = 0))
        }
        val error = assertThrows(IllegalArgumentException::class.java) {
            OcrResultSelector.improve(page(line()), page(line("候选", 0.9f, left = 20, right = 20)))
        }
        assertTrue(error.message, error.message!!.contains("candidate line 1"))
    }
}
