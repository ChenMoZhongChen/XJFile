package com.cmzc.xj_file.app.core.image

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * 精简 TIFF 解码器 —— 平台的 ImageDecoder / BitmapFactory 都不认 TIFF。
 *
 * 覆盖相机、扫描仪、系统截图里最常见的子集，够日常看：
 * - 字节序 II/MM；8bit 灰度 / RGB / RGBA / 调色板
 * - 压缩：无压缩、LZW、Deflate(8/32946)、PackBits
 * - 多条带（strip）、PlanarConfiguration=1、Predictor=1/2
 *
 * 解不了（16bit、JPEG 压缩、CMYK、分块 tile…）就返回 null，由上层显示"打不开"，
 * 不会崩、也不会给出花屏。
 */
object TiffDecoder {

    fun decode(bytes: ByteArray, targetW: Int, targetH: Int): Bitmap? =
        runCatching { decodeInner(bytes, targetW, targetH) }.getOrNull()

    private fun decodeInner(b: ByteArray, tw: Int, th: Int): Bitmap? {
        if (b.size < 8) return null
        val le = b[0] == 0x49.toByte() && b[1] == 0x49.toByte()
        val be = b[0] == 0x4D.toByte() && b[1] == 0x4D.toByte()
        if (!le && !be) return null
        val r = R(b, le)
        if (r.u16(2) != 42) return null
        val ifdOff = r.i32(4)
        if (ifdOff <= 0 || ifdOff >= b.size) return null
        val tags = r.ifd(ifdOff)

        val width = tags.int(256) ?: return null
        val height = tags.int(257) ?: return null
        val bits = tags.arr(258) ?: intArrayOf(8)
        val compression = tags.int(259) ?: 1
        val photometric = tags.int(262) ?: 2
        val offsets = tags.arr(273) ?: return null
        val counts = tags.arr(279) ?: return null
        val samples = tags.int(277) ?: 1
        val rowsPerStrip = tags.int(278) ?: height
        val planar = tags.int(284) ?: 1
        val predictor = tags.int(317) ?: 1
        if (width <= 0 || height <= 0) return null
        if (planar != 1) return null
        // 先把 8bit 做对；16bit/1bit 这类先老实说解不了，避免花屏
        if (bits.any { it != 8 }) return null
        val bpp = samples
        val sample = maxOf(1, maxOf(width / tw.coerceAtLeast(1), height / th.coerceAtLeast(1)))
        val outW = ((width + sample - 1) / sample).coerceAtLeast(1)
        val outH = ((height + sample - 1) / sample).coerceAtLeast(1)
        val pixels = IntArray(outW * outH)
        var rowStart = 0

        for (s in offsets.indices) {
            if (rowStart >= height) break
            val off = offsets[s]
            val len = counts.getOrElse(s) { 0 }
            if (off < 0 || len <= 0 || off + len > b.size) return null
            val raw = decompress(b.copyOfRange(off, off + len), compression) ?: return null
            val rows = minOf(rowsPerStrip, height - rowStart)
            val need = rows * width * bpp
            if (raw.size < need) return null
            val data = if (predictor == 2) undoPredictor(raw, width, rows, bpp) else raw
            for (y in 0 until rows) {
                val destY = rowStart + y
                if (destY % sample != 0) continue
                val srcRow = y * width * bpp
                var x = 0
                while (x < width) {
                    val c = pixel(data, srcRow + x * bpp, photometric, samples, tags)
                    pixels[(destY / sample) * outW + (x / sample)] = c
                    x += sample
                }
            }
            rowStart += rows
        }
        if (rowStart <= 0) return null
        return Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
    }

    private fun pixel(data: ByteArray, i: Int, photometric: Int, samples: Int, tags: TagMap): Int {
        fun u(k: Int) = data[i + k].toInt() and 0xFF
        return when (photometric) {
            0 -> { // WhiteIsZero
                val g = 255 - u(0)
                argb(g, g, g, 255)
            }

            1 -> { // BlackIsZero
                val g = u(0)
                argb(g, g, g, 255)
            }

            2 -> { // RGB(A)
                val a = if (samples >= 4) u(3) else 255
                argb(u(0), u(1), u(2), a)
            }

            3 -> paletteColor(u(0), tags)

            else -> 0xFF000000.toInt()
        }
    }

    private fun paletteColor(index: Int, tags: TagMap): Int {
        val cm = tags.arr(320) ?: return 0xFF000000.toInt()
        val n = cm.size / 3
        if (index < 0 || index >= n) return 0xFF000000.toInt()
        return argb((cm[index] shr 8) and 0xFF, (cm[index + n] shr 8) and 0xFF, (cm[index + 2 * n] shr 8) and 0xFF, 255)
    }

