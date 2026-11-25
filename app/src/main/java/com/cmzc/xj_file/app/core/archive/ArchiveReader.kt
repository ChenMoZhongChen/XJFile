package com.cmzc.xj_file.app.core.archive

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 压缩包只读格式。RAR/LZH/ZPAQ 没有可用的 Android 读取库，明确拒绝，
 * 不用其他格式冒充。
 */
enum class ArchiveReadFormat { ZIP, TAR, TGZ, SEVEN_Z, GZ, XZ }

class UnsupportedArchiveException(message: String) : Exception(message)

data class ArchiveEntryInfo(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
) {
    val name: String get() = path.substringAfterLast('/')
    val parent: String get() = path.substringBeforeLast('/', "")
}

object ArchiveFormats {
    private val unsupported = setOf("rar", "lzh", "zpaq")

    fun kindOf(fileName: String): ArchiveReadFormat? {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".tar.gz") -> ArchiveReadFormat.TGZ
            lower.endsWith(".tgz") -> ArchiveReadFormat.TGZ
            lower.endsWith(".zip") || lower.endsWith(".zipx") -> ArchiveReadFormat.ZIP
            lower.endsWith(".tar") -> ArchiveReadFormat.TAR
            lower.endsWith(".7z") -> ArchiveReadFormat.SEVEN_Z
            lower.endsWith(".gz") -> ArchiveReadFormat.GZ
            lower.endsWith(".xz") -> ArchiveReadFormat.XZ
            else -> null
        }
    }

    fun isArchive(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return kindOf(fileName) != null || unsupported.any { lower.endsWith(".$it") }
    }

    fun unsupportedLabel(fileName: String): String? {
        val lower = fileName.lowercase()
        return unsupported.firstOrNull { lower.endsWith(".$it") }?.uppercase()
    }
}

/**
 * 压缩包只读读取器。
 *
 * ZIP/7Z 用一个可随机访问的句柄；TAR/TGZ 每次打开条目时重新顺序扫描；
 * GZ/XZ 是单文件压缩，展示为一个合成条目。所有返回的 InputStream 由调用方关闭。
 */
