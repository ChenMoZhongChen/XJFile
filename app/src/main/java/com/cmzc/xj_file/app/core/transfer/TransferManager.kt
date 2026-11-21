package com.cmzc.xj_file.app.core.transfer

import android.content.Context
import android.util.Log
import com.cmzc.xj_file.app.core.fs.RemoteFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

enum class TransferStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

data class TransferTask(
    val id: String,
    /** 所属批次。一次复制/移动 = 一个批次。 */
    val batchId: String,
    /** 批次显示名（用于右侧任务列表） */
    val batchName: String,
    val name: String,
    val srcPath: String,
    val dstPath: String,
    /** 源文件大小，-1 表示未知 */
    val totalBytes: Long,
    val status: TransferStatus = TransferStatus.PENDING,
    val transferred: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val error: String? = null,
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    /** 这个文件是从断点几开始续的（0 = 从头传）。只作信息展示/日志。 */
    val resumedFrom: Long = 0L,
    /** 传输完正在算 sha256（写完了但还没验完，不算 COMPLETED）。 */
    val verifying: Boolean = false,
    /** 断点记录失效、被静默改成了从头重传（不弹提示，只在日志/属性里可见）。 */
    val silentlyRestarted: Boolean = false,
    /** 校验通过后的 sha256（拿不到源哈希时为 null）。 */
    val sha256: String? = null
) {
    val progress: Float
        get() = if (totalBytes > 0) (transferred.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    /** 还没结束：占着车道、需要前台服务续命。 */
    val isActive: Boolean
        get() = status == TransferStatus.PENDING || status == TransferStatus.RUNNING

    /** 剩余秒数。-1 表示未知 */
    val remainingSeconds: Long
        get() {
            if (status != TransferStatus.RUNNING) return -1
            if (totalBytes <= 0 || speedBytesPerSec <= 0) return -1
            return ((totalBytes - transferred) / speedBytesPerSec).coerceAtLeast(0)
        }
}

/**
 * 全局传输队列。串行执行，一次一个。
 * 所有公开方法都可在主线程调用。
 */
object TransferManager {

    private const val TAG = "XjFileTransfer"
    private const val PROGRESS_THROTTLE_MS = 200L

    /** 有活任务后，等这么久还没传完才拉前台服务（避免短任务闪通知）。 */
    private const val SERVICE_START_DELAY_MS = 1200L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val tasks: StateFlow<List<TransferTask>> = _tasks.asStateFlow()

    /**
     * 暂停粒度（版本 A：只拦「下发新任务」，正在跑的车道不打断）。
     *
     * - **批次级**：一次复制/移动 = 一个批次。进度弹窗和任务抽屉的批次行都按这个粒度暂停，
     *   所以「暂停某个复制」不会连坐别的批次 —— 比如「打开远程文件」的预览下载是独立批次，
     *   它该照常跑完。
     * - **单文件级**：只对还没开始（PENDING）的文件有意义。已经在跑的车道停不下来
     *   （版本 A 的语义：把它手上的那个文件传完）。
     *
     * 不记录断点、不删半截文件 —— 那是版本 B（真续传）才需要的。
     */
    private val _pausedBatches = MutableStateFlow<Set<String>>(emptySet())
    val pausedBatches: StateFlow<Set<String>> = _pausedBatches.asStateFlow()

    private val _pausedTasks = MutableStateFlow<Set<String>>(emptySet())
    val pausedTasks: StateFlow<Set<String>> = _pausedTasks.asStateFlow()

    /**
     * Application Context，用来拉前台服务。
     * TransferManager 是单例、自己拿不到 Context，所以由 XjFileApp.onCreate 注入一次。
     */
    @Volatile
    private var appContext: Context? = null

    /** 「延迟拉前台服务」用的 Job：短任务在它跑起来之前就结束了，就不用拉。 */
    private var serviceStartJob: Job? = null

    private class JobSpec(
        val taskId: String,
        /** 所属批次：pump() 判断「这批是不是被暂停了」要用。 */
        val batchId: String,
        val srcFs: RemoteFileSystem,
        val srcPath: String,
        val dstFs: RemoteFileSystem,
        val dstPath: String
    )

    /**
     * 保护 queue / currentJob / currentTaskId。
     * enqueue() 在主线程调，tryStartNext() 在 IO 协程的 finally 里调，
     * 两边同时动 ArrayDeque 会丢任务或 ConcurrentModificationException。
     */
    private val lock = Any()

    private val queue = ArrayDeque<JobSpec>()
    /**
     * 正在跑的任务：taskId → Job。
     * 小文件传输的时间几乎全花在「每个文件十几次往返」的固定开销上，
     * 串行跑等于把这些等待全部排队；同时跑几条「车道」就能把它们叠起来。
     * 上限 MAX_LANES：再多也吃不满带宽，反而把服务器的连接数占满。
     */
    private val running = mutableMapOf<String, Job>()

    private val settledStatuses = setOf(
        TransferStatus.COMPLETED,
        TransferStatus.FAILED,
        TransferStatus.CANCELLED
    )

    /** 同时跑几个文件的传输。小文件瓶颈是往返延迟，并行 3 条足够，再多会抢连接数。 */
    private const val MAX_LANES = 3

    /**
     * 由 XjFileApp.onCreate 调用一次：注入 Context，并开始盯着「有没有活跃任务」。
     *
     * 为什么放在这里而不是各个 enqueue 调用点：批次由 ViewModel 发起、重传由移动收尾发起、
     * 预览下载由 openRemoteFile 发起，三处都要挂前台服务，漏一处就会出现「切后台被杀」。
     * 统一在这里按状态驱动最不容易漏。
     *
     * 只负责「拉起来」，不负责「收掉」：收起动作由 TransferService 自己在任务清空时做。
     * 两边都收会抢跑 —— 实测过 stopService 先到、stopForeground 没执行，
     * 结果留下一条 ONGOING 且用户划不掉的通知。
     *
     * 注意：服务只负责「续命 + 展示」，传输仍跑在本对象的 scope 里。
     * 进程被系统杀掉后传输照样中断（本轮不做持久化/重启恢复）。
     */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        // distinctUntilChanged：进度回调 200ms 一次，不能让系统调用也跟着 200ms 一次
        scope.launch {
            tasks.map { list -> list.any { it.isActive } }
                .distinctUntilChanged()
                .collect { hasActive ->
                    val ctx = appContext ?: return@collect
                    if (hasActive) {
                        // 延迟一下再拉前台服务：像「打开远程文件」这种点开就看的短任务几百毫秒就传完了，
                        // 立刻挂前台服务会闪一条通知。拖过 1.2 秒还没传完的，才值得续命。
                        serviceStartJob?.cancel()
                        serviceStartJob = scope.launch {
                            delay(SERVICE_START_DELAY_MS)
                            if (_tasks.value.any { it.isActive }) TransferService.start(ctx)
                        }
                    } else {
                        // 全部结束：把暂停状态清干净，否则下一批复制的会「静默不开始」
                        serviceStartJob?.cancel()
                        serviceStartJob = null
                        _pausedBatches.value = emptySet()
                        _pausedTasks.value = emptySet()
                    }
                }
        }
    }

    // ===== 暂停 / 恢复（批次级 + 单文件级）=====

    /** 这个任务现在是不是被暂停了（批次级或单文件级，任一命中即算）。 */
    fun isPaused(task: TransferTask): Boolean = isTaskPaused(task.id, task.batchId)

    /** 供 runJob 内部用：任务级或批次级任一暂停即算。 */
    private fun isTaskPaused(taskId: String, batchId: String): Boolean =
        _pausedTasks.value.contains(taskId) || isBatchPausedDirect(batchId)

    private fun isBatchPausedDirect(batchId: String): Boolean =
        _pausedBatches.value.contains(batchId)

    fun isBatchPaused(batchId: String): Boolean = _pausedBatches.value.contains(batchId)

    /**
     * 暂停/恢复一个批次。
     *
     * 版本 B 起：暂停**不是**「等一下再开始」，而是让正在跑的文件**当场停下并保住半截文件**，
     * 恢复后从断点接着写。所以这里要主动 cancel 正在跑的 Job ——
     * IO 循环里的 `ensureActive()` 会抛 CancellationException，
     * runJob 的 catch 看到「当前处于暂停」就把状态退回 PENDING 而不删文件。
     */
    fun setBatchPaused(batchId: String, paused: Boolean) {
        _pausedBatches.update { if (paused) it + batchId else it - batchId }
        if (paused) {
            val jobs = synchronized(lock) {
                running.filterKeys { id -> _tasks.value.any { it.id == id && it.batchId == batchId } }
                    .values.toList()
            }
            jobs.forEach { it.cancel() }
        } else {
            pump()
        }
    }

    fun toggleBatchPause(batchId: String) = setBatchPaused(batchId, !isBatchPaused(batchId))

    /** 暂停/恢复单个文件。暂停会让正在跑的它当场停下（保留半截文件）。 */
    fun setTaskPaused(taskId: String, paused: Boolean) {
        _pausedTasks.update { if (paused) it + taskId else it - taskId }
        if (paused) {
            val job = synchronized(lock) { running[taskId] }
            job?.cancel()
        } else {
            pump()
        }
    }

    fun toggleTaskPause(taskId: String) = setTaskPaused(taskId, !_pausedTasks.value.contains(taskId))

    /** 所有活跃批次全暂停（通知栏的「暂停全部」）。 */
    fun pauseAll() {
        val ids = _tasks.value.filter { it.isActive }.map { it.batchId }.toSet()
        if (ids.isEmpty()) return
        _pausedBatches.update { it + ids }
        val jobs = synchronized(lock) { running.values.toList() }
        jobs.forEach { it.cancel() }
    }

    /** 全部恢复（通知栏的「恢复全部」），并从各自断点接着传。 */
    fun resumeAll() {
        if (_pausedBatches.value.isEmpty() && _pausedTasks.value.isEmpty()) return
        _pausedBatches.value = emptySet()
        _pausedTasks.value = emptySet()
        pump()
    }

    /** 还活着的任务是不是全被暂停了（通知栏标题和按钮要跟着变）。 */
    fun allActivePaused(): Boolean {
        val active = _tasks.value.filter { it.isActive }
        return active.isNotEmpty() && active.all { isPaused(it) }
    }

    /** 通知栏那一个按钮：全暂停 ⇄ 全恢复。 */
    fun toggleAllPause() {
        if (allActivePaused()) resumeAll() else pauseAll()
    }

    /** 入队一个传输任务。返回 taskId。 */
    fun enqueue(
        batchId: String,
        batchName: String,
        displayName: String,
        srcFs: RemoteFileSystem,
        srcPath: String,
        dstFs: RemoteFileSystem,
        dstPath: String,
        totalBytes: Long = -1L
    ): String {
        val task = TransferTask(
            id = UUID.randomUUID().toString(),
            batchId = batchId,
            batchName = batchName,
            name = displayName,
            srcPath = srcPath,
            dstPath = dstPath,
            totalBytes = totalBytes
        )
        _tasks.update { it + task }
        synchronized(lock) {
            queue.addLast(JobSpec(task.id, task.batchId, srcFs, srcPath, dstFs, dstPath))
        }
        Log.d(TAG, "enqueue ${task.id} ${srcPath} -> ${dstPath}")
        tryStartNext()
        return task.id
    }

    /**
     * 等这些任务全部结束（完成/失败/取消）。
     * 「任务已经不在列表里」也算结束 —— 用户可以在任务抽屉里移除/取消单个文件，
     * 若把它当成「还没结束」，调用方会永久挂起。
     */
    fun allSettled(ids: Set<String>): Flow<Boolean> = tasks.map { list ->
        ids.all { id ->
            val t = list.firstOrNull { it.id == id }
            t == null || t.status in settledStatuses
        }
    }

    fun cancel(taskId: String) {
        val job = synchronized(lock) { running[taskId] }
        if (job != null) {
            job.cancel()
            return
        }
        synchronized(lock) { queue.removeAll { it.taskId == taskId } }
        updateTask(taskId) {
            it.copy(status = TransferStatus.CANCELLED, finishedAt = System.currentTimeMillis())
        }
    }

    /** 从任务列表里彻底移除某个任务（含已完成/失败/取消的）。 */
    fun removeTask(taskId: String) {
        val job = synchronized(lock) {
            queue.removeAll { it.taskId == taskId }
            running[taskId]
        }
        job?.cancel()
        _tasks.update { list -> list.filterNot { it.id == taskId } }
    }

    fun cancelAll() {
        val jobs = synchronized(lock) {
            queue.clear()
            running.values.toList()
        }
        jobs.forEach { it.cancel() }
        _tasks.update { list ->
            list.map {
                if (it.status == TransferStatus.PENDING)
                    it.copy(status = TransferStatus.CANCELLED, finishedAt = System.currentTimeMillis())
                else it
            }
        }
    }

    /** 取消某个批次里所有还没结束的子任务。 */
    fun cancelBatch(batchId: String) {
        val taskIdsInBatch = _tasks.value
            .filter { it.batchId == batchId }
            .map { it.id }
            .toSet()
        val jobs = synchronized(lock) {
            queue.removeAll { it.taskId in taskIdsInBatch }
            taskIdsInBatch.mapNotNull { running[it] }
        }
        jobs.forEach { it.cancel() }
        _tasks.update { list ->
            list.map {
                if (it.batchId == batchId && it.status == TransferStatus.PENDING)
                    it.copy(status = TransferStatus.CANCELLED, finishedAt = System.currentTimeMillis())
                else it
            }
        }
    }

    /** 从队列和记录里彻底移除某个批次（含未开始的排队项）。 */
    fun removeBatch(batchId: String) {
        val taskIdsInBatch = _tasks.value
            .filter { it.batchId == batchId }
            .map { it.id }
            .toSet()
        if (taskIdsInBatch.isEmpty()) return
        val jobs = synchronized(lock) {
            queue.removeAll { it.taskId in taskIdsInBatch }
            taskIdsInBatch.mapNotNull { running[it] }
        }
        jobs.forEach { it.cancel() }
        _tasks.update { list -> list.filterNot { it.batchId == batchId } }
    }


    /** 清除已完成/失败/取消的任务 */
    fun clearFinished() {
        _tasks.update { list ->
            list.filter {
                it.status == TransferStatus.PENDING || it.status == TransferStatus.RUNNING
            }
        }
    }

    /**
     * 把队列里的任务填进空闲车道，直到车队满或者队列空。
     * 用 LAZY 启动是必须的：任务在锁内登记进 running，再在锁外 start，
     * 否则可能在登记之前就跑完了，状态对不上。
     */
    private fun pump() {
        while (true) {
            val started = synchronized(lock) {
                if (running.size >= MAX_LANES) return
                // 队列里可能有「任务已经被移除/已经跑过」的陈旧条目（暂停重排队、取消竞态都会留下），
                // 取到就必须丢掉继续取下一个 —— 否则会白跑一条车道，甚至往已删除的任务里写文件。
                val next = takeNextRunnable() ?: return
                val job = scope.launch(start = CoroutineStart.LAZY) { runJob(next) }
                running[next.taskId] = job
                next to job
            }
            val (spec, job) = started
            if (job.start()) continue
            // 还没真正开跑就被取消了：收尾，继续填下一辆。
            synchronized(lock) { running.remove(spec.taskId) }
            updateTask(spec.taskId) {
                if (it.status == TransferStatus.PENDING)
                    it.copy(status = TransferStatus.CANCELLED, finishedAt = System.currentTimeMillis())
                else it
            }
        }
    }

    /**
     * 从队列里取第一个「还能跑」的任务。
     *
     * 不能简单 removeFirst：暂停是按批次/按文件来的，被暂停的任务要留在队列里等恢复，
     * 而它后面那些没被暂停的任务应该照常跑（否则「暂停一个文件」会把整队堵死）。
     *
     * 同时顺手滤掉陈旧条目：任务已经被 removeTask/removeBatch 清掉的，直接丢弃。
     * 必须在持锁时调用（会改 queue）。
     */
    private fun takeNextRunnable(): JobSpec? {
        val pausedBatches = _pausedBatches.value
        val pausedTasks = _pausedTasks.value
        val tasks = _tasks.value
        while (queue.isNotEmpty()) {
            val spec = if (pausedBatches.isEmpty() && pausedTasks.isEmpty()) {
                queue.removeFirst()
            } else {
                val index = queue.indexOfFirst {
                    it.batchId !in pausedBatches && it.taskId !in pausedTasks
                }
                if (index < 0) return null
                queue.removeAt(index)
            }
            // 任务已经不在列表里（被移除/被清理）→ 这条 spec 作废
            val task = tasks.firstOrNull { it.id == spec.taskId } ?: continue
            // 已经结束的不再重跑
            if (task.status != TransferStatus.PENDING) continue
            return spec
        }
        return null
    }

    /** 队列 + 车道全部登记（供 enqueue 调用；语义上就是 pump）。 */
    private fun tryStartNext() = pump()

    private suspend fun runJob(spec: JobSpec) {
        Log.d(TAG, "开始任务 ${spec.taskId}: ${spec.srcPath} -> ${spec.dstPath}")
        updateTask(spec.taskId) {
            it.copy(status = TransferStatus.RUNNING, startedAt = System.currentTimeMillis())
        }
        var lastFlushTime = 0L
        var lastFlushBytes = 0L
        try {
            // ---------- 1) 搬运（带断点续传） ----------
            val mover = ResumableTransfer(
                taskId = spec.taskId,
                srcFs = spec.srcFs,
                srcPath = spec.srcPath,
                dstFs = spec.dstFs,
                dstPath = spec.dstPath,
                isPaused = { isTaskPaused(spec.taskId, spec.batchId) },
                onProgress = { done ->
                    val now = System.currentTimeMillis()
                    if (now - lastFlushTime >= PROGRESS_THROTTLE_MS) {
                        val dt = (now - lastFlushTime).coerceAtLeast(1)
                        val speed = (done - lastFlushBytes) * 1000L / dt
                        lastFlushTime = now
                        lastFlushBytes = done
                        updateTask(spec.taskId) {
                            it.copy(transferred = done, speedBytesPerSec = speed)
                        }
                    }
                },
                onResumeInfo = { from, resumed ->
                    if (from > 0 || resumed) {
                        updateTask(spec.taskId) {
                            it.copy(resumedFrom = from, silentlyRestarted = !resumed && it.resumedFrom > 0)
                        }
                    }
                }
            )
            mover.run()

            // ---------- 2) sha256 校验（写完不算完） ----------
            updateTask(spec.taskId) {
                it.copy(
                    verifying = true,
                    transferred = if (it.totalBytes > 0) it.totalBytes else it.transferred,
                    speedBytesPerSec = 0
                )
            }
            val sum = verifyIntegrity(spec)

            updateTask(spec.taskId) {
                it.copy(
                    status = TransferStatus.COMPLETED,
                    verifying = false,
                    sha256 = sum,
                    transferred = if (it.totalBytes > 0) it.totalBytes else it.transferred,
                    speedBytesPerSec = 0,
                    finishedAt = System.currentTimeMillis()
                )
            }
            Log.d(TAG, "完成 ${spec.taskId} sha=${JobFsInfo.short(sum)}")
        } catch (c: CancellationException) {
            markInterrupted(spec.taskId)
            throw c
        } catch (t: Throwable) {
            // 任务被取消时，底层库可能把 CancellationException 包成别的异常
            // （JSch 的 put 会 catch Exception 再包一层），按取消处理，别显示成"失败"
            if (!kotlin.coroutines.coroutineContext.isActive) {
                markInterrupted(spec.taskId)
                throw CancellationException("cancelled")
            }
            Log.e(TAG, "失败 ${spec.taskId}", t)
            updateTask(spec.taskId) {
                it.copy(
                    status = TransferStatus.FAILED,
                    verifying = false,
                    error = t.message ?: t.javaClass.simpleName,
                    finishedAt = System.currentTimeMillis()
                )
            }
        } finally {
            synchronized(lock) { running.remove(spec.taskId) }
            /*
             * 收尾清理的规则（版本 B 起分开了）：
             * - **取消**：把目标端的半截文件删掉（以前只有 ViewModel 的取消入口会删，
             *   通知栏的「取消全部」不经过它，所以这里补上）。
             * - **暂停**：什么都不删，也不作废断点 —— 半截文件就是断点的载体。
             * - **失败**：也不删，留给用户自己看；断点记录自然过期。
             *
             * 用 scope.launch 而不是直接调用 delete()：本协程此时可能已被取消，
             * 任何 suspend 调用都会立刻抛 CancellationException，清理就白写了。
             */
            val cur = _tasks.value.firstOrNull { it.id == spec.taskId }
            if (cur?.status == TransferStatus.CANCELLED) {
                ResumeStore.remove(spec.srcFs.tag, spec.srcPath, spec.dstFs.tag, spec.dstPath)
                scope.launch { runCatching { spec.dstFs.delete(spec.dstPath) } }
            } else if (cur?.status == TransferStatus.PENDING) {
                /*
                 * 暂停导致的中断：任务被取消时已经从队列里取出过，若不放回去，
                 * 恢复时 pump() 根本找不到它（真机踩过：点「恢复」一直卡在「准备中…」）。
                 * 放回队尾即可；pump() 的 takeNextRunnable 会跳过仍在暂停的任务，
                 * 所以这里不怕它被立刻又派出去。
                 */
                synchronized(lock) { queue.addLast(spec) }
            }
            pump()
        }
    }

    /** 取消/暂停都走这里：两者都从协程取消开始，区别在于「暂停」保留断点、状态回 PENDING。 */
    private fun markInterrupted(taskId: String) {
        val paused = _pausedTasks.value.contains(taskId) ||
            _tasks.value.firstOrNull { it.id == taskId }?.let { isBatchPausedDirect(it.batchId) } == true
        updateTask(taskId) {
            if (paused) {
                // 暂停：回到 PENDING，等恢复后从断点接着跑
                it.copy(status = TransferStatus.PENDING, speedBytesPerSec = 0)
            } else {
                it.copy(status = TransferStatus.CANCELLED, speedBytesPerSec = 0, finishedAt = System.currentTimeMillis())
            }
        }
    }

    /**
     * 校验目标文件：
     * 1. 大小必须和源一致；
     * 2. sha256 必须一致（拿不到源哈希时，只能退回到「只比大小」，并记日志）。
     * 校验不过 → 删掉目标文件、作废断点、抛异常（该批次里的文件会被上层判为失败，
     * 「移动」流程据此不删源并重传）。
     */
    private suspend fun verifyIntegrity(spec: JobSpec): String? {
        val srcSum = runCatching { spec.srcFs.sha256(spec.srcPath) }.getOrNull()
        if (srcSum == null) {
            Log.w(TAG, "源无法计算 sha256，只能比对大小: ${spec.srcPath}")
            return null
        }
        val srcSize = runCatching { spec.srcFs.size(spec.srcPath) }.getOrNull() ?: -1L
        val dstSize = runCatching { spec.dstFs.size(spec.dstPath) }.getOrNull() ?: -1L
        if (srcSize > 0 && dstSize != srcSize) {
            failAndCleanup(spec, "大小不一致（源 $srcSize / 目标 $dstSize）")
        }
        val dstSum = runCatching { spec.dstFs.sha256(spec.dstPath) }.getOrNull()
        if (dstSum == null) {
            Log.w(TAG, "目标无法计算 sha256，跳过哈希比对: ${spec.dstPath}")
            return null
        }
        if (!srcSum.equals(dstSum, ignoreCase = true)) {
            failAndCleanup(spec, "sha256 不一致")
        }
        return dstSum
    }

    private suspend fun failAndCleanup(spec: JobSpec, reason: String): Nothing {
        Log.e(TAG, "校验失败 $reason: ${spec.dstPath}")
        ResumeStore.remove(spec.srcFs.tag, spec.srcPath, spec.dstFs.tag, spec.dstPath)
        runCatching { spec.dstFs.delete(spec.dstPath) }
        throw IllegalStateException("校验失败：$reason")
    }

    private fun updateTask(id: String, transform: (TransferTask) -> TransferTask) {
        _tasks.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }
}