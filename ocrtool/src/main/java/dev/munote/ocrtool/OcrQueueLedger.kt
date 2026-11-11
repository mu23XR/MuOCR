package dev.munote.ocrtool

import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Persistent queue with *one* active task. It has no Android dependencies,
 * so ordering, interruption and recovery can be tested on the JVM.
 *
 * Every mutation atomically rewrites the small queue manifest. OCR page data
 * and the PDF export journal belong to each task's own work directory.
 */
internal class OcrQueueLedger(
    private val manifest: File,
    private val durableFile: DurableFile = DurableFile()
) {
    companion object {
        private const val RECOVERY_WINDOW_MS = 60L * 60L * 1000L
        private const val MAX_UNEXPECTED_RESTARTS = 3
    }

    internal object Status {
        const val WAITING = "waiting"
        const val RUNNING = "running"
        const val PAUSING = "pausing"
        const val PAUSED = "paused"
        const val CANCELLING = "cancelling"
        const val CANCELLED = "cancelled"
        const val FAILED = "failed"
        const val DONE = "done"
    }

    data class Entry(
        var id: String = "",
        var name: String = "",
        var inputUri: String = "",
        var treeUri: String = "",
        var outputUri: String = "",
        var status: String = Status.WAITING,
        var quality: Int = 2200,
        var progress: Int = 0,
        var page: Int = 0,
        var total: Int = 0,
        var stage: String = "等待处理",
        var error: String = "",
        // True only when "Pause all" suspended this entry. A manually paused
        // task must remain paused when the global queue is resumed.
        var pausedByGlobal: Boolean = false,
        // Unexpected Android/process deaths are tracked per PDF. If a bad PDF
        // repeatedly kills the worker, it must not starve the whole queue.
        // Zero defaults make older v1.1.x JSON queue files compatible.
        var unexpectedRestarts: Int = 0,
        var lastUnexpectedRestartAt: Long = 0L,
        var createdAt: Long = 0L,
        // Missing in old queue JSON: preserve its original text policy on upgrade.
        var simplifyChinese: Boolean = false,
        var discardLegacyCache: Boolean = false
    )

    data class Snapshot(
        var pausedAll: Boolean = false,
        var entries: MutableList<Entry> = mutableListOf()
    )

    private val gson = Gson()
    private val backup = File(manifest.parentFile, manifest.name + ".bak")
    private val cleanupLeases = mutableSetOf<String>()

    // Every read deserializes its own object graph, so callers share no mutable state.
    @Synchronized
    fun snapshot(): Snapshot = read().state

    @Synchronized
    fun add(inputs: List<Pair<String, String>>, treeUri: String, quality: Int,
            simplifyChinese: Boolean = false): List<Entry> {
        require(treeUri.isNotBlank())
        if (inputs.isEmpty()) return emptyList()
        return edit {
            val newlyAdded = inputs.map { (uri, filename) ->
                Entry(
                    id = UUID.randomUUID().toString(),
                    name = filename.ifBlank { "document.pdf" },
                    inputUri = uri,
                    treeUri = treeUri,
                    quality = quality.coerceIn(1200, 3200),
                    simplifyChinese = simplifyChinese,
                    createdAt = System.currentTimeMillis()
                ).also { entry -> it.entries.add(entry) }
            }
            newlyAdded.map(Entry::copy)
        }
    }

    /**
     * WorkManager guarantees only one queue supervisor executes at once.
     * On a restarted supervisor, a previously in-progress entry can resume
     * from its journal instead of being stuck in RUNNING forever.
     */
    /**
     * If Android repeatedly kills the worker during a single PDF, stop
     * automatically retrying THAT PDF and continue with other waiting files.
     * OCR/PDF checkpoints are kept; the user can explicitly retry.
     *
     * A 60-minute rolling window prevents three unrelated system restarts
     * over several days from incorrectly classifying a large book as broken.
     */
    @Synchronized
    fun recoverAfterWorkerRestart(nowMillis: Long = System.currentTimeMillis()) {
        edit { state ->
            state.entries.forEach { entry ->
                when (entry.status) {
                    Status.RUNNING -> {
                        val recent = entry.lastUnexpectedRestartAt > 0L &&
                            nowMillis >= entry.lastUnexpectedRestartAt &&
                            nowMillis - entry.lastUnexpectedRestartAt <= RECOVERY_WINDOW_MS
                        entry.unexpectedRestarts =
                            if (recent) (entry.unexpectedRestarts + 1).coerceAtMost(MAX_UNEXPECTED_RESTARTS)
                            else 1
                        entry.lastUnexpectedRestartAt = nowMillis
                        if (entry.unexpectedRestarts >= MAX_UNEXPECTED_RESTARTS) {
                            entry.status = Status.FAILED
                            entry.stage = "后台异常中断过多，已跳过"
                            entry.error = "系统在一小时内多次终止此 PDF 任务；已保留本文件的 OCR / 导出断点，" +
                                "并继续处理队列中的其他文件。请检查空间、内存或原 PDF 后手动重试。"
                        } else {
                            entry.status = Status.WAITING
                            entry.stage = "恢复未完成的任务（${entry.unexpectedRestarts}/${MAX_UNEXPECTED_RESTARTS}）"
                        }
                    }
                    Status.PAUSING -> {
                        entry.status = Status.PAUSED
                        entry.stage = "已暂停，可继续"
                        entry.unexpectedRestarts = 0
                        entry.lastUnexpectedRestartAt = 0L
                    }
                    Status.CANCELLING -> {
                        // With a destination recorded, the process may have died
                        // after close but before DONE. Keep cleanup blocked until
                        // the worker verifies the output; UI cleanup owns CANCELLED.
                        if (entry.outputUri.isBlank()) {
                            entry.status = Status.CANCELLED
                            entry.stage = "已取消"
                        } else {
                            entry.stage = "正在核对取消前的输出"
                        }
                        entry.unexpectedRestarts = 0
                        entry.lastUnexpectedRestartAt = 0L
                    }
                }
            }
        }
    }

    @Synchronized
    fun claimNext(): Entry? = edit { state ->
        if (state.pausedAll ||
            state.entries.any { it.status in setOf(Status.RUNNING, Status.PAUSING, Status.CANCELLING) }) {
            return@edit null
        }
        val entry = state.entries.firstOrNull {
            it.status == Status.WAITING && it.id !in cleanupLeases
        }
            ?: return@edit null
        entry.status = Status.RUNNING
        entry.pausedByGlobal = false
        entry.stage = "等待文件准备"
        entry.error = ""
        entry.copy()
    }

    @Synchronized
    fun setOutput(id: String, uri: String) = edit { state ->
        state.entries.find { it.id == id }?.let { it.outputUri = uri }
    }

    @Synchronized
    fun clearOutput(id: String) = edit { state ->
        state.entries.find { it.id == id }?.let { it.outputUri = "" }
    }

    @Synchronized
    fun progress(id: String, stage: String, page: Int, total: Int, percent: Int) =
        edit { state ->
            val entry = state.entries.find { it.id == id } ?: return@edit
            if (entry.status == Status.RUNNING) {
                entry.stage = stage
                entry.page = page
                entry.total = total
                entry.progress = percent.coerceIn(0, 100)
            }
        }

    @Synchronized
    fun stateOf(id: String): String? =
        read().state.entries.find { it.id == id }?.status

    /** Completion is committed only after the destination stream has closed successfully. */
    @Synchronized
    fun complete(id: String, uri: String, pages: Int) = edit { state ->
        require(uri.isNotBlank()) { "Missing completed PDF URI" }
        require(pages > 0) { "Invalid completed PDF page count" }
        val entry = state.entries.find { it.id == id }
            ?: throw IOException("Cannot complete missing OCR task '$id'")
        // A directive arriving after the final byte was copied cannot turn a
        // complete destination into an incomplete output eligible for deletion.
        entry.status = Status.DONE
        entry.outputUri = uri
        entry.progress = 100
        entry.page = pages
        entry.total = pages
        entry.stage = "完成"
        entry.error = ""
        entry.pausedByGlobal = false
        entry.unexpectedRestarts = 0
        entry.lastUnexpectedRestartAt = 0L
    }

    @Synchronized
    fun deferOutputCompletion(id: String, error: String) = edit { state ->
        val entry = state.entries.find { it.id == id }
            ?: throw IOException("Cannot retain output for missing OCR task '$id'")
        // The output may already be complete. A late cancellation must not make
        // it eligible for automatic CANCELLED-task deletion before verification.
        entry.status = Status.FAILED
        entry.pausedByGlobal = false
        entry.stage = "输出已保留，待核对完成状态"
        entry.error = error.take(800)
    }

    @Synchronized
    fun finish(id: String, status: String, error: String = "") = edit { state ->
        val entry = state.entries.find { it.id == id } ?: return@edit
        entry.status = when (entry.status) {
            Status.PAUSING -> Status.PAUSED
            Status.CANCELLING -> Status.CANCELLED
            // Pause-all can be released while the active task is shutting
            // down. Queue it again instead of leaving it unexpectedly paused.
            Status.RUNNING -> if (status == Status.PAUSED && !state.pausedAll)
                Status.WAITING else status
            else -> status
        }
        if (entry.status != Status.PAUSED && entry.status != Status.PAUSING) {
            entry.pausedByGlobal = false
        }
        // Successful completion and intentional pauses/cancellations are not
        // repeated background crashes. Reset their restart history.
        if (entry.status in setOf(Status.DONE, Status.PAUSED, Status.CANCELLED)) {
            entry.unexpectedRestarts = 0
            entry.lastUnexpectedRestartAt = 0L
        }
        if (entry.status == Status.DONE) {
            entry.progress = 100
            entry.stage = "完成"
        } else {
            entry.stage = when (entry.status) {
                Status.PAUSED -> "已暂停"
                Status.CANCELLED -> "已取消"
                Status.FAILED -> "失败，可重试"
                else -> entry.stage
            }
        }
        entry.error = error.take(800)
    }

    @Synchronized
    fun pause(id: String) = edit { state ->
        val entry = state.entries.find { it.id == id } ?: return@edit
        // Explicit per-document pause always overrides a global pause.
        if (entry.status in setOf(Status.RUNNING, Status.WAITING,
                Status.PAUSING, Status.PAUSED)) {
            entry.pausedByGlobal = false
        }
        entry.status = when (entry.status) {
            Status.RUNNING -> Status.PAUSING
            Status.WAITING -> Status.PAUSED
            else -> entry.status
        }
    }

    @Synchronized
    fun resume(id: String) = edit { state ->
        requireNotCleaning(id)
        val entry = state.entries.find { it.id == id } ?: return@edit
        if (entry.status == Status.PAUSED || entry.status == Status.FAILED) {
            entry.pausedByGlobal = false
            entry.unexpectedRestarts = 0
            entry.lastUnexpectedRestartAt = 0L
            entry.status = Status.WAITING
            entry.error = ""
            entry.stage = "等待续做"
        }
    }

    /**
     * Atomically return the *committed* status after cancellation.
     *
     * The UI must never read a WAITING status before this call and assume
     * that its cache can be deleted: the supervisor may claim that task in
     * between those two operations. Only CANCELLED with no retained output
     * is eligible for idle cleanup under a cleanup lease. A saved output
     * stays CANCELLING until the worker verifies whether it is complete.
     */
    @Synchronized
    fun cancel(id: String): String? = edit { state ->
        requireNotCleaning(id)
        val entry = state.entries.find { it.id == id } ?: return@edit null
        entry.pausedByGlobal = false
        entry.status = when (entry.status) {
            Status.RUNNING, Status.PAUSING -> Status.CANCELLING
            Status.WAITING, Status.PAUSED, Status.FAILED ->
                if (entry.outputUri.isNotBlank()) Status.CANCELLING else Status.CANCELLED
            else -> entry.status
        }
        entry.status
    }

    @Synchronized
    fun retry(id: String) = edit { state ->
        requireNotCleaning(id)
        val entry = state.entries.find { it.id == id } ?: return@edit
        if (entry.status == Status.CANCELLED || entry.status == Status.FAILED) {
            entry.pausedByGlobal = false
            entry.unexpectedRestarts = 0
            entry.lastUnexpectedRestartAt = 0L
            entry.status = Status.WAITING
            entry.stage = "等待重新处理"
            entry.error = ""
            // A complete destination may only need its queue commit retried.
            // Keep its URI until the worker reconciles it with the working PDF.
        }
    }

    @Synchronized
    fun pauseAll() = edit { state ->
        state.pausedAll = true
        state.entries.forEach { entry ->
            if (entry.status == Status.RUNNING) {
                entry.status = Status.PAUSING
                entry.pausedByGlobal = true
            }
        }
    }

    @Synchronized
    fun resumeAll() = edit { state ->
        state.pausedAll = false
        state.entries.forEach { entry ->
            // Restore ONLY the task held by global pause. Individually
            // paused tasks are deliberately left in PAUSED.
            if (entry.pausedByGlobal) {
                when (entry.status) {
                    Status.PAUSED -> {
                        entry.status = Status.WAITING
                        entry.stage = "等待续做"
                        entry.pausedByGlobal = false
                    }
                    Status.PAUSING -> {
                        // The worker might not have observed the pause yet.
                        // If it already exited, finish(PAUSED) requeues it.
                        entry.status = Status.RUNNING
                        entry.pausedByGlobal = false
                    }
                }
            }
        }
    }

    @Synchronized
    fun remove(id: String): Boolean = edit { state ->
        val entry = state.entries.find { it.id == id }
        if (entry == null || id in cleanupLeases ||
            entry.status in setOf(Status.RUNNING, Status.PAUSING, Status.CANCELLING)) {
            false
        } else {
            state.entries.remove(entry)
        }
    }

    /** The lease blocks retries while slow deletion runs without holding the queue lock. */
    fun <T> withCleanup(id: String, allowed: Set<String>, action: (Entry) -> T): T? =
        withCleanup(listOf(id), allowed) { action(it.single()) }

    /** Hold every selected task for the whole batch, including before its deletion starts. */
    fun <T> withCleanup(ids: Collection<String>, allowed: Set<String>, action: (List<Entry>) -> T): T? {
        require(Status.RUNNING !in allowed && Status.PAUSING !in allowed) {
            "Cannot clean resources still owned by a running OCR task"
        }
        val selected = ids.toSet()
        val entries = synchronized(this) {
            if (selected.any { it in cleanupLeases }) return null
            val current = read().state.entries.filter { it.id in selected }
            if (current.size != selected.size || current.any { it.status !in allowed }) return null
            cleanupLeases.addAll(selected)
            current.map { it.copy() }
        }
        try {
            return action(entries)
        } finally {
            synchronized(this) { cleanupLeases.removeAll(selected) }
        }
    }

    private fun requireNotCleaning(id: String) {
        if (id in cleanupLeases) {
            throw IOException("MuOCR task '$id' cache cleanup is in progress; retry after it finishes")
        }
    }

    @Synchronized
    fun setSimplifyChinese(id: String, enabled: Boolean) = edit { state ->
        requireNotCleaning(id)
        val entry = state.entries.find { it.id == id } ?: throw IOException("OCR 任务已移除")
        check(entry.status in setOf(Status.WAITING, Status.PAUSED, Status.FAILED, Status.CANCELLED)) {
            "请先暂停任务后再切换文字层"
        }
        check(entry.outputUri.isBlank()) { "已有输出尚待核对，请先重试完成收尾" }
        entry.simplifyChinese = enabled
    }

    @Synchronized
    fun prepareCacheClear(id: String) = edit { state ->
        check(id in cleanupLeases) { "Cache reset requires a cleanup lease" }
        state.entries.find { it.id == id }?.discardLegacyCache = true
    }

    @Synchronized
    fun noteCacheCleared(id: String) = edit { state ->
        check(id in cleanupLeases) { "Cache reset requires a cleanup lease" }
        val entry = state.entries.find { it.id == id } ?: return@edit
        if (entry.status != Status.DONE) {
            entry.page = 0
            entry.total = 0
            entry.progress = 0
            entry.stage = "缓存已清理，再次处理将从头识别"
            entry.error = ""
        }
    }

    private fun <T> edit(block: (Snapshot) -> T): T {
        val loaded = read()
        val result = block(loaded.state)
        write(loaded.state, loaded.primaryText)
        return result
    }

    private data class Loaded(val state: Snapshot, val primaryText: String?)

    private fun read(): Loaded {
        val file = if (manifest.isFile) manifest else backup
        if (!file.isFile) return Loaded(Snapshot(), null)
        fun parse(f: File): Loaded? = runCatching {
            val text = f.readText(Charsets.UTF_8)
            val state = gson.fromJson(text, Snapshot::class.java)
            if (state == null || state.entries == null) null else Loaded(state, text)
        }.getOrNull()
        val primary = if (manifest.isFile) parse(manifest) else null
        val state = (primary ?: parse(backup))?.state
            ?: throw IOException("MuOCR 任务队列记录损坏，请保留应用数据并备份 queue 文件")
        if (state.entries.any { it.id.isBlank() || it.inputUri.isBlank() }) {
            throw IOException("MuOCR 任务队列包含无效任务，拒绝覆盖")
        }
        return Loaded(state, primary?.primaryText)
    }

    private fun write(state: Snapshot, primaryText: String?) {
        val parent = manifest.parentFile ?: throw IOException("No queue directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create queue dir")
        val bytes = gson.toJson(state).toByteArray(Charsets.UTF_8)
        // A recovered backup is the only known-good copy. Never replace it
        // with the corrupt primary that caused recovery in the first place.
        if (primaryText != null) {
            durableFile.write(backup, primaryText.toByteArray(Charsets.UTF_8))
        }
        durableFile.write(manifest, bytes)
    }
}
