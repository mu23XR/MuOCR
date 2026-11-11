package dev.munote.ocrtool

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSUpdateInfo
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.LinkedHashSet
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Two-pass large-PDF pipeline.
 *
 * Pass 1 only uses PdfRenderer + ML Kit. Each recognized page is journaled to disk.
 * Pass 2 closes both of those engines BEFORE opening PDFBox. A small batch
 * of pages is patched into a separate working PDF with incremental revisions,
 * and PDFBox is closed between batches to bound font and COS object memory.
 *
 * In particular, a 1GB PDF is never read into a single ByteArray or Bitmap, nor
 * do the PDF rendering and PDF editing engines remain open at the same time.
 */
class LargePdfOcrWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private var activeStage = "等待开始"
    private var activePage = 0
    private var activeTotal = 0
    private var lastForegroundPage = -1
    private var currentTaskId: String? = null
    private var currentTaskName: String? = null
    private var lastDirectivePoll = 0L
    private var outputCopied = false
    private var simplifyChinese = false
    private var discardLegacyCache = false
    private var textFormatter = OcrChineseText(false)

    private class TaskPaused : RuntimeException("Task paused")
    private class TaskCancelled : RuntimeException("Task cancelled")

    // WorkManager's concrete Result subclasses are library-internal. Keep
    // document/queue control flow independent, adapting only at doWork's edge.
    private sealed interface DocumentOutcome {
        data class Completed(val pages: Int) : DocumentOutcome
        data class Failed(val error: String) : DocumentOutcome
    }

    override suspend fun doWork() = withContext(Dispatchers.IO) {
        if (!OcrCacheAccess.beginWorker()) return@withContext Result.retry()
        try {
            if (inputData.getBoolean(KEY_QUEUE_MODE, false)) {
                return@withContext runQueue()
            }
            // Requests created by the pre-queue 0.1.x/1.0.x releases remain supported.
            val input = inputData.getString(KEY_INPUT_URI)?.let(Uri::parse)
                ?: return@withContext failure("缺少输入 PDF")
            val output = inputData.getString(KEY_OUTPUT_URI)?.let(Uri::parse)
                ?: return@withContext failure("缺少输出 PDF")
            runDocument(input, output, inputData.getInt(KEY_MAX_DIMENSION, 2200), null).toWorkResult()
        } finally {
            OcrCacheAccess.endWorker()
        }
    }

    /**
     * One durable WorkManager supervisor loops serially through task entries.
     * A per-task pause/cancel is a cooperative flag, not a WorkManager
     * cancellation, so no unrelated queued work can be cancelled.
     */
    private suspend fun runQueue(): Result {
        val ledger = OcrQueueScheduler.store(applicationContext)
        ledger.recoverAfterWorkerRestart()
        // A process can be killed after recording SUCCESS but before deleting
        // the private 1GB workcopy; reclaim those finished directories here.
        val cleanupStates = setOf(OcrQueueLedger.Status.WAITING, OcrQueueLedger.Status.PAUSED,
            OcrQueueLedger.Status.CANCELLING, OcrQueueLedger.Status.CANCELLED,
            OcrQueueLedger.Status.FAILED, OcrQueueLedger.Status.DONE)
        val recoveryEntries = ledger.snapshot().entries
        if (recoveryEntries.any { it.status != OcrQueueLedger.Status.DONE && it.outputUri.isNotBlank() }) {
            // Comparing a recovered 1GB SAF destination can take time. Enter the
            // foreground before reading it, without changing the durable task state.
            setForeground(makeForeground("核对已导出 PDF，恢复任务队列", 0))
        }
        recoveryEntries.forEach { snapshot ->
            ledger.withCleanup(snapshot.id, cleanupStates) { entry ->
                if (entry.status != OcrQueueLedger.Status.DONE && entry.outputUri.isNotBlank()) {
                    try {
                        val pages = completedOutputPages(entry.id, entry.outputUri)
                        if (pages != null) {
                            ledger.complete(entry.id, entry.outputUri, pages)
                            cleanCompletedCache(OcrQueueScheduler.taskDirectory(applicationContext, entry.id))
                            return@withCleanup
                        }
                    } catch (error: Exception) {
                        Log.e(TAG, "Cannot reconcile saved output for ${entry.name}; retaining files", error)
                        ledger.deferOutputCompletion(entry.id,
                            "输出文件和断点已保留，暂无法确认完成，请重试：" +
                                (error.message ?: error.javaClass.simpleName))
                        return@withCleanup
                    }
                }
                if (entry.status == OcrQueueLedger.Status.CANCELLING) {
                    // Recovery has ruled out a complete destination. Only now may
                    // cancellation expose this task to UI/cache cleanup.
                    OcrQueueScheduler.deleteIncompleteOutput(applicationContext, entry.outputUri)
                    ledger.clearOutput(entry.id)
                    ledger.finish(entry.id, OcrQueueLedger.Status.CANCELLED)
                    OcrQueueScheduler.cleanTaskCache(applicationContext, entry.id)
                } else if (entry.status == OcrQueueLedger.Status.DONE ||
                    entry.status == OcrQueueLedger.Status.CANCELLED) {
                    if (entry.status == OcrQueueLedger.Status.CANCELLED) {
                        OcrQueueScheduler.deleteIncompleteOutput(
                            applicationContext, entry.outputUri
                        )
                        ledger.clearOutput(entry.id)
                    }
                    if (entry.status == OcrQueueLedger.Status.DONE) {
                        cleanCompletedCache(OcrQueueScheduler.taskDirectory(applicationContext, entry.id))
                    } else {
                        OcrQueueScheduler.cleanTaskCache(applicationContext, entry.id)
                    }
                } else if (entry.status == OcrQueueLedger.Status.FAILED &&
                    entry.outputUri.isNotBlank()) {
                    // A process killed mid-export may leave an incomplete PDF at
                    // the user-selected destination. Retain ONLY this task's
                    // original copy, page OCR and export checkpoints for retry.
                    OcrQueueScheduler.deleteIncompleteOutput(applicationContext, entry.outputUri)
                    ledger.clearOutput(entry.id)
                }
            }
        }
        while (true) {
            coroutineContext.ensureActive()
            val entry = ledger.claimNext() ?: return Result.success()
            currentTaskId = entry.id
            currentTaskName = entry.name
            lastForegroundPage = -1
            lastDirectivePoll = 0L
            outputCopied = false
            simplifyChinese = entry.simplifyChinese
            discardLegacyCache = entry.discardLegacyCache
            textFormatter = OcrChineseText(simplifyChinese)

            var output = entry.outputUri
            var verifyingPreviousOutput = false
            try {
                val cache = OcrQueueScheduler.taskDirectory(applicationContext, entry.id)
                if (File(cache, OcrCacheFiles.CLEANUP_MARKER).exists()) {
                    if (entry.outputUri.isNotBlank()) {
                        verifyingPreviousOutput = true
                        throw IOException("缓存清理未完成且输出待核对，请在缓存管理检查")
                    }
                    OcrCacheFiles.clear(OcrQueueScheduler.workRoots(applicationContext), cache)
                }
                if (output.isNotBlank()) {
                    verifyingPreviousOutput = true
                    val pages = completedOutputPages(entry.id, output)
                    if (pages != null) {
                        outputCopied = true
                        ledger.complete(entry.id, output, pages)
                        cleanCompletedCache(OcrQueueScheduler.taskDirectory(applicationContext, entry.id))
                        publishCompletion(pages)
                        continue
                    }
                    verifyingPreviousOutput = false
                }
                // A process can be killed while exporting. Delete an orphaned
                // partial destination, but preserve the private PDF journal.
                OcrQueueScheduler.deleteIncompleteOutput(applicationContext, entry.outputUri)
                val destination = OcrQueueScheduler.createOutputFile(applicationContext, entry)
                output = destination.toString()
                ledger.setOutput(entry.id, output)

                val result = runDocument(
                    Uri.parse(entry.inputUri), destination, entry.quality, entry.id
                )
                when (result) {
                    is DocumentOutcome.Completed -> {
                        val pages = result.pages
                        ledger.complete(entry.id, output, pages)
                        cleanCompletedCache(OcrQueueScheduler.taskDirectory(applicationContext, entry.id))
                        publishCompletion(pages)
                    }
                    is DocumentOutcome.Failed -> {
                        val error = result.error
                        if (outputCopied) throw IOException(error)
                        ledger.finish(entry.id, OcrQueueLedger.Status.FAILED, error)
                        OcrQueueScheduler.deleteIncompleteOutput(applicationContext, output)
                        ledger.clearOutput(entry.id)
                    }
                }
            } catch (_: TaskPaused) {
                OcrQueueScheduler.deleteIncompleteOutput(applicationContext, output)
                ledger.clearOutput(entry.id)
                ledger.finish(entry.id, OcrQueueLedger.Status.PAUSED)
            } catch (_: TaskCancelled) {
                OcrQueueScheduler.deleteIncompleteOutput(applicationContext, output)
                ledger.clearOutput(entry.id)
                ledger.finish(entry.id, OcrQueueLedger.Status.CANCELLED)
                ledger.withCleanup(entry.id, setOf(OcrQueueLedger.Status.CANCELLED)) { cancelled ->
                    OcrQueueScheduler.cleanTaskCache(applicationContext, cancelled.id)
                }
            } catch (cancelled: CancellationException) {
                // Android stopped the whole worker. Keep all task checkpoints:
                // the next supervisor will re-claim the interrupted task.
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Queue item failed: ${entry.name}", error)
                if (outputCopied || verifyingPreviousOutput) {
                    // A closed complete output (or an output we cannot verify) is
                    // never disposable. Keep the URI and work PDF for reconciliation.
                    try {
                        ledger.deferOutputCompletion(entry.id,
                            "输出文件和断点已保留，请重试完成收尾：" +
                                (error.message ?: error.javaClass.simpleName))
                    } catch (commitError: Exception) {
                        error.addSuppressed(commitError)
                        Log.e(TAG, "Cannot persist pending output finalization; retaining files", error)
                        return failure("输出文件和断点已保留，但无法保存完成记录：" +
                            (error.message ?: error.javaClass.simpleName))
                    }
                    continue
                }
                OcrQueueScheduler.deleteIncompleteOutput(applicationContext, output)
                ledger.clearOutput(entry.id)
                ledger.finish(entry.id, OcrQueueLedger.Status.FAILED,
                    error.message ?: error.javaClass.simpleName)
            } finally {
                currentTaskId = null
                currentTaskName = null
                // Never keep a previous large document's temporary PDFBox/
                // font objects alive unnecessarily while starting the next.
                System.gc()
            }
        }
    }

    private fun completedOutputPages(taskId: String, output: String): Int? =
        PdfOutputRecovery.completedPages(OcrQueueScheduler.taskDirectory(applicationContext, taskId)) {
            applicationContext.contentResolver.openInputStream(Uri.parse(output))
                ?: throw IOException("无法读取已记录的输出 PDF")
        }

    private fun cleanCompletedCache(directory: File) {
        try {
            OcrCacheFiles.clear(OcrQueueScheduler.workRoots(applicationContext), directory)
        } catch (error: Exception) {
            // DONE is already durable. Startup can retry cleanup without risking output.
            Log.w(TAG, "PDF 已完成，保留未清理缓存：${directory.absolutePath}", error)
        }
    }

    private suspend fun publishCompletion(pages: Int) {
        activeStage = "已完成"
        activePage = pages
        activeTotal = pages
        try {
            // Notifications are published after the only durable completion commit;
            // they do not rewrite the ledger or apply a late pause/cancel directive.
            setProgress(workDataOf("stage" to activeStage, "page" to pages,
                "total" to pages, "percent" to 100))
            setForeground(makeForeground("已完成 · $pages/$pages", 100))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "PDF 已完成，但进度通知失败；输出已保留", error)
        }
    }

    private fun checkTaskInterruption(force: Boolean = false) {
        val id = currentTaskId ?: return
        val now = System.nanoTime()
        if (!force && now - lastDirectivePoll < 150_000_000L) return
        lastDirectivePoll = now
        val state = OcrQueueScheduler.store(applicationContext).stateOf(id)
        when (state) {
            OcrQueueLedger.Status.PAUSING, OcrQueueLedger.Status.PAUSED ->
                throw TaskPaused()
            OcrQueueLedger.Status.CANCELLING, OcrQueueLedger.Status.CANCELLED, null ->
                throw TaskCancelled()
        }
    }

    private suspend fun runDocument(
        input: Uri,
        output: Uri,
        selectedQuality: Int,
        taskId: String?
    ): DocumentOutcome {
        activeStage = "准备文件"
        activePage = 0
        activeTotal = 0
        lastForegroundPage = -1
        if (input == output) return documentFailure("不能覆盖原 PDF，请另存到新文件")

        val maxDimension = selectedQuality.coerceIn(1200, 3200)
        val metadata = sourceMetadata(input)
        val roots = OcrQueueScheduler.workRoots(applicationContext)
        val jobDir = if (taskId == null) OcrCacheFiles.directory(roots, jobId(input, metadata))
            else OcrQueueScheduler.taskDirectory(applicationContext, taskId)
        val pagesDir = File(jobDir, "pages")
        val scratch = File(jobDir, "scratch")
        var completed = false
        var verifyingPreviousOutput = false
        var outputOpened = false

        return try {
            if (File(jobDir, OcrCacheFiles.CLEANUP_MARKER).exists()) {
                OcrCacheFiles.clear(roots, jobDir)
            }
            if (taskId == null) {
                // Legacy WorkManager input already holds the destination URI.
                // Reconcile it before preparation/reporting can fail and clean it.
                verifyingPreviousOutput = true
                if (File(jobDir, "export.state").exists()) {
                    setForeground(makeForeground("核对已导出的 PDF", 0))
                }
                val recoveredPages = PdfOutputRecovery.completedPages(jobDir) {
                    applicationContext.contentResolver.openInputStream(output)
                        ?: throw IOException("无法读取已记录的输出 PDF")
                }
                verifyingPreviousOutput = false
                if (recoveredPages != null) {
                    outputCopied = true
                    publishCompletion(recoveredPages)
                    completed = true
                    return DocumentOutcome.Completed(recoveredPages)
                }
            }
            if (!jobDir.exists() && !jobDir.mkdirs()) {
                throw IOException("无法建立工作目录，检查存储空间")
            }
            if (!pagesDir.isDirectory && !pagesDir.mkdirs()) {
                throw IOException("无法建立 OCR 缓存目录")
            }
            if (!scratch.isDirectory && !scratch.mkdirs()) {
                throw IOException("无法建立 PDF 临时目录")
            }

            if (taskId != null && !discardLegacyCache) {
                importLegacyCacheIfPresent(roots, input, metadata, jobDir)
            }
            report("准备文件", 0, 0, 0, true)
            val original = File(jobDir, "original.pdf")
            ensureSourceCopy(input, original, metadata.size)

            // Only a file descriptor and the recognizer are alive during the OCR pass.
            val pageCount = recognizePages(original, pagesDir, maxDimension)
            coroutineContext.ensureActive()

            // Release ML Kit, PdfRenderer and all of its bitmap resources before PDFBox.
            // An explicit GC here (once, not on each page) helps the constrained
            // Android Java heap before reading the PDF object structure.
            System.gc()

            report("载入原始 PDF 以写入文字层", 0, pageCount, 92, true)
            writeSearchablePdf(original, pagesDir, scratch, jobDir, pageCount) {
                val stream = applicationContext.contentResolver.openOutputStream(output, "wt")
                    ?: throw IOException("无法打开输出文件")
                outputOpened = true
                stream
            }

            // writeSearchablePdf returns only after target.flush AND close succeed.
            // Queue ownership commits DONE once, before releasing this work directory.
            outputCopied = true
            if (taskId == null) {
                publishCompletion(pageCount)
                completed = true
            }
            DocumentOutcome.Completed(pageCount)
        } catch (signal: TaskPaused) {
            throw signal
        } catch (signal: TaskCancelled) {
            throw signal
        } catch (cancelled: CancellationException) {
            // The page journal is deliberately kept so a new run may resume.
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "MuOCR heap exhausted at $activeStage page $activePage/$activeTotal", oom)
            documentFailure("内存不足（阶段：$activeStage，第 $activePage/$activeTotal 页）。本次已完成的 OCR 页已缓存，可重新运行继续。")
        } catch (error: Exception) {
            Log.e(TAG, "MuOCR failed at $activeStage page $activePage/$activeTotal", error)
            documentFailure("阶段：$activeStage，第 $activePage/$activeTotal 页；" +
                (error.message ?: error.javaClass.simpleName))
        } finally {
            if (!outputCopied && !verifyingPreviousOutput && (taskId != null || outputOpened)) {
                // Incomplete PDF output is not a usable document. Keep OCR cache,
                // but remove the partial output when the provider supports it.
                runCatching {
                    DocumentsContract.deleteDocument(applicationContext.contentResolver, output)
                }
            }
            try {
                if (!scratch.deleteRecursively()) Log.w(TAG, "PDF 临时目录未完全清理：${scratch.absolutePath}")
            } catch (error: Exception) {
                Log.w(TAG, "保留未清理的 PDF 临时目录：${scratch.absolutePath}", error)
            }
            if (completed) {
                // Success means neither the 1GB source copy nor OCR journals remain
                // in app storage.
                cleanCompletedCache(jobDir)
            }
        }
    }

    private suspend fun importLegacyCacheIfPresent(
        roots: List<File>,
        input: Uri,
        metadata: SourceMetadata,
        job: File
    ) {
        val legacy = OcrCacheFiles.directory(roots, jobId(input, metadata))
        if (!legacy.isDirectory || legacy == job) return

        val imported = OcrCacheFiles.importPages(legacy, job) { directory, index ->
            OcrPageJournal.read(directory, index) != null
        }
        if (imported > 0) {
            report("已接续旧版 $imported 页 OCR 缓存", 0, 0, 0, true)
            Log.i(TAG, "从 ${legacy.absolutePath} 接续 $imported 页 OCR 缓存；原缓存保留")
        }

        // If supported by the filesystem, a hard link reuses the 1GB input
        // bytes without consuming another 1GB. Fallback is the normal SAF copy.
        val original = File(legacy, "original.pdf")
        val linked = File(job, "original.pdf")
        if (original.isFile && !linked.exists()) {
            try { java.nio.file.Files.createLink(linked.toPath(), original.toPath()) }
            catch (error: Exception) {
                Log.i(TAG, "文件系统无法链接旧工作副本，将从已授权的原 PDF 复制：${error.message}")
            }
        }
    }

    private fun sourceMetadata(uri: Uri): SourceMetadata {
        var size = -1L
        var modified = 0L
        runCatching {
            applicationContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val modIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                    if (modIndex >= 0 && !cursor.isNull(modIndex)) {
                        modified = cursor.getLong(modIndex)
                    }
                }
            }
        }
        return SourceMetadata(size, modified)
    }

    private fun jobId(uri: Uri, metadata: SourceMetadata): String {
        return OcrCacheFiles.legacyId(uri.toString(), metadata.size, metadata.modified)
    }

    private suspend fun ensureSourceCopy(uri: Uri, sourceFile: File, expectedSize: Long) {
        if (sourceFile.isFile && sourceFile.length() > 0 &&
            (expectedSize <= 0L || sourceFile.length() == expectedSize)
        ) {
            report("复用上次的 PDF 工作副本", 0, 0, 5, true)
            return
        }
        val temp = File(sourceFile.parentFile, "original.pdf.part")
        temp.delete()

        if (expectedSize > 0L) {
            // Reserve additional room for PDFBox scratch and output. If the PDF
            // is already on a different volume, this check is conservative.
            val reserve = max(256L * 1024 * 1024, expectedSize / 5)
            if (sourceFile.parentFile!!.usableSpace < expectedSize + reserve) {
                throw IOException("可用存储空间不足：原 PDF 需要约 " +
                    (expectedSize / 1024 / 1024) + "MB 的工作副本，建议释放至少 2GB。")
            }
        }

        try {
            val stream = applicationContext.contentResolver.openInputStream(uri)
                ?: throw IOException("无法读取 PDF 数据")
            stream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var copied = 0L
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        checkTaskInterruption()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        copied += count
                        if (copied - lastReport >= 32L * 1024 * 1024) {
                            lastReport = copied
                            val pct = if (expectedSize > 0) {
                                ((copied * 5L) / expectedSize).toInt().coerceIn(0, 5)
                            } else 0
                            report("复制 PDF 工作副本（" +
                                (copied / 1024 / 1024) + "MB）", 0, 0, pct)
                        }
                    }
                    output.fd.sync()
                }
            }
            if (expectedSize > 0 && temp.length() != expectedSize) {
                throw IOException("源文件拷贝不完整，预期 $expectedSize 字节，实际 " + temp.length())
            }
            if (temp.length() <= 0) throw IOException("原 PDF 文件为空")
            sourceFile.delete()
            if (!temp.renameTo(sourceFile)) throw IOException("无法提交 PDF 工作副本")
            report("PDF 工作副本就绪", 0, 0, 5, true)
        } finally {
            temp.delete()
        }
    }

    private suspend fun recognizePages(
        source: File,
        pagesDir: File,
        maxDimension: Int
    ): Int {
        val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                val total = renderer.pageCount
                if (total <= 0) throw IOException("PDF 没有有效页面")

                // Only start ML Kit if any page is missing. With 583 cached
                // OCR pages, resuming export must not load the OCR model.
                var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null
                try {
                    for (index in 0 until total) {
                        coroutineContext.ensureActive()
                        checkTaskInterruption()
                        if (isStopped) throw CancellationException("OCR 已取消")

                        val previous = OcrPageJournal.read(pagesDir, index)
                        if (previous != null) {
                            report("复用已识别的页面", index + 1, total,
                                5 + (((index + 1) * 87L) / total).toInt())
                            continue
                        }

                        report("正在识别", index + 1, total,
                            5 + ((index * 87L) / total).toInt())

                        renderer.openPage(index).use { page ->
                            val engine = recognizer ?: TextRecognition.getClient(
                                ChineseTextRecognizerOptions.Builder().build()
                            ).also { recognizer = it }
                            val primary = recognizePage(page, engine, maxDimension)
                            var selected = primary
                            if (maxDimension == 2200 && OcrResultSelector.shouldRetry(primary)) {
                                // Experiments support augmenting balanced mode. Reversing
                                // the scales regressed some high-resolution results.
                                val alternateDimension = 2800
                                val alternateSize = targetBitmapSize(
                                    page.width, page.height, alternateDimension, MAX_OCR_PIXELS
                                )
                                if (alternateSize != (primary.width to primary.height)) {
                                    report("复核易错文字", index + 1, total,
                                        5 + ((index * 87L) / total).toInt(), true)
                                    // The first bitmap has already been recycled. Keep the
                                    // existing per-page pixel bound even when re-recognizing.
                                    val alternate = recognizePage(page, engine, alternateDimension)
                                    val selection = OcrResultSelector.improve(primary, alternate)
                                    selected = selection.page
                                    Log.i(TAG, "OCR 复核第 ${index + 1} 页：" +
                                        "${primary.width}x${primary.height} / " +
                                        "${alternate.width}x${alternate.height}，" +
                                        "采用 ${selection.replacements} 行复核结果")
                                }
                            } else if (primary.lines.isNotEmpty() &&
                                primary.lines.all { it.confidence == 0f }) {
                                Log.w(TAG, "第 ${index + 1} 页 OCR 未提供有效置信度，保留原始识别结果")
                            }
                            OcrPageJournal.write(pagesDir, index, OcrPageJournal.Page(
                                selected.width, selected.height, selected.lines.map { line ->
                                    OcrPageJournal.Line(line.text, android.graphics.Rect(
                                        line.left, line.top, line.right, line.bottom
                                    ))
                                }
                            ))
                        }
                        report("已识别", index + 1, total,
                            5 + (((index + 1) * 87L) / total).toInt())
                    }
                } finally {
                    recognizer?.close()
                }
                return total
            }
        }
    }

    private fun recognizePage(
        page: PdfRenderer.Page,
        engine: com.google.mlkit.vision.text.TextRecognizer,
        dimension: Int
    ): OcrResultSelector.Page {
        val (width, height) = targetBitmapSize(
            page.width, page.height, dimension, MAX_OCR_PIXELS
        )
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val result = Tasks.await(engine.process(InputImage.fromBitmap(bitmap, 0)))
            val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val rect = line.boundingBox ?: return@mapNotNull null
                val text = sanitize(line.text)
                if (text.isBlank() || rect.isEmpty) null else OcrResultSelector.Line(
                    text, rect.left, rect.top, rect.right, rect.bottom, line.confidence
                )
            }
            return OcrResultSelector.Page(width, height, lines)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Each batch opens PDFBox, appends a limited number of page text streams,
     * saves a small incremental revision and closes PDFBox BEFORE the next batch.
     *
     * In PDFBox-Android the incremental writer internally keeps the revision
     * in a ByteArrayOutputStream; hence we keep every batch small (16 pages).
     * The source and all completed edits are stored on disk rather than heap.
     *
     * The caller's PDF remains untouched; "writing.pdf" is a private workcopy.
     * Checkpoints survive app cancellation or process death.
     */
    private suspend fun writeSearchablePdf(
        original: File,
        pagesDir: File,
        scratch: File,
        jobDir: File,
        expectedPages: Int,
        openOutput: () -> java.io.OutputStream
    ) {
        val working = File(jobDir, "writing.pdf")
        val journal = PdfExportJournal(jobDir)
        OcrTextStyle.prepareExport(jobDir, simplifyChinese)
        var checkpoint = journal.recover(working)
            ?.takeIf {
                it.originalLength == original.length() &&
                    it.pageCount == expectedPages
            }

        if (checkpoint == null) {
            // Crash/inconsistent state: start PDF writing again, not OCR.
            // Existing OcrPageJournal results will still be reused.
            working.delete()
            val temp = File(jobDir, "writing.pdf.part")
            temp.delete()
            try {
                val required = original.length() + max(300L * 1024 * 1024, original.length() / 4)
                if (jobDir.usableSpace < required) {
                    throw IOException("存储空间不足：需要至少 " +
                        (required / 1024 / 1024) + "MB 写入 PDF 工作副本")
                }
                report("准备可恢复的 PDF 导出副本", 0, expectedPages, 92, true)
                FileInputStream(original).use { input ->
                    FileOutputStream(temp).use { output ->
                        copyStreamWithCancellation(input, output)
                        output.fd.sync()
                    }
                }
                if (temp.length() != original.length()) {
                    throw IOException("PDF 工作副本没有完整写入")
                }
                if (!temp.renameTo(working)) {
                    throw IOException("无法提交 PDF 工作副本")
                }
                checkpoint = journal.initialize(original.length(), expectedPages)
            } finally {
                temp.delete()
            }
        } else {
            report("复用已提交的 PDF 文字层（到第 ${checkpoint.nextPage} 页）",
                checkpoint.nextPage, expectedPages,
                92 + ((checkpoint.nextPage * 6L) / expectedPages).toInt(), true)
        }

        OcrTextStyle.commitMode(jobDir, simplifyChinese)
        var state = checkpoint ?: throw IOException("缺少 PDF 导出进度")
        var batchSize = BATCH_PAGES
        while (state.nextPage < expectedPages) {
            coroutineContext.ensureActive()
            checkTaskInterruption()
            if (isStopped) throw CancellationException("PDF 导出已取消")
            val start = state.nextPage
            val endExclusive = min(start + batchSize, expectedPages)
            val patch = File(scratch, "batch-${start + 1}-$endExclusive.patch")
            patch.delete()

            report("写入文字层（本批 ${start + 1}-$endExclusive 页）",
                start + 1, expectedPages,
                92 + ((start * 6L) / expectedPages).toInt(), true)
            try {
                val changed = writePageBatch(
                    working, pagesDir, scratch, patch,
                    expectedPages, start, endExclusive
                )
                coroutineContext.ensureActive()

                state = if (changed) {
                    journal.append(working, patch, state, endExclusive)
                } else {
                    journal.advanceWithoutChanges(state, endExclusive)
                }
                report("已提交文字层", endExclusive, expectedPages,
                    92 + ((endExclusive * 6L) / expectedPages).toInt(), true)
                patch.delete()
            } catch (oom: OutOfMemoryError) {
                // A heap failure may also happen DURING patch append. Reconcile
                // the on-disk transaction before any retry.
                patch.delete()
                state = journal.recover(working) ?: throw oom
                if (state.nextPage > start) {
                    // Commit succeeded; only the UI/reporting failed.
                    continue
                }
                if (batchSize <= 1) throw oom
                batchSize = max(1, batchSize / 2)
                Log.w(TAG, "PDFBox heap pressure: retrying at $batchSize pages per batch", oom)
                report("减小每批页数至 $batchSize 后重试",
                    start + 1, expectedPages,
                    92 + ((start * 6L) / expectedPages).toInt(), true)
                System.gc()
            } finally {
                patch.delete()
            }
        }

        // A completed work PDF can be re-exported without repeating OCR or
        // text layer generation if a user-selected document provider fails.
        report("将完整可搜索 PDF 写入目标文件", expectedPages, expectedPages, 99, true)
        openOutput().use { target ->
            FileInputStream(working).use { source ->
                copyStreamWithCancellation(source, target)
            }
            target.flush()
        }
    }

    /** PDFBox is fully released after each small batch. */
    private suspend fun writePageBatch(
        working: File,
        pagesDir: File,
        scratch: File,
        patch: File,
        expectedPages: Int,
        start: Int,
        endExclusive: Int
    ): Boolean {
        var changed = false
        val touched = LinkedHashSet<COSDictionary>()
        PDDocument.load(
            working,
            MemoryUsageSetting.setupTempFileOnly().setTempDir(scratch)
        ).use { document ->
            if (document.isEncrypted) throw IOException("此版本暂不支持加密 PDF")
            if (document.numberOfPages != expectedPages) {
                throw IOException("PDF 页面数不一致：当前 " + document.numberOfPages +
                    "，期望 " + expectedPages)
            }

            PdfSystemFontResolver(document, TAG, applicationContext).use { fonts ->
                val fontRuns = OcrFontRuns { text: String -> fonts.resolve(text, false) }
                for (index in start until endExclusive) {
                    coroutineContext.ensureActive()
                    checkTaskInterruption()
                    if (isStopped) throw CancellationException("PDF 导出已取消")
                    val page = OcrPageJournal.read(pagesDir, index)
                        ?: throw IOException("第 " + (index + 1) + " 页 OCR 缓存丢失")
                    if (page.lines.isEmpty()) continue

                    val pdfPage = document.getPage(index)
                    try { appendInvisibleLayer(document, pdfPage, page, fontRuns) }
                    catch (error: IOException) {
                        throw IOException("第 ${index + 1} 页写入文字层失败：${error.message}", error)
                    }
                    markPageForIncrementalSave(document, pdfPage, touched)
                    changed = true
                    report("写入文字层", index + 1, expectedPages,
                        92 + (((index + 1) * 6L) / expectedPages).toInt())
                }
                if (changed) {
                    // PDFBox-Android 2.0.27.0 does not subset fonts when
                    // saveIncremental() is called. Without this the invisible
                    // Unicode layer may be unreadable/search may fail.
                    fonts.subsetFontsForIncrementalSave()
                    val patchWriter = IncrementalPdfPatchOutputStream(
                        patch, working.length()
                    )
                    patchWriter.use { writer ->
                        document.saveIncremental(writer, touched)
                    }
                    patchWriter.verify()
                }
            }
        }
        return changed
    }

    /**
     * PDFBox incremental saves only dictionaries reachable along a changed
     * page-tree path (or dictionaries explicitly forced to be written).
     * Mark the page, its resources /Font map, each /Parent, and the catalog.
     */
    private fun markPageForIncrementalSave(
        document: PDDocument,
        page: PDPage,
        touched: MutableSet<COSDictionary>
    ) {
        fun mark(dict: COSDictionary?) {
            if (dict != null) {
                dict.setNeedToBeUpdated(true)
                touched.add(dict)
            }
        }

        mark(document.documentCatalog.cosObject)
        var node: COSDictionary? = page.cosObject
        val visited = HashSet<COSDictionary>()
        while (node != null && visited.add(node)) {
            mark(node)
            node = node.getCOSDictionary(COSName.PARENT)
        }

        val resources = page.resources?.cosObject
        mark(resources)
        mark(resources?.getCOSDictionary(COSName.FONT))

        // /Contents is sometimes an existing indirect array, not a direct
        // child of the page dictionary. Mark it too so PDFBox writes its
        // new reference to the invisible text content stream.
        val contents = page.cosObject.getDictionaryObject(COSName.CONTENTS)
        if (contents is COSUpdateInfo) {
            contents.setNeedToBeUpdated(true)
        }
    }

    private suspend fun copyStreamWithCancellation(
        input: java.io.InputStream,
        output: java.io.OutputStream
    ) {
        val buffer = ByteArray(128 * 1024)
        while (true) {
            coroutineContext.ensureActive()
            checkTaskInterruption()
            if (isStopped) throw CancellationException("文件复制已取消")
            checkTaskInterruption()
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) output.write(buffer, 0, count)
        }
    }

    private fun appendInvisibleLayer(
        document: PDDocument,
        page: PDPage,
        ocrPage: OcrPageJournal.Page,
        fonts: OcrFontRuns<PDFont>
    ) {
        val crop = page.cropBox ?: page.mediaBox ?: return
        val rotation = normalizeRotation(page.rotation)
        val (displayW, displayH) = displaySize(rotation, crop.width, crop.height)
        val m = displayToUser(
            rotation, crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height
        )
        val sx = displayW / ocrPage.width.toFloat()
        val sy = displayH / ocrPage.height.toFloat()

        PDPageContentStream(
            document, page, PDPageContentStream.AppendMode.APPEND, true, true
        ).use { out ->
            out.saveGraphicsState()
            try {
                out.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
                for (line in ocrPage.lines) {
                    val text = textFormatter.format(line.text)
                    if (text.isBlank()) continue
                    val bounds = line.rect
                    val boxW = max(0.5f, bounds.width() * sx)
                    val boxH = max(1f, bounds.height() * sy)
                    val x = bounds.left * sx
                    val y = displayH - bounds.bottom * sy + boxH * 0.12f
                    val runs = fonts.split(text)
                    val fontSize = (boxH * 0.88f).coerceIn(1f, 96f)
                    val naturalWidth = runs.sumOf { (it.font.getStringWidth(it.text) / 1000f).toDouble() }.toFloat() * fontSize
                    if (naturalWidth <= 0f) continue
                    val horizontalScale = (boxW / naturalWidth * 100f).coerceIn(5f, 1000f)

                    var beganText = false
                    try {
                        out.beginText()
                        beganText = true
                        out.setRenderingMode(RenderingMode.NEITHER)
                        out.setHorizontalScaling(horizontalScale)
                        out.newLineAtOffset(x, y)
                        // One text position and common scale preserve the original line box.
                        for (run in runs) {
                            out.setFont(run.font, fontSize)
                            out.showText(run.text)
                        }
                        out.endText()
                        beganText = false
                    } finally {
                        if (beganText) runCatching { out.endText() }
                    }
                }
            } finally {
                out.restoreGraphicsState()
            }
        }
    }

    private fun targetBitmapSize(
        width: Int, height: Int, maxDimension: Int, maxPixels: Long
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val byDimension = maxDimension.toDouble() / max(width, height).toDouble()
        val byPixels = sqrt(maxPixels.toDouble() / (width.toLong() * height.toLong()).toDouble())
        val scale = min(byDimension, byPixels).coerceIn(0.001, 4.0)
        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }

    private fun sanitize(text: String): String =
        text.replace('\n', ' ').replace('\r', ' ').replace(WHITESPACE_REGEX, " ").trim()

    private fun normalizeRotation(rotation: Int): Int = ((rotation % 360) + 360) % 360

    private fun displaySize(rotation: Int, width: Float, height: Float): Pair<Float, Float> =
        if (normalizeRotation(rotation) % 180 == 0) width to height else height to width

    private fun displayToUser(
        rotation: Int, llx: Float, lly: Float, width: Float, height: Float
    ): FloatArray = when (normalizeRotation(rotation)) {
        90 -> floatArrayOf(0f, 1f, -1f, 0f, llx + width, lly)
        180 -> floatArrayOf(-1f, 0f, 0f, -1f, llx + width, lly + height)
        270 -> floatArrayOf(0f, -1f, 1f, 0f, llx, lly + height)
        else -> floatArrayOf(1f, 0f, 0f, 1f, llx, lly)
    }

    private suspend fun report(
        stage: String,
        page: Int,
        total: Int,
        percent: Int,
        forceNotification: Boolean = false
    ) {
        val changedStage = activeStage != stage
        activeStage = stage
        activePage = page
        activeTotal = total
        currentTaskId?.let { task ->
            OcrQueueScheduler.store(applicationContext).progress(
                task, stage, page, total, percent
            )
        }
        checkTaskInterruption(force = true)
        setProgress(
            workDataOf(
                "stage" to stage,
                "page" to page,
                "total" to total,
                "percent" to percent
            )
        )
        if (forceNotification || changedStage || lastForegroundPage < 0 ||
            page - lastForegroundPage >= 5) {
            lastForegroundPage = page
            setForeground(makeForeground(
                if (total > 0) "$stage · $page/$total" else stage,
                percent
            ))
        }
    }

    private fun makeForeground(text: String, percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MuOCR processing",
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(currentTaskName?.let { "MuOCR · " + it.take(36) } ?: "MuOCR")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun documentFailure(message: String) = DocumentOutcome.Failed(message.take(600))

    private fun DocumentOutcome.toWorkResult(): Result = when (this) {
        is DocumentOutcome.Completed -> Result.success(workDataOf("pages" to pages))
        is DocumentOutcome.Failed -> failure(error)
    }

    private fun failure(message: String): Result =
        Result.failure(workDataOf(
            "error" to message.take(600),
            "stage" to activeStage,
            "page" to activePage,
            "total" to activeTotal
        ))

    private data class SourceMetadata(val size: Long, val modified: Long)

    companion object {
        const val KEY_QUEUE_MODE = "queue_mode"
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_MAX_DIMENSION = "max_dimension"
        private const val TAG = "MuOCR"
        private const val CHANNEL_ID = "muocr"
        private const val NOTIFICATION_ID = 2301
        private const val MAX_OCR_PIXELS = 4_500_000L
        private const val BATCH_PAGES = 16
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
