package dev.munote.ocrtool

import java.io.File
import java.io.IOException

/** Shared by single/batch actions; only the confirmed directory snapshot is deleted. */
internal object OcrCacheCleanup {
    private val cleanable = setOf(OcrQueueLedger.Status.PAUSED, OcrQueueLedger.Status.FAILED,
        OcrQueueLedger.Status.CANCELLED, OcrQueueLedger.Status.DONE)

    fun allowed(task: OcrQueueLedger.Entry?): Boolean = task == null ||
        task.status in cleanable && (task.outputUri.isBlank() || task.status == OcrQueueLedger.Status.DONE)

    fun select(rows: List<OcrCacheFiles.Usage>, tasks: List<OcrQueueLedger.Entry>,
               completedOnly: Boolean): List<OcrCacheFiles.Usage> {
        val byId = tasks.associateBy { it.id }
        return rows.filter { row ->
            val task = byId[row.taskId]
            allowed(task) && (!completedOnly || task?.status in
                setOf(OcrQueueLedger.Status.DONE, OcrQueueLedger.Status.CANCELLED))
        }
    }

    fun clear(roots: List<File>, store: OcrQueueLedger, rows: List<OcrCacheFiles.Usage>,
              ensureIdle: () -> Unit,
              delete: (File) -> Boolean = File::delete): Int = OcrCacheAccess.maintain {
        ensureIdle() // Includes pre-queue WorkManager requests after an upgrade.
        val taskIds = store.snapshot().entries.map { it.id }.toSet()
        val selected = rows.distinctBy { it.directory.absolutePath }
        val ids = selected.mapNotNull { it.taskId }.filter { it in taskIds }.toSet()
        store.withCleanup(ids, cleanable) { entries ->
            if (entries.any { !allowed(it) }) {
                throw IOException("输出文件尚待核对，请先在任务列表重试完成收尾，缓存已保留")
            }
            var cleared = 0
            for (row in selected) {
                try {
                    if (row.taskId in ids) store.prepareCacheClear(checkNotNull(row.taskId))
                    OcrCacheFiles.clear(roots, row.directory, delete)
                    if (row.taskId in ids) store.noteCacheCleared(checkNotNull(row.taskId))
                    cleared++
                } catch (error: Exception) {
                    throw IOException("已清理 $cleared/${selected.size} 处；清理未完成：${row.directory.absolutePath}\n${error.message}", error)
                }
            }
            cleared
        } ?: throw IOException("任务状态已变化或正在清理，请刷新后重试；尚未开始删除")
    }
}
