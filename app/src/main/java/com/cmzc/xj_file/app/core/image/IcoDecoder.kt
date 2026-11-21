package com.cmzc.xj_file.app.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ICO / CUR 解码。
 *
 * 平台解码器不认 ICO。文件本身就是一个"目录 + 若干帧"，每帧要么是 PNG（直接交给
 * BitmapFactory），要么是 BMP 的 DIB 裸数据（缺 14 字节文件头，需要补上）。
 * 这里挑面积最大的一帧来解——预览场景已经够了。
 */
object IcoDecoder {

    fun decode(bytes: ByteArray, targetW: Int, targetH: Int): Bitmap? = runCatching {
        if (bytes.size < 6) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.getShort(0).toInt() != 0) return null
        val type = buf.getShort(2).toInt()
        if (type != 1 && type != 2) return null // 1=ICO, 2=CUR
        val count = buf.getShort(4).toInt() and 0xFFFF
        if (count <= 0) return null

        var bestOff = -1
        var bestLen = -1
        var bestArea = -1
        for (i in 0 until count) {
            val e = 6 + i * 16
            if (e + 16 > bytes.size) break
            val w = buf.get(e).toInt() and 0xFF
            val h = buf.get(e + 1).toInt() and 0xFF
            val area = (if (w == 0) 256 else w) * (if (h == 0) 256 else h)
            val len = buf.getInt(e + 8)
            val off = buf.getInt(e + 12)
            if (len > 0 && off >= 0 && off + len <= bytes.size &&
                (area > bestArea || (area == bestArea && len > bestLen))
            ) {
                bestArea = area
                bestLen = len
                bestOff = off
            }
        }
        if (bestOff < 0) return null
        val frame = bytes.copyOfRange(bestOff, bestOff + bestLen)
        if (frame.size > 8 &&
            frame[0] == 0x89.toByte() && frame[1] == 'P'.code.toByte() &&
            frame[2] == 'N'.code.toByte() && frame[3] == 'G'.code.toByte()
        ) {
            return BitmapFactory.decodeByteArray(frame, 0, frame.size)
        }
        decodeDib(frame, targetW, targetH)
    }.getOrNull()

    /**
     * ICO 里的 BMP 帧：DIB 高度是"图像 + AND 掩码"的两倍。
     * 直接补文件头解会得到上下两张拼在一起、底下一条全黑；这里把高度改回一半。
     */
    private fun decodeDib(dib: ByteArray, targetW: Int, targetH: Int): Bitmap? {
        if (dib.size < 40) return null
        val headerSize = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
        if (headerSize < 40) return null
        val copy = dib.copyOf()
        val bi = ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN)
        val h = bi.getInt(8)
        if (h != Int.MIN_VALUE) bi.putInt(8, h / 2)
        val bitCount = bi.getShort(14).toInt() and 0xFFFF
        val paletteEntries = if (bitCount <= 8) (1 shl bitCount) else 0
        val pixelOffset = 14 + headerSize + paletteEntries * 4
        val fileSize = 14 + copy.size
        val out = ByteArrayOutputStream(fileSize)
        out.write(byteArrayOf('B'.code.toByte(), 'M'.code.toByte()))
        out.write(le32(fileSize))
        out.write(le32(0))
        out.write(le32(pixelOffset))
        out.write(copy)
        val bmp = out.toByteArray()
        return BitmapFactory.decodeByteArray(bmp, 0, bmp.size)
    }

    private fun le32(v: Int) = byteArrayOf(
        v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()
    )
}
