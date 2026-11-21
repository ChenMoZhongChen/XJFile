package com.cmzc.xj_file.app.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.cmzc.xj_file.app.core.fs.RemoteFile
import com.cmzc.xj_file.app.core.playback.MediaTypes
import com.cmzc.xj_file.app.core.playback.PlaybackEngine
import com.cmzc.xj_file.app.core.playback.PlaybackSource
import com.cmzc.xj_file.app.core.playback.ViewerPage
import com.cmzc.xj_file.app.ui.components.MediaFilmstrip
import com.cmzc.xj_file.app.ui.components.ViewerTopBar
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 内置播放页（阶段四）：整屏盖在主界面上面，边下边播。
 *
 * 播放器不放进 ViewModel：MainActivity 已声明 `configChanges=orientation|screenSize`，
 * 横竖屏不会重建 Composable，`remember` 足够活过旋转；挂在 Activity 级 ViewModel 上
 * 反而要额外管「什么时候 release」，一漏就是连接泄漏（SMB 句柄/SFTP 车道）。
 *
 * 翻页规则（用户明确要求）：**只在视频之间切**，上一个/下一个按钮永远是视频，
 * 不会切着切着蹦出一张图片。只有关掉「严格分离」时，底部缩略图条才允许跨类型跳。
 *
 * 交互：
 * - 顶栏：返回 / 文件名 / 上一个 / 下一个 / 全屏横屏
 * - 长按右边缘 = 3 倍速快进（松开恢复），长按左边缘 = 连续后退
 * - 失败一律回退「整份下载 → 系统播放器」，界面只说人话，不显示错误码
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    pages: List<ViewerPage>,
    startIndex: Int,
    strip: List<ViewerPage>,
    onBack: () -> Unit,
    onFallback: (ViewerPage, String) -> Unit,
    onCrossType: (RemoteFile) -> Unit,
    /** 相册打开：pages 是图片 + 视频混排，滑到图片要换渲染器。 */
    mixed: Boolean = false,
    /** 混排时滑到了图片页：交给上层换成图片查看页。 */
    onPageChanged: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }

    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))) }
    var landscape by remember { mutableStateOf(false) }
    var edgeMode by remember { mutableIntStateOf(0) }
    var failureReason by remember { mutableStateOf<String?>(null) }

    val page = pages.getOrNull(index) ?: pages.first()
    val isCd = page.source is PlaybackSource.Cdda
    // 音频 CD 的「文件」叫「音轨 N」，没有扩展名；靠数据源类型把它认出来
    val isAudio = isCd || MediaTypes.isAudio(page.file.name)

    // ★ 音频用进程级播放器（PlaybackEngine）：退到后台、熄屏、Activity 被系统回收都要继续放，
    //   由 PlaybackService 前台服务续命。视频不享受这个待遇 —— 后台解码没意义，还要占解码器。
    //   页面销毁时不 release 全局播放器；停不停由「返回 / 跨类型 / 播放失败」这几条显式路径决定。
    val isOwnPlayer = !isAudio
    val player = remember(isAudio) {
        if (!isOwnPlayer) {
            PlaybackEngine.player(context)
        } else {
            // setEnableDecoderFallback：硬解初始化失败（编码不在硬解白名单、被别的 App 占用）
            // 时自动换软解，能救回来一大半「明明能播却报解码错误」的片子。
            val renderers = DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            ExoPlayer.Builder(context, renderers).build().apply {
                // 申请音频焦点：来电/别的 App 放声音时按系统规则压低或暂停，而不是两边一起响。
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true
                )
                // 拔耳机/断蓝牙自动暂停，避免突然外放
                setHandleAudioBecomingNoisy(true)
            }
        }
    }

    // 打开视频页时把还在后台放的音频收掉：同一个 App 不叠两路声音。
    // 只认 isAudio 变化，翻页不会误触发。
    LaunchedEffect(isAudio) {
        if (!isAudio) PlaybackEngine.stop()
    }

    // 左右边缘长按都是快进（用户明确要求：两边都应该是快进，不再有"左边缘回退"）
    val edge = remember { EdgeSpeedController(player) { edgeMode = it } }

    // 进/退全屏：强制横屏 + 隐藏状态栏/导航栏。离开播放页必须还原，
    // 否则回到文件列表时还是横的、状态栏也没了。
    fun applyFullscreen(enter: Boolean) {
        landscape = enter
        val act = activity ?: return
        val window = act.window
        if (enter) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        } else {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(player, isOwnPlayer) {
        onDispose {
            // 视频页的播放器是本页面私有的：离开就释放。
            // 从视频滑到图片时会销毁本页：立刻 release() 要释放解码器/网络数据源，
            // 在主线程上能卡出明显一顿。这里先暂停，延后一拍再收，页面切换先完成。
            if (isOwnPlayer) {
                player.pause()
                val doomed = player
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runCatching { doomed.release() }
                }, 300L)
            }
            // 音频的全局播放器**不在这里释放**：页面退出可能只是 Activity 被系统回收，
            // 声音还得继续。真正要停的时候由 closePlayer()/跨类型/失败这几条路径调
            // PlaybackEngine.stop()，它会顺带收掉前台服务和通知。
            // 播放器没了，横屏/隐藏状态栏也必须跟着还原
            if (landscape) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                val window = activity?.window
                if (window != null) {
                    WindowCompat.getInsetsController(window, window.decorView)
                        .show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "内置播放失败 ${error.errorCodeName} ${error.message}", error)
                failureReason = error.errorCodeName ?: "播放失败"
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 换页 = 换数据源，但**不重建播放器**（setMediaSource 复用解码器/音频通道，快得多）
    LaunchedEffect(page.file.path) {
        if (isAudio) {
            // 先登记「现在播的是谁」，再把前台服务拉起来：之后锁屏/切后台的通知和
            // 锁屏控制都靠这一份状态。服务在用户点开页面时启动，属于前台启动，不违规。
            PlaybackEngine.updateNowPlaying(page.file.name, if (isCd) "音频 CD" else "音频")
            PlaybackEngine.startService(context)
        }
        // 防抖：快速连按「下一首」时，key 会连着变，LaunchedEffect 会被取消重来，
        // 只有最后停下的那一页才真的去起流。600ms 能吃掉连点的抖动，又不会让单次切歌
        // 明显变慢。多个 reader 同时读同一台光驱时，光驱会在各自 LBA 之间来回跳，
        // 听起来就是疯狂巡道。
        delay(600)
        val meta = MediaMetadata.Builder().setTitle(page.file.name)
        if (isCd) meta.setArtist("音频 CD")
        val mediaSource = ProgressiveMediaSource
            .Factory(page.source.dataSourceFactory(context))
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(page.source.uri)
                    .setMediaMetadata(meta.build())
                    .build()
            )
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
    }

    // 只回退一次：failureReason 一旦置位就走人
    LaunchedEffect(failureReason) {
        val reason = failureReason ?: return@LaunchedEffect
        failureReason = null
        // 音频失败要把前台服务/通知一起收掉，否则界面回退了、通知栏还挂着「正在播放」
        if (isAudio) PlaybackEngine.stop()
        onFallback(page, reason)
    }

    // 切后台/熄屏：视频暂停（没必要在后台继续解码，也省得占着网络连接白白耗电）；
    // 音频**不暂停** —— 这正是本次要的后台播放，进程由 PlaybackService 保活。
    DisposableEffect(lifecycleOwner, isAudio) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !isAudio) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun goTo(target: Int) {
        if (target !in pages.indices || target == index) return
        edge.stop()
        player.setPlaybackParameters(PlaybackParameters.DEFAULT)
        index = target
    }

    // 用户明确要求：只有音频 CD 自动切轨，普通音频/视频播完就停。
    //
    // PlaybackEngine 自己的监听在 STATE_ENDED 时会先 pause()，这里随后把 index
    // 挪到下一轨；下一页的 LaunchedEffect 会重新 setMediaSource + prepare + play。
    // 监听器跟着 index 重建，闭包里读到的永远是当前页，不会在快速切轨时错位。
    DisposableEffect(player, isCd, index, pages) {
        if (!isCd) {
            onDispose { }
        } else {
            var handled = false
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (!handled && playbackState == Player.STATE_ENDED && index < pages.lastIndex) {
                        handled = true
                        goTo(index + 1)
                    }
                }
            }
            player.addListener(listener)
            onDispose { player.removeListener(listener) }
        }
    }

    /**
     * 视频页的「左右滑动换页」。
     *
     * 为什么不盖一层 Compose 手势：整屏是 PlayerView，盖上去会把控制条挡死。
     * 这里用 GestureDetector 在 dispatchTouchEvent 里旁听：只认横向 fling，
     * 底部 22% 是控制条区域，留给进度条拖动，不抢。
     *
     * 回调里读的都是快照状态（index/pages），所以 handler 只建一次也不会读到旧值。
     */
    val swipeHandler = remember { SwipeHandler(context) }
    swipeHandler.onSwipe = { dir ->
        val target = index + dir
        if (target in pages.indices) {
            edge.stop()
            player.setPlaybackParameters(PlaybackParameters.DEFAULT)
            if (mixed && MediaTypes.isImage(pages[target].file.name)) {
                // 从音频滑到图片：播放页要被图片页顶掉，先把声音和通知收干净
                if (isAudio) PlaybackEngine.stop()
                onPageChanged(target)
            } else {
                goTo(target)
            }
        }
    }

    /**
     * 返回：音频顺手停掉（通知/服务一起收），视频交给 onDispose 释放。
     * 用户按返回就是「我不听了」，和按 Home 的「退到后台继续」要区分开。
     */
    fun closePlayer() {
        if (isAudio) PlaybackEngine.stop()
        onBack()
    }

    // 全屏时返回键先退全屏，再按一次才退出播放页
    BackHandler(enabled = true) {
        if (landscape) applyFullscreen(false) else closePlayer()
    }

    // 缩略图条只给视频页：音频页下面已经是「本文件夹全部音频」的播放列表，
    // 再压一条重复的缩略图既没用又占地方（CD 页一直就是没有缩略图条的）。
    val bottomInset = if (strip.isNotEmpty() && !isAudio) 76.dp else 0.dp

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (isAudio) {
            // 音频没有画面，PlayerView 只会给一块黑底 + 一条控制条，太敷衍。
            // 换成封面/标签/进度条/控制键的播放页，播放本身还是同一个 ExoPlayer。
            AudioPlayerSurface(
                player = player,
                page = page,
                canPrev = index > 0,
                canNext = index < pages.lastIndex,
                onPrev = { goTo(index - 1) },
                onNext = { goTo(index + 1) },
                // 音频 CD 是 TOC 音轨；普通音频是「当前文件夹的全部音频文件」。
                // 两者用同一套播放列表 UI，用户不用来回点「下一首」。
                tracks = if (isAudio) pages else emptyList(),
                currentIndex = index,
                onSelectTrack = { goTo(it) },
                modifier = Modifier.fillMaxSize().padding(bottom = bottomInset)
            )
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    // 用 dispatchTouchEvent 而不是 setOnTouchListener 观察边缘长按：
                    // 控制条显示时，触摸先被 PlayerControlView 这个子 View 吃掉，
                    // OnTouchListener 根本不会被回调（踩过）。dispatchTouchEvent 在分发给子 View
                    // 之前就拿到事件，而且我们只观察、不拦截，控制条照常工作。
                    object : PlayerView(ctx) {
                        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                            edge.onTouch(this, ev)
                            swipeHandler.onTouch(this, ev)
                            return super.dispatchTouchEvent(ev)
                        }
                    }.apply {
                        this.player = player
                        useController = true
                        // 看视频时别让屏幕自己睡过去
                        keepScreenOn = true
                        // 网络卡顿时给个转圈，否则用户只看到黑屏以为卡死
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                        // 视频页换页改成左右滑动，控制条里自带的"上一个/下一个"就是误导，直接藏掉
                        findViewById<View>(androidx.media3.ui.R.id.exo_prev)?.visibility = View.GONE
                        findViewById<View>(androidx.media3.ui.R.id.exo_next)?.visibility = View.GONE
                        // 底部缩略图条会压住控制条，整条往上让 32dp，进度条才不会被吃掉一半
                        val pad = (32 * resources.displayMetrics.density).toInt()
                        setPadding(0, 0, 0, pad)
                    }
                },
                update = { it.player = player }
            )
        }

        // 倍速 HUD：只在长按生效期间显示（音频没有画面，也就没有边缘长按）
        if (!isAudio && edgeMode != 0) {
            Text(
                text = "快进 ${FAST_FORWARD_SPEED.toInt()} 倍",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(if (edgeMode > 0) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 18.dp)
                    .background(Color(0x99000000), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        ViewerTopBar(
            title = page.file.name,
            onBack = { closePlayer() },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            // 视频页不再放"上一个/下一个"：按用户要求改成左右滑动换页。
            // 音频页（CD）保留按钮，因为那边还有曲目列表、翻页也是点按的语义。
            if (isAudio) {
                IconButton(
                    onClick = { goTo(index - 1) },
                    enabled = index > 0
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "上一个",
                        tint = if (index > 0) Color.White else Color(0x55FFFFFF)
                    )
                }
                IconButton(
                    onClick = { goTo(index + 1) },
                    enabled = index < pages.lastIndex
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "下一个",
                        tint = if (index < pages.lastIndex) Color.White else Color(0x55FFFFFF)
                    )
                }
            }
            // 全屏只对视频有意义；音频页面本来就是竖屏一块牌子，别给它一个点了没用的按钮
            if (!isAudio) {
                IconButton(onClick = { applyFullscreen(!landscape) }) {
                    Icon(
                        imageVector = if (landscape) Icons.Filled.FullscreenExit
                        else Icons.Filled.Fullscreen,
                        contentDescription = if (landscape) "退出全屏" else "全屏",
                        tint = Color.White
                    )
                }
            }
        }

        // 缩略图条常显（非空时，仅视频页）：只在控制条收起时显示的话，
        // 视频播完停在末帧、控制条不再自动隐藏，这条就永远不出现了（踩过）。
        // 它会盖住控制条最下面那行「时间文字 + 齿轮」，但进度条本身在它上面，够用。
        if (strip.isNotEmpty() && !isAudio) {
            MediaFilmstrip(
                items = strip,
                currentPath = page.file.path,
                onSelect = { picked ->
                    val target = pages.indexOfFirst { it.file.path == picked.file.path }
                    if (mixed && target >= 0) {
                        if (MediaTypes.isImage(picked.file.name)) {
                            // 音频页被图片页顶掉：先停声音，别让两套页面同时出声
                            if (isAudio) PlaybackEngine.stop()
                            onPageChanged(target)
                        } else {
                            goTo(target)
                        }
                    } else if (MediaTypes.isImage(picked.file.name)) {
                        if (isAudio) PlaybackEngine.stop()
                        onCrossType(picked.file)
                    } else {
                        if (target >= 0) {
                            goTo(target)
                        } else {
                            // 点的是另一类媒体（比如音频条里点了视频）：这页守不住了，
                            // 交给上层换查看页，声音也要跟着收。
                            if (isAudio) PlaybackEngine.stop()
                            onCrossType(picked.file)
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/**
 * 长按左右边缘的倍速 / 快退控制。
 *
 * 为什么挂在 View 的 OnTouchListener 上、而不是盖一层 Compose 手势：
 * 整个画面就是 PlayerView，盖一层 Compose 手势会把播放器自带的控制条挡死。
 * OnTouchListener 只观察、永远返回 false，控制条照常工作。
 *
 * 快进用 3 倍速（真实变速）；快退用「每 350ms 往回跳 3 秒」——
 * ExoPlayer 不支持负速率播放，只能靠连续 seek 做等效快退。
 */
private class EdgeSpeedController(
    private val player: ExoPlayer,
    private val onModeChanged: (Int) -> Unit,
) {
    private var pendingDir = 0
    private var mode = 0

    private val startRunnable = Runnable { activate(pendingDir) }

    fun onTouch(view: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val width = view.width
                if (width <= 0) return
                pendingDir = when {
                    event.x < width * EDGE_FRACTION -> -1
                    event.x > width * (1f - EDGE_FRACTION) -> 1
                    else -> 0
                }
                if (pendingDir != 0) view.postDelayed(startRunnable, HOLD_MS)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(startRunnable)
                pendingDir = 0
                stop()
            }
        }
    }

    /** 左、右边缘都走同一条路：3 倍速快进（用户要求两边都算快进）。 */
    private fun activate(dir: Int) {
        if (dir == 0 || mode == dir) return
        mode = dir
        player.setPlaybackParameters(PlaybackParameters(FAST_FORWARD_SPEED))
        onModeChanged(dir)
    }

    fun stop() {
        if (mode != 0) {
            mode = 0
            player.setPlaybackParameters(PlaybackParameters.DEFAULT)
            onModeChanged(0)
        }
    }

    private companion object {
        /** 边缘判定带宽：左右各 28%，中间 44% 留给「轻点显示控制条」。 */
        const val EDGE_FRACTION = 0.28f
        const val HOLD_MS = 350L
    }
}

/**
 * 视频页的横向滑动换页。
 *
 * 只旁听、不拦截手势本身：PlayerView 的控制条（进度条拖动、暂停）照常工作。
 * 判定条件收紧一点（横向 >140px 且横压竖 1.5 倍），避免控制条上的小抖动被当成翻页；
 * 底部 22%（控制条区域）直接不参与，防止拖进度条时误翻页。
 */
private class SwipeHandler(context: Context) {
    /** 每次重组都会刷新这个回调，所以它读到的 index/pages 永远是最新的。 */
    var onSwipe: (Int) -> Unit = {}

    private var viewHeight = 0
    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) < SWIPE_MIN_PX) return false
                if (abs(dx) < abs(dy) * 1.5f) return false
                if (viewHeight > 0 && e1.y > viewHeight * 0.78f) return false
                onSwipe(if (dx < 0) 1 else -1)
                return true
            }
        }
    )

    fun onTouch(view: View, event: MotionEvent) {
        viewHeight = view.height
        detector.onTouchEvent(event)
    }

    private companion object {
        const val SWIPE_MIN_PX = 140f
    }
}

/** 倍速档位（HUD 和实际变速共用一份）。 */
private const val FAST_FORWARD_SPEED = 3f

/** Compose 的 LocalContext 可能是 ContextWrapper，真要拿 Activity 得一层层剥。 */
private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private const val TAG = "XjPlayer"