class ArchiveReader private constructor(
    private val file: File,
    private val format: ArchiveReadFormat,
    private val zipFile: ZipFile?,
    private val zipEntries: Map<String, ZipEntry>,
    private val sevenZFile: SevenZFile?,
    private val sevenZEntries: Map<String, SevenZArchiveEntry>,
    val entries: List<ArchiveEntryInfo>,
) : Closeable {

    fun openEntry(entry: ArchiveEntryInfo): InputStream {
        if (entry.isDirectory) throw IllegalArgumentException("目录不能作为文件读取")
        return when (format) {
            ArchiveReadFormat.ZIP -> {
                val zipEntry = zipEntries[entry.path]
                    ?: throw IllegalStateException("压缩包条目不存在：${entry.path}")
                zipFile?.getInputStream(zipEntry)
                    ?: throw IllegalStateException("无法读取压缩包条目：${entry.path}")
            }

            ArchiveReadFormat.SEVEN_Z -> {
                val sevenEntry = sevenZEntries[entry.path]
                    ?: throw IllegalStateException("压缩包条目不存在：${entry.path}")
                sevenZFile?.getInputStream(sevenEntry)
                    ?: throw IllegalStateException("无法读取压缩包条目：${entry.path}")
            }

            ArchiveReadFormat.TAR, ArchiveReadFormat.TGZ -> openTarEntry(entry.path)
            ArchiveReadFormat.GZ -> openGzip()
            ArchiveReadFormat.XZ -> XZInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024))
        }
    }

    private fun openTarEntry(path: String): InputStream {
        val tar = openTarStream()
        try {
            while (true) {
                val entry = tar.nextEntry ?: break
                val clean = normalizeEntryName(entry.name) ?: continue
                if (clean == path) return tar
            }
        } catch (t: Throwable) {
            runCatching { tar.close() }
            throw t
        }
        runCatching { tar.close() }
        throw IllegalStateException("压缩包条目不存在：$path")
    }

    private fun openGzip(): InputStream =
        GZIPInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024))

    private fun openTarStream(): TarArchiveInputStream {
        val base = BufferedInputStream(FileInputStream(file), 64 * 1024)
        return if (format == ArchiveReadFormat.TGZ) {
            TarArchiveInputStream(GzipCompressorInputStream(base))
        } else {
            TarArchiveInputStream(base)
        }
    }

    override fun close() {
        runCatching { zipFile?.close() }
        runCatching { sevenZFile?.close() }
    }

    companion object {
        fun open(file: File, displayName: String = file.name): ArchiveReader {
            require(file.isFile) { "压缩包不存在：${file.absolutePath}" }
            val format = ArchiveFormats.kindOf(displayName)
                ?: ArchiveFormats.kindOf(file.name)
                ?: throw UnsupportedArchiveException(
                    "暂不支持读取 ${ArchiveFormats.unsupportedLabel(displayName) ?: "这个"} 压缩包"
                )
            return when (format) {
                ArchiveReadFormat.ZIP -> openZip(file)
                ArchiveReadFormat.SEVEN_Z -> openSevenZ(file)
                ArchiveReadFormat.TAR, ArchiveReadFormat.TGZ -> openTar(file, format)
                ArchiveReadFormat.GZ, ArchiveReadFormat.XZ -> openSingle(file, format)
            }
        }

        private fun openZip(file: File): ArchiveReader {
            // Bandizip/Windows 的 ZIP 可能不带 UTF-8 标志，中文名按 GB18030 写入。
            // 先用 UTF-8 读；如果出现替换字符，再试 GB18030，取损坏更少的一份。
            val utf8 = runCatching { readZip(file, Charsets.UTF_8) }.getOrNull()
            val gb = runCatching { readZip(file, Charset.forName("GB18030")) }.getOrNull()
            val chosen = when {
                utf8 == null -> gb ?: error("无法读取 ZIP")
                gb == null -> utf8
                zipScore(gb.entries) < zipScore(utf8.entries) -> gb
                else -> utf8
            }
            if (chosen !== utf8) utf8?.zip?.let { runCatching { it.close() } }
            if (chosen !== gb) gb?.zip?.let { runCatching { it.close() } }
            return ArchiveReader(
                file, ArchiveReadFormat.ZIP, chosen.zip, chosen.map,
                sevenZFile = null, sevenZEntries = emptyMap(),
                entries = chosen.entries.sortedForDisplay(),
            )
        }

        private data class ZipContent(
            val zip: ZipFile,
            val map: Map<String, ZipEntry>,
            val entries: List<ArchiveEntryInfo>,
        )

        private fun readZip(file: File, charset: Charset): ZipContent {
            val zip = ZipFile(file, charset)
            val map = LinkedHashMap<String, ZipEntry>()
            val entries = ArrayList<ArchiveEntryInfo>()
            val dirs = HashSet<String>()
            try {
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    val entry = enumeration.nextElement()
                    val clean = normalizeEntryName(entry.name) ?: continue
                    if (clean.isBlank()) continue
                    val isDir = entry.isDirectory || entry.name.endsWith('/')
                    if (isDir) {
                        addDirectory(entries, dirs, clean)
                    } else {
                        ensureParents(entries, dirs, clean)
                        if (map.putIfAbsent(clean, entry) == null) {
                            entries.add(
                                ArchiveEntryInfo(
                                    path = clean,
                                    isDirectory = false,
                                    size = entry.size.coerceAtLeast(0),
                                    lastModified = entry.time.coerceAtLeast(0),
                                )
                            )
                        }
                    }
                }
                return ZipContent(zip, map, entries)
            } catch (t: Throwable) {
                runCatching { zip.close() }
                throw t
            }
        }

        private fun zipScore(entries: List<ArchiveEntryInfo>): Int =
            entries.sumOf { entry ->
                entry.path.count { it == '\uFFFD' || it.code < 0x20 }
            }

        private fun openSevenZ(file: File): ArchiveReader {
            val sevenZ = SevenZFile(file)
            val map = LinkedHashMap<String, SevenZArchiveEntry>()
            val entries = ArrayList<ArchiveEntryInfo>()
            val dirs = HashSet<String>()
            try {
                while (true) {
                    val entry = sevenZ.nextEntry ?: break
                    val clean = normalizeEntryName(entry.name) ?: continue
                    if (clean.isBlank()) continue
                    if (entry.isDirectory) {
                        addDirectory(entries, dirs, clean)
                    } else {
                        ensureParents(entries, dirs, clean)
                        if (map.putIfAbsent(clean, entry) == null) {
                            entries.add(
                                ArchiveEntryInfo(
                                    path = clean,
                                    isDirectory = false,
                                    size = entry.size.coerceAtLeast(0),
                                    lastModified = runCatching {
                                        entry.lastModifiedDate?.time ?: 0L
                                    }.getOrDefault(0L),
                                )
                            )
                        }
                    }
                }
                return ArchiveReader(
                    file, ArchiveReadFormat.SEVEN_Z, zipFile = null, zipEntries = emptyMap(),
                    sevenZFile = sevenZ, sevenZEntries = map,
                    entries = entries.sortedForDisplay(),
                )
            } catch (t: Throwable) {
                runCatching { sevenZ.close() }
                throw t
            }
        }

        private fun openTar(file: File, format: ArchiveReadFormat): ArchiveReader {
            val entries = ArrayList<ArchiveEntryInfo>()
            val dirs = HashSet<String>()
            openTarStream(file, format).use { tar ->
                while (true) {
                    val raw = tar.nextEntry ?: break
                    val entry = raw as? TarArchiveEntry ?: continue
                    val clean = normalizeEntryName(entry.name) ?: continue
                    if (clean.isBlank()) continue
                    if (entry.isDirectory) {
                        addDirectory(entries, dirs, clean)
                    } else if (isTarLink(entry)) {
                        // 不把链接条目暴露为可解压文件，避免解压时跟随到目标路径。
                        continue
                    } else {
                        ensureParents(entries, dirs, clean)
                        entries.add(
                            ArchiveEntryInfo(
                                path = clean,
                                isDirectory = false,
                                size = entry.size.coerceAtLeast(0),
                                lastModified = tarModified(entry),
                            )
                        )
                    }
                }
            }
            return ArchiveReader(
                file, format, zipFile = null, zipEntries = emptyMap(),
                sevenZFile = null, sevenZEntries = emptyMap(),
                entries = entries.sortedForDisplay(),
            )
        }

        private fun openSingle(file: File, format: ArchiveReadFormat): ArchiveReader {
            val base = file.name.substringBeforeLast('.', file.name).ifBlank { "data" }
            return ArchiveReader(
                file, format, zipFile = null, zipEntries = emptyMap(),
                sevenZFile = null, sevenZEntries = emptyMap(),
                entries = listOf(
                    ArchiveEntryInfo(
                        path = base,
                        isDirectory = false,
                        size = 0L,
                        lastModified = file.lastModified(),
                    )
                ),
            )
        }

        private fun openTarStream(file: File, format: ArchiveReadFormat): TarArchiveInputStream {
            val base = BufferedInputStream(FileInputStream(file), 64 * 1024)
            return if (format == ArchiveReadFormat.TGZ) {
                TarArchiveInputStream(GzipCompressorInputStream(base))
            } else {
                TarArchiveInputStream(base)
            }
        }

        private fun addDirectory(
            entries: MutableList<ArchiveEntryInfo>,
            dirs: MutableSet<String>,
            path: String,
        ) {
            if (path.isBlank()) return
            ensureParents(entries, dirs, path)
            if (dirs.add(path)) {
                entries.add(ArchiveEntryInfo(path, isDirectory = true, size = 0L, lastModified = 0L))
            }
        }

        private fun ensureParents(
            entries: MutableList<ArchiveEntryInfo>,
            dirs: MutableSet<String>,
            path: String,
        ) {
            val parts = path.split('/').filter { it.isNotEmpty() }
            var current = ""
            for (i in 0 until parts.size - 1) {
                current = if (current.isEmpty()) parts[i] else "$current/${parts[i]}"
                if (dirs.add(current)) {
                    entries.add(
                        ArchiveEntryInfo(current, isDirectory = true, size = 0L, lastModified = 0L)
                    )
                }
            }
        }
    }
}

