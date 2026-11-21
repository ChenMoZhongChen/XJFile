package com.cmzc.xj_file.app.core.fs

import java.io.File
import java.io.InputStream

/**
 * 统一文件系统接口。本地 / SMB / SFTP 各自实现一份。
 * 所有方法都必须在 IO 线程调用（内部会 withContext(Dispatchers.IO)）。
 */
interface RemoteFileSystem {

    /** 根路径。本地 "/storage/emulated/0/"，SMB "//host/" */
    val rootPath: String

    /** 列出目录。path 必须以 "/" 结尾。 */
    suspend fun list(path: String): List<RemoteFile>

    /** 下载远程文件到本地 File。onProgress(已传字节, 总字节)，总字节为 -1 表示未知 */
    suspend fun download(remote: String, local: File, onProgress: (Long, Long) -> Unit)

    /** 上传本地文件到远程路径。onProgress 同上 */
    suspend fun upload(local: File, remote: String, onProgress: (Long, Long) -> Unit)

    /**
     * 打开一个远程文件的输入流。
     * 调用方负责 close。
     * 注意：SFTP 实现里，流未关闭前该 fs 实例不能做其他操作。
     */
    suspend fun openInput(path: String): InputStream

    /**
     * 从 input 读数据，写到 targetPath。
     * - 调用方负责创建和关闭 input，本方法不关闭它。
     * - onProgress(已传字节, 总字节)，总字节未知时传 -1。
     * - 若 targetPath 已存在，直接覆盖。
     */
    suspend fun writeFrom(input: InputStream, targetPath: String, onProgress: (Long, Long) -> Unit)

    // ================= 断点续传 / 校验（版本 B） =================

    /**
     * 从 `offset` 处开始读。offset=0 等价于 [openInput]。
     * 实现必须做到「服务端偏移读」（SMB 用 FILE_OPEN 后的 offset、SFTP 用
     * `SSH_FXP_READ` 的 offset），**不能**靠先读后丢 —— 阶段四给大文件拖进度条时，
     * 读后丢等于把整部片子下两遍。
     */
    suspend fun openInputFrom(path: String, offset: Long): InputStream =
        if (offset == 0L) openInput(path)
        else throw UnsupportedOperationException("$tag 不支持随机读")

    /**
     * 从 `offset` 处开始写：
     * - offset == 0 → 覆盖写（新建/截断）；
     * - offset > 0 → 目标文件里已有 offset 字节，接着往后写。
     *
     * 如果协议做不到「接着写」，实现**必须抛 [ResumeUnsupportedException]**，
     * 让上层悄悄改从头传 —— 不要假装支持、更不要悄悄截断已有数据。
     */
    suspend fun openOutputFrom(path: String, offset: Long): ResumeWrite {
        if (offset == 0L) throw ResumeUnsupportedException("$tag 不支持增量写")
        throw ResumeUnsupportedException("$tag 不支持续写")
    }

    /** 文件内容的 SHA-256（hex 小写）。用于传输后校验。 */
    suspend fun sha256(path: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val total = size(path) ?: -1L
        var done = 0L
        openInput(path).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
                done += n
                onProgress(done, total)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 日志/断点记录里用的短标识，例如 "local" / "smb:192.168.18.31"。子类覆盖成真实值。 */
    val tag: String
        get() = javaClass.simpleName

    suspend fun delete(path: String)

    suspend fun move(from: String, to: String)

    suspend fun mkdir(path: String)

    suspend fun rename(path: String, newName: String)

    suspend fun exists(path: String): Boolean

    /** 返回路径对应的大小（字节）。拿不到返回 null（路径不存在/无权限/网络错）。 */
    suspend fun size(path: String): Long?

    /** 释放连接。UI 不再使用该 fs 时应调用。 */
    suspend fun close()
}

/** 目标侧做不到「从 offset 接着写」。上层捕获后静默降级成从头传。 */
class ResumeUnsupportedException(message: String) : Exception(message)

/**
 * 一扇「从 offset 接着写」的窗口。
 *
 * 为什么不用 OutputStream：续传要能精确知道「到底写进去多少字节」，
 * 而协议层（JSch put / smbj write）本身是阻塞的整段写。这里用显式 write 更好对账。
 */
interface ResumeWrite : AutoCloseable {
    /** 本次已经写入的字节数（不含 offset 之前就有的那部分）。 */
    val written: Long

    /** 顺序写一块。 */
    fun write(buf: ByteArray, off: Int, len: Int)

    /** 正常收尾（flush + 关流）。异常退出时也要调用，实现里要能容忍半途关。 */
    override fun close()

    /** 底层连接是否还能复用（SFTP 被取消后通道已废，不能再还回车道池）。 */
    val reusable: Boolean
        get() = false
}
