package com.cmzc.xj_file.app.ui.image

import android.graphics.drawable.AnimatedImageDrawable
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cmzc.xj_file.app.core.fs.RemoteFile
import com.cmzc.xj_file.app.core.image.ImageLoader
import com.cmzc.xj_file.app.core.image.Thumbnails
import com.cmzc.xj_file.app.core.playback.MediaTypes
import com.cmzc.xj_file.app.core.playback.ViewerPage
import com.cmzc.xj_file.app.core.util.DebugLog
import com.cmzc.xj_file.app.ui.components.MediaFilmstrip
import com.cmzc.xj_file.app.ui.components.ViewerTopBar
import kotlinx.coroutines.launch

/**
 * 内置图片查看页（和播放页同一层覆盖在主界面上）。
 *
 * 翻页规则（用户明确要求）：**只在图片之间左右翻**。视频永远不会出现在这个 pager 里，
 * 哪怕列表里图片和视频是挨着的 —— 底部缩略图条在「混合浏览」打开时才会跨类型。
 *
 * 手势：单指左右滑 = 翻页；双指缩放 / 放大后单指拖动 = 看细节；双击在 1× 和 2.5× 之间切。
 * 关键点：**没放大时单指拖动必须留给 pager**，所以手势检测只在「双指」或「已放大」时才消费事件。
 *
 * 顶栏的旋转按钮按 90° 一档转；旋转只影响显示，不动文件。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewerScreen(
    pages: List<ViewerPage>,
    startIndex: Int,
    strip: List<ViewerPage>,
    onBack: () -> Unit,
    onCrossType: (RemoteFile) -> Unit,
    /** 相册打开：pages 是图片 + 视频混排。 */
    mixed: Boolean = false,
    /** 滑到了另一页（混排跨类型时把控制权交给播放页）。 */
    onPageChanged: (Int) -> Unit = {},
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val pagerState = rememberPagerState(initialPage = startIndex) { pages.size }
    val scope = rememberCoroutineScope()
    var zoomed by remember { mutableStateOf(false) }
    // 旋转是"这一张图"的状态：换到下一张就重置
    var rotation by remember(pagerState.currentPage) { mutableIntStateOf(0) }

    val targetPx = remember(configuration.screenWidthDp, configuration.screenHeightDp, density) {
        val w = with(density) { configuration.screenWidthDp.dp.toPx() }.toInt()
        val h = with(density) { configuration.screenHeightDp.dp.toPx() }.toInt()
        maxOf(w, h)
    }

    val current = pages.getOrNull(pagerState.currentPage) ?: pages.first()

    // 相册混排：滑到视频页就不再当图片解，直接把控制权交给播放页。
    // 放在 settledPage 而不是 currentPage，是为了等翻页动画停下来再切，避免来回抖。
    LaunchedEffect(pagerState.settledPage, pages) {
        if (!mixed) return@LaunchedEffect
        val settled = pagerState.settledPage
        val target = pages.getOrNull(settled) ?: return@LaunchedEffect
        if (MediaTypes.isVideo(target.file.name)) onPageChanged(settled)
    }

    // 离开查看页就把解码缓存放掉：一屏大图动辄十几 MB，留着没意义
    DisposableEffect(Unit) {
        onDispose {
            ImageLoader.clearCache()
            Thumbnails.clear()
        }
    }

    BackHandler(enabled = true) { onBack() }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            // 放大之后单指留给平移，否则一拖就翻页，看细节时很难受
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize()
        ) { index ->
            ImagePage(
                page = pages[index],
                targetPx = targetPx,
                rotation = rotation,
                onZoomChanged = { isZoomed ->
                    if (pagerState.currentPage == index) zoomed = isZoomed
                }
            )
        }

        ViewerTopBar(
            title = current.file.name,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            IconButton(onClick = { rotation = (rotation + 90) % 360 }) {
                Icon(
                    imageVector = Icons.Filled.RotateRight,
                    contentDescription = "旋转",
                    tint = Color.White
                )
            }
        }

        if (strip.isNotEmpty()) {
            MediaFilmstrip(
                items = strip,
                currentPath = current.file.path,
                onSelect = { picked ->
                    val target = pages.indexOfFirst { it.file.path == picked.file.path }
                    if (mixed) {
                        // 混排：同一个列表里的直接翻过去（视频页由上面那个 LaunchedEffect 接管）
                        if (target >= 0) scope.launch { pagerState.animateScrollToPage(target) }
                        else onCrossType(picked.file)
                    } else if (MediaTypes.isImage(picked.file.name)) {
                        if (target >= 0) {
                            scope.launch { pagerState.animateScrollToPage(target) }
                        } else {
                            onCrossType(picked.file)
                        }
                    } else {
                        // 缩略图条点到视频：交给上层换成播放器（跨类型只在开关关闭时可能发生）
                        onCrossType(picked.file)
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/** 解码结果：静态位图，或者动图（GIF / 动图 WebP）。 */
private sealed interface ImageContent {
    class Still(val bitmap: ImageBitmap) : ImageContent
    class Animated(val drawable: AnimatedImageDrawable) : ImageContent
}

/** 这些扩展名才值得试动图解码；其余一律走静态路径（省一次整文件读取）。 */
private val ANIMATED_EXTS = setOf("gif", "webp")

/** 一页图片：自己负责加载、渲染。失败只在页内提示，绝不把整个查看页踢掉。 */
@Composable
private fun ImagePage(
    page: ViewerPage,
    targetPx: Int,
    rotation: Int,
    onZoomChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var content by remember(page.file.path) { mutableStateOf<ImageContent?>(null) }
    var progress by remember(page.file.path) { mutableFloatStateOf(0f) }
    var failure by remember(page.file.path) { mutableStateOf<String?>(null) }
    val isVideo = MediaTypes.isVideo(page.file.name)

    LaunchedEffect(page.file.path) {
        // 相册混排时这一页可能是视频：不解码，交给播放页（父层会切过去）
        if (isVideo) return@LaunchedEffect
        try {
            var loaded: ImageContent? = null
            val ext = page.file.name.substringAfterLast('.', "").lowercase()
            if (ext in ANIMATED_EXTS) {
                // 先按动图解：GIF / 动图 WebP 会拿到 AnimatedImageDrawable
                val animated = ImageLoader.loadAnimated(context, page.source, targetPx, targetPx)
                if (animated != null) loaded = ImageContent.Animated(animated)
            }
            if (loaded == null) {
                val bitmap = ImageLoader.load(
                    context = context,
                    source = page.source,
                    targetWidth = targetPx,
                    targetHeight = targetPx
                ) { done, total ->
                    if (total > 0) progress = (done.toFloat() / total).coerceIn(0f, 1f)
                }
                loaded = ImageContent.Still(bitmap.asImageBitmap())
            }
            content = loaded
        } catch (t: Throwable) {
            Log.e(TAG, "图片打开失败 ${page.file.name}", t)
            // vivo 屏蔽 logcat，失败原因写进应用私有日志，便于事后定位格式问题
            DebugLog.ev(context, "image-fail size=${page.file.size} path=${page.file.path} ${t.message}")
            failure = t.message ?: "解码失败"
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val c = content) {
            null -> when {
                failure != null -> Text(
                    text = if (page.file.size == 0L) "文件是空的（0 字节）" else "图片打不开",
                    color = Color(0xFF9A9A9A),
                    fontSize = 14.sp
                )

                isVideo -> Text(text = "视频", color = Color(0xFF9A9A9A), fontSize = 14.sp)

                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = Color(0xFFCCCCCC),
                        strokeWidth = 2.dp
                    )
                    if (progress > 0f) {
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            color = Color(0xFF9A9A9A),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }

            else -> ImageViewport(c, rotation = rotation, onZoomChanged = onZoomChanged)
        }
    }
}

/**
 * 画图 + 手势。
 *
 * 为什么自己算矩阵、而不是 `Image` + `graphicsLayer`：加了旋转之后，
 * 竖图转 90° 就放不进原来那个"已按原方向 fit 好"的布局框，会被裁掉。
 * 这里统一用「先算 fit 比例、再 rotate/scale/translate」一次画出来，
 * 旋转、缩放、平移互不打架。
 */
@Composable
private fun ImageViewport(
    content: ImageContent,
    rotation: Int,
    onZoomChanged: (Boolean) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    // 动图需要我们自己按帧重画（Drawable 离开 View 后不会自动申请重绘）
    val frameTick = remember { mutableIntStateOf(0) }
    val animated = content is ImageContent.Animated

    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            frameTick.intValue++
        }
    }

    fun clampPan(pan: Offset, s: Float): Offset {
        val maxX = box.width * (s - 1f) / 2f
        val maxY = box.height * (s - 1f) / 2f
        return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            offset = Offset.Zero
                        }
                        onZoomChanged(scale > 1.01f)
                    }
                )
            }
            .pointerInput(Unit) {
                // 手写手势而不是 detectTransformGestures：后者会无条件消费单指拖动，
                // 那样 pager 就永远翻不动页面了。这里只在「双指」或「已放大」时接管。
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var twoFingers = false
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val down = event.changes.filter { it.pressed }
                        pressed = down.isNotEmpty()
                        if (down.size >= 2) twoFingers = true
                        if ((twoFingers || scale > 1.01f) && down.isNotEmpty()) {
                            val next = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            scale = next
                            offset = if (next <= 1.01f) Offset.Zero
                            else clampPan(offset + event.calculatePan(), next)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            onZoomChanged(next > 1.01f)
                        }
                    }
                }
            }
    ) {
        val tick = frameTick.intValue // 读一下 → 绘图层订阅帧号；值本身不参与计算
        val (iw, ih) = when (content) {
            is ImageContent.Still ->
                content.bitmap.width.toFloat() to content.bitmap.height.toFloat()

            is ImageContent.Animated ->
                content.drawable.intrinsicWidth.toFloat() to
                    content.drawable.intrinsicHeight.toFloat()
        }
        if (iw <= 0f || ih <= 0f) return@Canvas

        val turned = rotation % 180 != 0
        val fit = minOf(
            size.width / (if (turned) ih else iw),
            size.height / (if (turned) iw else ih)
        )

        withTransform({
            translate(size.width / 2f + offset.x, size.height / 2f + offset.y)
            rotate(rotation.toFloat(), Offset.Zero)
            scale(fit * scale, fit * scale, Offset.Zero)
            translate(-iw / 2f, -ih / 2f)
        }) {
            when (content) {
                is ImageContent.Still -> drawImage(content.bitmap, topLeft = Offset.Zero)

                is ImageContent.Animated -> {
                    // tick 只是为了触发重绘；具体显示哪一帧由 Drawable 按时间自己推进
                    if (tick >= Int.MIN_VALUE) {
                        drawIntoCanvas { canvas ->
                            content.drawable.setBounds(0, 0, iw.toInt(), ih.toInt())
                            content.drawable.draw(canvas.nativeCanvas)
                        }
                    }
                }
            }
        }
    }
}

private const val TAG = "XjImageViewer"
