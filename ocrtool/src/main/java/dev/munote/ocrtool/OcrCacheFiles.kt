package dev.munote.ocrtool

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/** Only app-owned work directories are eligible, never SAF input/output documents. */
internal object OcrCacheFiles {
    const val CLEANUP_MARKER = "cleanup.pending"
    private val legacyName = Regex("[0-9a-f]{32}")
    private val queueName = Regex("queue-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private val pageName = Regex("page_[0-9]{6}\\.json")
    private val durable = DurableFile()

    data class Usage(val directory: File, val bytes: Long, val pages: Int,
                     val cleanupPending: Boolean) {
        val taskId: String? get() = directory.name.removePrefix("queue-")
            .takeIf { queueName.matches(directory.name) }
    }

    fun legacyId(uri: String, size: Long, modified: Long): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$uri|$size|$modified".toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    fun directory(roots: List<File>, name: String): File {
        require(validName(name)) { "Invalid OCR cache name: $name" }
        val existing = roots.map { File(it, name) }.filter { it.exists() }
            .distinctBy { it.canonicalPath }
        if (existing.size > 1) throw IOException("发现两处同名 OCR 缓存，请在缓存管理核对：$name")
        return (existing.singleOrNull() ?: File(roots.first(), name)).also { validate(roots, it) }
    }

    fun scan(roots: List<File>): List<Usage> = roots.distinctBy { it.canonicalPath }.flatMap { root ->
        if (!root.exists()) emptyList() else children(root).filter { validName(it.name) }.map { dir ->
            validate(roots, dir)
            var bytes = 0L
            var pages = 0
            walk(dir) { file ->
                bytes += file.length()
                if (file.parentFile == File(dir, "pages") && pageName.matches(file.name)) pages++
            }
            Usage(dir, bytes, pages, File(dir, CLEANUP_MARKER).exists())
        }
    }

    /** The durable marker survives interrupted/failed deletion; retries must finish it first. */
    fun clear(roots: List<File>, dir: File, delete: (File) -> Boolean = File::delete) {
        validate(roots, dir)
        if (!dir.exists()) return
        walk(dir) {} // Reject links/unreadable subtrees before removing any user progress.
        durable.write(File(dir, CLEANUP_MARKER), "muocr-cleanup-v1\n".toByteArray())
        fun remove(file: File) {
            rejectLink(file)
            if (file.isDirectory) children(file).forEach { remove(it) }
            if (file.exists() && !delete(file)) throw IOException("无法删除缓存：${file.absolutePath}")
        }
        children(dir).filter { it.name != CLEANUP_MARKER }.forEach { remove(it) }
        remove(File(dir, CLEANUP_MARKER))
        remove(dir)
    }

    /** Merge every missing page on each attempt, so a killed import cannot strand the remainder. */
    fun importPages(legacy: File, job: File, validPage: (File, Int) -> Boolean): Int {
        if (!legacy.isDirectory || legacy.canonicalFile == job.canonicalFile) return 0
        if (File(legacy, CLEANUP_MARKER).exists()) {
            throw IOException("旧版缓存尚未清理完成，请先在缓存管理重试：${legacy.absolutePath}")
        }
        val oldPages = File(legacy, "pages")
        if (!oldPages.exists()) return 0
        val newPages = File(job, "pages")
        check(newPages.isDirectory || newPages.mkdirs()) { "无法创建 OCR 页缓存目录" }
        var copied = 0
        rejectLink(oldPages)
        for (source in children(oldPages).filter { pageName.matches(it.name) }.sortedBy { it.name }) {
            rejectLink(source)
            if (source.length() > 8L * 1024 * 1024) throw IOException("OCR 页记录异常过大：${source.name}")
            val index = source.name.substring(5, 11).toInt() - 1
            if (index < 0 || !validPage(oldPages, index)) {
                throw IOException("旧版 OCR 页记录损坏，请保留或手动清理：${source.absolutePath}")
            }
            val target = File(newPages, source.name)
            rejectLink(target)
            if (validPage(newPages, index)) continue
            durable.write(target, source.readBytes())
            copied++
        }
        return copied
    }

    private fun validName(name: String) = legacyName.matches(name) || queueName.matches(name)

    private fun validate(roots: List<File>, dir: File) {
        require(validName(dir.name)) { "拒绝清理未知目录：${dir.absolutePath}" }
        rejectLink(dir)
        if (roots.none { it.canonicalFile == dir.canonicalFile.parentFile }) {
            throw IOException("OCR 缓存路径越界：${dir.absolutePath}")
        }
        if (dir.exists() && !dir.isDirectory) throw IOException("OCR 缓存不是目录：${dir.absolutePath}")
    }

    private fun children(dir: File): Array<File> = dir.listFiles()
        ?: throw IOException("无法读取缓存目录：${dir.absolutePath}")

    private fun rejectLink(file: File) {
        if (Files.isSymbolicLink(file.toPath())) throw IOException("拒绝操作缓存中的符号链接：${file.absolutePath}")
    }

    private fun walk(dir: File, visit: (File) -> Unit) {
        rejectLink(dir)
        for (file in children(dir)) {
            rejectLink(file)
            if (file.isDirectory) walk(file, visit) else visit(file)
        }
    }
}

/** Queue and pre-queue WorkManager requests share the same process-level ownership. */
internal object OcrCacheAccess {
    private var worker = false
    private var maintenance = false

    @Synchronized fun beginWorker(): Boolean {
        if (worker || maintenance) return false
        worker = true
        return true
    }
    @Synchronized fun endWorker() { worker = false }
    @Synchronized fun workerActive() = worker

    fun <T> maintain(action: () -> T): T {
        synchronized(this) {
            if (worker || maintenance) throw IOException("任务仍在处理或缓存正在清理，请先暂停任务并等待停止后重试")
            maintenance = true
        }
        try { return action() }
        finally { synchronized(this) { maintenance = false } }
    }
}
