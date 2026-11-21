package com.cmzc.xj_file.app.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * RAW 系（CR2 / NEF / ARW / DNG / RAW）的"能看"方案。
 *
 * 真正解析传感器 RAW 需要 dcraw/libraw 级别的东西，代价太大；但这些文件里几乎都嵌了
 * 一张 JPEG 预览（相机缩略图/全尺寸预览）。这里扫出**最大**的那段 JPEG 来显示，
 * 用于"先看一眼拍的是什么"足够，画质不如原图，但比打不开强。
 */
object RawPreview {

    fun decode(bytes: ByteArray, targetW: Int, targetH: Int): Bitmap? {
        val jpeg = largestEmbeddedJpeg(bytes) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
    }

    /** 找 FFD8FF … FFD9 之间最长的一段。文件不超过 96MB（ImageLoader 的上限），扫描可接受。 */
    private fun largestEmbeddedJpeg(bytes: ByteArray): ByteArray? {
        var best: ByteArray? = null
        var i = 0
        while (i + 3 < bytes.size) {
            if ((bytes[i].toInt() and 0xFF) == 0xFF &&
                (bytes[i + 1].toInt() and 0xFF) == 0xD8 &&
                (bytes[i + 2].toInt() and 0xFF) == 0xFF
            ) {
                var j = i + 3
                var found = -1
                while (j + 1 < bytes.size) {
                    if ((bytes[j].toInt() and 0xFF) == 0xFF && (bytes[j + 1].toInt() and 0xFF) == 0xD9) {
                        found = j + 2
                        break
                    }
                    j++
                }
                if (found > 0) {
                    val len = found - i
                    if (best == null || len > best.size) best = bytes.copyOfRange(i, found)
                    i = found
                    continue
                }
            }
            i++
        }
        return best
    }

    private fun sampleSize(w: Int, h: Int, tw: Int, th: Int): Int {
        if (w <= 0 || h <= 0 || tw <= 0 || th <= 0) return 1
        return maxOf(w.toFloat() / tw, h.toFloat() / th).toInt().coerceAtLeast(1)
    }
}
