package dev.munote.ocrtool

import java.io.IOException
import java.util.Locale

/** One instance per open PDF batch; fonts must not outlive their document. */
internal class OcrFontRuns<F : Any>(private val resolve: (String) -> F) {
    data class Run<F>(val font: F, val text: String)

    private val fonts = HashMap<Int, F>()

    fun split(text: String): List<Run<F>> {
        val runs = ArrayList<Run<F>>()
        val buffer = StringBuilder()
        var current: F? = null
        var offset = 0
        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            offset += Character.charCount(cp)
            if (cp in 0xD800..0xDFFF) throw IOException("OCR 文字包含不完整的字符（${code(cp)}），缓存已保留")
            val font = fonts.getOrPut(cp) {
                val character = String(Character.toChars(cp))
                try { resolve(character) }
                catch (error: IOException) {
                    // A blank substitute would silently remove this character from copy/search.
                    throw IOException("字符「$character」（${code(cp)}）无法载入可嵌入字体，OCR 缓存已保留：${error.message}", error)
                }
            }
            if (current !== font && buffer.isNotEmpty()) {
                runs.add(Run(checkNotNull(current), buffer.toString()))
                buffer.setLength(0)
            }
            current = font
            buffer.appendCodePoint(cp)
        }
        if (buffer.isNotEmpty()) runs.add(Run(checkNotNull(current), buffer.toString()))
        return runs
    }

    private fun code(cp: Int) = "U+" + cp.toString(16).uppercase(Locale.ROOT).padStart(4, '0')
}
