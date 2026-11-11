package dev.munote.ocrtool

import android.icu.text.Transliterator
import java.io.IOException

/** One instance per document; raw page journals are deliberately never rewritten. */
internal class OcrChineseText(private val simplified: Boolean) {
    private val converter: Transliterator? by lazy {
        if (!simplified) null else {
            val ids = Transliterator.getAvailableIDs().toList()
            if ("Traditional-Simplified" !in ids) {
                throw IOException("本设备不支持离线繁转简，请在任务卡片选择「改为原文」后重试；已保留 OCR 缓存")
            }
            try { Transliterator.getInstance("Traditional-Simplified") }
            catch (error: IllegalArgumentException) {
                throw IOException("无法初始化离线繁转简，请在任务卡片选择「改为原文」后重试；已保留 OCR 缓存", error)
            }
        }
    }

    fun format(raw: String): String {
        val text = OcrTextStyle.clean(raw)
        return converter?.transliterate(text) ?: text
    }
}
