package com.cmzc.xj_file.app.core.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.cmzc.xj_file.app.MainActivity
import com.cmzc.xj_file.app.core.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 音频播放前台服务。
 *
 * 职责和 [com.cmzc.xj_file.app.core.transfer.TransferService] 一样：**只续命、只展示**。
 * 真正播放的是 [PlaybackEngine] 里的那个 ExoPlayer，本服务不碰播放逻辑；
 * 服务被系统停掉时最多丢通知，播放器还在进程里（进程活着的期间）。
 *
 * 为什么必须是 `mediaPlayback` 类型的前台服务：targetSdk 34 下，App 退到后台还想
 * 持续放音频/持续读网络，只有这一条合法路径；否则息屏几分钟就会被 Doze 掐掉，
 * CD 更明显（SSH 流一断就要重开 3~5 秒的 PowerShell）。
 *
 * 通知和锁屏控制用系统自带的 [MediaSession] + [Notification.MediaStyle]，
 * 不额外引入 media3-session（这个工程锁 compileSdk 34 / media3 1.3.1，
 * 能不加依赖就不加）。
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 锁屏/蓝牙/耳机按键的媒体会话。创建失败也不能影响播放，所以整体 runCatching。 */
    private var session: MediaSession? = null

    /** 后台保活：播放期间 CPU 别睡、WiFi 别进省电模式（CD 是持续的 SSH 流）。 */
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** 上次通知的完整签名，状态没变就不重复 notify。 */
    private var lastSignature = ""

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        runCatching { createSession() }.onFailure { Log.e(TAG, "媒体会话创建失败", it) }
        // startForegroundService 后必须尽快 startForeground；先摆一条当前状态，
        // 真正的标题/播放状态由下面的收集器刷新。
        startForeground(
            NOTIF_ID,
            buildNotification(PlaybackEngine.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        if (PlaybackEngine.state.value.keepAwake) acquireLocks()
        DebugLog.ev(this, "PlaybackService onCreate")

        scope.launch {
            PlaybackEngine.state.collect { st ->
                if (st.keepAwake) acquireLocks() else releaseLocks()
                runCatching { refresh(st) }
                    .onFailure { Log.w(TAG, "刷新播放通知失败", it) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 动作（播放/暂停/停止）走 PlaybackActionReceiver 广播，这里不处理。
        // 进程被杀后不重启：播放器状态在内存里，重启一个空服务没有意义。
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        runCatching { session?.release() }
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) }
        DebugLog.ev(this, "PlaybackService onDestroy")
        super.onDestroy()
    }

    /** 媒体会话：让锁屏、蓝牙、耳机按键能控制播放。 */
    private fun createSession() {
        val s = MediaSession(this, "XjPlayback")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = PlaybackEngine.play()
            override fun onPause() = PlaybackEngine.pause()
            override fun onStop() = PlaybackEngine.stop()
        })
        s.setSessionActivity(openAppIntent())
        s.isActive = true
        session = s
    }

    /** 状态变了：同步媒体会话和通知。 */
    private fun refresh(st: PlaybackEngine.State) {
        updateSession(st)
        val signature = "${st.title}|${st.subtitle}|${st.playing}|${st.playWhenReady}"
        if (signature == lastSignature) return
        lastSignature = signature
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(st))
        }
    }

    private fun updateSession(st: PlaybackEngine.State) {
        val s = session ?: return
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, st.title.ifBlank { "正在播放" })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, st.subtitle)
                .build()
        )
        val state = if (st.playing || st.playWhenReady) {
            PlaybackState.STATE_PLAYING
        } else {
            PlaybackState.STATE_PAUSED
        }
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP
                )
                // 位置用 UNKNOWN：这里不做每秒刷新，给个假进度反而会被系统拿去画错位置
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun buildNotification(st: PlaybackEngine.State): Notification {
        val title = st.title.ifBlank { "正在播放" }
        val text = when {
            st.subtitle.isNotBlank() && (st.playing || st.playWhenReady) -> "${st.subtitle} · 正在播放"
            st.subtitle.isNotBlank() -> "${st.subtitle} · 已暂停"
            st.playing || st.playWhenReady -> "正在播放"
            else -> "已暂停"
        }
        val toggleIntent = broadcast(PlaybackActionReceiver.ACTION_TOGGLE, REQ_TOGGLE)
        val stopIntent = broadcast(PlaybackActionReceiver.ACTION_STOP, REQ_STOP)

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(
                action(
                    if (st.playing || st.playWhenReady) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play,
                    if (st.playing || st.playWhenReady) "暂停" else "播放",
                    toggleIntent
                )
            )
            .addAction(
                action(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "停止",
                    stopIntent
                )
            )

        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1)
        session?.let { style.setMediaSession(it.sessionToken) }
        return builder.setStyle(style).build()
    }

    private fun action(icon: Int, title: String, intent: PendingIntent): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, intent).build()

    private fun broadcast(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(this, PlaybackActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            REQ_OPEN,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * 只拿两个轻量锁，不阻止息屏：
     * - PARTIAL_WAKE_LOCK：后台播放时 CPU 别睡（CD 的 SSH 读流被挂起就会欠载）；
     * - WifiLock：WiFi 别进省电模式，SMB/SFTP 上的音频和 CD 都靠它。
     */
    private fun acquireLocks() {
        if (wakeLock?.isHeld == true && wifiLock?.isHeld == true) return
        runCatching {
            if (wakeLock?.isHeld != true) {
                wakeLock = getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
            if (wifiLock?.isHeld != true) {
                @Suppress("DEPRECATION")
                wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                    ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WAKE_LOCK_TAG)
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
        }.onFailure { Log.w(TAG, "获取播放保活锁失败", it) }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        private const val TAG = "XjPlayback"

        const val CHANNEL_ID = "xj_playback"
        private const val NOTIF_ID = 2001
        private const val WAKE_LOCK_TAG = "XjFile:playback"
        private const val REQ_OPEN = 11
        private const val REQ_TOGGLE = 12
        private const val REQ_STOP = 13

        /** 通知频道。Application 和 Service 各调用一次也无害（创建是幂等的）。 */
        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音频播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台播放音频 / CD 的控制"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }
    }
}
