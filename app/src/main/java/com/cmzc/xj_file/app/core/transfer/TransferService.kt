package com.cmzc.xj_file.app.core.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cmzc.xj_file.app.MainActivity
import com.cmzc.xj_file.app.core.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 传输前台服务。
 *
 * 职责只有两件事：
 * 1. 「续命」—— 传输期间让进程活在前台，切后台/锁屏不被系统回收；
 * 2. 「展示」—— 通知栏显示整体进度，并提供暂停/恢复、取消全部。
 *
 * 它**不持有**任何传输逻辑：任务、队列、车道都在 TransferManager 里，
 * 本服务只是 TransferManager.tasks 的一个观察者。所以服务被意外停掉不影响传输（反过来才影响）。
 *
 * 生命周期：TransferManager 只负责「有活任务时把它拉起来」；
 * 收起完全由本服务自己决定（任务清空 → stopForeground(REMOVE) + stopSelf）。
 * 两边都收会抢跑 —— 实测过 stopService 先到、stopForeground 没执行，
 * 留下一条 ONGOING 而且用户划不掉的通知。
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 上次真正 notify 的时间，用来节流（进度会 200ms 一次，通知不能被这么刷）。 */
    private var lastNotifyAt = 0L

    /** 上次通知里的「结构性」内容（状态/文件名/计数）。变了就立刻刷，不等节流。 */
    private var lastStructural = ""

    /** 上次通知里的完整签名（含百分比）。没变就别重复 notify。 */
    private var lastSignature = ""

    /** 后台保活用：传输期间别让 CPU 睡、别让 WiFi 省电降速。 */
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        // startForegroundService 之后必须尽快 startForeground，否则 5 秒后 ANR/崩溃。
        // 这里先摆一条「准备中」，真正的进度由下面的收集器补上。
        startForeground(
            NOTIF_ID,
            buildNotification(Summary.EMPTY),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        acquireKeepAliveLocks()

        scope.launch {
            TransferManager.tasks.collect { list ->
                val summary = summarize(list)
                if (summary.activeCount == 0) {
                    // 任务全清空（完成/取消/被移除）：收掉通知和服务
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                maybeNotify(summary, list)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 动作（暂停/取消）走 TransferActionReceiver 广播，这里不处理任何 action。
        // 进程被杀后不重启：传输状态在内存里，重启一个空服务没有意义。
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        releaseKeepAliveLocks()
        // 兜底：不管是 stopSelf 还是被系统停掉，都不留僵尸通知。
        // 通知是 setOngoing(true)，用户自己划不掉，只能由我们取消。
        stopForeground(STOP_FOREGROUND_REMOVE)
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) }
        super.onDestroy()
    }

    /**
     * 后台保活：只拿两个「轻量」的锁，不阻止息屏、不阻止锁屏。
     *
     * - PARTIAL_WAKE_LOCK：传输期间 CPU 别睡（Doze/息屏后网络 IO 会被节流，大文件会卡在半路）。
     * - WifiLock：别让 WiFi 进省电模式掉速/断连。
     *
     * 真正杀死进程的是「用户划掉」「厂商 i管家清理」「系统内存回收」，
     * 这些应用层救不了；能救的是「息屏后被 Doze 掐掉网络」这一类。
     */
    private fun acquireKeepAliveLocks() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "获取保活锁失败", it) }
    }

    private fun releaseKeepAliveLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    /**
     * 节流刷新通知：
     * - 什么都没变 → 直接返回，不打扰系统；
     * - 只有百分比在动 → 最快 NOTIFY_THROTTLE_MS 一次；
     * - 结构性内容变了（文件传完、暂停/恢复、开始新文件）→ 立刻刷。
     */
    private fun maybeNotify(summary: Summary, list: List<TransferTask>) {
        val structural =
            "${summary.paused}|${summary.total}|${summary.completed}|${summary.failed}|${summary.runningName}"
        val signature = "$structural|${summary.percent}"
        if (signature == lastSignature) return

        val now = SystemClock.elapsedRealtime()
        val structuralChanged = structural != lastStructural
        if (!structuralChanged && now - lastNotifyAt < NOTIFY_THROTTLE_MS) return

        lastSignature = signature
        lastStructural = structural
        lastNotifyAt = now
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(
                NOTIF_ID,
                buildNotification(summary)
            )
        }
    }

    private fun buildNotification(summary: Summary): Notification {
        val title = when {
            summary.total == 0 -> "准备传输…"
            summary.paused -> "传输已暂停"
            summary.activeCount == 0 -> "传输完成"
            else -> "正在传输 ${summary.activeCount} 个文件"
        }

        val counts = if (summary.total == 0) {
            "准备中…"
        } else buildString {
            append("已完成 ${summary.completed} / 共 ${summary.total}")
            if (summary.failed > 0) append(" · 失败 ${summary.failed}")
            if (summary.sumTotal > 0) {
                append(" · ${Formatters.formatSize(summary.sumDone)}")
                append(" / ${Formatters.formatSize(summary.sumTotal)}")
            }
        }

        val bigText = buildString {
            append(counts)
            append('\n')
            if (summary.paused) {
                // 版本 B 起暂停是真的原地停住（半截文件保留、断点落盘），
                // 所以这里不能再写「当前文件传完后不再开始新文件」那种版本 A 的文案。
                append("已暂停：已传的部分会保留，恢复后接着传")
                if (summary.speed > 0) append("（当前 ${Formatters.formatSize(summary.speed)}/s）")
            } else {
                append("当前文件：${summary.runningName ?: "准备中…"}")
                if (summary.speed > 0) append("\n速度：${Formatters.formatSize(summary.speed)}/s")
            }
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            REQ_OPEN,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 动作用广播而不是直接丢给 Service：广播这条路径不需要再启动一次服务，
        // 也就不会踩 Android 12+ 的后台启动限制。
        val toggleIntent = PendingIntent.getBroadcast(
            this,
            REQ_TOGGLE,
            Intent(this, TransferActionReceiver::class.java)
                .setAction(TransferActionReceiver.ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getBroadcast(
            this,
            REQ_CANCEL,
            Intent(this, TransferActionReceiver::class.java)
                .setAction(TransferActionReceiver.ACTION_CANCEL_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(counts)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setProgress(100, summary.percent, summary.sumTotal <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent)
            .addAction(
                if (summary.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (summary.paused) "恢复全部" else "暂停全部",
                toggleIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "取消全部",
                cancelIntent
            )
            .build()
    }

    /**
     * 通知栏只关心「还在传的这一批」。
     * TransferManager.tasks 里会留着上一批已完成的任务（任务抽屉要展示），
     * 若直接按全量算，第二批 8 个文件会显示成「已完成 15 / 共 16」，看着像出错。
     */
    private class Summary(
        val activeCount: Int,
        val total: Int,
        val completed: Int,
        val failed: Int,
        val percent: Int,
        val runningName: String?,
        val speed: Long,
        val sumDone: Long,
        val sumTotal: Long,
        /** 还活着的任务是不是全被暂停了（批次级或单文件级都算）。 */
        val paused: Boolean
    ) {
        companion object {
            val EMPTY = Summary(0, 0, 0, 0, 0, null, 0, 0, 0, false)
        }
    }

    private fun summarize(list: List<TransferTask>): Summary {
        val active = list.filter { it.isActive }
        // 有批次在跑时只看这些批次；没有活跃任务时（即将 stopSelf）用全量，内容无所谓
        val activeBatchIds = active.map { it.batchId }.toSet()
        val relevant = if (activeBatchIds.isEmpty()) list else list.filter { it.batchId in activeBatchIds }

        val sumTotal = relevant.sumOf { if (it.totalBytes > 0) it.totalBytes else 0L }
        val sumDone = relevant.sumOf { it.transferred }
        val percent = if (sumTotal > 0) (sumDone * 100 / sumTotal).toInt().coerceIn(0, 100) else 0

        return Summary(
            activeCount = active.size,
            total = relevant.size,
            completed = relevant.count { it.status == TransferStatus.COMPLETED },
            failed = relevant.count { it.status == TransferStatus.FAILED },
            percent = percent,
            runningName = active.firstOrNull { it.status == TransferStatus.RUNNING }?.name,
            speed = relevant.sumOf { it.speedBytesPerSec },
            sumDone = sumDone,
            sumTotal = sumTotal,
            paused = active.isNotEmpty() && active.all { TransferManager.isPaused(it) }
        )
    }

    companion object {
        private const val TAG = "XjFileTransfer"

        const val CHANNEL_ID = "xj_transfer"
        const val NOTIF_ID = 1001
        private const val WAKE_LOCK_TAG = "XjFile:transfer"
        private const val REQ_OPEN = 1
        private const val REQ_TOGGLE = 2
        private const val REQ_CANCEL = 3

        /** 进度刷新的最快间隔。 */
        private const val NOTIFY_THROTTLE_MS = 700L

        /** 通知频道。写在 XjFileApp.onCreate 里调用一次即可（重复创建是幂等的）。 */
        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "文件传输",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "传输进度，可暂停/恢复/取消"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }

        /** 拉起前台服务。App 在后台时系统可能拒绝，记一笔日志就好，传输本身不受影响。 */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TransferService::class.java)
                )
            }.onFailure { Log.w(TAG, "启动前台服务失败", it) }
        }
    }
}
