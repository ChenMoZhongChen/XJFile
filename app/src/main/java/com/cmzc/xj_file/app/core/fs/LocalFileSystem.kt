package com.cmzc.xj_file.app.core.fs

import android.os.Environment
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import com.cmzc.xj_file.app.core.util.FileNameComparator
import java.io.InputStream

/**
 * 本地文件系统实现。
 *
 * 处理了三种特殊情况：
 *   /                       → 三管齐下（反射 libcore + ls 命令 + File.listFiles + 硬编码兜底）
 *   /storage/               → 虚拟列表（Android 11+ 不能直接列）
 *   /storage/emulated/      → 虚拟列表（只有 "0"）
 *
 * 其它目录走标准 File IO。依赖 MANAGE_EXTERNAL_STORAGE 权限。
 */
class LocalFileSystem : RemoteFileSystem {

    override val rootPath: String = PathUtil.LOCAL_ROOT

    override val tag: String = "local"

    override suspend fun list(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val normalized = PathUtil.normalizeLocal(path)

        when (normalized) {
            "/" -> return@withContext listRoot()
            "/storage/" -> return@withContext listStorageRoot()
            "/storage/emulated/" -> return@withContext listEmulatedRoot()
        }

        val dir = File(normalized)
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()

        val children = dir.listFiles() ?: return@withContext emptyList()
        children.map { it.toRemoteFile() }
            .sortedWith(
                compareByDescending<RemoteFile> { it.isDirectory }
                    .thenBy(FileNameComparator) { it.name }
            )
    }

    // ---------------- 根目录 / 存储根处理 ----------------

    /**
     * 列 / 根目录。
     * MT 管理器在非 root 下也就能显示这 10 个左右：data/etc/mnt/proc/product/sdcard/storage/system/system_ext/vendor
     */
    private fun listRoot(): List<RemoteFile> {
        // 1. 先尝试拿名字列表（三管齐下）
        val names: List<String> = listNamesViaLibcore()
            .takeIf { it.isNotEmpty() }
            ?: listNamesViaLs()
            ?: listNamesViaFile()
            ?: emptyList()

        if (names.isNotEmpty()) {
            val list = names.mapNotNull { name ->
                if (name.isBlank() || name == "." || name == "..") return@mapNotNull null
                val f = File("/$name")
                val isDir = runCatching { f.isDirectory }.getOrDefault(true)
                RemoteFile(
                    name = name,
                    path = if (isDir) PathUtil.normalizeLocal(f.absolutePath) else "/$name",
                    isDirectory = isDir,
                    size = 0L,
                    lastModified = runCatching { f.lastModified() }.getOrDefault(0L),
                    permission = getPermissionString(f)
                )
            }.sortedWith(compareBy(FileNameComparator) { it.name })

            if (list.isNotEmpty()) return list
        }

        // 2. 硬编码兜底
        val known = listOf(
            "acct", "apex", "bin", "bugreports", "cache", "config",
            "data", "data_mirror", "debug_ramdisk", "dev", "etc",
            "init", "linkerconfig", "mnt", "odm", "oem", "postinstall",
            "proc", "product", "root", "sbin", "sdcard", "storage",
            "sys", "system", "system_ext", "vendor"
        )
        return known.mapNotNull { name ->
            val f = File("/$name")
            if (runCatching { f.exists() }.getOrDefault(false)) {
                RemoteFile(
                    name = name,
                    path = PathUtil.normalizeLocal("/$name"),
                    isDirectory = true,
                    size = 0L,
                    lastModified = 0L,
                    permission = getPermissionString(f)
                )
            } else null
        }.sortedWith(compareBy(FileNameComparator) { it.name })
    }

