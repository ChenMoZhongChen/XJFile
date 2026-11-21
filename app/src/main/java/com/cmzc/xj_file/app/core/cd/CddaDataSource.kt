package com.cmzc.xj_file.app.core.cd

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.cmzc.xj_file.app.core.disk.CdTrack
import com.cmzc.xj_file.app.core.disk.PcScript
import com.cmzc.xj_file.app.core.fs.SftpFileSystem
import com.cmzc.xj_file.app.core.util.DebugLog
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把「PC 光驱里的一条 CD 音轨」包装成 Media3 的 DataSource。
 *
 * 远端是边读边传的：`open()` 只算位置，真正的 exec 流要等第一次真读 PCM 时才拉起来
 * （wav 头在内存里，不值得为它启动一次要 3 秒的 PowerShell）。
 *
 * 三个必须守住的点（和 [com.cmzc.xj_file.app.core.playback.RemoteDataSource] 同一套教训）：
 * 1. `read()` 必须能被 `close()` 打断 —— ExoPlayer 取消一次加载只有 close 这一条路；
 * 2. `transferStarted` / `transferEnded` 必须成对，否则 BaseDataSource 内部 dataSpec 为空，
 *    没配对就调 transferEnded 会让 DefaultBandwidthMeter 空指针，还会盖掉真正的异常；
 * 3. `open()` 是「换位置」不是「新连接」：同一个实例会被 seek 反复复用，先关旧流。
 *
 * seek 的实现就是「关掉旧命令、按新 LBA 重开一条」：首版接受 2~3 秒延迟，
 * 换来的是逻辑足够简单、且每次都能从任意扇区精确接上。
 */
