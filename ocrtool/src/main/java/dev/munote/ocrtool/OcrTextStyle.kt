package dev.munote.ocrtool

import java.io.File
import java.io.IOException

/** A separate export marker keeps existing OCR JSON and PDF checkpoint formats compatible. */
internal object OcrTextStyle {
    private const val RAW = "raw-v2"
    private const val SIMPLE = "simplified-v2"
    private val supported = setOf("raw-v1", "simplified-v1", RAW, SIMPLE)
    private const val MARKER = "text-style.state"

    fun clean(text: String): String {
        val result = StringBuilder(text.length)
        var space = false
        var offset = 0
        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            offset += Character.charCount(cp)
            when {
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85 -> space = result.isNotEmpty()
                Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt() -> Unit
                else -> {
                    if (space) result.append(' ')
                    result.appendCodePoint(cp)
                    space = false
                }
            }
        }
        return result.toString()
    }

    fun prepareExport(dir: File, simplified: Boolean) {
        val file = File(dir, MARKER)
        val previous = if (file.isFile) file.readText(Charsets.US_ASCII).trim() else "raw-v1"
        if (previous !in supported) throw IOException("文字层模式记录损坏：${file.absolutePath}")
        if (previous != mode(simplified)) {
            // Invalidate before publishing a new mode. A crash can repeat export, never mix modes.
            for (name in listOf("export.state", "export.pending")) {
                val state = File(dir, name)
                if (state.exists() && !state.delete()) throw IOException("无法重置旧文字层进度：${state.absolutePath}")
            }
        }
    }

    fun commitMode(dir: File, simplified: Boolean) {
        DurableFile().write(File(dir, MARKER), (mode(simplified) + "\n").toByteArray(Charsets.US_ASCII))
    }

    private fun mode(simplified: Boolean) = if (simplified) SIMPLE else RAW
}
