package com.cmzc.xj_file.app.core.fs

import android.util.Log
import com.cmzc.xj_file.app.core.fs.smb.SmbShareEnumerator
import com.cmzc.xj_file.app.core.util.FileNameComparator
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.Share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import java.io.InputStream

/**
 * smbj 实现的 SMB2/SMB3 客户端（适配 smbj 0.13.0）。
 *
 * 路径规则：//host/share/sub/dir/
 *
 * 列共享：smbj 本身没有列共享的能力，这里借用 jcifs-ng 在 hostOnly 分支枚举。
 *
 * 注意：强制使用 SMB 2.1 协议，避免 SMB 3.0 的 AES 密钥派生在 Android 上崩溃。
 */
class SmbFileSystem(
    private val host: String,
    private val port: Int = 445,
    private val username: String = "",
    private val password: String = "",
    private val domain: String = ""
) : RemoteFileSystem {

    override val rootPath: String = "//$host/"

    override val tag: String = "smb:$host"

    // 只启用 SMB 2.1，不协商 SMB 3.0
    private val client = SMBClient(
        SmbConfig.builder()
            .withDialects(com.hierynomus.mssmb2.SMB2Dialect.SMB_2_1)
            .build()
    )

    @Volatile private var connection: Connection? = null
    @Volatile private var session: Session? = null
    private val shares = ConcurrentHashMap<String, DiskShare>()

    private fun ensureConnected() {
        Log.d("XjFileSMB", "ensureConnected: host=$host port=$port username=${if (username.isBlank()) "(blank)" else username}")
        if (session != null && connection?.isConnected == true) {
            Log.d("XjFileSMB", "已有连接，复用")
            return
        }
        synchronized(this) {
            if (session != null && connection?.isConnected == true) return
            try {
                Log.d("XjFileSMB", "开始 TCP 连接 $host:$port ...")
                val conn = client.connect(host, port)
                Log.d("XjFileSMB", "TCP 连接成功")
                connection = conn

                val auth = if (username.isBlank()) {
                    Log.d("XjFileSMB", "使用 Guest 空密码认证")
                    AuthenticationContext("Guest", CharArray(0), null)
                } else {
                    Log.d("XjFileSMB", "使用用户认证: $username")
                    AuthenticationContext(username, password.toCharArray(), domain.ifBlank { null })
                }

                Log.d("XjFileSMB", "开始 SMB 认证 ...")
                session = conn.authenticate(auth)
                Log.d("XjFileSMB", "SMB 认证成功")
            } catch (t: Throwable) {
                Log.e("XjFileSMB", "连接/认证失败", t)
                throw t
            }
        }
    }

    private fun getShare(name: String): DiskShare {
        shares[name]?.let { return it }
        val s = session ?: error("Session 未建立")
        val share: Share = s.connectShare(name)
        require(share is DiskShare) { "$name 不是磁盘共享" }
        shares[name] = share
        return share
    }

    /** 解析路径，返回 (share, 内部路径, 是否只是主机根) */
    private fun parsePath(path: String): Triple<String?, String, Boolean> {
        val body = path.removePrefix("//").trim('/')
        if (body.isEmpty()) return Triple(null, "", true)
        val parts = body.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return Triple(null, "", true)
        val share = parts[1]
        val inner = parts.drop(2).joinToString("\\")
        return Triple(share, inner, false)
    }

    /** 根据 share + inner + fileName 构造规范路径 */
    private fun buildChildPath(share: String, inner: String, name: String, isDir: Boolean): String {
        val base = "//$host/$share/"
        val mid = if (inner.isEmpty()) "" else inner.replace('\\', '/') + "/"
        val suffix = if (isDir) "/" else ""
        return PathUtil.normalizeRemote(base + mid + name + suffix)
    }

    override suspend fun list(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        Log.d("XjFileSMB", "list: path=$path")
        ensureConnected()
        val (share, inner, hostOnly) = parsePath(path)

        if (hostOnly) {
            Log.d("XjFileSMB", "hostOnly 分支，用 jcifs-ng 列共享")
            val names = SmbShareEnumerator.listShares(
                host = host,
                username = username,
                password = password,
                domain = domain
            )
            Log.d("XjFileSMB", "jcifs-ng 列共享返回 ${names.size} 个: $names")
            return@withContext names.map { name ->
                RemoteFile(
                    name = name,
                    path = PathUtil.normalizeRemote("//$host/$name/"),
                    isDirectory = true,
                    size = 0L,
                    lastModified = 0L,
                    permission = null
                )
            }
        }

        val diskShare = getShare(share!!)
        val raw: List<FileIdBothDirectoryInformation> =
            if (inner.isEmpty()) diskShare.list("") else diskShare.list(inner)

        raw.filter { it.fileName != "." && it.fileName != ".." }
            .map { e ->
                val isDir = (e.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                RemoteFile(
                    name = e.fileName,
                    path = buildChildPath(share, inner, e.fileName, isDir),
                    isDirectory = isDir,
                    size = e.endOfFile,
                    lastModified = e.lastWriteTime?.toEpochMillis() ?: 0L,
                    permission = null
                )
            }
            .sortedWith(
                compareByDescending<RemoteFile> { it.isDirectory }
                    .thenBy(FileNameComparator) { it.name }
            )
    }

    override suspend fun download(remote: String, local: File, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            ensureConnected()
            val (share, inner, _) = parsePath(remote)
            requireNotNull(share) { "无效的 SMB 路径：$remote" }
            val diskShare = getShare(share)
            val smbFile = diskShare.openFile(
                inner,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            smbFile.use { f ->
                val total = f.fileInformation.standardInformation.endOfFile
                local.parentFile?.mkdirs()
                FileOutputStream(local).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var offset = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = f.read(buf, offset)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        offset += n
                        onProgress(offset, total)
                    }
                }
            }
        }
    }

    override suspend fun upload(local: File, remote: String, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            ensureConnected()
            val (share, inner, _) = parsePath(remote)
            requireNotNull(share) { "无效的 SMB 路径：$remote" }
            val diskShare = getShare(share)
            val smbFile = diskShare.openFile(
                inner,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null
            )
            smbFile.use { f ->
                val total = local.length()
                local.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n <= 0) break
                        f.write(buf, written)
                        written += n
                        onProgress(written, total)
                    }
                }
            }
        }
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        ensureConnected()
        val (share, inner, _) = parsePath(path)
        requireNotNull(share) { "无效的 SMB 路径：$path" }
        val diskShare = getShare(share)
        // 先分辨是目录还是文件，再走对应的删除。
        // 不能「rm 失败就当成目录递归删」——rm 失败的原因可能是没权限/被占用，
        // 这时再 rmdir(inner, true) 语义就变了。
        if (inner.isEmpty()) error("不能删除共享根目录")
        if (diskShare.folderExists(inner)) diskShare.rmdir(inner, true)
        else diskShare.rm(inner)
    }

    /**
     * 同一个共享内移动。SMB rename 天然支持跨目录移动，所以直接 rename 即可。
     * 跨共享不行，只能由上层用「复制 + 删源」实现。
     */
    override suspend fun move(from: String, to: String) = withContext(Dispatchers.IO) {
        ensureConnected()
        val (fromShare, fromInner, _) = parsePath(from)
        val (toShare, toInner, _) = parsePath(to)
        requireNotNull(fromShare) { "无效的 SMB 路径：$from" }
        requireNotNull(toShare) { "无效的 SMB 路径：$to" }
        require(fromShare == toShare) { "SMB 跨共享移动请用「复制 + 删除」" }
        val src = fromInner.trimEnd('\\')
        val dst = toInner.trimEnd('\\')
        require(src.isNotEmpty()) { "不能移动共享根目录" }
        require(dst.isNotEmpty()) { "无效的目标路径：$to" }
        if (src == dst) return@withContext
        // DiskShare 没有 rename()，必须先把条目 open 出来（DELETE 权限），再调 DiskEntry.rename。
        // newName 是相对共享根的完整路径。
        // replaceIfExists=false：目标已存在就报错，不静默覆盖（覆盖 = 直接抹掉对方的文件）。
        getShare(fromShare).open(
            src,
            EnumSet.of(AccessMask.DELETE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        ).use { it.rename(dst, false) }
    }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        ensureConnected()
        val (share, inner, _) = parsePath(path)
        requireNotNull(share)
        getShare(share).mkdir(inner)
    }

    /**
     * 重命名 = 在同一个目录里改名字。
     *
     * smbj 的 DiskShare 没有 rename()，要用 DiskEntry.rename(String, boolean)，
     * 而 DiskEntry 必须先用 DELETE 权限 open 出来。
     * newName 参数是「相对共享根的完整路径」，所以这里拼上原目录。
     */
    override suspend fun rename(path: String, newName: String) = withContext(Dispatchers.IO) {
        ensureConnected()
        val (share, inner, _) = parsePath(path)
        requireNotNull(share) { "无效的 SMB 路径：$path" }
        val name = newName.trim()
        require(name.isNotEmpty()) { "新名字不能为空" }
        require(!name.contains('\\') && !name.contains('/')) { "新名字不能包含路径分隔符" }
        val trimmed = inner.trimEnd('\\')
        require(trimmed.isNotEmpty()) { "不能重命名共享根目录" }
        val parent = trimmed.substringBeforeLast('\\', "")
        val newInner = if (parent.isEmpty()) name else "$parent\\$name"
        if (newInner == trimmed) return@withContext
        // replaceIfExists=false：新名字已被占用时让服务端报错、由 UI 提示，
        // 不要静默覆盖（那等于顺手删掉一个别人的文件）。
        getShare(share).open(
            trimmed,
            EnumSet.of(AccessMask.DELETE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        ).use { it.rename(newInner, false) }
    }

    override suspend fun size(path: String): Long? = withContext(Dispatchers.IO) {
        ensureConnected()
        val (share, inner, hostOnly) = parsePath(path)
        if (hostOnly || share == null) return@withContext null
        val diskShare = getShare(share)
        try {
            if (diskShare.fileExists(inner)) {
                diskShare.getFileInformation(inner).standardInformation.endOfFile
            } else null
        } catch (t: Throwable) {
            null
        }
    }


    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        ensureConnected()
        val (share, inner, hostOnly) = parsePath(path)
        if (hostOnly || share == null) return@withContext true
        val diskShare = getShare(share)
        try { diskShare.fileExists(inner) || diskShare.folderExists(inner) }
        catch (t: Throwable) { false }
    }

    override suspend fun openInput(path: String): InputStream = withContext(Dispatchers.IO) {
        ensureConnected()
        val (share, inner, _) = parsePath(path)
        requireNotNull(share) { "无效的 SMB 路径：$path" }
        val diskShare = getShare(share)
        val smbFile = diskShare.openFile(
            inner,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        val raw = smbFile.inputStream
        object : java.io.FilterInputStream(raw) {
            override fun close() {
                runCatching { super.close() }
                runCatching { smbFile.close() }
            }
        }
    }

    override suspend fun writeFrom(
        input: InputStream,
        targetPath: String,
        onProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        ensureConnected()
        val (share, inner, _) = parsePath(targetPath)
        requireNotNull(share) { "无效的 SMB 路径：$targetPath" }
        val diskShare = getShare(share)
        val smbFile = diskShare.openFile(
            inner,
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null
        )
        try {
            smbFile.outputStream.use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    onProgress(done, -1L)
                }
            }
        } finally {
            runCatching { smbFile.close() }
        }
    }

    // ---------------- 断点续传（SMB 是「能真续」的协议：FILE_OPEN_IF + 显式偏移写） ----------------

    override suspend fun openInputFrom(path: String, offset: Long): InputStream =
        withContext(Dispatchers.IO) {
            if (offset == 0L) return@withContext openInput(path)
            val raw = openInput(path)
            // smbj 的输入流支持 skip；这里循环到位，防止实现只 skips 一部分
            var left = offset
            while (left > 0) {
                val n = raw.skip(left)
                if (n <= 0) {
                    if (raw.read() < 0) {
                        runCatching { raw.close() }
                        throw java.io.EOFException("跳过 $offset 字节越界")
                    }
                    left--
                } else left -= n
            }
            raw
        }

    /**
     * SMB 续写：FILE_OPEN_IF 打开已存在的文件，用 write(buf, offset) 从指定偏移写。
     * 这是三种协议里唯一能「服务端真追加」的（本地也能，但本地是应用自己 seek）。
     */
    override suspend fun openOutputFrom(path: String, offset: Long): ResumeWrite =
        withContext(Dispatchers.IO) {
            ensureConnected()
            val (share, inner, _) = parsePath(path)
            requireNotNull(share) { "无效的 SMB 路径：$path" }
            val diskShare = getShare(share)
            if (offset > 0L) {
                val existing = runCatching { diskShare.getFileInformation(inner).standardInformation.endOfFile }
                    .getOrNull() ?: throw ResumeUnsupportedException("SMB 目标读不到大小")
                if (existing != offset) {
                    throw ResumeUnsupportedException("SMB 目标大小不符：期望 $offset，实际 $existing")
                }
            }
            // offset==0 但目标已存在：必须用 FILE_OVERWRITE_IF 让服务端截断。
            // smbj 0.13 没有「截断到 N 字节」的便捷 API，所以「接着写但结尾要变短」这种情形
            // 只能靠上层保证不会发生 —— 见 close() 的注释。
            val smbFile = diskShare.openFile(
                inner,
                EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                // offset==0 时用 FILE_OVERWRITE_IF：**必须**让它截断旧文件。
                // 用 FILE_OPEN_IF 只是从偏移 0 开始覆盖，旧内容比新内容长时尾巴会残留
                // （真机踩过：源被改小后重传，目标 8.8 MB 的旧尾巴还在，校验判大小不一致）。
                // offset>0 时必须用 FILE_OPEN_IF，否则会把已经传好的半截文件清掉。
                if (offset == 0L) SMB2CreateDisposition.FILE_OVERWRITE_IF
                else SMB2CreateDisposition.FILE_OPEN_IF,
                null
            )
            object : ResumeWrite {
                override var written = 0L
                    private set
                private var closed = false

                override fun write(buf: ByteArray, off: Int, len: Int) {
                    // 注意：这里**不要**调 coroutineContext.ensureActive() —— write 是普通方法，
                    // 拿不到当前协程的上下文（实测会拿到一个已取消的上下文，第一次写就抛
                    // CancellationException，整个传输被判成"取消"）。取消检查由上层
                    // ResumableTransfer 的搬运循环每块做一次。
                    smbFile.write(buf, offset + written, off, len)
                    written += len
                }

                override fun close() {
                    if (closed) return
                    closed = true
                    // smbj 0.13 没有便捷的「截断到 N 字节」API。
                    // offset==0 用 FILE_OVERWRITE_IF 打开，长度天然正确；
                    // offset>0 的正常续传路径里 expected 就是文件应有长度（源更大），
                    // 「源比半截文件短」的情况已在 openOutputFrom 里直接拒绝、回退整文件写。
                    runCatching { smbFile.close() }
                }

                override val reusable = true
            }
        }

    override suspend fun close() = withContext(Dispatchers.IO) {
        shares.values.forEach { runCatching { it.close() } }
        shares.clear()
        runCatching { session?.close() }
        runCatching { connection?.close() }
        session = null
        connection = null
    }
}