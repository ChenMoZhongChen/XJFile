package com.cmzc.xj_file.app.core.image

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * 读取 APK / EXE 自身携带的图标；没有图标时返回 null，由 FileTypeIcon 回退默认图标。
 * 只读本地文件，远程文件无法直接解析，继续使用默认图标。
 */
object AppIconLoader {

    private val cache = LruCache<String, Bitmap>(48)

    suspend fun load(context: Context, file: File, targetPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}|$targetPx"
            cache.get(key)?.let { return@withContext it }
            val bitmap = runCatching {
                when (file.extension.lowercase()) {
                    "apk" -> loadApk(context, file, targetPx)
                    "exe" -> loadExe(file, targetPx)
                    else -> null
                }
            }.getOrNull() ?: return@withContext null
            kotlin.coroutines.coroutineContext.ensureActive()
            cache.put(key, bitmap)
            bitmap
        }

    private fun loadApk(context: Context, file: File, targetPx: Int): Bitmap? {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_ACTIVITIES
        ) ?: return null
        val app = info.applicationInfo ?: return null
        val iconRes = app.icon
        if (iconRes == 0) return null
        app.sourceDir = file.absolutePath
        app.publicSourceDir = file.absolutePath
        // 优先直接从 APK 资源表取图标，避免 PackageManager 在归档模式下回退默认图标。
        val drawable = runCatching { loadArchiveDrawable(context, file, iconRes) }.getOrNull()
            ?: runCatching { pm.getApplicationIcon(app) }.getOrNull()
            ?: return null
        val side = targetPx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, side, side)
        drawable.draw(canvas)
        return bitmap
    }

    private fun loadArchiveDrawable(context: Context, file: File, iconRes: Int): Drawable? {
        if (iconRes == 0) return null
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
        addAssetPath.invoke(assets, file.absolutePath)
        val resources = Resources(
            assets,
            context.resources.displayMetrics,
            context.resources.configuration,
        )
        return resources.getDrawable(iconRes, null)
    }

    private fun loadExe(file: File, targetPx: Int): Bitmap? {
        val raf = RandomAccessFile(file, "r")
        return try {
            PeIconReader(raf).readLargestIcon()?.let { scaleToTarget(it, targetPx) }
        } finally {
            runCatching { raf.close() }
        }
    }

    /** 远程图标读取器把 PE 头和资源节拼成稀疏文件后，复用这里的解析。 */
    internal fun loadExeFile(file: File, targetPx: Int): Bitmap? = loadExe(file, targetPx)

    private fun scaleToTarget(source: Bitmap, targetPx: Int): Bitmap {
        val side = targetPx.coerceAtLeast(1)
        if (source.width == side && source.height == side) return source
        val output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val scale = minOf(side.toFloat() / source.width, side.toFloat() / source.height)
        val drawW = source.width * scale
        val drawH = source.height * scale
        val left = (side - drawW) / 2f
        val top = (side - drawH) / 2f
        canvas.drawBitmap(
            source,
            null,
            android.graphics.RectF(left, top, left + drawW, top + drawH),
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true
            },
        )
        return output
    }

    private class PeIconReader(private val raf: RandomAccessFile) {
        private val fileLength = raf.length()
        private var resourceRva = 0L
        private val sections = mutableListOf<Section>()

        private data class Section(val va: Long, val virtualSize: Long, val rawSize: Long, val rawPtr: Long)
        private data class Leaf(val id: Int, val dataRva: Long, val size: Int)

        fun readLargestIcon(): Bitmap? {
            if (fileLength < 0x100) return null
            if (u16(0) != 0x5A4D) return null
            val pe = u32(0x3C)
            if (pe + 24 > fileLength || u32(pe) != 0x00004550L) return null
            val coff = pe + 4
            val sectionCount = u16(coff + 2)
            val optionalSize = u16(coff + 16)
            val optional = coff + 20
            if (optional + optionalSize > fileLength) return null
            val magic = u16(optional)
            val dataDir = optional + if (magic == 0x20B) 112L else 96L
            resourceRva = u32(dataDir + 16)
            if (resourceRva == 0L) return null
            val sectionTable = optional + optionalSize
            repeat(sectionCount) { i ->
                val off = sectionTable + i * 40L
                if (off + 40 > fileLength) return@repeat
                sections += Section(
                    va = u32(off + 12),
                    virtualSize = u32(off + 8),
                    rawSize = u32(off + 16),
                    rawPtr = u32(off + 20),
                )
            }
            val groups = leaves(14)
            val icons = leaves(3).associateBy { it.id }
            if (groups.isEmpty() || icons.isEmpty()) return null

            var best: Bitmap? = null
            var bestArea = 0
            for (group in groups) {
                val data = bytes(mapRva(group.dataRva) ?: continue, group.size) ?: continue
                if (data.size < 6) continue
                val count = u16(data, 4)
                var p = 6
                repeat(count) {
                    if (p + 14 > data.size) return@repeat
                    val width = (data[p].toInt() and 0xFF).let { if (it == 0) 256 else it }
                    val height = (data[p + 1].toInt() and 0xFF).let { if (it == 0) 256 else it }
                    val bytesInRes = u32(data, p + 8).toInt()
                    val iconId = u16(data, p + 12)
                    p += 14
                    if (bytesInRes <= 8 || bytesInRes > 4 * 1024 * 1024) return@repeat
                    val icon = icons[iconId] ?: return@repeat
                    val offset = mapRva(icon.dataRva) ?: return@repeat
                    val iconBytes = bytes(offset, icon.size.coerceAtMost(bytesInRes)) ?: return@repeat
                    val bitmap = if (isPng(iconBytes)) {
                        BitmapFactory.decodeByteArray(
                            iconBytes,
                            0,
                            iconBytes.size,
                            BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            }
                        )
                    } else {
                        decodeDib(iconBytes)
                    } ?: return@repeat
                    val area = width * height
                    if (area > bestArea) {
                        bestArea = area
                        best = bitmap
                    }
                }
            }
            return best
        }

        private fun leaves(typeId: Int): List<Leaf> {
            val root = mapRva(resourceRva) ?: return emptyList()
            val typeEntry = entries(root).firstOrNull { it.first == typeId } ?: return emptyList()
            val typeDir = mapRva(resourceRva + typeEntry.second) ?: return emptyList()
            val result = mutableListOf<Leaf>()
            for ((nameId, rel) in entries(typeDir)) {
                val node = mapRva(resourceRva + rel) ?: continue
                for ((_, langRel) in entries(node)) {
                    val dataEntry = mapRva(resourceRva + langRel) ?: continue
                    val dataRva = u32(dataEntry)
                    val size = u32(dataEntry + 4).toInt()
                    if (dataRva != 0L && size > 0) {
                        result += Leaf(nameId, dataRva, size)
                    }
                }
            }
            return result
        }

        /** 返回 (ID, 相对资源根的偏移)；只处理整数 ID。 */
        private fun entries(dirOffset: Long): List<Pair<Int, Long>> {
            if (dirOffset + 16 > fileLength) return emptyList()
            val named = u16(dirOffset + 12)
            val ids = u16(dirOffset + 14)
            val total = named + ids
            val result = mutableListOf<Pair<Int, Long>>()
            repeat(total) { i ->
                val off = dirOffset + 16 + i * 8L
                if (off + 8 > fileLength) return@repeat
                val rawId = u32(off)
                if (rawId and 0x80000000L != 0L) return@repeat
                val rel = u32(off + 4) and 0x7FFFFFFFL
                result += (rawId.toInt() to rel)
            }
            return result
        }

        private fun mapRva(rva: Long): Long? {
            for (s in sections) {
                val span = maxOf(s.virtualSize, s.rawSize)
                if (rva >= s.va && rva < s.va + span) {
                    val delta = rva - s.va
                    if (delta >= s.rawSize) return null
                    val off = s.rawPtr + delta
                    return if (off < fileLength) off else null
                }
            }
            return null
        }

        private fun u16(off: Long): Int {
            if (off < 0 || off + 2 > fileLength) return -1
            raf.seek(off)
            return raf.read() or (raf.read() shl 8)
        }

        private fun u16(data: ByteArray, off: Int): Int {
            if (off + 2 > data.size) return 0
            return (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
        }

        private fun u32(off: Long): Long {
            if (off < 0 || off + 4 > fileLength) return 0
            raf.seek(off)
            var value = 0L
            repeat(4) { value = value or ((raf.read().toLong() and 0xFFL) shl (it * 8)) }
            return value
        }

        private fun u32(data: ByteArray, off: Int): Long {
            if (off + 4 > data.size) return 0
            var value = 0L
            repeat(4) { value = value or ((data[off + it].toLong() and 0xFFL) shl (it * 8)) }
            return value
        }

        private fun bytes(off: Long, size: Int): ByteArray? {
            if (off < 0 || size < 0 || off + size > fileLength) return null
            val data = ByteArray(size)
            raf.seek(off)
            raf.readFully(data)
            return data
        }
    }

    private fun isPng(data: ByteArray): Boolean {
        if (data.size < 8) return false
        val sig = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return sig.indices.all { (data[it].toInt() and 0xFF) == sig[it] }
    }

    /** RT_ICON 里没有 PNG 时通常是 BITMAPINFOHEADER + 像素 + AND 掩码。 */
    private fun decodeDib(data: ByteArray): Bitmap? {
        if (data.size < 40) return null
        val headerSize = le32(data, 0)
        val width = le32(data, 4)
        val rawHeight = le32(data, 8)
        val bpp = le16(data, 14)
        val compression = le32(data, 16)
        if (headerSize < 40 || width <= 0 || width > 1024 || rawHeight == 0) return null
        if (compression != 0 || bpp !in intArrayOf(1, 4, 8, 24, 32)) return null
        val height = (kotlin.math.abs(rawHeight) / 2).coerceAtMost(1024)
        if (height <= 0) return null

        val colorsUsed = le32(data, 32)
        val paletteCount = if (bpp <= 8) {
            if (colorsUsed in 1..256) colorsUsed else 1 shl bpp
        } else {
            0
        }
        val paletteOffset = headerSize
        val xorOffset = paletteOffset + paletteCount * 4
        val xorStride = ((width * bpp + 31) / 32) * 4
        val xorSize = xorStride * height
        if (xorOffset < 0 || xorOffset + xorSize > data.size) return null
        val maskOffset = xorOffset + xorSize
        val maskStride = ((width + 31) / 32) * 4

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val srcY = if (rawHeight > 0) height - 1 - y else y
            var p = xorOffset + srcY * xorStride
            for (x in 0 until width) {
                var r = 0
                var g = 0
                var b = 0
                var a = 255
                when (bpp) {
                    32 -> {
                        b = data[p].toInt() and 0xFF
                        g = data[p + 1].toInt() and 0xFF
                        r = data[p + 2].toInt() and 0xFF
                        a = data[p + 3].toInt() and 0xFF
                        p += 4
                    }
                    24 -> {
                        b = data[p].toInt() and 0xFF
                        g = data[p + 1].toInt() and 0xFF
                        r = data[p + 2].toInt() and 0xFF
                        p += 3
                    }
                    else -> {
                        val index = when (bpp) {
                            8 -> data[p++].toInt() and 0xFF
                            4 -> {
                                val value = data[p].toInt() and 0xFF
                                val index = if (x % 2 == 0) value ushr 4 else value and 0x0F
                                if (x % 2 == 1) p++
                                index
                            }
                            else -> {
                                val value = data[p].toInt() and 0xFF
                                val index = (value ushr (7 - x % 8)) and 1
                                if (x % 8 == 7) p++
                                index
                            }
                        }
                        val c = paletteOffset + index * 4
                        if (c + 3 < data.size) {
                            b = data[c].toInt() and 0xFF
                            g = data[c + 1].toInt() and 0xFF
                            r = data[c + 2].toInt() and 0xFF
                        }
                    }
                }
                // AND 掩码：1 表示透明
                val maskPos = maskOffset + srcY * maskStride + x / 8
                if (maskPos < data.size) {
                    val maskBit = (data[maskPos].toInt() ushr (7 - x % 8)) and 1
                    if (maskBit == 1) a = 0
                }
                pixels[y * width + x] = Color.argb(a, r, g, b)
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
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
