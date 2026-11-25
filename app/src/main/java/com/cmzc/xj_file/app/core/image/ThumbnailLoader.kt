package com.cmzc.xj_file.app.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * 文件列表缩略图专用加载器。
 *
 * 和 ImageLoader 分开的原因：ImageLoader 为了 EXIF/动图/全屏查看，会把整份图片读进内存；
 * 文件列表只需要 22dp 的小图，滚动一屏十几张时那样读会明显卡。
 * 这里本地图片直接用 BitmapFactory 的 inSampleSize，按目标像素的 2 倍解码，
 * 再缩到实际显示尺寸；本地视频用 MediaMetadataRetriever 取一帧。
 */
object ThumbnailLoader {

    private const val MAX_CACHE = 160
    private val cache = LruCache<String, Bitmap>(MAX_CACHE)
    private val imagePermits = Semaphore(3)
    /** 视频取帧比图片重，单条车道，避免滚动时同时开一堆 MediaMetadataRetriever。 */
    private val videoPermits = Semaphore(1)

    suspend fun loadImage(file: File, targetPx: Int): Bitmap? =
        load(
            "img|${file.absolutePath}|${file.length()}|${file.lastModified()}|$targetPx",
            imagePermits,
        ) {
            decodeImage(file, targetPx)
        }

    suspend fun loadVideo(file: File, targetPx: Int): Bitmap? =
        load(
            "vid|${file.absolutePath}|${file.length()}|${file.lastModified()}|$targetPx",
            videoPermits,
        ) {
            decodeVideo(file, targetPx)
        }

    private suspend fun load(
        key: String,
        permits: Semaphore,
        decode: () -> Bitmap?,
    ): Bitmap? {
        cache.get(key)?.let { return it }
        return permits.withPermit {
            withContext(Dispatchers.IO) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val bitmap = runCatching { decode() }.getOrNull() ?: return@withContext null
                kotlin.coroutines.coroutineContext.ensureActive()
                cache.put(key, bitmap)
                bitmap
            }
        }
    }

    private fun decodeImage(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        return scaleDown(decoded, targetPx)
    }

    private fun decodeVideo(file: File, targetPx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val side = (targetPx * 2).coerceAtLeast(targetPx)
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    1_000_000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    side,
                    side
                )
            } else {
                retriever.getFrameAtTime(
                    1_000_000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
            } ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame?.let { scaleDown(it, targetPx) }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        var sample = 1
        val target = max(targetPx, 1) * 2
        while (width / sample > target || height / sample > target) {
            sample *= 2
        }
        return sample
    }

    private fun scaleDown(bitmap: Bitmap, targetPx: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= targetPx) return bitmap
        val ratio = targetPx.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