/** 统一成无前导斜杠、无 `..` 的展示路径；不安全的条目直接拒绝。 */
internal fun normalizeEntryName(raw: String): String? {
    var p = raw.replace('\\', '/').trim()
    while (p.startsWith("./")) p = p.removePrefix("./")
    p = p.trimStart('/').trimEnd('/')
    if (p.isBlank()) return null
    val parts = p.split('/').filter { it.isNotEmpty() && it != "." }
    if (parts.isEmpty() || parts.any { it == ".." }) return null
    return parts.joinToString("/")
}

private fun List<ArchiveEntryInfo>.sortedForDisplay(): List<ArchiveEntryInfo> =
    sortedWith(compareByDescending<ArchiveEntryInfo> { it.isDirectory }.thenBy { it.path })

/**
 * Commons Compress 的不同 1.x 补丁版本对 TarArchiveEntry 的 Kotlin 属性映射不一致，
 * 这里用方法名做一次兼容读取；失败就当作普通文件，不影响打开压缩包。
 */
private fun isTarLink(entry: TarArchiveEntry): Boolean =
    runCatching {
        entry.javaClass.getMethod("isSymbolicLink").invoke(entry) as Boolean
    }.getOrDefault(false) ||
        runCatching {
            entry.javaClass.getMethod("isLink").invoke(entry) as Boolean
        }.getOrDefault(false)

private fun tarModified(entry: TarArchiveEntry): Long =
    runCatching {
        (entry.javaClass.getMethod("getModTime").invoke(entry) as? java.util.Date)?.time ?: 0L
    }.getOrDefault(0L)
