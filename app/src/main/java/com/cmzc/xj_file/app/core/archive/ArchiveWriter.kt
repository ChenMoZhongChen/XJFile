package com.cmzc.xj_file.app.core.archive

import com.cmzc.xj_file.app.core.fs.RemoteFile
import com.cmzc.xj_file.app.core.fs.RemoteFileSystem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/**
 * 压缩包写入器：所有格式共用同一套「先写本地临时文件」流程。
 *
 * LZH / ZPAQ 只预留：当前 Android/JVM 依赖里没有可用的写入库，
 * 不用 ZIP 冒充，避免生成扩展名对、内容错的假文件。
 */
object ArchiveWriter {

    suspend fun create(
        fs: RemoteFileSystem,
        selected: List<RemoteFile>,
        tempFile: File,
        type: ArchiveType,
        level: Int,
    ): ArchiveResult {
        if (!type.supported) throw UnsupportedOperationException("${type.label} 暂不支持创建")
        if (type.singleFileOnly &&
            (selected.size != 1 || selected.first().isDirectory)
        ) {
            throw IllegalArgumentException("${type.label} 只支持单个文件；多选请用 ZIP/7Z/TAR")
        }
        return when (type) {
            ArchiveType.ZIP, ArchiveType.ZIPX ->
                ZipArchiveWriter.compress(fs, selected, tempFile, level)
            ArchiveType.SEVEN_Z -> write7z(fs, selected, tempFile, level)
            ArchiveType.TAR -> writeTar(fs, selected, tempFile)
            ArchiveType.TGZ -> writeTgz(fs, selected, tempFile, level)
            ArchiveType.GZ -> writeSingle(fs, selected.first(), tempFile) { out ->
                LevelGzipOutputStream(out, ArchiveLevels.gzipLevel(level))
            }
            ArchiveType.XZ -> writeSingle(fs, selected.first(), tempFile) { out ->
                XZOutputStream(out, LZMA2Options(ArchiveLevels.xzPreset(level)))
            }
            ArchiveType.LZH, ArchiveType.ZPAQ, ArchiveType.RAR ->
                throw UnsupportedOperationException("${type.label} 暂不支持创建")
        }
    }

    private suspend fun writeTar(
        fs: RemoteFileSystem,
        selected: List<RemoteFile>,
        tempFile: File,
    ): ArchiveResult {
        var entries = 0
        FileOutputStream(tempFile).use { fileOut ->
            TarArchiveOutputStream(BufferedOutputStream(fileOut, 64 * 1024)).use { tar ->
                entries = fillTar(fs, selected, tar)
            }
        }
        return ArchiveResult(entries, tempFile.length())
    }

    private suspend fun writeTgz(
        fs: RemoteFileSystem,
        selected: List<RemoteFile>,
        tempFile: File,
        level: Int,
    ): ArchiveResult {
        var entries = 0
        FileOutputStream(tempFile).use { fileOut ->
            LevelGzipOutputStream(
                BufferedOutputStream(fileOut, 64 * 1024),
                ArchiveLevels.gzipLevel(level),
            ).use { gz ->
                TarArchiveOutputStream(gz).use { tar ->
                    entries = fillTar(fs, selected, tar)
                }
            }
        }
        return ArchiveResult(entries, tempFile.length())
    }

    private suspend fun fillTar(
        fs: RemoteFileSystem,
        selected: List<RemoteFile>,
        tar: TarArchiveOutputStream,
    ): Int {
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
        var entries = 0
        val visited = HashSet<String>()
        for (item in selected) {
            entries += writeTarItem(fs, item, item.name, tar, visited, 0)
        }
        return entries
    }

