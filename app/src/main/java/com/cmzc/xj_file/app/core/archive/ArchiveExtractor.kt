package com.cmzc.xj_file.app.core.archive

import com.cmzc.xj_file.app.core.fs.LocalFileSystem
import com.cmzc.xj_file.app.core.fs.PathUtil
import com.cmzc.xj_file.app.core.fs.RemoteFileSystem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ArchiveExtractResult(
    val extracted: Int = 0,
    val directories: Int = 0,
    val renamed: Int = 0,
    val skipped: Int = 0,
    val errors: List<String> = emptyList(),
)

/**
 * 只负责“压缩包 -> 当前目录”的解压。
 *
 * 不覆盖已有文件：同名时自动加 (1)、(2)，和 Windows 资源管理器一致。
 * 所有条目路径先经过 [normalizeEntryName] 清洗，拒绝绝对路径和 `..`。
 */
object ArchiveExtractor {

    suspend fun extract(
        reader: ArchiveReader,
        entries: List<ArchiveEntryInfo>,
        targetFs: RemoteFileSystem,
        targetDir: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ArchiveExtractResult {
        val selected = entries.filter { it.path.isNotBlank() }
            .sortedWith(compareBy<ArchiveEntryInfo> { it.path.count { c -> c == '/' } }.thenBy { it.path })
        var extracted = 0
        var directories = 0
        var renamed = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        onProgress(0, selected.size)

        for ((index, entry) in selected.withIndex()) {
            currentCoroutineContext().ensureActive()
            try {
                val destination = joinTarget(targetFs, targetDir, entry.path)
                if (entry.isDirectory) {
                    ensureDirectory(targetFs, destination)
                    directories++
                } else if (entry.name.isBlank()) {
                    skipped++
                } else {
                    val parent = destination.substringBeforeLast('/', "")
                    if (parent.isNotEmpty()) ensureDirectory(targetFs, parent)
                    val actual = uniquePath(targetFs, destination)
                    if (actual != destination) renamed++
                    reader.openEntry(entry).use { input ->
                        targetFs.writeFrom(input, actual) { _, _ -> }
                    }
                    extracted++
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                val reason = t.message ?: t.javaClass.simpleName
                errors += "${entry.path}：$reason"
                if (errors.size >= 20) {
                    errors += "错误较多，已停止显示"
                }
            }
            onProgress(index + 1, selected.size)
        }
        return ArchiveExtractResult(
            extracted = extracted,
            directories = directories,
            renamed = renamed,
            skipped = skipped,
            errors = errors,
        )
    }

    private fun joinTarget(fs: RemoteFileSystem, targetDir: String, entryPath: String): String =
        if (fs is LocalFileSystem) {
            PathUtil.joinLocal(targetDir, entryPath)
        } else {
            PathUtil.joinRemote(targetDir, entryPath)
        }

    private suspend fun ensureDirectory(fs: RemoteFileSystem, path: String) {
        if (path.isBlank()) return
        if (fs.exists(path)) return
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        if (parent.isNotEmpty() && parent != path.trimEnd('/')) {
            ensureDirectory(fs, parent)
        }
        // 目录可能被另一个条目抢先创建，忽略这种竞态。
        runCatching { fs.mkdir(path) }
    }

    private suspend fun uniquePath(fs: RemoteFileSystem, path: String): String {
        if (!fs.exists(path)) return path
        val slash = path.lastIndexOf('/')
        val parent = if (slash > 0) path.substring(0, slash) else ""
        val name = path.substring(slash + 1)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (i < 10000) {
            val candidateName = "$base ($i)$ext"
            val candidate = if (parent.isEmpty()) candidateName else "$parent/$candidateName"
            if (!fs.exists(candidate)) return candidate
            i++
        }
        return path
    }
}
