package com.cmzc.xj_file.app.core.image

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.LruCache
import com.cmzc.xj_file.app.core.playback.MediaTypes
import com.cmzc.xj_file.app.core.playback.PlaybackSource
import com.cmzc.xj_file.app.core.playback.ViewerPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 查看页底部缩略图条用的缩略图：图片降采样、视频抠一帧。
 *
 * 三条约束决定了这里的写法：
 *
 * 1. **懒加载**：缩略图条可能对着一个几百个文件的目录，只有滚到眼前的那几格才去读，
 *    绝不预生成整列（远程图片每个都要读一份文件）。
 * 2. **必须缓存**：来回滚动时反复读 SMB 是不可接受的，用小 LRU 兜住（96 格，约几 MB）。
 * 3. **失败不能炸**：视频抠帧依赖系统解码器和容器解析，远程还隔着一条自定义
 *    MediaDataSource，失败很正常 —— 失败就返回 null，界面上显示一个占位块。
 */
object Thumbnails {

    private const val TAG = "XjThumbnails"

    /** 96 格足够覆盖一屏半；再多就是白占内存。 */
    private val cache = LruCache<String, Bitmap>(96)

    fun clear() = cache.evictAll()

    suspend fun load(context: Context, page: ViewerPage, sizePx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            cache.get(page.file.path)?.let { return@withContext it }
            val bitmap = runCatching {
                if (MediaTypes.isImage(page.file.name)) {
                    ImageLoader.load(context, page.source, sizePx, sizePx)
                } else {
                    videoFrame(context, page, sizePx)
                }
            }.onFailure {
                Log.e(TAG, "缩略图失败 ${page.file.name}", it)
            }.getOrNull()
            if (bitmap != null) cache.put(page.file.path, bitmap)
            bitmap
        }

    private fun videoFrame(context: Context, page: ViewerPage, sizePx: Int): Bitmap? {
        val retriever = openRetriever(context, page)
        return try {
            // 先试 1 秒处：不少录屏/实拍的第一帧是全黑的，1 秒的帧更像"封面"
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame?.let { scaleDown(it, sizePx) }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleDown(bitmap: Bitmap, sizePx: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= sizePx) return bitmap
        val ratio = sizePx.toFloat() / longest
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}
