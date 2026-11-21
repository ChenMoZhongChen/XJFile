package com.cmzc.xj_file.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 长按「打开方式」：内置文本编辑器 / 系统应用。
 *
 * 5B 的规则是未知后缀默认进文本编辑器，但二进制、Office/PDF、音视频仍可能需要
 * 交回系统应用；这个对话框就是把选择权交回给用户，而不是替用户猜死。
 * [textEnabled] 为 false 时（Office/PDF/媒体）不显示文本编辑器选项。
 */
@Composable
fun OpenWithDialog(
    fileName: String,
    textEnabled: Boolean,
    onDismiss: () -> Unit,
    onText: () -> Unit,
    onSystem: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("打开方式") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = fileName,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (textEnabled) {
                    TextButton(
                        onClick = onText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        Text("文本编辑器")
                    }
                }
                TextButton(
                    onClick = onSystem,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (textEnabled) 2.dp else 10.dp)
                ) {
                    Text(if (textEnabled) "系统应用" else "用系统应用打开")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
