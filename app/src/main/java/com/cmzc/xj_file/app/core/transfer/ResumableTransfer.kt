package com.cmzc.xj_file.app.core.transfer

import android.util.Log
import com.cmzc.xj_file.app.core.fs.RemoteFileSystem
import com.cmzc.xj_file.app.core.fs.ResumeUnsupportedException
import com.cmzc.xj_file.app.core.fs.ResumeWrite
import kotlinx.coroutines.ensureActive

/**
 * 一次「把 src 写到 dst」的实际执行者，带断点续传。
 *
 * ## 续传的 tag
 * 每次可能可续的写都会在 [ResumeStore] 里留一条记录，四要素
 * `srcTag|srcPath|dstTag|dstPath` → `offset`（目标里已有的字节数）。
 * 暂停时保留记录和半截文件；恢复时用同一 tag 查回来，从 offset 接着写。
 *
 * ## 能力探测，而不是猜
 * - 目标 `openOutputFrom(path, offset)` 成功 → 真续传；
 * - 抛 [ResumeUnsupportedException] → 悄悄改成从 0 写（用户要求：别提示，直接重传）。
 *   SFTP 目标就属于这种：OpenSSH 对 `RESUME` 那一位直接 `failed to resume`（真机实测）。
 *
 * ## 半截文件归谁管
 * 这里只负责「写到哪就记到哪」，**删不删半截文件由 TransferManager 决定**：
 * 暂停要留（断点载体），取消要删（不留垃圾）。
 */
