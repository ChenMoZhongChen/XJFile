package com.cmzc.xj_file.app.core.playback

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import com.cmzc.xj_file.app.core.cd.CddaDataSource
import com.cmzc.xj_file.app.core.cd.WavHeader
import com.cmzc.xj_file.app.core.disk.CdTrack
import com.cmzc.xj_file.app.core.disk.PcScript
import com.cmzc.xj_file.app.core.fs.RemoteFileSystem
import com.cmzc.xj_file.app.core.fs.SftpFileSystem
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 一个可读取的媒体源（播放器 / 图片查看器共用）。
 *
 * 上层只认三样东西：展示名、[uri]、以及按需造读取器的能力，
 * 完全不知道数据来自 SMB / SFTP / 本地 —— 以后要放 HTTP URL 也不用改调用方。
 *
 * 为什么留这一层（而不是直接把 fs + path 塞给播放器）：阶段四·扩展要做
 * 「跨网络流式播放音频（PC 光驱 CD / 共享音乐）」，那时候源是 DLNA 给的 HTTP URL，
 * 只要多一个 [Direct] 用法即可，播放页一行都不用动。
 */
sealed interface PlaybackSource {

    val displayName: String

    /** 文件总字节数；<= 0 表示未知。 */
    val sizeBytes: Long

    /** 只用于展示和日志；真正的字节从下面两个入口出。 */
    val uri: Uri

    /**
     * 播放器用：每次 ExoPlayer 需要读数据时调用。必须**每次返回新实例** ——
     * DataSource 不是线程安全的，ExoPlayer 自己也按「一次加载一个实例」的约定用。
     *
     * 注意它支持 seek（[androidx.media3.datasource.DataSource.open] 的 position），
     * 走的是 fs 的随机读；图片查看器不要用这个，见 [openInputStream]。
     */
    fun dataSourceFactory(context: Context): DataSource.Factory

    /**
     * 图片用：把整个文件当一条普通的流读出来。
     *
     * 为什么图片不走 DataSource：图片要的是一次性全量字节（解码器要能前后翻），
     * 而不是带 seek 的分块读；走 `openInputStream` 还能少一层包装。
     */
    suspend fun openInputStream(context: Context): InputStream

    /**
     * 视频缩略图用（[android.media.MediaMetadataRetriever] 的随机读入口）。
     * 本地文件返回 null —— 调用方改用 `setDataSource(context, uri)` 即可。
     */
    fun mediaDataSource(): MediaDataSource? = null

    /** 网络栏（SMB/SFTP）里的文件：走 fs 的读流。 */
    class Remote(
        private val fs: RemoteFileSystem,
        private val path: String,
        override val sizeBytes: Long,
        override val displayName: String,
    ) : PlaybackSource {
        override val uri: Uri = Uri.parse("xj-remote://" + Uri.encode(path))

        override fun dataSourceFactory(context: Context): DataSource.Factory =
            RemoteDataSource.Factory(fs, path, sizeBytes)

        override suspend fun openInputStream(context: Context): InputStream =
            fs.openInput(path)

        override fun mediaDataSource(): MediaDataSource = FsMediaDataSource(fs, path, sizeBytes)
    }

    /**
     * PC 光驱里的一条音频 CD 音轨（CD-DA）。
     *
     * 它不是一个文件：没有可以 stat 的大小，也不能用 SFTP 的随机读。所以走 [CddaDataSource] ——
     * 用 TOC 算出的总字节数拼一个 wav 头，远端按 LBA 边读边传。
     *
     * 播放页对它的处理和普通音频完全一样（[PlayerScreen] 只多判断一个「这是不是音频」），
     * 上一首/下一首就是切 ViewerPage 列表，不需要另做一套界面。
     */
    class Cdda(
        private val fs: SftpFileSystem,
        private val drive: String,
        private val track: CdTrack,
        override val displayName: String,
    ) : PlaybackSource {

        override val sizeBytes: Long = WavHeader.SIZE + track.pcmBytes

        override val uri: Uri = Uri.parse("xj-cdda://$drive/${track.number}")

        override fun dataSourceFactory(context: Context): DataSource.Factory {
            val speed = cdSpeedKbps(context)
            return CddaDataSource.Factory(
                context = context,
                fs = fs,
                track = track,
                commandFor = { token, lba, sectors ->
                    PcScript.command(
                        context,
                        "cdstream.ps1",
                        "__DRIVE__" to drive,
                        "__LBA__" to lba.toString(),
                        "__SECTORS__" to sectors.toString(),
                        "__SPEED__" to speed.toString(),
                        "__TOKEN__" to token,
                    )
                },
            )
        }

        override suspend fun openInputStream(context: Context): InputStream =
            fs.openExecStream(
                PcScript.command(
                    context,
                    "cdstream.ps1",
                    "__DRIVE__" to drive,
                    "__LBA__" to track.startLba.toString(),
                    "__SECTORS__" to track.sectors.toString(),
                    "__SPEED__" to cdSpeedKbps(context).toString(),
                    "__TOKEN__" to "0",
                )
            )

        override fun mediaDataSource(): MediaDataSource? = null

        private companion object {
            /**
             * 2 倍速 = 352 KB/s。
             *
             * 1x（176）虽然最像普通 CD 机的动静，但余量太小：CD-DA 播放固定要 176 KB/s，
             * 网络稍有抖动或光驱掉速就会欠载。用户要求「转速再高一点点」给抖动留余量，
             * 所以定在 2x —— 仍然远低于最高速的「翻录感」，但吞吐有余量。
             *
             * 现场可用 Settings.Global 的 `xj_cd_speed` 覆盖（单位 KB/s，1x=176），不用改包。
             */
            const val DEFAULT_CD_SPEED_KBPS = 352

            fun cdSpeedKbps(context: Context): Int = runCatching {
                android.provider.Settings.Global.getInt(
                    context.contentResolver,
                    "xj_cd_speed",
                    DEFAULT_CD_SPEED_KBPS,
                )
            }.getOrDefault(DEFAULT_CD_SPEED_KBPS)
        }
    }

    /**
     * 本地文件（以及以后的 http(s) 流）：交给 Media3 自带的 DefaultDataSource，
     * 它认得 file://、content://、http(s)://、asset:// 等。
     */
    class Direct(
        override val uri: Uri,
        override val displayName: String,
    ) : PlaybackSource {
        override val sizeBytes: Long
            get() = if (uri.scheme == "file") {
                File(requireNotNull(uri.path)).let { if (it.exists()) it.length() else -1L }
            } else -1L

        constructor(file: File, displayName: String) : this(Uri.fromFile(file), displayName)

        override fun dataSourceFactory(context: Context): DataSource.Factory =
            DefaultDataSource.Factory(context)

        override suspend fun openInputStream(context: Context): InputStream {
            if (uri.scheme == "file") {
                return File(requireNotNull(uri.path)).inputStream()
            }
            return context.contentResolver.openInputStream(uri)
                ?: throw IOException("打不开 $uri")
        }
    }
}