    /** 手段 1：反射调 libcore.io.Libcore.os.listdir("/") */
    private fun listNamesViaLibcore(): List<String> {
        return try {
            val libcoreClass = Class.forName("libcore.io.Libcore")
            val osField = libcoreClass.getField("os")
            val osInstance = osField.get(null) ?: return emptyList()
            val m = osInstance.javaClass.getMethod("listdir", String::class.java)
            @Suppress("UNCHECKED_CAST")
            val result = m.invoke(osInstance, "/") as? Array<String>
            result?.toList() ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** 手段 2：Runtime.exec("ls -a /") */
    private fun listNamesViaLs(): List<String>? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("/system/bin/ls", "-a", "/"))
            val out = p.inputStream.bufferedReader().use { it.readLines() }
            p.waitFor()
            val filtered = out.filter { it.isNotBlank() && it != "." && it != ".." }
            if (filtered.isEmpty()) null else filtered
        } catch (t: Throwable) {
            null
        }
    }

    /** 手段 3：File.listFiles() */
    private fun listNamesViaFile(): List<String>? {
        return try {
            val files = File("/").listFiles() ?: return null
            val names = files.map { it.name }
            if (names.isEmpty()) null else names
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 列 /storage/。
     * Android 11+ 无法直接列 → 至少显示 emulated。
     */
    private fun listStorageRoot(): List<RemoteFile> {
        val result = mutableListOf<RemoteFile>()

        val direct = runCatching { File("/storage/").listFiles() }.getOrNull()
        if (direct != null) {
            direct.forEach { f ->
                result.add(
                    RemoteFile(
                        name = f.name,
                        path = PathUtil.normalizeLocal(f.absolutePath),
                        isDirectory = f.isDirectory,
                        size = 0L,
                        lastModified = f.lastModified(),
                        permission = getPermissionString(f)
                    )
                )
            }
        }

        if (result.none { it.name == "emulated" }) {
            val emulatedFile = File("/storage/emulated")
            if (emulatedFile.exists() || Environment.getExternalStorageDirectory().exists()) {
                result.add(
                    RemoteFile(
                        name = "emulated",
                        path = PathUtil.normalizeLocal("/storage/emulated"),
                        isDirectory = true,
                        size = 0L,
                        lastModified = runCatching { emulatedFile.lastModified() }.getOrDefault(0L),
                        permission = getPermissionString(emulatedFile)
                    )
                )
            }
        }

        return result.sortedWith(compareBy(FileNameComparator) { it.name })
    }

    /** 列 /storage/emulated/，通常只有 "0"。 */
    private fun listEmulatedRoot(): List<RemoteFile> {
        val primary = Environment.getExternalStorageDirectory()
        return listOf(
            RemoteFile(
                name = primary.name,
                path = PathUtil.normalizeLocal(primary.absolutePath),
                isDirectory = true,
                size = 0L,
                lastModified = runCatching { primary.lastModified() }.getOrDefault(0L),
                permission = getPermissionString(primary)
            )
        )
    }

    // ---------------- 工具方法 ----------------

    private fun File.toRemoteFile(): RemoteFile = RemoteFile(
        name = name,
        path = if (isDirectory) PathUtil.normalizeLocal(absolutePath) else absolutePath,
        isDirectory = isDirectory,
        size = if (isFile) length() else 0L,
        lastModified = lastModified(),
        permission = getPermissionString(this)
    )

    /**
     * 用 Os.stat 获取文件权限，拼成 "-rw-rw----(660)"。
     * 失败返回 null。
     */
    private fun getPermissionString(f: File): String? {
        return try {
            val stat = Os.stat(f.absolutePath)
            val mode = stat.st_mode

            val typeChar = when {
                OsConstants.S_ISDIR(mode) -> 'd'
                OsConstants.S_ISLNK(mode) -> 'l'
                OsConstants.S_ISCHR(mode) -> 'c'
                OsConstants.S_ISBLK(mode) -> 'b'
                OsConstants.S_ISFIFO(mode) -> 'p'
                OsConstants.S_ISSOCK(mode) -> 's'
                else -> '-'
            }

            val userBits = buildString {
                append(if (mode and OsConstants.S_IRUSR != 0) 'r' else '-')
                append(if (mode and OsConstants.S_IWUSR != 0) 'w' else '-')
                append(if (mode and OsConstants.S_IXUSR != 0) 'x' else '-')
            }
            val groupBits = buildString {
                append(if (mode and OsConstants.S_IRGRP != 0) 'r' else '-')
                append(if (mode and OsConstants.S_IWGRP != 0) 'w' else '-')
                append(if (mode and OsConstants.S_IXGRP != 0) 'x' else '-')
            }
            val otherBits = buildString {
                append(if (mode and OsConstants.S_IROTH != 0) 'r' else '-')
                append(if (mode and OsConstants.S_IWOTH != 0) 'w' else '-')
                append(if (mode and OsConstants.S_IXOTH != 0) 'x' else '-')
            }

            val octal = (mode and 0x1FF).toString(8).padStart(3, '0')
            "$typeChar$userBits$groupBits$otherBits($octal)"
        } catch (t: Throwable) {
            null
        }
    }

    // ---------------- 文件操作 ----------------

    override suspend fun download(remote: String, local: File, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            val src = File(remote)
            require(src.exists()) { "源文件不存在：$remote" }
            val total = src.length()
            local.parentFile?.mkdirs()
            FileInputStream(src).use { input ->
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
            require(local.exists()) { "本地文件不存在：${local.absolutePath}" }
            val total = local.length()
            val dst = File(remote)
            dst.parentFile?.mkdirs()
            FileInputStream(local).use { input ->
                FileOutputStream(dst).use { output ->
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

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val f = File(path)
        if (!f.exists()) return@withContext
        if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    override suspend fun move(from: String, to: String) = withContext(Dispatchers.IO) {
        val src = File(from)
        val dst = File(to)
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) {
            src.copyRecursively(dst, overwrite = true)
            src.deleteRecursively()
        }
    }

    override suspend fun mkdir(path: String) {
        withContext(Dispatchers.IO) {
            File(path).mkdirs()
        }
    }

    override suspend fun rename(path: String, newName: String) = withContext(Dispatchers.IO) {
        val src = File(path)
        val dst = File(src.parentFile, newName)
        if (!src.renameTo(dst)) error("重命名失败")
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        File(path).exists()
    }

    override suspend fun size(path: String): Long? = withContext(Dispatchers.IO) {
        val f = File(path)
        if (f.exists()) f.length() else null
    }

    override suspend fun openInput(path: String): InputStream = withContext(Dispatchers.IO) {
        FileInputStream(File(path))
    }

    /** 本地随机读：RandomAccessFile 定位后包成 InputStream。 */
    override suspend fun openInputFrom(path: String, offset: Long): InputStream =
        withContext(Dispatchers.IO) {
            if (offset == 0L) return@withContext openInput(path)
            val raf = java.io.RandomAccessFile(path, "r")
            raf.seek(offset)
            object : InputStream() {
                override fun read(): Int = raf.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
                override fun close() = raf.close()
            }
        }

    /**
     * 本地增量写：offset==0 覆盖（FileOutputStream 默认），offset>0 用 RandomAccessFile 定位后接着写。
     * 本地是最容易做真续传的一端，暂停后恢复确实是从断点往下写。
     */
    override suspend fun openOutputFrom(path: String, offset: Long): ResumeWrite =
        withContext(Dispatchers.IO) {
            val dst = File(path)
            dst.parentFile?.mkdirs()
            if (offset == 0L) {
                val out = FileOutputStream(dst)
                return@withContext object : ResumeWrite {
                    override var written = 0L
                        private set
                    override fun write(buf: ByteArray, off: Int, len: Int) {
                        out.write(buf, off, len)
                        written += len
                    }
                    override fun close() {
                        runCatching { out.fd.sync() }
                        runCatching { out.close() }
                    }
                    override val reusable = true
                }
            }
            // 目标必须真有这么多字节，否则没地方接
            val actual = if (dst.exists()) dst.length() else 0L
            if (actual != offset) {
                throw ResumeUnsupportedException("本地目标大小不符：期望 $offset，实际 $actual")
            }
            val raf = java.io.RandomAccessFile(dst, "rw")
            raf.seek(offset)
            object : ResumeWrite {
                override var written = 0L
                    private set
                override fun write(buf: ByteArray, off: Int, len: Int) {
                    raf.write(buf, off, len)
                    written += len
                }
                override fun close() {
                    // 续写结束把文件截到「offset + 实际写入量」：
                    // 万一源比原来的半截文件短（源被改小），RandomAccessFile 不会自动截断，
                    // 尾巴会残留导致大小校验失败。
                    runCatching { raf.setLength(offset + written) }
                    runCatching { raf.fd.sync() }
                    runCatching { raf.close() }
                }
                override val reusable = true
            }
        }

    override suspend fun writeFrom(
        input: InputStream,
        targetPath: String,
        onProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val dst = File(targetPath)
        dst.parentFile?.mkdirs()
        FileOutputStream(dst).use { out ->
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
    }

    override suspend fun close() { }
}