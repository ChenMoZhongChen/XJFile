package com.cmzc.xj_file.app.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cmzc.xj_file.app.core.disk.DriveInfo
import com.cmzc.xj_file.app.core.fs.RemoteFile
import com.cmzc.xj_file.app.core.fs.PathUtil
import com.cmzc.xj_file.app.core.settings.AppSettings
import com.cmzc.xj_file.app.core.util.Formatters
import com.cmzc.xj_file.app.ui.theme.LocalAnimationsEnabled
import com.cmzc.xj_file.app.ui.theme.LocalModuleColors
import com.cmzc.xj_file.app.vm.PanelUiState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 两个面板的滚动位置缓存放在 Composable 外：
 * 打开压缩包时 FilePanel 会被临时移出组合，局部的 remember 会丢；
 * 用 panelKey + sourceId + path 作为键，退出压缩包后还能回到原滚动位置。
 */
private val panelListStateCache = mutableMapOf<String, LazyListState>()

@Composable
fun FilePanel(
    state: PanelUiState,
    focused: Boolean,
    onFocus: () -> Unit,
    onClearError: () -> Unit,
    onOpen: (RemoteFile) -> Unit,
    onLongPress: (RemoteFile) -> Unit,
    onDriveLongPress: (DriveInfo, RemoteFile) -> Unit = { _, _ -> },
    onSwipeSelect: (RemoteFile) -> Unit,
    onToggleSelect: (RemoteFile) -> Unit,
    onPathClick: () -> Unit,
    onGoUp: () -> Unit,
    highlightPath: String? = null,
    onHighlightConsumed: () -> Unit = {},
    onRefresh: () -> Unit = {},
    scrollToTopTick: Int = 0,
    onCopyPath: (String) -> Unit = {},
    onDisconnect: () -> Unit = {},
    loadThumbnail: (suspend (RemoteFile, Int) -> Bitmap?)? = null,
    panelKey: String = "default",
    modifier: Modifier = Modifier
) {
    // 每个面板、每个路径一个独立 LazyListState，返回旧目录或退出压缩包时能恢复滚动位置
    val listState = panelListStateCache.getOrPut(
        "$panelKey|${state.sourceId}|${state.currentPath}"
    ) {
        LazyListState()
    }

    // 文件栏不走「界面动画」总开关：设置里关掉动画时，文件列表的跟手/高亮仍然保留
    // （用户要求「除了文件栏」以外都不展示动画）。
    // 唯一例外是「新建后滚动到新文件」——那次长距离滚动关掉动画时直接跳过去。
    val animationsOn = LocalAnimationsEnabled.current

    LaunchedEffect(highlightPath, state.items, animationsOn) {
        val target = highlightPath ?: return@LaunchedEffect
        val idx = state.items.indexOfFirst { it.path == target }
        if (idx < 0) return@LaunchedEffect
        val offset = if (state.canGoUp) 1 else 0
        if (animationsOn) listState.animateScrollToItem(idx + offset)
        else listState.scrollToItem(idx + offset)
    }

    // 「回到顶部」：顶栏按钮只把计数 +1，这里监听变化再滚。
    // 用计数而不是 Boolean，是为了连点也能每次都生效（Boolean 得先复位）。
    LaunchedEffect(scrollToTopTick) {
        if (scrollToTopTick > 0) {
            // 空列表（刚切目录还没加载完）时滚到 0 会越界，直接跳过
            if (listState.layoutInfo.totalItemsCount > 0) {
                listState.animateScrollToItem(0)
            }
        }
    }

    val consumeHighlight: () -> Unit = {
        if (highlightPath != null) onHighlightConsumed()
    }

    // ============ 下拉刷新（实验性，默认关闭）============
    // 设置里「下拉刷新（实验性）」打开才拦截手势；关着时完全不干预列表滚动。
    // 打开时：列表跟手一起下滑，拖过约两行半（2.5 行）才真正刷新。
    val pullEnabled by AppSettings.pullToRefresh.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    // 这台机器上实测一行（名字 + 时间/大小 + 权限）约 132px ≈ 50dp
    val rowHeightPx = with(density) { 50.dp.toPx() }
    val thresholdPx = rowHeightPx * 2.5f               // 拖 2.5 行 = 刷新
    val maxPullPx = thresholdPx * 1.15f                // 超过阈值后留一点橡皮筋余量
    var pullOffset by remember { mutableFloatStateOf(0f) }
    var triggered by remember { mutableStateOf(false) }
    // 手指是否还按在屏上
    var fingerDown by remember { mutableStateOf(false) }
    // 手势序号：每次重新按下 +1。用来判断「刷新完成时，手指还是触发这次刷新的那一根吗」。
    var gestureSeq by remember { mutableIntStateOf(0) }
    var triggerGesture by remember { mutableIntStateOf(-1) }
    // 刷新完成、已经瞬间归位之后，本次手势剩下的位移全部吃掉
    var suppressDrag by remember { mutableStateOf(false) }

    // 关掉开关时立刻清掉可能残留的拉下状态
    LaunchedEffect(pullEnabled) {
        if (!pullEnabled) {
            pullOffset = 0f
            triggered = false
            suppressDrag = false
        }
    }

    // 刷新结束 → 列表**瞬间**弹回原位（不做平滑动画），不管手指还在不在屏上。
    // 盯 loadSeq 而不是 loading：本地目录刷新几十毫秒就结束，loading 的 true→false
    // 可能被 Compose 合并掉，用只增不减的计数才不会丢。
    LaunchedEffect(state.loadSeq) {
        if (triggered) {
            pullOffset = 0f
            triggered = false
            // 只有还是触发这次刷新的那次手势，才需要吃掉剩余位移
            suppressDrag = fingerDown && gestureSeq == triggerGesture
        }
    }

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(
            available: Offset,
            source: NestedScrollSource
        ): Offset {
            if (!pullEnabled) return Offset.Zero
            if (state.loading) return Offset.Zero
            if (state.selectionMode) return Offset.Zero

            // 刷新已结束、手指还没抬：本次手势的位移全部吃掉，列表保持归位状态
            if (suppressDrag) return Offset(0f, available.y)

            // 手指往回（上）推：先把已经拉出来的距离收回去，列表本身先别动
            if (available.y < 0f) {
                if (pullOffset <= 0f) return Offset.Zero
                val delta = available.y.coerceAtLeast(-pullOffset)
                pullOffset += delta
                return Offset(0f, delta)
            }

            // 新的一次下拉（从 0 开始）无条件清掉上一轮的标记，双保险
            if (pullOffset <= 0f && triggered) {
                triggered = false
            }

            val atTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
            if (!atTop) return Offset.Zero

            val target = (pullOffset + available.y).coerceAtMost(maxPullPx)
            val consumed = target - pullOffset
            pullOffset = target

            if (!triggered && pullOffset >= thresholdPx) {
                triggered = true
                triggerGesture = gestureSeq
                onRefresh()
            }
            return Offset(0f, consumed)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!pullEnabled) return Velocity.Zero
            if (!triggered && pullOffset > 0f) {
                while (pullOffset > 0f) {
                    pullOffset = (pullOffset - 40f).coerceAtLeast(0f)
                    delay(16)
                }
            }
            return Velocity.Zero
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                if (focused) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.background
            )
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.any { it.pressed }
                        val newDown = event.changes.any { it.pressed && !it.previousPressed }
                        if (newDown) {
                            // 每一次新的按下都重置：即使上一轮的 fingerDown 因为漏事件卡住了，
                            // 这里也能把 suppressDrag 清干净（否则后续下拉会一直被吃掉）。
                            fingerDown = true
                            gestureSeq++
                            suppressDrag = false
                        }
                        if (pressed) {
                            onFocus()
                            onClearError()
                        } else if (fingerDown) {
                            fingerDown = false
                            suppressDrag = false
                        }
                    }
                }
            }
    ) {
        PathBar(
            sourceLabel = state.sourceDisplayLabel,
            path = state.currentPath,
            onClick = {
                consumeHighlight()
                onFocus()
                onClearError()
                onPathClick()
            },
            onCopyPath = onCopyPath
        )

        if (state.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }
        state.error?.let { err ->
            Text(
                text = "⚠ $err",
                color = MaterialTheme.colorScheme.error,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .nestedScroll(nestedScrollConnection)
        ) {
            LazyColumn(
                state = listState,
                // 跟手：整列向下平移。用 graphicsLayer 而不是 offset，
                // 是因为前者只做绘制位移、不触发重新布局，下拉时更跟手。
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = pullOffset }
            ) {
                if (state.canGoUp) {
                    item(key = "\u002E\u002E") {
                        ParentRow(
                            onClick = {
                                consumeHighlight()
                                onFocus()
                                onClearError()
                                onGoUp()
                            }
                        )
                    }
                } else if (PathUtil.isRemoteHostRoot(state.currentPath)) {
                    // 共享列表视图：.. 变成「断开连接」
                    item(key = "\u002E\u002E_disconnect") {
                        ParentRow(
                            label = "..（断开连接）",
                            onClick = {
                                consumeHighlight()
                                onFocus()
                                onClearError()
                                onDisconnect()
                            }
                        )
                    }
                }
                // 磁盘行以「探针结果」为准，不看目录列表：音频 CD 的盘符
                // OpenSSH 根本不会列出来（没文件系统），但它恰恰是最需要显示的一行。
                if (state.drivesLoading) {
                    // 探测期间显示占位骨架，且**不可点** —— 先画出可点的 "C:" 会让人点了没反应
                    items(6) { DriveLoadingRow() }
                }
                val driveRows = if (state.drives.isNotEmpty()) {
                    state.drives.map { d ->
                        d to RemoteFile(
                            name = "${d.letter}:",
                            path = state.currentPath.trimEnd('/') + "/${d.letter}:/",
                            isDirectory = true,
                            size = d.sizeBytes,
                            lastModified = 0
                        )
                    }
                } else {
                    emptyList()
                }
                items(driveRows, key = { "drive:" + it.first.letter }) { (drive, entry) ->
                    DriveRow(
                        info = drive,
                        selected = entry.path in state.selection,
                        highlighted = entry.path == highlightPath,
                        showSelection = state.selectionMode,
                        onClick = {
                            consumeHighlight()
                            onFocus()
                            onClearError()
                            onOpen(entry)
                        },
                        onLongClick = {
                            consumeHighlight()
                            onFocus()
                            onClearError()
                            onDriveLongPress(drive, entry)
                        }
                    )
                }
                // 探针没覆盖到的普通条目（根目录下没有的话通常为空）。
                // 加载期间整段不画：目录列表里的 "C:" "D:" 就是盘符本身，先画出来等于给了个假按钮。
                if (!state.drivesLoading) {
                    items(
                        state.items.filter { state.drives.byEntryName(it.name) == null },
                        key = { it.path }
                    ) { item ->
                    FileRow(
                        item = item,
                        selected = item.path in state.selection,
                        selectionMode = state.selectionMode,
                        highlighted = item.path == highlightPath,
                        loadThumbnail = loadThumbnail,
                        onClick = {
                            consumeHighlight()
                            onFocus()
                            onClearError()
                            if (state.selectionMode) {
                                onToggleSelect(item)
                            } else {
                                onOpen(item)
                            }
                        },
                        onLongClick = {
                            consumeHighlight()
                            onFocus()
                            onClearError()
                            onLongPress(item)
                        },
                        onSwipeLeft = {
                            consumeHighlight()
                            onFocus()
                            onClearError()
                            onSwipeSelect(item)
                        }
                    )
                    }
                }
            }

            // 顶部下拉指示器：跟着列表一起往下走，停在拉开的那道缝里
            if (pullEnabled && pullOffset > 0f) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .size(24.dp)
                        .graphicsLayer {
                            translationY = pullOffset * 0.5f
                            alpha = (pullOffset / thresholdPx).coerceIn(0f, 1f)
                        },
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
private fun ParentRow(label: String = "..", onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickableCompat(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = LocalModuleColors.current.folder,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: RemoteFile,
    selected: Boolean,
    selectionMode: Boolean,
    highlighted: Boolean,
    loadThumbnail: (suspend (RemoteFile, Int) -> Bitmap?)?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipeLeft: () -> Unit
) {
    // 文件栏的动画不受总开关影响
    val moduleColors = LocalModuleColors.current
    val targetBg = when {
        highlighted -> MaterialTheme.colorScheme.tertiaryContainer
        selected && selectionMode -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val bg by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(durationMillis = 350),
        label = "fileRowBg"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .pointerInput(item.path) {
                // 横滑多选。老实现用 detectHorizontalDragGestures：只要横向斜率稍占优，
                // 它就会吃掉事件，竖着滚列表时手指一歪就被判成「滑动多选」（用户反馈误判）。
                //
                // 现在的规则（用户定的）：横向至少划 40dp 才算「想多选」；
                // 同时给上下位移设 40dp 上限 —— 上下抖得比这还多，说明是在滚列表，
                // 直接放手不消费事件，让 LazyColumn 正常滚。
                val triggerPx = 40.dp.toPx()
                val maxDyPx = 40.dp.toPx()
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dx = 0f
                    var dy = 0f
                    var horizontal = false
                    var fired = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            ?: break
                        if (!change.pressed) break
                        dx += change.position.x - change.previousPosition.x
                        dy += change.position.y - change.previousPosition.y
                        if (!fired) {
                            // 上下位移超限 = 用户在滚动，不是想多选
                            if (abs(dy) > maxDyPx) break
                            // 先确认是横向占优，再开始吃事件（避免抢走列表滚动）
                            if (!horizontal && abs(dx) > slop && abs(dx) > abs(dy)) {
                                horizontal = true
                            }
                            if (horizontal) {
                                change.consume()
                                if (abs(dx) >= triggerPx) {
                                    fired = true
                                    onSwipeLeft()
                                }
                            }
                        } else {
                            // 已经触发过：本次手势剩下的位移全吃掉，免得又滚一段
                            change.consume()
                        }
                    }
                }
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        FilePreviewIcon(
            item = item,
            loadThumbnail = loadThumbnail,
            modifier = Modifier.padding(top = 2.dp),
            size = 22.dp,
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = Formatters.formatTime(item.lastModified),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!item.isDirectory && item.size > 0) {
                    Text(
                        text = Formatters.formatSize(item.size),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item.permission?.let { perm ->
                Text(
                    text = perm,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = null)