internal class ResumableTransfer(
    private val taskId: String,
    private val srcFs: RemoteFileSystem,
    private val srcPath: String,
    private val dstFs: RemoteFileSystem,
    private val dstPath: String,
    /** 当前任务是否处于「暂停中」。只有暂停才落断点，取消不落。 */
    private val isPaused: () -> Boolean,
    /** 上报已传字节（含续传前就有的部分）。 */
    private val onProgress: (Long) -> Unit,
    /** 回报「从哪续的、是不是真的续上了」，供日志/UI。 */
    private val onResumeInfo: (from: Long, resumed: Boolean) -> Unit
) {

    private companion object {
        const val TAG = "XjFileResume"
        const val BUF = 64 * 1024

        /** 每写这么多字节落一次断点（别为每个块都写 SharedPreferences）。 */
        const val CHECKPOINT_BYTES = 4L * 1024 * 1024
    }

    /**
     * 执行传输；正常写完返回 true。
     * 被取消/暂停打断时抛 CancellationException（由 TransferManager 收尾）。
     */
    suspend fun run(): Boolean {
        val size = runCatching { srcFs.size(srcPath) }.getOrNull() ?: -1L
        val (idSize, idMtime) = identifySource(size)

        // ---------- 1) 找一个能接的断点 ----------
        var offset = findResumeOffset(idSize, idMtime, size)
        val resumed = offset > 0
        onResumeInfo(offset, resumed)
        if (resumed) Log.d(TAG, "[$taskId] 从断点 $offset 继续")

        // ---------- 2) 打开目标写窗 ----------
        // offset==0 且目标**整体不支持**增量写时（SFTP 就是这种，它的 openOutputFrom 一律拒绝），
        // 要走常规的 writeFrom 整文件写，绝不能把这个功能直接判死。
        // 注意：这里不能直接调 suspend 的 transferLegacy()（在 catch 块里），所以只做标记。
        var writer: ResumeWrite? = null
        var useLegacyWrite = false
        try {
            writer = dstFs.openOutputFrom(dstPath, offset)
        } catch (e: ResumeUnsupportedException) {
            if (offset > 0L) {
                // 有断点但目标不支持续写 → 静默从头传一次
                Log.d(TAG, "[$taskId] 目标不支持续写，静默重传（${e.message}）")
                ResumeStore.remove(srcFs.tag, srcPath, dstFs.tag, dstPath)
                offset = 0L
                onResumeInfo(0L, false)
            }
            // 再试一次 offset=0 的增量写；还不行就走整文件写
            writer = try {
                dstFs.openOutputFrom(dstPath, 0L)
            } catch (e2: ResumeUnsupportedException) {
                Log.d(TAG, "[$taskId] 目标不支持增量写，改用 writeFrom（${e2.message}）")
                useLegacyWrite = true
                null
            }
        }

        // ---------- 2b) 整文件写兜底路径（SFTP 目标走这里） ----------
        if (useLegacyWrite) {
            transferLegacy()
            ResumeStore.remove(srcFs.tag, srcPath, dstFs.tag, dstPath)
            if (size > 0) onProgress(size)
            return true
        }
        val w = writer ?: error("写窗没打开")

        // ---------- 3) 搬运 ----------
        var written = offset
        try {
            val input = if (offset > 0L) srcFs.openInputFrom(srcPath, offset)
            else srcFs.openInput(srcPath)
            try {
                var lastCheckpoint = offset
                val buf = ByteArray(BUF)
                while (true) {
                    // ★ 必须每块检查一次取消：Job.cancel() 只把协程标记成已取消，
                    // 这里不主动检查的话（尤其 SMB 的 read/write 是普通阻塞调用），
                    // 循环会一路把整个文件写完，暂停就成了摆设。
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n <= 0) break
                    kotlin.coroutines.coroutineContext.ensureActive()
                    w.write(buf, 0, n)
                    written += n
                    onProgress(written)
                    if (written - lastCheckpoint >= CHECKPOINT_BYTES) {
                        lastCheckpoint = written
                        saveCheckpoint(idSize, idMtime, written)
                    }
                }
            } finally {
                runCatching { input.close() }
            }
            w.close()
            // 写完就作废断点：后面还有 sha 校验，校验失败会整份删掉重来
            ResumeStore.remove(srcFs.tag, srcPath, dstFs.tag, dstPath)
            if (size > 0) onProgress(size)
            return true
        } catch (t: Throwable) {
            // 暂停：把当前偏移落盘，半截文件留着当断点
            if (isPaused() && written > 0) {
                saveCheckpoint(idSize, idMtime, written)
                Log.d(TAG, "[$taskId] 暂停，断点记为 $written")
            }
            throw t
        } finally {
            runCatching { w.close() }
        }
    }

    /**
     * 兜底路径：目标整体不支持增量写（SFTP），走传统的「整文件写」。
     * 这条路**不支持暂停续传**：暂停就从头来（用户要求的「续不了就偷偷重传」）。
     */
    private suspend fun transferLegacy() {
        val size = runCatching { srcFs.size(srcPath) }.getOrNull() ?: -1L
        val input = srcFs.openInput(srcPath)
        // 先在 suspend 作用域里拿到当前协程上下文，再交给普通回调 lambda 使用
        // （在 lambda 里直接调 coroutineContext 是拿不到调用方上下文的）
        val ctx = kotlin.coroutines.coroutineContext
        try {
            dstFs.writeFrom(input, dstPath) { done, _ ->
                ctx.ensureActive()
                onProgress(done)
            }
        } finally {
            runCatching { input.close() }
        }
        if (size > 0) onProgress(size)
    }

    /**
     * 找可接的偏移。三件事都必须成立才认：
     * 1. 记录的源身份（大小+修改时间）和现在一致 —— 源被人改过就重传；
     * 2. 目标里真有那么多字节 —— 半截文件被删了就没法接；
     * 3. 还没传完。
     */
    private suspend fun findResumeOffset(idSize: Long, idMtime: Long, srcSize: Long): Long {
        val rec = ResumeStore.find(
            srcTag = srcFs.tag, srcPath = srcPath,
            dstTag = dstFs.tag, dstPath = dstPath,
            srcSize = idSize, srcMtime = idMtime
        ) ?: return 0L

        if (srcSize in 0..rec.offset) {
            Log.d(TAG, "[$taskId] 断点偏移 ${rec.offset} >= 源大小 $srcSize，作废")
            ResumeStore.remove(srcFs.tag, srcPath, dstFs.tag, dstPath)
            return 0L
        }
        // 源比断点大，但剩下的部分可能比目标文件里已有的还短 —— 这种「续写会把文件变小」
        // 的场景，除了本地之外（它能在 close 时截断）没有可靠的收尾手段，
        // 干脆作废断点、从头覆盖写（FILE_OVERWRITE_IF / FileOutputStream 都会截断）。
        val remaining = srcSize - rec.offset
        Log.d(TAG, "[$taskId] 断点 ${rec.offset}，源剩余 $remaining 字节")
        val actual = runCatching { dstFs.size(dstPath) }.getOrNull() ?: -1L
        if (actual != rec.offset) {
            Log.d(TAG, "[$taskId] 断点失效：目标实际 $actual，记录 ${rec.offset}")
            ResumeStore.remove(srcFs.tag, srcPath, dstFs.tag, dstPath)
            return 0L
        }
        return rec.offset
    }

    private suspend fun identifySource(knownSize: Long): Pair<Long, Long> {
        val mtime = runCatching {
            val parent = JobFsInfo.parentOf(srcPath)
            srcFs.list(parent).firstOrNull {
                it.path.trimEnd('/') == srcPath.trimEnd('/')
            }?.lastModified ?: 0L
        }.getOrDefault(0L)
        return knownSize to mtime
    }

    private fun saveCheckpoint(srcSize: Long, srcMtime: Long, offset: Long) {
        ResumeStore.put(
            ResumeStore.Record(
                srcTag = srcFs.tag, srcPath = srcPath,
                dstTag = dstFs.tag, dstPath = dstPath,
                srcSize = srcSize, srcMtime = srcMtime,
                offset = offset, updatedAt = System.currentTimeMillis()
            )
        )
    }
}