    private fun argb(r: Int, g: Int, b: Int, a: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun decompress(src: ByteArray, compression: Int): ByteArray? = when (compression) {
        1 -> src
        5 -> lzw(src)
        8, 32946 -> inflate(src)
        32773 -> packBits(src)
        else -> null
    }

    private fun undoPredictor(src: ByteArray, width: Int, rows: Int, bpp: Int): ByteArray {
        val rowBytes = width * bpp
        for (y in 0 until rows) {
            val base = y * rowBytes
            for (i in bpp until rowBytes) {
                src[base + i] = (src[base + i] + src[base + i - bpp]).toByte()
            }
        }
        return src
    }

    /** TIFF 的 LZW：MSB 先出、9~12 位、提前一位换码长（early change）。 */
    private fun lzw(src: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(src.size * 4)
        val dict = arrayOfNulls<IntArray>(4096)
        for (i in 0 until 256) dict[i] = intArrayOf(i)
        var dictSize = 258
        var codeLen = 9
        var prev: IntArray? = null
        var bitBuf = 0L
        var bitCnt = 0
        var pos = 0
        while (true) {
            while (bitCnt < codeLen) {
                if (pos >= src.size) return out.toByteArray()
                bitBuf = (bitBuf shl 8) or (src[pos++].toLong() and 0xFF)
                bitCnt += 8
            }
            val code = ((bitBuf ushr (bitCnt - codeLen)) and ((1L shl codeLen) - 1)).toInt()
            bitCnt -= codeLen
            when (code) {
                256 -> {
                    for (i in 0 until 256) dict[i] = intArrayOf(i)
                    dictSize = 258
                    codeLen = 9
                    prev = null
                }

                257 -> return out.toByteArray()

                else -> {
                    val entry: IntArray = when {
                        code < dictSize && dict[code] != null -> dict[code]!!
                        prev != null -> prev!! + prev!![0]
                        else -> return null
                    }
                    for (v in entry) out.write(v)
                    if (prev != null && dictSize < 4096) {
                        dict[dictSize++] = prev!! + entry[0]
                        if (dictSize + 1 >= (1 shl codeLen) && codeLen < 12) codeLen++
                    }
                    prev = entry
                }
            }
        }
    }

    private fun inflate(src: ByteArray): ByteArray? {
        val inf = Inflater()
        inf.setInput(src)
        val out = ByteArrayOutputStream(src.size * 4)
        val buf = ByteArray(16 * 1024)
        try {
            var guard = 0
            while (!inf.finished() && guard++ < 100_000) {
                val n = inf.inflate(buf)
                if (n > 0) out.write(buf, 0, n)
                else if (inf.needsInput() || inf.needsDictionary()) break
            }
        } catch (t: Throwable) {
            return null
        } finally {
            inf.end()
        }
        return out.toByteArray()
    }

    private fun packBits(src: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(src.size * 4)
        var i = 0
        while (i < src.size) {
            val n = src[i++].toInt()
            when {
                n >= 0 -> {
                    val count = n + 1
                    for (k in 0 until count) if (i < src.size) out.write(src[i++].toInt())
                }

                n != -128 -> {
                    val count = -n + 1
                    if (i < src.size) {
                        val v = src[i++]
                        for (k in 0 until count) out.write(v.toInt())
                    }
                }
            }
        }
        return out.toByteArray()
    }

    private class TagMap(private val values: Map<Int, IntArray>) {
        fun int(tag: Int): Int? = values[tag]?.firstOrNull()
        fun arr(tag: Int): IntArray? = values[tag]
    }

    private class R(private val b: ByteArray, private val le: Boolean) {
        fun u16(o: Int): Int = if (le) {
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        } else {
            ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        }

        fun i32(o: Int): Int = if (le) {
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
        } else {
            ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
        }

        fun ifd(off: Int): TagMap {
            val count = u16(off)
            val map = HashMap<Int, IntArray>()
            for (i in 0 until count) {
                val e = off + 2 + i * 12
                if (e + 12 > b.size) break
                val tag = u16(e)
                val type = u16(e + 2)
                val n = i32(e + 4)
                if (n < 0 || n > (1 shl 20)) continue
                val unit = when (type) {
                    1 -> 1
                    3 -> 2
                    4 -> 4
                    else -> 4
                }
                val total = unit * n
                val dataOff = if (total <= 4) e + 8 else i32(e + 8)
                if (dataOff < 0 || dataOff + total > b.size) continue
                map[tag] = IntArray(n) { k ->
                    when (type) {
                        1 -> b[dataOff + k].toInt() and 0xFF
                        3 -> u16(dataOff + 2 * k)
                        4 -> i32(dataOff + 4 * k)
                        else -> 0
                    }
                }
            }
            return TagMap(map)
        }
    }
}