    private suspend fun writeTarItem(
        fs: RemoteFileSystem,
        item: RemoteFile,
        entryName: String,
        tar: TarArchiveOutputStream,
        visited: MutableSet<String>,
        depth: Int,
    ): Int {
        currentCoroutineContext().ensureActive()
        if (depth > 64 || !visited.add(item.path)) return 0
        val safeName = safeEntryName(entryName)
        return if (item.isDirectory) {
            val entry = TarArchiveEntry("$safeName/").apply {
                if (item.lastModified > 0) setModTime(item.lastModified)
            }
            tar.putArchiveEntry(entry)
            tar.closeArchiveEntry()
            var count = 1
            val dirPath = if (item.path.endsWith("/")) item.path else item.path + "/"
            for (child in runCatching { fs.list(dirPath) }.getOrElse { emptyList() }) {
                count += writeTarItem(
                    fs, child, "$safeName/${child.name}", tar, visited, depth + 1
                )
            }
            count
        } else {
            val entry = TarArchiveEntry(safeName).apply {
                size = item.size.coerceAtLeast(0)
                if (item.lastModified > 0) setModTime(item.lastModified)
            }
            tar.putArchiveEntry(entry)
            fs.openInput(item.path).use { input -> copy(input, tar) }
            tar.closeArchiveEntry()
            1
        }
    }

    private suspend fun write7z(
        fs: RemoteFileSystem,
        selected: List<RemoteFile>,
        tempFile: File,
        level: Int,
    ): ArchiveResult {
        var entries = 0
        SevenZOutputFile(tempFile).use { sz ->
            // 0 级用 COPY（仅存储）；1~3 用 LZMA2。7Z 的压缩预设不方便逐级暴露，
            // UI 的级别只影响是否启用压缩，体积差异交给 LZMA2 默认参数。
            sz.setContentCompression(
                if (ArchiveLevels.zipLevel(level) == 0) SevenZMethod.COPY else SevenZMethod.LZMA2
            )
            val visited = HashSet<String>()
            for (item in selected) {
                entries += write7zItem(fs, item, item.name, sz, visited, 0)
            }
        }
        return ArchiveResult(entries, tempFile.length())
    }

    private suspend fun write7zItem(
        fs: RemoteFileSystem,
        item: RemoteFile,
        entryName: String,
        sz: SevenZOutputFile,
        visited: MutableSet<String>,
        depth: Int,
    ): Int {
        currentCoroutineContext().ensureActive()
        if (depth > 64 || !visited.add(item.path)) return 0
        val safeName = safeEntryName(entryName)
        return if (item.isDirectory) {
            val entry = SevenZArchiveEntry()
            entry.name = "$safeName/"
            entry.isDirectory = true
            sz.putArchiveEntry(entry)
            sz.closeArchiveEntry()
            var count = 1
            val dirPath = if (item.path.endsWith("/")) item.path else item.path + "/"
            for (child in runCatching { fs.list(dirPath) }.getOrElse { emptyList() }) {
                count += write7zItem(
                    fs, child, "$safeName/${child.name}", sz, visited, depth + 1
                )
            }
            count
        } else {
            val entry = SevenZArchiveEntry()
            entry.name = safeName
            entry.size = item.size.coerceAtLeast(0)
            sz.putArchiveEntry(entry)
            fs.openInput(item.path).use { input -> copyToSevenZ(input, sz) }
            sz.closeArchiveEntry()
            1
        }
    }

    private suspend fun writeSingle(
        fs: RemoteFileSystem,
        item: RemoteFile,
        tempFile: File,
        wrap: (OutputStream) -> OutputStream,
    ): ArchiveResult {
        FileOutputStream(tempFile).use { fileOut ->
            wrap(BufferedOutputStream(fileOut, 64 * 1024)).use { out ->
                fs.openInput(item.path).use { input -> copy(input, out) }
            }
        }
        return ArchiveResult(1, tempFile.length())
    }

    private suspend fun copyToSevenZ(input: java.io.InputStream, output: SevenZOutputFile) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
    }

    private suspend fun copy(input: java.io.InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
    }

    private fun safeEntryName(name: String): String =
        name.replace('\\', '/').trim('/').ifBlank { "unnamed" }

    /** GZIPOutputStream 默认等级无法从构造器指定，这里用受保护的 def 设置。 */
    private class LevelGzipOutputStream(out: OutputStream, level: Int) : GZIPOutputStream(out) {
        init {
            def.setLevel(level.coerceIn(0, 9))
        }
    }
}
