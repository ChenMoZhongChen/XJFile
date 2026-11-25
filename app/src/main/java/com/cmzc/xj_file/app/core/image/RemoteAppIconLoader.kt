package com.cmzc.xj_file.app.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.cmzc.xj_file.app.core.fs.RemoteFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.InflaterInputStream
import kotlin.math.min

/**
 * 远程 APK / EXE 图标读取器。
 *
 * 不下载整份文件：
 * - APK 只读 ZIP 尾部的中央目录，再按偏移取候选的 launcher PNG/WebP；
 * - EXE 只读 PE 头、资源节，再把这两段拼成稀疏临时文件交给本地 PE 解析器。
 *
 * 依赖 [RemoteFileSystem.openInputFrom] 的服务端偏移读。
 */
object RemoteAppIconLoader {

    private const val BLOCK = 64 * 1024
    private const val MAX_CENTRAL = 16 * 1024 * 1024
    private const val MAX_ENTRY = 8 * 1024 * 1024
    private const val MAX_RESOURCE = 64 * 1024 * 1024

    private val cache = LruCache<String, Bitmap>(32)

    suspend fun load(
        fs: RemoteFileSystem,
        path: String,
        size: Long,
        extension: String,
        targetPx: Int,
        tempDir: File,
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (size <= 0) return@withContext null
        val key = "${fs.tag}|$path|$size|$extension|$targetPx"
        cache.get(key)?.let { return@withContext it }
        val reader = FsRandomReader(fs, path, size)
        val bitmap = runCatching {
            when (extension.lowercase()) {
                "apk" -> loadApk(reader)
                "exe" -> loadExe(reader, tempDir, targetPx)
                else -> null
            }
        }.getOrNull() ?: return@withContext null
        cache.put(key, bitmap)
        bitmap
    }

    // ---------------- APK ZIP ----------------

    private suspend fun loadApk(reader: FsRandomReader): Bitmap? {
        val tailSize = min(reader.size, 65_557L).toInt()
        if (tailSize < 22) return null
        val tail = reader.read(reader.size - tailSize, tailSize)
        val eocd = findLastSignature(tail, 0x06054B50) ?: return null
        if (eocd + 22 > tail.size) return null
        val centralSize = le32(tail, eocd + 12).toLong()
        val centralOffset = le32(tail, eocd + 16).toLong()
        if (centralSize <= 0 || centralSize > MAX_CENTRAL || centralOffset < 0) return null
        if (centralOffset + centralSize > reader.size) return null
        val central = reader.read(centralOffset, centralSize.toInt())
        val candidates = mutableListOf<ZipEntry>()
        var p = 0
        while (p + 46 <= central.size) {
            if (le32(central, p).toLong() != 0x02014B50L) break
            val method = le16(central, p + 10)
            val compSize = le32(central, p + 20).toLong()
            val nameLen = le16(central, p + 28)
            val extraLen = le16(central, p + 30)
            val commentLen = le16(central, p + 32)
            val localOffset = le32(central, p + 42).toLong()
            if (p + 46 + nameLen > central.size) break
            val name = String(central, p + 46, nameLen, Charsets.UTF_8)
            if (isIconEntry(name) && compSize in 1..MAX_ENTRY.toLong()) {
                candidates += ZipEntry(name, method, compSize, localOffset)
            }
            p += 46 + nameLen + extraLen + commentLen
        }
        var best: Bitmap? = null
        var bestArea = 0
        for (entry in candidates.take(16)) {
            val data = readZipEntry(reader, entry) ?: continue
            val bitmap = BitmapFactory.decodeByteArray(
                data, 0, data.size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            ) ?: continue
            val area = bitmap.width * bitmap.height
            if (area > bestArea) {
                bestArea = area
                best = bitmap
            }
        }
        return best
    }

