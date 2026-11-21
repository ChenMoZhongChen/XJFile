package com.cmzc.xj_file.app.core.playback

import android.media.MediaDataSource
import com.cmzc.xj_file.app.core.fs.RemoteFileSystem
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream

/**
 * 把 fs 的随机读包装成 [MediaDataSource]，给 `MediaMetadataRetriever` 抠视频首帧用。
 *
 * 只在做缩略图时用，读量很小（几 KB 到几百 KB）。
 *
 * 为什么每次换位置都重开流、而不是 skip：
 * SFTP 的 skip 是「读够 n 字节再丢」，而视频的 moov 往往在文件末尾，
 * 抠一帧就要 skip 掉整部片子 —— 慢到没法用。重开流反而是最省的（SMB 的 skip 是 O(1)，
 * 但为了一条路径统一，这里就不做协议区分了）。
 */
class FsMediaDataSource(
    private val fs: RemoteFileSystem,
    private val path: String,
    private val size: Long,
) : MediaDataSource() {

    private var stream: InputStream? = null
    private var pos = 0L

    override fun getSize(): Long = size

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position < 0) return -1
        if (this.size > 0 && position >= this.size) return -1

        val input = reposition(position) ?: return -1
        val n = try {
            input.read(buffer, offset, size)
        } catch (t: Throwable) {
            throw IOException("读 $path @$position 失败", t)
        }
        if (n > 0) pos += n
        return if (n < 0) -1 else n
    }

    private fun reposition(target: Long): InputStream? {
        stream?.let { if (target == pos) return it }
        runCatching { stream?.close() }
        stream = null
        val opened = runBlocking { fs.openInputFrom(path, target) }
        stream = opened
        pos = target
        return opened
    }

    override fun close() {
        runCatching { stream?.close() }
        stream = null
        pos = 0
    }
}
