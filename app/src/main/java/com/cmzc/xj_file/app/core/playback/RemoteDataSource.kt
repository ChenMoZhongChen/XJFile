package com.cmzc.xj_file.app.core.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.cmzc.xj_file.app.core.fs.RemoteFileSystem
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把 [RemoteFileSystem] 的随机读包成 ExoPlayer 的 DataSource —— 边下边播的关键。
 *
 * 设计上**只依赖 `openInputFrom(path, offset)` + `size(path)`**，不碰 SMB/SFTP 细节，
 * 所以阶段四·扩展（HTTP 音频流）不需要改这里。
 *
 * 三个必须守住的点（违反就会「能播但一拖进度条就废」）：
 *
 * 1. **先问大小、再开流**。SFTP 实现里「流没关之前不能对该 fs 做别的操作」，
 *    反过来先 open 再 size 会撞上这条约束。大小能由调用方传进来就传（列目录时已经有了）。
 * 2. **`read()` 必须能被 `close()` 打断**。ExoPlayer 取消一次加载的唯一手段就是
 *    `close()`，如果它只置个标志位、不真的关流，读线程会一直卡在网络读上。
 * 3. **`open()` 不是「新连接」而是「新位置」**。ExoPlayer 每次 seek 都会复用同一个
 *    DataSource 实例反复 open/close，所以 `open()` 第一件事就是把上一次的流关掉。
 *
 * 关于 `runBlocking`：`fs` 的方法是 suspend 的，而 DataSource 是阻塞式接口。
 * ExoPlayer 调用 `open()/read()` 的是它自己的加载线程（不是主线程），
 * 在这里阻塞是安全的；反而**不能**另起线程再 join —— 那样 close() 就打断不了它了。
 */
class RemoteDataSource(
    private val fs: RemoteFileSystem,
    private val path: String,
    /** 列目录时已经拿到的大小；<= 0 表示未知，open 时再问 fs。 */
    private val sizeHint: Long = -1L,
) : BaseDataSource(/* isNetwork = */ true) {

    /** 复用同一个实例时，`close()` 之后这个值会被置位，用来防「open 还在连接中就被取消」。 */
    private val cancelled = AtomicBoolean(false)

    private var stream: InputStream? = null
    private var uri: Uri? = null

    /**
     * 是否有一轮传输正在进行（已 `transferStarted` 且还没 `transferEnded`）。
     *
     * ★ BaseDataSource 的 `transferEnded()` 会拿内部 `dataSpec` 回调给监听者，
     * 而 `dataSpec` 只有 `transferStarted()` 里才会被赋值 —— 没有配对就调它，
     * DefaultBandwidthMeter 直接空指针（真机踩过：这个 NPE 还会把真正的
     * IOException 盖掉，日志里只剩一个莫名其妙的 NPE）。所以必须自己记着。
     */
    private var transferring = false

    /** 本次 open 之后还能读多少字节；[UNKNOWN] 表示不知道，一直读到流 EOF。 */
    private var bytesRemaining: Long = 0
    private var bytesRead: Long = 0

    override fun open(dataSpec: DataSpec): Long {
        // 同一个实例会被 seek 反复复用：先把上一次的流关掉，否则连接越积越多
        close()
        cancelled.set(false)
        uri = dataSpec.uri

        val start = dataSpec.position
        val total = if (sizeHint > 0) sizeHint else querySize()
        if (total in 0..start) {
            // 位置越界（文件被截断/改小）：交给 ExoPlayer 当「读不到」处理，别静默返回空
            throw DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE)
        }

        val opened = try {
            // fetch 块：openInputFrom 内部会 withContext(IO)，runBlocking 只是等它
            runBlocking { fs.openInputFrom(path, start) }
        } catch (t: Throwable) {
            Log.e(TAG, "open 失败 ${fs.tag} $path @$start", t)
            throw IOException("打开远程文件失败：$path @$start", t)
        }

        // open 期间用户可能已经退出播放页（close 被调过）：这时别把流挂上，直接关掉
        if (cancelled.get()) {
            runCatching { opened.close() }
            throw IOException("播放已取消")
        }
        stream = opened
        bytesRead = 0
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            total >= 0 -> total - start
            else -> UNKNOWN
        }
        transferStarted(dataSpec)
        transferring = true
        Log.e(TAG, "open ${fs.tag} $path start=$start total=$total remain=$bytesRemaining")
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val input = stream ?: throw IOException("RemoteDataSource 未打开")
        val want = if (bytesRemaining == UNKNOWN) length
        else minOf(length.toLong(), bytesRemaining).toInt()

        val n = try {
            input.read(buffer, offset, want)
        } catch (t: Throwable) {
            // close() 打断阻塞读是正常取消路径，不能当成播放错误抛给 ExoPlayer
            if (cancelled.get()) return C.RESULT_END_OF_INPUT
            throw IOException("读远程文件失败：$path", t)
        }
        if (n == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != UNKNOWN) bytesRemaining -= n
        bytesRead += n
        bytesTransferred(n)
        return n
    }

    override fun close() {
        cancelled.set(true)
        val s = stream
        stream = null
        // 这里真的关流，不只是置标志：正在阻塞的 read() 靠它抛异常退出
        runCatching { s?.close() }
        if (s != null) Log.e(TAG, "close after $bytesRead bytes")
        // 只有确实开始过一轮传输才收尾，否则 BaseDataSource 内部 dataSpec 为空会 NPE
        if (transferring) {
            transferring = false
            transferEnded()
        }
    }

    override fun getUri(): Uri? = uri

    private fun querySize(): Long =
        runCatching { runBlocking { fs.size(path) } }.getOrNull() ?: UNKNOWN

    /** 每次 ExoPlayer 要读数据都造一个新实例（DataSource 不是线程安全的）。 */
    class Factory(
        private val fs: RemoteFileSystem,
        private val path: String,
        private val sizeHint: Long = -1L,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = RemoteDataSource(fs, path, sizeHint)
    }

    private companion object {
        const val TAG = "XjRemoteDataSource"

        /** 大小未知的哨兵值。不能用 0（0 表示「读到尾了」）。 */
        const val UNKNOWN = -1L
    }
}
