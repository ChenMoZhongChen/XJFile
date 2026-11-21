package com.cmzc.xj_file.app.core.archive

import com.cmzc.xj_file.app.core.fs.RemoteFile
import com.cmzc.xj_file.app.core.fs.RemoteFileSystem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 压缩包写入结果，给调用方做提示/日志用。 */
data class ArchiveResult(
    val entryCount: Int,
    val archiveBytes: Long,
)

/**
 * 把当前选中的文件/文件夹打成 ZIP。
 *
 * 为什么先写本地临时文件再上传：
 * - SFTP 的输入流和输出流会抢同一套车道，边读远端文件边写远端压缩包容易互相卡死；
 * - 先把压缩包落到 cache，再一次 writeFrom 到目标目录，失败时只需删本地临时文件；
 * - 7Z 如果需要随机写，也能复用这条临时文件路径。
 *
 * 压缩本身是顺序读 + 顺序写：每次只打开一个源文件，内存占用就是 64KB 缓冲。
 * 目录层级用 `/` 分隔写进 entry，空目录也会保留一条 `dir/` 记录。
 */
object ZipArchiveWriter {

    suspend fun compress(
        fs: RemoteFileSystem,
        selected: List<RemoteFile>,
        tempFile: File,
        level: Int,
    ): ArchiveResult {
        tempFile.parentFile?.mkdirs()
        var entries = 0

        FileOutputStream(tempFile).use { fileOut ->
            ZipOutputStream(
                BufferedOutputStream(fileOut, 64 * 1024),
                StandardCharsets.UTF_8,
            ).use { zip ->
                zip.setLevel(ArchiveLevels.zipLevel(level))
                val visited = HashSet<String>()
                for (item in selected) {
                    entries += writeItem(
                        fs = fs,
                        item = item,
                        entryName = safeEntryName(item.name),
                        zip = zip,
                        visited = visited,
                        depth = 0,
                    )
                }
            }
        }
        return ArchiveResult(entries, tempFile.length())
    }

    private suspend fun writeItem(
        fs: RemoteFileSystem,
        item: RemoteFile,
        entryName: String,
        zip: ZipOutputStream,
        visited: MutableSet<String>,
        depth: Int,
    ): Int {
        currentCoroutineContext().ensureActive()
        // 本地符号链接/异常目录可能绕回自己；深度 + 路径去重双保险
        if (depth > 64 || !visited.add(item.path)) return 0
        if (entryName.isBlank()) return 0

        return if (item.isDirectory) {
            var count = 1
            val dirEntry = ZipEntry("$entryName/").apply {
                if (item.lastModified > 0) time = item.lastModified
            }
            zip.putNextEntry(dirEntry)
            zip.closeEntry()

            val dirPath = if (item.path.endsWith("/")) item.path else item.path + "/"
            val children = runCatching { fs.list(dirPath) }.getOrElse { emptyList() }
            for (child in children) {
                count += writeItem(
                    fs = fs,
                    item = child,
                    entryName = "$entryName/${safeEntryName(child.name)}",
                    zip = zip,
                    visited = visited,
                    depth = depth + 1,
                )
            }
            count
        } else {
            val entry = ZipEntry(entryName).apply {
                if (item.lastModified > 0) time = item.lastModified
            }
            zip.putNextEntry(entry)
            fs.openInput(item.path).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    zip.write(buffer, 0, read)
                }
            }
            zip.closeEntry()
            1
        }
    }

    private fun safeEntryName(name: String): String =
        name.replace('\\', '/').trim('/').ifBlank { "unnamed" }
}
