package dev.munote.ocrtool

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkManager
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Storage I/O always runs off the UI thread and cleanup requires an idle worker + ledger lease. */
class CacheActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "MuOCR 缓存管理"
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(content) })
        refresh()
    }

    private fun refresh(): Unit = background("正在统计缓存…") {
        val roots = OcrQueueScheduler.workRoots(applicationContext)
        val rows = OcrCacheFiles.scan(roots)
        val tasks = OcrQueueScheduler.store(applicationContext).snapshot().entries.associateBy { it.id }
        runOnUiThread {
            if (isDestroyed || isFinishing) return@runOnUiThread
            content.removeAllViews()
            label("缓存管理", 24f)
            label("共 ${rows.size} 处 · ${rows.sumOf { it.pages }} 页记录 · " + size(rows.sumOf { it.bytes }))
            label("位置：\n" + roots.joinToString("\n") { it.absolutePath })
            label("以上是应用专用工作目录，Android 可能限制文件管理器访问。文件大小按逻辑大小合计，共享副本可能重复计数。")
            label("清理会删除所选缓存的 OCR 页和 PDF 工作副本，之后需重新识别。原 PDF、已导出的 PDF 和内置字库保留。先回主页暂停任务，等待显示「已暂停」后再清理。")
            button("刷新缓存列表") { refresh() }
            val completed = OcrCacheCleanup.select(rows, tasks.values.toList(), completedOnly = true)
            val eligible = OcrCacheCleanup.select(rows, tasks.values.toList(), completedOnly = false)
            button("清理已完成/取消的缓存（${completed.size} 处）") {
                confirmBatch(completed, rows.size - completed.size)
            }.isEnabled = completed.isNotEmpty()
            button("清理全部可清理缓存（${eligible.size} 处）") {
                confirmBatch(eligible, rows.size - eligible.size)
            }.isEnabled = eligible.isNotEmpty()
            button("返回任务列表") { finish() }
            for (row in rows.sortedBy { it.directory.name }) {
                val task = tasks[row.taskId]
                label(task?.name ?: if (row.taskId == null) "旧版单文件缓存" else "已移除任务的残留缓存", 18f)
                label("${row.pages} 页记录 · ${size(row.bytes)}" +
                    if (row.cleanupPending) " · 上次清理未完成" else "")
                label(row.directory.absolutePath)
                if (row.taskId == null) label("覆盖升级会保留此缓存；在主页通过原来的文件提供方，重新选择未改动的同一个原 PDF，可接续已识别页面。接续后这份旧缓存仍保留，可在确认输出后清理。")
                val allowed = OcrCacheCleanup.allowed(task)
                if (!allowed) label("此任务尚未暂停或输出待核对，请先返回主页处理。")
                button(if (row.cleanupPending) "重试清理此缓存" else "清理此缓存") {
                    AlertDialog.Builder(this).setTitle("清理这份缓存？")
                        .setMessage("${task?.name ?: row.directory.name}\n${row.pages} 页记录 · ${size(row.bytes)}\n\n将失去这份缓存的续做进度，再次处理需从头识别。原 PDF 和已导出的 PDF 不会被删除。")
                        .setNegativeButton("保留", null)
                        .setPositiveButton("确认清理") { _, _ -> clear(listOf(row)) }.show()
                }.isEnabled = allowed
            }
        }
    }

    private fun confirmBatch(rows: List<OcrCacheFiles.Usage>, skipped: Int) {
        AlertDialog.Builder(this).setTitle("清理这 ${rows.size} 处缓存？")
            .setMessage("共 ${rows.sumOf { it.pages }} 页记录 · ${size(rows.sumOf { it.bytes })}\n" +
                "未选中 $skipped 处缓存。排队、运行中及输出待核对任务不会被清理。\n\n" +
                "包含旧版缓存及暂停/失败任务时，将失去所选缓存的续做进度，再次处理需从头识别。\n" +
                "原 PDF、已导出的 PDF 和内置字库保留。")
            .setNegativeButton("保留", null)
            .setPositiveButton("确认清理 ${rows.size} 处") { _, _ -> clear(rows) }.show()
    }

    private fun clear(rows: List<OcrCacheFiles.Usage>): Unit = background("正在清理所选缓存…", refreshAfter = true) {
        val count = OcrCacheCleanup.clear(OcrQueueScheduler.workRoots(applicationContext),
            OcrQueueScheduler.store(applicationContext), rows, ensureIdle = {
                val pending = WorkManager.getInstance(applicationContext)
                    .getWorkInfosByTag(LargePdfOcrWorker::class.java.name).get(10, TimeUnit.SECONDS)
                    .any { !it.state.isFinished }
                if (pending) throw IOException("后台任务尚未停止，请先暂停任务，稍候刷新后重试")
            })
        runOnUiThread {
            if (!isDestroyed && !isFinishing) Toast.makeText(this, "已清理 $count 处缓存", Toast.LENGTH_LONG).show()
        }
    }

    private fun background(message: String, refreshAfter: Boolean = false, action: () -> Unit) {
        if (busy) return
        busy = true
        content.removeAllViews()
        label(message)
        Thread {
            var success = false
            try { action(); success = true }
            catch (error: Exception) {
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        content.removeAllViews()
                        label("操作未完成：${error.message ?: error.javaClass.simpleName}")
                        button("重新读取缓存") { refresh() }
                        button("返回任务列表") { finish() }
                    }
                }
            } finally {
                runOnUiThread {
                    busy = false
                    if (success && refreshAfter && !isDestroyed && !isFinishing) refresh()
                }
            }
        }.start()
    }

    private fun label(value: String, size: Float = 14f) {
        content.addView(TextView(this).apply {
            text = value
            textSize = size
            setPadding(0, 12, 0, 12)
            setTextIsSelectable(true)
        })
    }

    private fun button(value: String, action: () -> Unit): Button = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener { action() }
        content.addView(this)
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    }

}