class CddaDataSource(
    private val context: android.content.Context,
    private val fs: SftpFileSystem,
    private val header: ByteArray,
    private val firstLba: Long,
    private val totalSectors: Long,
    /** 生成「从 (lba, sectors) 开始读」的远端命令（PcScript 打包好的整条 ssh 参数）。 */
    private val commandFor: (token: String, lba: Long, sectors: Long) -> String,
    /** 诊断日志入口（vivo 屏蔽 logcat，只能写私有文件）。 */
    private val log: (String) -> Unit = {},
) : BaseDataSource(/* isNetwork = */ true) {

    /**
     * 这条流的唯一标记。
     *
     * 远端脚本会把自己的 PID 写进 `%TEMP%\xjcd-<token>.pid`，关闭时我们用同 token 的
     * cdkill.ps1 定点杀它 —— 只断 SSH 通道杀不掉（Windows 无 SIGHUP），
     * 切歌后留下的旧流会和新流抢同一台光驱，表现就是疯狂寻道 + 断断续续。
     */
    private val token: String = java.lang.Integer.toHexString(
        java.util.concurrent.ThreadLocalRandom.current().nextInt(0x10000000, 0x7FFFFFFF)
    )

    private val cancelled = AtomicBoolean(false)
    private val headerSize = header.size

    private var stream: InputStream? = null
    private var uri: Uri? = null
    private var transferring = false

    /** 本次 open 之后还剩多少字节可读（含还没吐完的 wav 头）。 */
    private var bytesRemaining = 0L
    private var headerPos = 0
    private var nextLba = 0L
    private var sectorsLeft = 0L
    /** 位置落在扇区中间时，新开的流要先丢掉这么多字节，才能对齐 ExoPlayer 的字节位置。 */
    private var skipBytes = 0

    // 吞吐日志用（见 logThroughput）
    private var readSinceLastLog = 0
    private var lastLogAt = System.nanoTime()
    /** 本次 open 之后一共吐出多少字节（诊断用）。 */
    private var totalRead = 0L

    override fun open(dataSpec: DataSpec): Long {
        // 同一个实例会被 seek 反复复用：先把上一次的流关掉，否则远端会挂着一堆 PowerShell
        close()
        cancelled.set(false)
        uri = dataSpec.uri

        val total = headerSize + totalSectors * WavHeader.SECTOR_BYTES
        val pos = dataSpec.position
        if (pos < 0 || pos >= total) {
            throw DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE)
        }
        val pcmPos = (pos - headerSize).coerceAtLeast(0L)
        val sectorIndex = pcmPos / WavHeader.SECTOR_BYTES
        skipBytes = (pcmPos % WavHeader.SECTOR_BYTES).toInt()
        nextLba = firstLba + sectorIndex
        sectorsLeft = totalSectors - sectorIndex
        headerPos = if (pos < headerSize) pos.toInt() else headerSize
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, total - pos)
        } else {
            total - pos
        }

        transferStarted(dataSpec)
        transferring = true
        log("open pos=$pos remain=$bytesRemaining lba=$nextLba sectors=$sectorsLeft skip=$skipBytes")
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT

        // wav 头是内存里的字节，直接吐，不用等远端
        if (headerPos < headerSize) {
            val n = minOf(length.toLong(), (headerSize - headerPos).toLong(), bytesRemaining).toInt()
            System.arraycopy(header, headerPos, buffer, offset, n)
            headerPos += n
            bytesRemaining -= n
            bytesTransferred(n)
            return n
        }

        val input = ensureStream() ?: return C.RESULT_END_OF_INPUT
        val want = minOf(length.toLong(), bytesRemaining).toInt()
        val n = try {
            input.read(buffer, offset, want)
        } catch (t: Throwable) {
            if (cancelled.get()) return C.RESULT_END_OF_INPUT
            log("read-error ${t.javaClass.simpleName}: ${t.message}")
            throw IOException("读 CD 音轨失败", t)
        }
        if (n <= 0) {
            log("read-eof remain=$bytesRemaining")
            return C.RESULT_END_OF_INPUT
        }
        bytesRemaining -= n
        totalRead += n
        bytesTransferred(n)
        logThroughput(n)
        return n
    }

    /**
     * 一段读取的吞吐日志。
     *
     * 为什么要留这条：CD 是 176 KB/s 的恒速流，一旦链路低于这个数就会「能播但一直停」，
     * 而界面上只看得到进度条在爬。日志里能一眼区分是光驱慢、SSH 慢还是解码慢。
     */
    private fun logThroughput(n: Int) {
        readSinceLastLog += n
        if (readSinceLastLog >= 256 * 1024) {
            val now = System.nanoTime()
            val ms = (now - lastLogAt) / 1_000_000.0
            if (ms > 0) {
                val kbps = readSinceLastLog / 1024.0 / (ms / 1000.0)
                log("throughput ${readSinceLastLog / 1024}KB in ${ms.toInt()}ms = ${kbps.toInt()}KB/s")
            }
            readSinceLastLog = 0
            lastLogAt = now
        }
    }

    /** 第一次真要 PCM 时才起远端命令（起一次约 3 秒，能省就省）。 */
    private fun ensureStream(): InputStream? {
        stream?.let { return it }
        if (sectorsLeft <= 0L) return null

        val opened = try {
            runBlocking { fs.openExecStream(commandFor(token, nextLba, sectorsLeft)) }
        } catch (t: Throwable) {
            if (cancelled.get()) return null
            throw IOException("打开 CD 数据流失败：lba=$nextLba", t)
        }
        if (cancelled.get()) {
            runCatching { opened.close() }
            return null
        }

        // 丢掉扇区内的偏移（最多 2351 字节），之后每个字节都对得上
        var left = skipBytes
        skipBytes = 0
        val junk = ByteArray(2352)
        while (left > 0) {
            val n = try {
                opened.read(junk, 0, minOf(junk.size, left))
            } catch (t: Throwable) {
                runCatching { opened.close() }
                if (cancelled.get()) return null
                throw IOException("跳过 CD 扇区偏移失败", t)
            }
            if (n <= 0) {
                runCatching { opened.close() }
                return null
            }
            left -= n
        }

        stream = opened
        log("stream-open lba=$nextLba sectors=$sectorsLeft")
        return opened
    }

    override fun close() {
        cancelled.set(true)
        val s = stream
        stream = null
        // 真的关流（断会话）：正在阻塞的 read() 靠它抛异常退出，远端进程也随之结束
        runCatching { s?.close() }
        if (s != null) {
            log("close read=$totalRead remain=$bytesRemaining")
            killRemote()
        }
        if (transferring) {
            transferring = false
            transferEnded()
        }
    }

    /** 兜底清理：按 token 杀掉远端脚本进程（异步，不阻塞 ExoPlayer 的加载线程）。 */
    private fun killRemote() {
        val cmd = runCatching {
            PcScript.command(context, "cdkill.ps1", "__TOKEN__" to token)
        }.getOrNull() ?: return
        Thread {
            runCatching { kotlinx.coroutines.runBlocking { fs.exec(cmd, 15_000) } }
        }.apply { isDaemon = true }.start()
    }

    override fun getUri(): Uri? = uri

    /** 每次 ExoPlayer 需要读数据都造一个新实例（DataSource 不是线程安全的）。 */
    class Factory(
        private val context: android.content.Context,
        private val fs: SftpFileSystem,
        private val track: CdTrack,
        private val commandFor: (token: String, lba: Long, sectors: Long) -> String,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = CddaDataSource(
            context = context,
            fs = fs,
            header = WavHeader.of(track.pcmBytes),
            firstLba = track.startLba,
            totalSectors = track.sectors,
            commandFor = commandFor,
            log = { msg -> DebugLog.ev(context, msg) },
        )
    }
}
