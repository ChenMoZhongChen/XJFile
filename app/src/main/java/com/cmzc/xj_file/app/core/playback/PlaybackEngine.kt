package com.cmzc.xj_file.app.core.playback

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.cmzc.xj_file.app.core.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 全局音频播放器（进程级单例）。
 *
 * **为什么播放器不能留在 PlayerScreen 里**：
 * 之前的播放器是 Compose `remember` 出来的，页面一销毁就 release —— 切后台、锁屏、
 * Activity 被系统回收，任何一个都会让音乐和 CD 直接断掉。音频要「退到后台继续放」，
 * 播放器就必须活在进程上，由 [PlaybackService] 负责续命和通知，而不是由某个页面管生死。
 *
 * **为什么只给音频用，视频仍用页面内播放器**：
 * 1. 用户只要求音频后台播放，视频退后台继续解码没有意义，反而占解码器和网络；
 * 2. CD 需要 2 分钟起步的缓冲（见下），视频用这套缓冲会白占几十 MB 内存；
 * 3. 视频的 `DisposableEffect` 释放时序（延后 300ms）是专门调过的，不动它最稳。
 *
 * 播放器本身用 application context 创建，不持有 Activity，页面走光也不会泄漏。
 */
@OptIn(UnstableApi::class)
object PlaybackEngine {

    /**
     * 通知栏/媒体会话要展示的状态。
     *
     * [keepAwake] 用「按过播放」而不是「正在出声」判断：CD 起播要等 3~5 秒的远端
     * PowerShell，缓冲期间 `isPlaying` 是 false，这时候把 CPU/WiFi 锁放掉，
     * 后台播放会卡在起播那一刻。
     */
    data class State(
        val title: String = "",
        val subtitle: String = "",
        val playing: Boolean = false,
        val playWhenReady: Boolean = false,
    ) {
        val keepAwake: Boolean get() = playing || playWhenReady
    }

    private const val TAG = "XjPlayback"

    /** Application context；XjFileApp 里 init，服务/UI 都从这里拿。 */
    private var appContext: Context? = null

    @Volatile
    private var exo: ExoPlayer? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 取全局音频播放器。第一次调用时创建；之后整个进程共用。
     *
     * ExoPlayer 必须在带 Looper 的线程创建，这里只允许主线程 —— 调用方只有
     * PlayerScreen 和前台服务，都在主线程。
     */
    fun player(context: Context): ExoPlayer {
        appContext = context.applicationContext
        exo?.let { return it }
        check(Looper.myLooper() == Looper.getMainLooper()) { "音频播放器必须在主线程创建" }
        return synchronized(this) {
            exo ?: createPlayer(context.applicationContext).also { exo = it }
        }
    }

    private fun createPlayer(context: Context): ExoPlayer {
        // 硬解失败自动换软解：和视频页同一套配置，音频容器（flac/ape 之类）出问题能救回来
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        // CD 的缓冲必须够大：远端每次 seek/补数据都要重开一条 PowerShell（3~5 秒），
        // 默认 50 秒缓冲会让加载线程频繁停下等播放，光驱跟着反复起停（听着像一直巡道）。
        // 单曲音频用同一套缓冲也只是内存略大一点，换来一套播放器、少一条要维护的路径。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 120_000,
                /* maxBufferMs = */ 240_000,
                /* bufferForPlaybackMs = */ 2_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
            )
            .build()

        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .build()
            .apply {
                val self = this
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ true
                )
                // 拔耳机/断蓝牙自动暂停，避免突然外放
                setHandleAudioBecomingNoisy(true)
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _state.update { it.copy(playing = isPlaying) }
                        DebugLog.ev(context, "audio isPlaying=$isPlaying")
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        _state.update { it.copy(playWhenReady = playWhenReady) }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        // 播完最后一句：isPlaying 已经变 false，但 playWhenReady 可能还是 true。
                        // 不主动 pause 的话，前台服务会一直拿着 CPU/WiFi 锁、通知一直写「正在播放」，
                        // 用户放着不管就是一整晚的耗电。
                        if (playbackState == Player.STATE_ENDED) {
                            _state.update { it.copy(playing = false, playWhenReady = false) }
                            runCatching { self.pause() }
                        }
                    }
                })
            }
    }

    /** 换一首/换一轨时刷新通知和媒体会话的文字。 */
    fun updateNowPlaying(title: String, subtitle: String) {
        _state.update { st ->
            if (st.title == title && st.subtitle == subtitle) st
            else st.copy(title = title, subtitle = subtitle)
        }
    }

    /**
     * 拉起播放前台服务。
     *
     * 必须在 App 处于前台时调用（用户点开音频页的那一刻），Android 12+ 不允许
     * 后台随意启动前台服务；真正播放时服务已经在了，熄屏/切后台都不受影响。
     */
    fun startService(context: Context) {
        val ctx = appContext ?: context.applicationContext
        runCatching {
            ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java))
        }.onFailure { Log.w(TAG, "启动播放前台服务失败", it) }
    }

    fun play() {
        val p = exo ?: return
        // 播到结尾后按钮会变成「播放」：不先 seek 回 0，按下去只会立刻又停在结尾。
        if (p.playbackState == Player.STATE_ENDED) {
            runCatching { p.seekTo(0L) }
        }
        // 通知栏「停止」之后播放器停在 IDLE（数据源已关、光驱进程已杀），
        // 再按播放要先把同一首重新 prepare 起来，否则按了没反应。
        if (p.playbackState == Player.STATE_IDLE && p.mediaItemCount > 0) {
            runCatching { p.prepare() }
        }
        p.play()
        ensureService()
    }

    fun pause() {
        exo?.pause()
    }

    /** 通知栏的播放/暂停按钮：按「用户意图」判断，缓冲中也能正确切回去。 */
    fun toggle() {
        val p = exo ?: return
        if (p.isPlaying || p.playWhenReady) p.pause() else play()
    }

    /**
     * 停止并收掉通知/服务。
     *
     * 只 stop 不 release，也**不 clearMediaItems**：播放器留着、当前这条媒体也留着，
     * 用户从通知栏按了「停止」再回到页面时，点播放还能从原地重新 prepare（对 CD 来说
     * 就是按当前 LBA 重开一条远端流）。stop() 本身会关掉数据源，进而按 token 杀掉
     * 远端读盘进程，光驱不会继续转。
     */
    fun stop() {
        val p = exo
        if (p != null) {
            // 先把 playWhenReady 放掉：不然页面上的按钮会以为还在播，点了是「暂停」
            runCatching { p.pause() }
            runCatching { p.stop() }
        }
        _state.update { it.copy(playing = false, playWhenReady = false) }
        val ctx = appContext
        if (ctx != null) {
            runCatching { ctx.stopService(Intent(ctx, PlaybackService::class.java)) }
        }
    }

    private fun ensureService() {
        val ctx = appContext ?: return
        runCatching {
            ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java))
        }.onFailure { Log.w(TAG, "启动播放前台服务失败", it) }
    }
}
