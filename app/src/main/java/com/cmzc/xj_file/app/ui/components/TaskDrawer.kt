package com.cmzc.xj_file.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cmzc.xj_file.app.core.transfer.TransferStatus
import com.cmzc.xj_file.app.core.transfer.TransferTask
import com.cmzc.xj_file.app.core.util.Formatters
import com.cmzc.xj_file.app.ui.theme.LocalModuleColors
import androidx.compose.foundation.shape.RoundedCornerShape

private data class BatchDisplay(
    val batchId: String,
    val batchName: String,
    val tasks: List<TransferTask>
)

@Composable
fun TaskDrawer(
    progress: Float,
    drawerWidth: Dp,
    tasks: List<TransferTask>,
    pausedBatches: Set<String>,
    pausedTasks: Set<String>,
    onClose: () -> Unit,
    onCancelBatch: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onRemoveBatch: (String) -> Unit,
    onToggleBatchPause: (String) -> Unit,
    onToggleTaskPause: (String) -> Unit
) {
    if (progress <= 0.01f) return

    val density = LocalDensity.current
    val widthPx = with(density) { drawerWidth.toPx() }

    val allGroups = remember(tasks) {
        tasks.groupBy { it.batchId }
            .map { (batchId, list) ->
                BatchDisplay(
                    batchId = batchId,
                    batchName = list.firstOrNull()?.batchName ?: "传输",
                    tasks = list
                )
            }
    }
    // 只显示：还有未结束的，或有失败的
    val visibleGroups = allGroups.filter { g ->
        g.tasks.any {
            it.status == TransferStatus.PENDING ||
                    it.status == TransferStatus.RUNNING ||
                    it.status == TransferStatus.FAILED
        }
    }

    var expandedBatchId by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress * 0.5f }
                .background(Color.Black)
                .clickable(onClick = onClose)
        )
        // 抽屉本体
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(drawerWidth)
                .fillMaxHeight()
                .graphicsLayer { translationX = widthPx * (1f - progress) }
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "任务",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = LocalModuleColors.current.button
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (visibleGroups.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无传输任务",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(visibleGroups, key = { it.batchId }) { group ->
                            BatchRow(
                                group = group,
                                expanded = expandedBatchId == group.batchId,
                                paused = group.batchId in pausedBatches,
                                pausedTasks = pausedTasks,
                                onToggleExpand = {
                                    expandedBatchId =
                                        if (expandedBatchId == group.batchId) null else group.batchId
                                },
                                onTogglePause = { onToggleBatchPause(group.batchId) },
                                onToggleTaskPause = onToggleTaskPause,
                                onCancel = { onCancelBatch(group.batchId) },
                                onCancelTask = onCancelTask,
                                onRemove = { onRemoveBatch(group.batchId) }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        item { Spacer(Modifier.height(32.dp)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BatchRow(
    group: BatchDisplay,
    expanded: Boolean,
    paused: Boolean,
    pausedTasks: Set<String>,
    onToggleExpand: () -> Unit,
    onTogglePause: () -> Unit,
    onToggleTaskPause: (String) -> Unit,
    onCancel: () -> Unit,
    onCancelTask: (String) -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val buttonColor = LocalModuleColors.current.button
    val stillRunning = group.tasks.any {
        it.status == TransferStatus.PENDING || it.status == TransferStatus.RUNNING
    }
    val hasFailed = group.tasks.any { it.status == TransferStatus.FAILED }
    val totalBytes = group.tasks.sumOf { if (it.totalBytes > 0) it.totalBytes else 0L }
    val transferred = group.tasks.sumOf { it.transferred }
    val progress =
        if (totalBytes > 0) (transferred.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    val currentName = group.tasks.firstOrNull { it.status == TransferStatus.RUNNING }?.name

    var showMenu by remember { mutableStateOf(false) }

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (hasFailed && !stillRunning) onRemove() else onToggleExpand()
                    },
                    onLongClick = {
                        if (hasFailed && !stillRunning) showMenu = true
                    }
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = group.batchName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 暂停是「批次级」的：只停这一批的下发，别的批次（比如预览下载）照常跑
                if (stillRunning) {
                    IconButton(
                        onClick = onTogglePause,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (paused) Icons.Default.PlayArrow
                            else Icons.Default.Pause,
                            contentDescription = if (paused) "恢复本批次" else "暂停本批次",
                            modifier = Modifier.size(18.dp),
                            tint = buttonColor
                        )
                    }
                }
                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess
                        else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        modifier = Modifier.size(20.dp),
                        tint = buttonColor
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                )
                if (stillRunning) {
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFB3261E)
                    ) {
                        Text(
                            text = "取消",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            modifier = Modifier
                                .clickable(onClick = onCancel)
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))

            if (!expanded) {
                Text(
                    text = currentName ?: statusText(group, paused),
                    fontSize = 11.sp,
                    color = if (hasFailed && !stillRunning)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                group.tasks.forEach { task ->
                    FileProgressRow(
                        task = task,
                        // 批次整体暂停时不给单文件的恢复按钮（点它也没用，得先恢复批次）
                        paused = task.id in pausedTasks,
                        batchPaused = paused,
                        onCancelTask = onCancelTask,
                        onToggleTaskPause = onToggleTaskPause
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("复制错误信息") },
                onClick = {
                    showMenu = false
                    val errors = group.tasks
                        .filter { it.status == TransferStatus.FAILED }
                        .joinToString("\n") { "${it.name}: ${it.error ?: "未知错误"}" }
                    clipboard.setText(AnnotatedString(errors))
                    android.widget.Toast.makeText(
                        context, "已复制", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            )
            DropdownMenuItem(
                text = { Text("删除记录") },
                onClick = {
                    showMenu = false
                    onRemove()
                }
            )
        }
    }
}

@Composable
private fun FileProgressRow(
    task: TransferTask,
    paused: Boolean,
    batchPaused: Boolean,
    onCancelTask: (String) -> Unit,
    onToggleTaskPause: (String) -> Unit
) {
    val cancellable = task.status == TransferStatus.PENDING ||
            task.status == TransferStatus.RUNNING
    // 单文件暂停只对「还没开始」的有意义；在跑的车道停不下来（版本 A）
    val showPauseChip = task.status == TransferStatus.PENDING && !batchPaused
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (paused) "${task.name}（已暂停）" else task.name,
                fontSize = 11.sp,
                color = if (paused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 版本 B：写完还要算 sha256，这期间状态文字要变，否则看着像已经完事了
            when {
                task.verifying -> Text(
                    text = "正在校验 sha256…",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.tertiary
                )
                task.resumedFrom > 0 -> Text(
                    text = "从断点继续（已传 " + Formatters.formatSize(task.resumedFrom) + "）",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(2.dp))
            LinearProgressIndicator(
                progress = { task.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
            )
        }
        if (showPauseChip) {
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = if (paused) "继续" else "暂停",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .clickable { onToggleTaskPause(task.id) }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        if (cancellable) {
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFFB3261E)
            ) {
                Text(
                    text = "取消",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    modifier = Modifier
                        .clickable { onCancelTask(task.id) }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

private fun statusText(group: BatchDisplay, paused: Boolean): String {
    val total = group.tasks.size
    val completed = group.tasks.count { it.status == TransferStatus.COMPLETED }
    val failed = group.tasks.count { it.status == TransferStatus.FAILED }
    val cancelled = group.tasks.count { it.status == TransferStatus.CANCELLED }
    val done = completed + failed + cancelled
    val base = when {
        done < total -> "已完成 $completed / 共 $total"
        failed > 0 -> "有 $failed 个失败 / 共 $total"
        cancelled > 0 -> "已取消"
        completed == total -> "全部完成（$total 个）"
        else -> "已完成 $completed / 共 $total"
    }
    // 版本 B：暂停是原地停住，半截文件都保留，恢复时从断点接着传
    return if (paused && done < total) "已暂停（保留断点） · $base" else base
}