    private data class ZipEntry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val localOffset: Long,
    )

    private fun isIconEntry(name: String): Boolean {
        val lower = name.lowercase()
        if (!lower.startsWith("res/")) return false
        val iconish = lower.contains("ic_launcher") ||
            lower.contains("ic_launcher_round") ||
            lower.contains("app_icon") ||
            lower.contains("/icon") ||
            lower.contains("logo")
        if (!iconish) return false
        return lower.endsWith(".png") || lower.endsWith(".webp") ||
            lower.endsWith(".jpg") || lower.endsWith(".jpeg")
    }

    private suspend fun readZipEntry(reader: FsRandomReader, entry: ZipEntry): ByteArray? {
        if (entry.localOffset < 0 || entry.localOffset + 30 > reader.size) return null
        val local = reader.read(entry.localOffset, 30)
        if (le32(local, 0).toLong() != 0x04034B50L) return null
        val nameLen = le16(local, 26)
        val extraLen = le16(local, 28)
        val dataOffset = entry.localOffset + 30 + nameLen + extraLen
        if (dataOffset < 0 || dataOffset + entry.compressedSize > reader.size) return null
        val compressed = reader.read(dataOffset, entry.compressedSize.toInt())
        return when (entry.method) {
            0 -> compressed
            8 -> inflate(compressed)
            else -> null
        }
    }

    private fun inflate(data: ByteArray): ByteArray? {
        return runCatching {
            InflaterInputStream(data.inputStream()).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_ENTRY) return null
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        }.getOrNull()
    }

    // ---------------- EXE PE ----------------

    private suspend fun loadExe(
        reader: FsRandomReader,
        tempDir: File,
        targetPx: Int,
    ): Bitmap? {
        val headerSize = min(reader.size, 64L * 1024L).toInt()
        if (headerSize < 0x100) return null
        val header = reader.read(0, headerSize)
        if (le16(header, 0) != 0x5A4D) return null
        val pe = le32(header, 0x3C).toLong()
        if (pe + 24 > header.size || le32(header, pe.toInt()).toLong() != 0x00004550L) return null
        val coff = pe + 4
        val sectionCount = le16(header, coff.toInt() + 2)
        val optionalSize = le16(header, coff.toInt() + 16)
        val optional = coff + 20
        if (optional + optionalSize > header.size) return null
        val magic = le16(header, optional.toInt())
        val dataDir = optional + if (magic == 0x20B) 112L else 96L
        val resourceRva = le32(header, (dataDir + 16).toInt()).toLong()
        if (resourceRva == 0L) return null
        val sectionTable = optional + optionalSize
        var resourceRaw = -1L
        var resourceSize = 0L
        repeat(sectionCount) { i ->
            val off = sectionTable + i * 40L
            if (off + 40 > header.size) return@repeat
            val virtualSize = le32(header, (off + 8).toInt()).toLong()
            val va = le32(header, (off + 12).toInt()).toLong()
            val rawSize = le32(header, (off + 16).toInt()).toLong()
            val rawPtr = le32(header, (off + 20).toInt()).toLong()
            val span = maxOf(virtualSize, rawSize)
            if (resourceRva >= va && resourceRva < va + span) {
                val delta = resourceRva - va
                if (delta < rawSize) {
                    resourceRaw = rawPtr + delta
                    resourceSize = min(rawSize - delta, MAX_RESOURCE.toLong())
                }
            }
        }
        if (resourceRaw < 0 || resourceSize <= 0) return null
        val resource = reader.read(resourceRaw, resourceSize.toInt())
        val temp = File.createTempFile("xj-appicon", ".exe", tempDir)
        return try {
            RandomAccessFile(temp, "rw").use { raf ->
                raf.setLength(reader.size)
                raf.seek(0)
                raf.write(header)
                raf.seek(resourceRaw)
                raf.write(resource)
            }
            AppIconLoader.loadExeFile(temp, targetPx)
        } finally {
            temp.delete()
        }
    }

    // ---------------- helper ----------------

    private class FsRandomReader(
        private val fs: RemoteFileSystem,
        private val path: String,
        val size: Long,
    ) {
        private val blocks = object : LinkedHashMap<Long, ByteArray>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
                size > 8
        }

        suspend fun read(offset: Long, count: Int): ByteArray {
            require(offset >= 0 && count >= 0)
            if (count == 0) return ByteArray(0)
            if (offset + count > size) throw IllegalArgumentException("read past EOF")
            val out = ByteArray(count)
            var copied = 0
            while (copied < count) {
                val absolute = offset + copied
                val blockStart = absolute / BLOCK * BLOCK
                val block = blocks[blockStart] ?: readBlock(blockStart).also { blocks[blockStart] = it }
                val begin = (absolute - blockStart).toInt()
                val n = min(count - copied, block.size - begin)
                if (n <= 0) break
                System.arraycopy(block, begin, out, copied, n)
                copied += n
            }
            return out
        }

        private suspend fun readBlock(offset: Long): ByteArray {
            val want = min(BLOCK.toLong(), size - offset).toInt()
            if (want <= 0) return ByteArray(0)
            return fs.openInputFrom(path, offset).use { input ->
                readExactly(input, want)
            }
        }
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var pos = 0
        while (pos < count) {
            val n = input.read(out, pos, count - pos)
            if (n <= 0) break
            pos += n
        }
        return if (pos == count) out else out.copyOf(pos)
    }

    private fun findLastSignature(data: ByteArray, signature: Int): Int? {
        var i = data.size - 22
        while (i >= 0) {
            if (le32(data, i).toLong() == signature.toLong()) return i
            i--
        }
        return null
    }

    private fun le16(data: ByteArray, off: Int): Int {
        if (off + 2 > data.size) return 0
        return (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun le32(data: ByteArray, off: Int): Int {
        if (off + 4 > data.size) return 0
        return (data[off].toInt() and 0xFF) or
            ((data[off + 1].toInt() and 0xFF) shl 8) or
            ((data[off + 2].toInt() and 0xFF) shl 16) or
            ((data[off + 3].toInt() and 0xFF) shl 24)
    }
}
