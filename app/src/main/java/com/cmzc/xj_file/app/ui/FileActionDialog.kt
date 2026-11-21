package com.cmzc.xj_file.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * 长按文件/文件夹后弹出的操作对话框（对齐 MT 样式，2 列 4 行）。
 *
 * 不实现「工具」「添加书签」。
 */
@Composable
fun FileActionDialog(
    selectionCount: Int,
    canOpenWith: Boolean,
    canRename: Boolean,
    canShare: Boolean,
    /** 两栏在同一目录时移动没有意义，置灰 */
    canMove: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onZip: () -> Unit,
    onProp: () -> Unit,
    onShare: () -> Unit,
    onOpenWith: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 16.dp)
            ) {
                // Row 1: 复制 | 移动
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ActionCell(Icons.Default.ContentCopy, "复制", onCopy, true, Modifier.weight(1f))
                    ActionCell(Icons.Default.DriveFileMove, "移动", onMove, canMove, Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                // Row 2: 删除 | 重命名
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ActionCell(Icons.Default.Delete, "删除", onDelete, true, Modifier.weight(1f))
                    ActionCell(Icons.Default.DriveFileRenameOutline, "重命名", onRename, canRename, Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                // Row 3: 压缩 | 属性
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ActionCell(Icons.Default.FolderZip, "压缩", onZip, true, Modifier.weight(1f))
                    ActionCell(Icons.Default.Info, "属性", onProp, true, Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                // Row 4: 分享 | 打开方式
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ActionCell(Icons.Default.Share, "分享", onShare, canShare, Modifier.weight(1f))
                    ActionCell(Icons.Default.OpenInNew, "打开方式", onOpenWith, canOpenWith, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    leftOnClick: () -> Unit,
    rightOnClick: () -> Unit,
    leftEnabled: Boolean = true,
    rightEnabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionCell(
            icon = Icons.Default.ContentCopy,
            label = "复制",
            onClick = leftOnClick,
            enabled = leftEnabled,
            modifier = Modifier.weight(1f)
        )
        ActionCell(
            icon = Icons.Default.DriveFileMove,
            label = "移动",
            onClick = rightOnClick,
            enabled = rightEnabled,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ActionCell(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val alpha = if (enabled) 1f else 0.35f
    Row(
        modifier = modifier
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
        )
    }
}

// 供上面 ActionRow 组合的两行专用——为了少写重复，我把 8 个操作拆成了 4 个 Row，
// 但内容各不相同，直接展开写 8 个 Cell 更清晰。下面是最终展开版。