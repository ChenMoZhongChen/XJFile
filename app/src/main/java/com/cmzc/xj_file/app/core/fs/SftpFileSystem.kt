package com.cmzc.xj_file.app.core.fs

import com.cmzc.xj_file.app.core.util.FileNameComparator
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Vector
import kotlin.coroutines.coroutineContext
import java.io.InputStream

/**
 * SFTP 客户端实现（基于 JSch mwiede 分支 0.2.20）。
 *
 * 路径规则：//host/绝对路径/
 *   - //host/           → SFTP 的 /
 *   - //host/home/foo/  → SFTP 的 /home/foo/
 *
 * 认证：
 *   - privateKeyPath 为空 → 用密码
 *   - privateKeyPath 非空 → 用私钥（passphrase 可选）
 */
class SftpFileSystem(
    private val host: String,
    private val port: Int = 22,
    private val username: String = "",
    private val password: String = "",
    private val privateKeyPath: String = "",
    private val passphrase: String = "",
    private val cacheDir: java.io.File? = null
) : RemoteFileSystem {

    override val rootPath: String = "//$host/"

    override val tag: String = "sftp:$host"

    private var jsch: JSch? = null
    private var session: Session? = null
    private var channel: ChannelSftp? = null

    /** 一条「车道」：独占一个会话 + 一条通道。同一时刻只被一个传输流使用。 */
    private class Lane(val session: Session, val channel: ChannelSftp)

    private val laneLock = Any()
    private val idleReadLanes = ArrayDeque<Lane>()
    private val idleWriteLanes = ArrayDeque<Lane>()
    @Volatile private var lanesClosed = false

    /** 控制类操作（列目录/删除/重命名/建目录）串行执行，避免并发写同一条连接。 */
    private val controlLock = Any()

    private companion object {
        /** 每种角色最多缓存几条空闲车道，多了就断开，别长期占着服务器的连接数。 */
        const val MAX_IDLE_LANES = 3
    }

    // ---------------- 连接 ----------------

    private fun openSession(): Pair<JSch, Session> {
        val j = JSch()
        if (privateKeyPath.isNotBlank()) {
            val keyFile = File(privateKeyPath)
            require(keyFile.exists()) { "私钥文件不存在：$privateKeyPath" }
            if (passphrase.isNotBlank()) {
                j.addIdentity(privateKeyPath, passphrase)
            } else {
                j.addIdentity(privateKeyPath)
            }
        }

        val s = j.getSession(username, host, port)
        if (password.isNotBlank()) s.setPassword(password)
        // 自用场景，跳过 host key 检查（首次连接会自动接受）
        s.setConfig("StrictHostKeyChecking", "no")
        s.connect(15000)
        return j to s
    }

    private fun ensureConnected() {
        if (session?.isConnected == true && channel?.isConnected == true) return
        synchronized(this) {
            if (session?.isConnected == true && channel?.isConnected == true) return

            val (j, s) = openSession()
            val ch = openSftpChannel(s)

            jsch = j
            session = s
            channel = ch
        }
    }

    /**
     * 开一条 sftp 通道。
     *
     * 注意：这里特意**不**关闭 JSch 的流水线（默认 16 个在途请求）。曾经为了避开
     * 上游那个 rest_byte 越界（mwiede/jsch#778）把它降到 1，结果 RTT 一高就把
     * 吞吐打到 2.6 MB/s（2 GB 要 13 分钟），不可接受。真正让连接崩掉的是
     * 「同一会话被两个通道并发写」，已经在会话层面拆开了，详见 writeSession。
     */
    private fun openSftpChannel(s: Session): ChannelSftp {
        val ch = s.openChannel("sftp") as ChannelSftp
        ch.connect(15000)
        return ch
    }

    private fun channelOrThrow(): ChannelSftp =
        channel ?: error("SFTP 通道未建立")

    /**
     * 在远端跑一条命令，返回 stdout（按 UTF-8 解）。
     *
     * 用途：磁盘容量、光驱里是什么盘、CD 音轨这类「操作系统知道、文件系统看不到」的信息，
     * 只能让远端自己报（Windows 下就是一段 PowerShell）。SFTP 协议本身没有这些。
     *
     * 单独开一条会话：exec 通道活得短，塞进车道池会把「一条连接一个写入者」的
     * 那套记账搅乱，得不偿失。
     */
    suspend fun exec(command: String, timeoutMs: Int = 20000): String = withContext(Dispatchers.IO) {
        val (_, session) = openSession()
        var ch: ChannelExec? = null
        try {
            val channel = session.openChannel("exec") as ChannelExec
            ch = channel
            channel.setCommand(command)
            // stderr 单独收走：PowerShell 的警告/progress 走 stderr，
            // 混进 stdout 会把 JSON 弄坏。但出错时得能看见，不然排查是瞎的。
            val stderr = java.io.ByteArrayOutputStream()
            channel.setErrStream(stderr)
            val stdout = channel.inputStream
            channel.connect(timeoutMs)

            // ★ timeoutMs 必须覆盖「读 stdout」，不能只盖 connect：
            // 远端 PowerShell 只要不退出，readBytes() 就会永久阻塞。磁盘探测里
            // 一条卡住的光驱命令（GET CONFIGURATION 等了 15 秒还没回）就会把
            // 线程池占死，UI 一直转圈。这里在超时后主动断通道/会话，让阻塞的
            // read 立刻抛出来，再把超时当普通失败交给调用方。
            val reader = async(Dispatchers.IO) { stdout.readBytes() }
            val bytes = try {
                withTimeout(timeoutMs.toLong()) { reader.await() }
            } catch (e: TimeoutCancellationException) {
                runCatching { channel.disconnect() }
                runCatching { session.disconnect() }
                throw java.io.IOException("远端命令超时：${timeoutMs}ms", e)
            }
            val text = String(bytes, Charsets.UTF_8)
            if (text.isBlank()) {
                throw IllegalStateException(
                    "远端命令没有输出，退出码=${runCatching { channel.exitStatus }.getOrDefault(-1)}，" +
                        "stderr=${stderr.toString("UTF-8").take(1200)}"
                )
            }
            text
        } finally {
            runCatching { ch?.disconnect() }
            runCatching { session.disconnect() }
        }
    }

    /**
     * 长驻 exec 通道：把远端命令的 stdout 当**连续字节流**读（音频 CD 用）。
     *
     * 和 [exec] 的区别：那个是一次性收完就断；这里必须让流活着，直到调用方 close()。
     * 生命周期挂在返回流的 close() 上 —— 关流 = 断通道 = 远端 PowerShell 下次写 stdout
     * 失败退出，不会留下孤儿进程。CD 每次 seek 都要重开一条，所以必须收干净。
     *
     * 为什么单独开会话而不进车道池：车道池记的是 sftp 通道的账，
     * 把 exec 通道混进去会把「一条连接同一时刻只有一个写入者」那套约束搅乱。
     */
    suspend fun openExecStream(command: String, connectTimeoutMs: Int = 20000): InputStream =
        withContext(Dispatchers.IO) {
            val (_, s) = openSession()
            try {
                val ch = s.openChannel("exec") as ChannelExec
                ch.setCommand(command)
                // stderr 单独收走：PowerShell 的警告会混进 stdout，把 PCM 数据弄坏
                ch.setErrStream(java.io.ByteArrayOutputStream())
                ch.connect(connectTimeoutMs)
                val raw = ch.inputStream
                object : java.io.FilterInputStream(raw) {
                    private var closed = false

                    override fun close() {
                        if (closed) return
                        closed = true
                        // 先告诉远端脚本「自己退出」：Windows 上没有 SIGHUP，
                        // 只断通道可能把它留在写 stdout 的阻塞里（真机踩过：孤儿进程抢光驱）。
                        runCatching {
                            ch.outputStream.write("quit\n".toByteArray())
                            ch.outputStream.flush()
                        }
                        runCatching { super.close() }
                        runCatching { ch.disconnect() }
                        runCatching { s.disconnect() }
                    }
                }
            } catch (t: Throwable) {
                runCatching { s.disconnect() }
                throw t
            }
        }

    /** 判断是不是「通道半死」：JSch 会把 io_in 读失败包装成 SftpException(cause=IOException)。 */    private fun isBrokenChannel(t: Throwable): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 4) {
            if (cur is java.io.IOException) return true
            cur = cur.cause
            depth++
        }
        return false
    }

    /**
     * 用主通道执行一次只读操作。
     *
     * JSch 在批量传输刚结束时可能把通道的 io_in 关掉，但 isConnected 还是 true，
     * 于是紧接着的 ls/stat 会抛 IOException("inputstream is closed")——以前只能靠
     * 用户手动刷新，等下一次调用触发重连。这里改成自动：撞上通道类错误就把这个
     * 通道丢掉、重开一条，然后立刻重试一次。
     *
     * 只用于「重试安全」的只读操作（list / size / exists / openInput）。
     * 写入和 rename 不套这层：重试会重复读流，或者出现「其实成功了却报错」。
     * 写通道和会话不动，避免误伤正在进行中的传输。
     */
    private fun <T> onChannel(block: (ChannelSftp) -> T): T {
        synchronized(controlLock) {
            ensureConnected()
            return try {
                block(channelOrThrow())
            } catch (t: Throwable) {
                if (!isBrokenChannel(t)) throw t
                synchronized(this) {
                    runCatching { channel?.disconnect() }
                    channel = null
                }
                ensureConnected()
                block(channelOrThrow())
            }
        }
    }

    /**
     * 从池里取一条车道；池空就新开一条（独立会话 + 独立通道）。
     *
     * 为什么要「每条车道一个会话」：JSch 的 Session.write() 没有加锁
     * （源码里 synchronized 被注释掉了），两个通道同时写同一个传输流会让
     * SSH 包交错，服务器直接报 "Corrupted MAC on input" 把连接掐掉。
     * 一条连接只给一条车道用，就永远只有一个写入者。
     */
    private fun acquireLane(idle: ArrayDeque<Lane>): Lane {
        lanesClosed = false
        synchronized(laneLock) {
            while (idle.isNotEmpty()) {
                val lane = idle.removeFirst()
                if (lane.channel.isConnected) return lane
                runCatching { lane.session.disconnect() }
            }
        }
        val (_, s) = openSession()
        return Lane(s, openSftpChannel(s))
    }

    /** 用完还回池里；池里最多留 MAX_IDLE_LANES 条，多余的直接断掉。 */
    private fun releaseLane(idle: ArrayDeque<Lane>, lane: Lane) {
        synchronized(laneLock) {
            if (!lanesClosed && lane.channel.isConnected && idle.size < MAX_IDLE_LANES) {
                idle.addLast(lane)
            } else {
                discardLane(lane)
            }
        }
    }

    private fun discardLane(lane: Lane) {
        runCatching { lane.channel.disconnect() }
        runCatching { lane.session.disconnect() }
    }

    /** 取一条读车道并打开读流（失败且是通道类错误时换一条车道重试一次）。 */
    private fun openReadStream(internalPath: String): Pair<Lane, InputStream> {
        val lane = acquireLane(idleReadLanes)
        return try {
            lane to lane.channel.get(internalPath)
        } catch (t: Throwable) {
            discardLane(lane)
            if (!isBrokenChannel(t)) throw t
            val retry = acquireLane(idleReadLanes)
            retry to retry.channel.get(internalPath)
        }
    }

    // ---------------- 路径转换 ----------------

    /** 外部路径 //host/xxx/ → SFTP 内部路径 /xxx（两端都去斜杠，避免把文件当目录） */
    private fun parsePath(path: String): String {
        val body = path.removePrefix("//")
        val slashIdx = body.indexOf('/')
        if (slashIdx < 0) return "/"
        val afterHost = body.substring(slashIdx + 1)
        val cleaned = afterHost.trim('/')
        return if (cleaned.isEmpty()) "/" else "/$cleaned"
    }

    /** SFTP 内部路径 /xxx/ → 外部路径 //host/xxx/ */
    private fun buildExternalPath(internalPath: String, isDir: Boolean): String {
        val body = internalPath.trimStart('/').trimEnd('/')
        val suffix = if (isDir) "/" else ""
        return PathUtil.normalizeRemote("//$host/$body$suffix")
    }

    // ---------------- 权限格式化 ----------------

    private fun formatPermissions(mode: Int, isDir: Boolean): String {
        val typeChar = if (isDir) 'd' else '-'
        val sb = StringBuilder()
        sb.append(typeChar)
        // Kotlin 不支持 0o 八进制字面量，这里用十进制：
        // 八进制 400=256, 200=128, 100=64
        // 八进制 040=32, 020=16, 010=8
        // 八进制 004=4, 002=2, 001=1
        val perms = listOf(
            Triple(256, 128, 64),   // user: r w x
            Triple(32, 16, 8),      // group: r w x
            Triple(4, 2, 1)         // other: r w x
        )
        for ((rBit, wBit, xBit) in perms) {
            sb.append(if (mode and rBit != 0) 'r' else '-')
            sb.append(if (mode and wBit != 0) 'w' else '-')
            sb.append(if (mode and xBit != 0) 'x' else '-')
        }
        val octal = (mode and 0x1FF).toString(8).padStart(3, '0')
        return "$sb($octal)"
    }

    // ---------------- 核心操作 ----------------

    override suspend fun list(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val internalPath = parsePath(path)

        // ★ Windows OpenSSH 的虚拟根目录 "/" 在列目录时会逐个盘符取卷信息。
        // 只要有一台光驱是空盘/卡住（真机踩过：N: 空光驱），ls("/") 就会无限挂住，
        // 而且 JSch 没有超时；表现就是 SFTP 根目录永远停在加载。
        // 根目录要显示的磁盘行本来就来自 DriveProbe(drives.ps1)，不依赖这里列出的条目，
        // 所以根目录直接返回空列表，把「列根目录」这一步整个绕开。
        if (internalPath == "/") return@withContext emptyList()

        onChannel { ch ->
            @Suppress("UNCHECKED_CAST")
            val entries = ch.ls(internalPath) as Vector<ChannelSftp.LsEntry>

            entries.filter { it.filename != "." && it.filename != ".." }
                .map { e ->
                    val attrs: SftpATTRS = e.attrs
                    val isDir = attrs.isDir
                    val childInternal = if (internalPath.endsWith("/"))
                        "$internalPath${e.filename}"
                    else
                        "$internalPath/${e.filename}"
                    RemoteFile(
                        name = e.filename,
                        path = buildExternalPath(childInternal, isDir),
                        isDirectory = isDir,
                        size = attrs.size,
                        lastModified = attrs.mTime.toLong() * 1000L,
                        permission = formatPermissions(attrs.permissions, isDir)
                    )
                }
                .sortedWith(
                    compareByDescending<RemoteFile> { it.isDirectory }
                        .thenBy(FileNameComparator) { it.name }
                )
        }
    }

    override suspend fun download(remote: String, local: File, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            ensureConnected()
            val ch = channelOrThrow()
            val internalPath = parsePath(remote)

            val total = runCatching { ch.lstat(internalPath).size }.getOrDefault(-1L)

            local.parentFile?.mkdirs()
            ch.get(internalPath).use { input ->
                FileOutputStream(local).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        }
    }

    override suspend fun upload(local: File, remote: String, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            val internalPath = parsePath(remote)
            val total = local.length()
            val lane = acquireLane(idleWriteLanes)
            var ok = false
            try {
                val ctx = kotlin.coroutines.coroutineContext
                val counting = ProgressInputStream(
                    FileInputStream(local), total, onProgress
                ) { ctx.ensureActive() }
                counting.use { lane.channel.put(it, internalPath, ChannelSftp.OVERWRITE) }
                onProgress(total, total)
                ok = true
            } finally {
                if (ok) releaseLane(idleWriteLanes, lane) else discardLane(lane)
            }
        }
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        ensureConnected()
        val ch = channelOrThrow()
        val internalPath = parsePath(path)
        deleteRecursive(ch, internalPath)
    }

    private fun deleteRecursive(ch: ChannelSftp, internalPath: String) {
        val attrs = ch.lstat(internalPath)
        if (attrs.isDir) {
            @Suppress("UNCHECKED_CAST")
            val children = ch.ls(internalPath) as Vector<ChannelSftp.LsEntry>
            for (child in children) {
                if (child.filename == "." || child.filename == "..") continue
                val childPath = if (internalPath.endsWith("/"))
                    "$internalPath${child.filename}"
                else
                    "$internalPath/${child.filename}"
                deleteRecursive(ch, childPath)
            }
            ch.rmdir(internalPath)
        } else {
            ch.rm(internalPath)
        }
    }

    override suspend fun move(from: String, to: String) = withContext(Dispatchers.IO) {
        ensureConnected()
        val ch = channelOrThrow()
        ch.rename(parsePath(from), parsePath(to))
    }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        ensureConnected()
        val ch = channelOrThrow()
        ch.mkdir(parsePath(path))
    }

    override suspend fun rename(path: String, newName: String) = withContext(Dispatchers.IO) {
        ensureConnected()
        val ch = channelOrThrow()
        val internal = parsePath(path).trimEnd('/')
        val parent = internal.substringBeforeLast('/', "")
        val newInternal = if (parent.isEmpty()) "/$newName" else "$parent/$newName"
        ch.rename(internal, newInternal)
    }

    override suspend fun size(path: String): Long? = withContext(Dispatchers.IO) {
        val internalPath = parsePath(path)
        runCatching { onChannel { it.lstat(internalPath).size } }.getOrNull()
    }


    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val internalPath = parsePath(path)
        runCatching { onChannel { it.lstat(internalPath) } }.isSuccess
    }

    override suspend fun openInput(path: String): InputStream = withContext(Dispatchers.IO) {
        val (lane, raw) = openReadStream(parsePath(path))
        wrapReadLane(lane, raw)
    }

    /**
     * 读流的统一外壳：负责「用完把车道还回池里」。
     *
     * 正常读到 EOF 说明车道干净，直接复用。没读到 EOF 就关时要分两种情况：
     *
     * - [laneTrustedOnEarlyClose] = false（管道式读流，即 [openInput]）：
     *   JSch 会把通道的 io_in 关掉而 isConnected 仍为 true，这条车道已经不可信，必须丢掉。
     * - = true（偏移读流，即 [openInputFrom]）：那种流的 close() 是
     *   `RequestQueue.cancel` + `sendCLOSE` + 等回执，通道本身是干净的，
     *   可以还回池里 —— 阶段四拖进度条时每次 seek 都要关流重开，
     *   如果每次丢车道就等于每次重开一条 SSH 会话（握手 + 认证），肉眼可见地卡。
     */
    private fun wrapReadLane(
        lane: Lane,
        raw: InputStream,
        laneTrustedOnEarlyClose: Boolean = false
    ): InputStream =
        object : java.io.FilterInputStream(raw) {
            private var reachedEof = false
            private var released = false

            override fun read(): Int {
                val b = super.read()
                if (b < 0) reachedEof = true
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n < 0) reachedEof = true
                return n
            }

            override fun close() {
                if (released) return
                released = true
                runCatching { super.close() }
                val reusable = reachedEof || laneTrustedOnEarlyClose
                if (reusable) releaseLane(idleReadLanes, lane) else discardLane(lane)
            }
        }

    override suspend fun writeFrom(
        input: InputStream,
        targetPath: String,
        onProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val internalPath = parsePath(targetPath)
        val lane = acquireLane(idleWriteLanes)
        var ok = false
        try {
            // 把协程的取消状态传进流里：JSch 的 put 是阻塞循环，不主动检查就停不下来，
            // 用户点了取消其实还在后台把文件传完（真机实测过）。
            val ctx = kotlin.coroutines.coroutineContext
            val counting = ProgressInputStream(input, -1L, onProgress) { ctx.ensureActive() }
            lane.channel.put(counting, internalPath, ChannelSftp.OVERWRITE)
            onProgress(counting.done, counting.done)
            ok = true
        } finally {
            if (ok) releaseLane(idleWriteLanes, lane) else discardLane(lane)
        }
    }

    // ---------------- 随机读（续传的「读源」一侧 / 阶段四边下边播的 seek） ----------------

    /**
     * 从 offset 处开始读。
     *
     * ★ 用 JSch 原生的 `get(path, monitor, skip)`：它把 skip 变成
     * `SSH_FXP_READ` 请求里的**服务端偏移**，不会把前面的字节下载一遍再丢掉。
     *
     * 为什么不能用「先 get 再 skip」：InputStream.skip() 落到 JSch 的读流上就是
     * 老实读够 n 字节然后扔掉。续传场景（offset 只有几 MB）看着没事，但阶段四
     * 给 1.65 GB 的视频拖一次进度条，就等于把整部片子先下一遍 —— 实测这条路走不通，
     * 所以才换成原生偏移读。
     *
     * 注意：**服务端不支持「从 offset 接着写」**——OpenSSH 对 RESUME 那一位直接报
     * "failed to resume"。所以 SFTP 只作为**源**支持续传；作为目标时会抛 ResumeUnsupported，
     * 由上层悄悄改从头传（用户要求：能续就续，续不了就静默重传）。
     */
    override suspend fun openInputFrom(path: String, offset: Long): InputStream =
        withContext(Dispatchers.IO) {
            if (offset == 0L) return@withContext openInput(path)
            val internalPath = parsePath(path)
            val lane = acquireLane(idleReadLanes)
            val raw = try {
                lane.channel.get(internalPath, null, offset)
            } catch (t: Throwable) {
                // 通道类错误：这条车道的状态已经不可信，丢掉重来不了就交给上层报错
                discardLane(lane)
                throw t
            }
            wrapReadLane(lane, raw, laneTrustedOnEarlyClose = true)
        }

    /** 目标侧写不了偏移：明确拒绝，让上层降级。 */
    override suspend fun openOutputFrom(path: String, offset: Long): ResumeWrite {
        if (offset == 0L) {
            throw ResumeUnsupportedException("SFTP 增量写不支持")
        }
        throw ResumeUnsupportedException("SFTP 服务端不支持从偏移续写")
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        lanesClosed = true
        synchronized(laneLock) {
            idleReadLanes.forEach { discardLane(it) }
            idleWriteLanes.forEach { discardLane(it) }
            idleReadLanes.clear()
            idleWriteLanes.clear()
        }
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        channel = null
        session = null
        jsch = null
    }

    /**
     * 边读边报进度的包装流。upload / writeFrom 共用。
     * 不负责关闭被包装的流——由调用方决定谁 close。
     */
    private class ProgressInputStream(
        input: InputStream,
        private val total: Long,
        private val onProgress: (Long, Long) -> Unit,
        /** 每读一块检查一次协程是否已取消：JSch 的 put 是阻塞的，没有中断点就停不下来 */
        private val checkActive: () -> Unit = {}
    ) : java.io.FilterInputStream(input) {

        var done: Long = 0L
            private set

        override fun read(): Int {
            checkActive()
            val b = super.read()
            if (b >= 0) {
                done++
                onProgress(done, total)
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            checkActive()
            val n = super.read(b, off, len)
            if (n > 0) {
                done += n
                onProgress(done, total)
            }
            return n
        }
    }
}