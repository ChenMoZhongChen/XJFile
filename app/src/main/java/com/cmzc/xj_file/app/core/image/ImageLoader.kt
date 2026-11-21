package com.cmzc.xj_file.app.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.util.Log
import android.util.LruCache
import com.cmzc.xj_file.app.core.playback.PlaybackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * 图片解码：把「远程/本地」统一成一张能直接显示的 Bitmap。
 *
 * 两条硬要求，决定了这里不能用最简单的 `BitmapFactory.decodeStream`：
 *
 * 1. **不能 OOM**。手机照片动辄 4000×3000，全尺寸 ARGB_8888 就是 48 MB，
 *    连开两张就崩。所以先按目标尺寸算降采样倍数再解。
 * 2. **EXIF 要摆正**。手机拍的照片常常是「横着存、竖着看」，BitmapFactory
 *    完全忽略 EXIF 方向，必须靠 ImageDecoder（它按规范自动旋转）。
 *
 * 流程：整份读进内存（图片通常几 MB；超过 96 MB 直接拒绝，别拿手机内存硬扛）
 * → ImageDecoder 按目标尺寸降采样解码 → 失败再退 BitmapFactory（兜住 BMP 之类冷门格式）。
 */
object ImageLoader {

    private const val TAG = "XjImageLoader"

    /** 超过这个大小就不内置打开了：正常照片/截图不会有这么大，多半不是图片。 */
    private const val MAX_BYTES = 96L * 1024 * 1024

    /**
     * 解码结果缓存：翻页来回滑时不用重读整份文件。
     * 按字节计大小、上限 48 MB —— 内存比"再读一次 SMB"值钱。
     */
    private val pageCache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun clearCache() = pageCache.evictAll()

    /**
     * 动图（GIF / 动图 WebP）专用入口。
     *
     * 用 `decodeDrawable` 而不是 `decodeBitmap`：只有前者能拿到 [AnimatedImageDrawable]。
     * 静态图会返回 BitmapDrawable —— 这时返回 null，让调用方走静态路径
     * （静态路径带缓存、降采样更省）。
     *
     * 调用方只对 gif/webp 调它，避免给 jpg/png 白读一遍文件。
     */
    suspend fun loadAnimated(
        context: Context,
        source: PlaybackSource,
        targetWidth: Int,
        targetHeight: Int,
    ): AnimatedImageDrawable? = withContext(Dispatchers.IO) {
        val bytes = source.openInputStream(context).use { readAll(it, source.sizeBytes) { _, _ -> } }
        runCatching {
            val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            val drawable = ImageDecoder.decodeDrawable(src) { decoder, info, _ ->
                decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height, targetWidth, targetHeight))
            }
            (drawable as? AnimatedImageDrawable)?.apply {
                repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                start()
            }
        }.onFailure {
            Log.e(TAG, "动图解码失败，改走静态路径", it)
        }.getOrNull()
    }

    /**
     * @param targetWidth/targetHeight 期望显示区域（像素）。只用它算降采样倍数，
     *   不参与缩放——真正的缩放交给界面层。
     * @param onProgress (已读字节, 总字节)；总字节未知时为 -1。
     */
    suspend fun load(
        context: Context,
        source: PlaybackSource,
        targetWidth: Int,
        targetHeight: Int,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Bitmap = withContext(Dispatchers.IO) {
        // key 里带上目标尺寸：查看页要的是全屏大图，缩略图条要的是 160px，
        // 两者绝不能互相命中，否则缩略图会把大图撑花、大图会被缩略图喂成糊的。
        val key = "${source.uri}@${targetWidth}x${targetHeight}"
        pageCache.get(key)?.let { return@withContext it }

        val bytes = source.openInputStream(context).use { input ->
            readAll(input, source.sizeBytes, onProgress)
        }
        val bitmap = decode(bytes, source.displayName, targetWidth, targetHeight)
        pageCache.put(key, bitmap)
        bitmap
    }

    private fun readAll(
        input: InputStream,
        expected: Long,
        onProgress: (Long, Long) -> Unit
    ): ByteArray {
        val initial = if (expected in 1..(4L * 1024 * 1024)) expected.toInt() else 64 * 1024
        val out = ByteArrayOutputStream(initial)
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            if (total > MAX_BYTES) throw IOException("文件超过 96 MB，不内置打开")
            onProgress(total, expected)
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray, name: String, tw: Int, th: Int): Bitmap {
        // 平台解码器不认的格式，先走自己的解码器（SVG / TIFF / ICO / RAW 预览）
        val ext = name.substringAfterLast('.', "").lowercase()
        customDecode(ext, bytes, tw, th)?.let { return it }

        // minSdk 29 → ImageDecoder 一定在。它按 EXIF 摆正、按 sampleSize 降采样。
        try {
            val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                // 用软件位图：硬件位图在缩放/复用路径上限制多，看图场景不值当
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // API 28 的 ImageInfo 没有 width/height 属性，统一用 size
                decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height, tw, th))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "ImageDecoder 解不开，退到 BitmapFactory", t)
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            // 扩展名可能写错（比如 .jpg 其实是 TIFF/ICO）：再按文件头兜一次
            magicDecode(bytes, tw, th)?.let { return it }
            throw IOException("这个文件不是图片")
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, tw, th)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IOException("图片解码失败")
    }

    /** 扩展名对应的额外解码器；不认识的格式返回 null，交给平台。 */
    private fun customDecode(ext: String, bytes: ByteArray, tw: Int, th: Int): Bitmap? = when (ext) {
        "svg" -> SvgDecoder.decode(bytes, tw, th)
        "ico", "cur" -> IcoDecoder.decode(bytes, tw, th)
        "tif", "tiff" -> TiffDecoder.decode(bytes, tw, th)
        "raw", "cr2", "nef", "arw", "dng" -> RawPreview.decode(bytes, tw, th)
        else -> null
    }

    /** 只看文件头：II*\0 / MM\0* = TIFF；00 00 01 00 = ICO。 */
    private fun magicDecode(bytes: ByteArray, tw: Int, th: Int): Bitmap? {
        if (bytes.size < 4) return null
        val tiff = (bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x2A.toByte() && bytes[3] == 0x00.toByte()) ||
            (bytes[0] == 0x4D.toByte() && bytes[1] == 0x4D.toByte() &&
                bytes[2] == 0x00.toByte() && bytes[3] == 0x2A.toByte())
        if (tiff) return TiffDecoder.decode(bytes, tw, th)
        if (bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
            bytes[2] == 1.toByte() && bytes[3] == 0.toByte()
        ) {
            return IcoDecoder.decode(bytes, tw, th)
        }
        return null
    }

    /**
     * 降采样倍数：取「宽高里更吃紧的那一边」的倒数。
     * 例：4000×3000 放进 1260×2800 的屏幕 → max(4000/1260, 3000/2800)=3.17 → 取 3。
     */
    private fun sampleSize(w: Int, h: Int, tw: Int, th: Int): Int {
        if (w <= 0 || h <= 0 || tw <= 0 || th <= 0) return 1
        val divisor = maxOf(w.toFloat() / tw, h.toFloat() / th)
        return divisor.toInt().coerceAtLeast(1)
    }
}
