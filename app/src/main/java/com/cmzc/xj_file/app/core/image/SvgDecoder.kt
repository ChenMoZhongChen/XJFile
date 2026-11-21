package com.cmzc.xj_file.app.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import java.io.ByteArrayInputStream

/**
 * SVG 解码：平台的 ImageDecoder / BitmapFactory 都不认 SVG。
 *
 * 用 androidSVG 把矢量渲染成位图。渲染尺寸取"文档尺寸 × 适配屏幕的缩放"，
 * 上限 2 倍，避免超大画布把内存吃穿。
 */
object SvgDecoder {

    fun decode(bytes: ByteArray, targetW: Int, targetH: Int): Bitmap? = runCatching {
        val svg = SVG.getFromInputStream(ByteArrayInputStream(bytes))
        var w = svg.documentWidth
        var h = svg.documentHeight
        if (w <= 0f || h <= 0f) {
            w = targetW.toFloat().coerceAtLeast(1f)
            h = targetH.toFloat().coerceAtLeast(1f)
        }
        val scale = minOf(targetW / w, targetH / h).coerceIn(0.01f, 2f)
        val outW = (w * scale).toInt().coerceAtLeast(1)
        val outH = (h * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawPicture(svg.renderToPicture(outW, outH))
        bitmap
    }.getOrNull()
}
