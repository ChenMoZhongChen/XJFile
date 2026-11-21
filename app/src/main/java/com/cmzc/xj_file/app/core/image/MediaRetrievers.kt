package com.cmzc.xj_file.app.core.image

import android.content.Context
import android.media.MediaMetadataRetriever
import com.cmzc.xj_file.app.core.playback.PlaybackSource
import com.cmzc.xj_file.app.core.playback.ViewerPage

/**
 * 统一的 [MediaMetadataRetriever] 建法：本地走 (Context, Uri)，远程走自定义 MediaDataSource。
 *
 * 抽出来是因为缩略图和音频封面都要用同一条路 —— 远程那个 `MediaDataSource`
 * 一旦哪边写歪了，就会出现"本地好好的、SMB 上抠不出帧"这种难查的问题。
 *
 * **调用方负责 release**（放 finally 里）。
 */
internal fun openRetriever(context: Context, page: ViewerPage): MediaMetadataRetriever {
    val retriever = MediaMetadataRetriever()
    try {
        when (val source = page.source) {
            is PlaybackSource.Remote -> retriever.setDataSource(source.mediaDataSource())
            is PlaybackSource.Direct -> retriever.setDataSource(context, source.uri)
            // 音频 CD 是一条裸 PCM 流，没有容器/标签可读；音频页会跳过取标签这一步。
            is PlaybackSource.Cdda ->
                throw IllegalStateException("CD 音轨没有可读的容器标签")
        }
        return retriever
    } catch (t: Throwable) {
        runCatching { retriever.release() }
        throw t
    }
}
