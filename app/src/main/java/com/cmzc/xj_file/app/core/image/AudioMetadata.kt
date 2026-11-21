package com.cmzc.xj_file.app.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import com.cmzc.xj_file.app.core.playback.ViewerPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 音频文件的内嵌信息：封面 + 标题/艺术家/专辑。
 *
 * mp3/flac/m4a 里通常带这些标签，有就显示，没有就让界面退回"文件名 + 音符图标"，
 * 不能因为标签缺失就显示空白。
 */
data class AudioInfo(
    val title: String?,
    val artist: String?,
    val album: String?,
    val cover: Bitmap?,
)

object AudioMetadata {

    private const val TAG = "XjAudioMeta"

    /** 封面足够大就行，大封面（比如 2000px）在手机上纯属浪费内存。 */
    private const val COVER_MAX_PX = 720

    suspend fun load(context: Context, page: ViewerPage): AudioInfo? = withContext(Dispatchers.IO) {
        val retriever = try {
            openRetriever(context, page)
        } catch (t: Throwable) {
            Log.e(TAG, "打开 ${page.file.name} 失败", t)
            return@withContext null
        }
        try {
            val cover = retriever.embeddedPicture?.let { bytes ->
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                    ?.let { scaleDown(it, COVER_MAX_PX) }
            }
            AudioInfo(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                cover = cover
            )
        } catch (t: Throwable) {
            Log.e(TAG, "读 ${page.file.name} 的标签失败", t)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 只取时长（毫秒），给「文件夹音频列表」用。
     *
     * 为什么不复用 [load]：列表里可能有几十上百首，每首都解封面会吃内存；
     * 这里只读一个 DURATION 字段就释放。CD 音轨没有容器标签，调用方自己用
     * TOC 的扇区数算，不走这里。
     */
    suspend fun duration(context: Context, page: ViewerPage): Long? = withContext(Dispatchers.IO) {
        val retriever = runCatching { openRetriever(context, page) }.getOrNull()
            ?: return@withContext null
        try {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
        } catch (t: Throwable) {
            // 远程文件标签读失败是常事（网络/容器不支持），列表显示 --:-- 即可
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxPx: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxPx) return bitmap
        val ratio = maxPx.toFloat() / longest
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}
