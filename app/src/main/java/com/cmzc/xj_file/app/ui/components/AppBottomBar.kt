package com.cmzc.xj_file.app.ui.components

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cmzc.xj_file.app.ui.theme.LocalModuleColors

@Composable
fun AppBottomBar(
    selectionMode: Boolean,
    focusedIsLeft: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    /** 两栏在同一目录时「移动」没有意义，置灰 */
    canMove: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onAdd: () -> Unit,
    onSwap: () -> Unit,
    onFocusUp: () -> Unit,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onCancelSelection: () -> Unit,
    onSwipeProgress: (Float) -> Unit,
    onSwipeEnd: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(48.dp)
                .padding(horizontal = 4.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dragAmount -> onSwipeProgress(dragAmount) },
                        onDragEnd = { onSwipeEnd() }
                    )
                },
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!selectionMode) {
                BottomButton(Icons.Default.ArrowBack, "后退", canGoBack, onBack)
                BottomButton(Icons.Default.ArrowForward, "前进", canGoForward, onForward)
                BottomButton(Icons.Default.Add, "新建文件或文件夹", true, onAdd)
                BottomButton(Icons.Default.SwapHoriz, "交换左右两栏", true, onSwap)
                BottomButton(Icons.Default.ArrowUpward, "返回上一级目录", true, onFocusUp)
            } else {
                // 移动箭头：焦点在左栏则指向右，焦点在右栏则指向左
                val moveArrow = if (focusedIsLeft) Icons.Default.ArrowForward
                else Icons.Default.ArrowBack
                BottomButton(Icons.Default.SelectAll, "全选", true, onSelectAll)
                BottomButton(Icons.Default.ContentCopy, "复制", true, onCopy)
                BottomButton(Icons.Default.Delete, "删除", true, onDelete)
                BottomButton(moveArrow, "移动", canMove, onMove)
                BottomButton(Icons.Default.Close, "取消选中", true, onCancelSelection)
            }
        }
    }
}

@Composable
private fun BottomButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    // 底栏按钮统一吃「模块颜色 → 按钮」
    val buttonColor = LocalModuleColors.current.button
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) buttonColor else buttonColor.copy(alpha = 0.3f),
            modifier = Modifier.size(22.dp)
        )
    }
}