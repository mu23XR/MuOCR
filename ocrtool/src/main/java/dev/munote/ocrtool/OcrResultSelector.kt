package dev.munote.ocrtool

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Selects complete engine lines without correcting or rewriting their text. */
internal object OcrResultSelector {
    data class Line(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val confidence: Float
    )

    data class Page(val width: Int, val height: Int, val lines: List<Line>)
    data class Selection(val page: Page, val replacements: Int)

    private const val MAX_BASE_CONFIDENCE = 0.8f
    private const val MIN_CONFIDENCE_GAIN = 0.06
    private const val MIN_IOU = 0.5
    private const val MIN_COVERAGE = 0.8
    private const val GEOMETRY_EPSILON = 1e-9
    private const val CONFIDENCE_EPSILON = 1e-7

    fun shouldRetry(page: Page): Boolean {
        validate(page, "base")
        // All-zero confidence can mean that the engine does not supply scores.
        return hasConfidence(page) && page.lines.any {
            it.text.isNotBlank() && it.confidence <= MAX_BASE_CONFIDENCE
        }
    }

    fun improve(base: Page, candidate: Page): Selection {
        validate(base, "base")
        validate(candidate, "candidate")
        if (!hasConfidence(base) || !hasConfidence(candidate) ||
            base.lines.isEmpty() || candidate.lines.isEmpty()) return Selection(base, 0)

        val baseBoxes = base.lines.map { normalize(it, base) }
        val candidateBoxes = candidate.lines.map { normalize(it, candidate) }
        val baseBest = IntArray(base.lines.size) { -1 }
        val candidateBest = IntArray(candidate.lines.size) { -1 }
        val baseScores = DoubleArray(base.lines.size)
        val candidateScores = DoubleArray(candidate.lines.size)
        val baseAmbiguous = BooleanArray(base.lines.size)
        val candidateAmbiguous = BooleanArray(candidate.lines.size)

        // Store only each line's best partner, not a quadratic score matrix.
        for (i in baseBoxes.indices) {
            for (j in candidateBoxes.indices) {
                val score = overlap(baseBoxes[i], candidateBoxes[j]).iou
                if (score <= 0.0) continue
                if (score > baseScores[i] + GEOMETRY_EPSILON) {
                    baseBest[i] = j
                    baseScores[i] = score
                    baseAmbiguous[i] = false
                } else if (abs(score - baseScores[i]) <= GEOMETRY_EPSILON) {
                    baseAmbiguous[i] = true
                }
                if (score > candidateScores[j] + GEOMETRY_EPSILON) {
                    candidateBest[j] = i
                    candidateScores[j] = score
                    candidateAmbiguous[j] = false
                } else if (abs(score - candidateScores[j]) <= GEOMETRY_EPSILON) {
                    candidateAmbiguous[j] = true
                }
            }
        }

        var replacements = 0
        val selected = base.lines.toMutableList()
        for (i in base.lines.indices) {
            val j = baseBest[i]
            if (j < 0 || candidateBest[j] != i || baseAmbiguous[i] || candidateAmbiguous[j]) continue
            val original = base.lines[i]
            val alternative = candidate.lines[j]
            if (original.confidence > MAX_BASE_CONFIDENCE || alternative.text.isBlank() ||
                original.text == alternative.text) continue
            // Float scores may straddle an exact six-percentage-point boundary.
            if (alternative.confidence.toDouble() - original.confidence.toDouble() +
                CONFIDENCE_EPSILON < MIN_CONFIDENCE_GAIN) continue
            val geometry = overlap(baseBoxes[i], candidateBoxes[j])
            if (geometry.iou + GEOMETRY_EPSILON < MIN_IOU ||
                geometry.baseCoverage + GEOMETRY_EPSILON < MIN_COVERAGE ||
                geometry.candidateCoverage + GEOMETRY_EPSILON < MIN_COVERAGE) continue

            selected[i] = alternative.copy(
                left = mapCoordinate(alternative.left, candidate.width, base.width, false, i),
                top = mapCoordinate(alternative.top, candidate.height, base.height, false, i),
                right = mapCoordinate(alternative.right, candidate.width, base.width, true, i),
                bottom = mapCoordinate(alternative.bottom, candidate.height, base.height, true, i)
            )
            replacements++
        }
        return if (replacements == 0) Selection(base, 0)
        else Selection(Page(base.width, base.height, selected), replacements)
    }

    private fun hasConfidence(page: Page) = page.lines.any { it.confidence > 0f }

    private fun validate(page: Page, label: String) {
        require(page.width > 0 && page.height > 0) {
            "$label: invalid OCR page dimensions ${page.width}x${page.height}"
        }
        page.lines.forEachIndexed { index, line ->
            require(line.confidence.isFinite() && line.confidence >= 0f && line.confidence <= 1f) {
                "$label line ${index + 1}: invalid OCR confidence ${line.confidence}"
            }
            require(line.left < line.right && line.top < line.bottom) {
                "$label line ${index + 1}: invalid OCR bounds " +
                    "[${line.left},${line.top},${line.right},${line.bottom}]"
            }
        }
    }

    private data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val area: Double get() = (right - left) * (bottom - top)
    }

    private data class Overlap(val iou: Double, val baseCoverage: Double, val candidateCoverage: Double)

    private fun normalize(line: Line, page: Page) = Box(
        line.left.toDouble() / page.width, line.top.toDouble() / page.height,
        line.right.toDouble() / page.width, line.bottom.toDouble() / page.height
    )

    private fun overlap(base: Box, candidate: Box): Overlap {
        val area = max(0.0, min(base.right, candidate.right) - max(base.left, candidate.left)) *
            max(0.0, min(base.bottom, candidate.bottom) - max(base.top, candidate.top))
        if (area == 0.0) return Overlap(0.0, 0.0, 0.0)
        return Overlap(area / (base.area + candidate.area - area),
            area / base.area, area / candidate.area)
    }

    private fun mapCoordinate(value: Int, sourceSize: Int, targetSize: Int,
                              upper: Boolean, index: Int): Int {
        val scaled = value.toDouble() * targetSize / sourceSize
        // Round outwards so a smaller render's glyph bounds cannot be clipped.
        val rounded = if (upper) ceil(scaled) else floor(scaled)
        require(rounded >= Int.MIN_VALUE && rounded <= Int.MAX_VALUE) {
            "candidate line ${index + 1}: OCR coordinate exceeds the base page coordinate range"
        }
        return rounded.toInt()
    }
}
