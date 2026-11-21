package com.cmzc.xj_file.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.cmzc.xj_file.app.ui.theme.LocalModuleColors

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AppTopBar(
    path: String,
    stats: String,
    onMenu: () -> Unit,
    onRefresh: () -> Unit,
    onTasks: () -> Unit,
    onSettings: () -> Unit,
    onGallery: () -> Unit = {},
    onPathClick: () -> Unit,
    onScrollToTop: () -> Unit = {},
    onSwipeRight: () -> Unit = {},
    onCopyPath: (String) -> Unit = {}
) {
    val context = LocalContext.current
    // 顶栏这几个都是按钮，跟「模块颜色 → 按钮」走
    val buttonColor = LocalModuleColors.current.button
    var showMoreMenu by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(48.dp)
                .padding(horizontal = 2.dp)
                .pointerInput(Unit) {
                    var totalDx = 0f
                    var triggered = false
                    detectHorizontalDragGestures(
                        onDragStart = {
                            totalDx = 0f
                            triggered = false
                        },
                        onDragEnd = {
                            totalDx = 0f
                            triggered = false
                        },
                        onDragCancel = {
                            totalDx = 0f
                            triggered = false
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            totalDx += dragAmount
                            // 只在右滑超过阈值时触发一次
                            if (!triggered && totalDx > 60f) {
                                triggered = true
                                onSwipeRight()
                            }
                        }
                    )
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Default.Menu, contentDescription = "菜单", tint = buttonColor)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = onPathClick,
                        onLongClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("path", path))
                            onCopyPath(path)
                        }
                    )
            ) {
                Text(
                    text = path,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                )
                Text(
                    text = stats,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 回到顶部：图标是「一条横线 + 向上的箭头」（VerticalAlignTop），
            // 放在刷新左边，避免和刷新键混淆。
            IconButton(onClick = onScrollToTop) {
                Icon(
                    Icons.Default.VerticalAlignTop,
                    contentDescription = "回到顶部",
                    tint = buttonColor
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = buttonColor)
            }
            Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                IconButton(onClick = { showMoreMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多",
                        tint = buttonColor
                    )
                }
                AppDropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("任务") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            showMoreMenu = false
                            onTasks()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("设置") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            showMoreMenu = false
                            onSettings()
                        }
                    )
                    // 放最后：相册是"看内容"的入口，排在任务/设置这类系统入口之后
                    DropdownMenuItem(
                        text = { Text("类相册视图") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.PhotoLibrary,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            showMoreMenu = false
                            onGallery()
                        }
                    )
                }
            }
        }
    }
}