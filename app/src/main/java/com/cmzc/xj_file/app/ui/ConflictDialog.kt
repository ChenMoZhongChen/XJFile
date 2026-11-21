package com.cmzc.xj_file.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * 用户在冲突对话框上选择的动作。
 */
enum class ConflictAction {
    OVERWRITE,  // 覆盖
    SKIP,       // 跳过
    RENAME      // 重命名
}

/**
 * 一个冲突项。由 ViewModel 检测后生成。
 *
 * @param srcPath          源文件的完整路径（用于回传决策时定位）
 * @param srcName          源文件名（显示用）
 * @param dstPath          目标完整路径（显示用）
 * @param dstName          目标文件名
 * @param isDirectory      是否是目录
 * @param autoRenamedName  自动算好的新名字（例如 report (1).pdf），重命名时使用
 */
data class ConflictItem(
    val srcPath: String,
    val srcName: String,
    val dstPath: String,
    val dstName: String,
    val isDirectory: Boolean,
    val autoRenamedName: String
)

/**
 * 用户对某个冲突的最终决策。
 *
 * @param action     选择的动作
 * @param applyToAll 是否对本次批量剩余冲突都应用此选择
 * @param renamedTo  仅当 action == RENAME 时有效，新的文件名
 */
data class ConflictResolution(
    val action: ConflictAction,
    val applyToAll: Boolean,
    val renamedTo: String? = null
)

/**
 * 冲突处理对话框。
 *
 * @param conflict  当前要处理的冲突
 * @param index     当前是第几个（从 1 开始）
 * @param total     本次批量共几个冲突
 * @param onResolve 用户点「确定」时回调
 * @param onCancel  用户点「取消」或点空白处关闭时回调（取消整个批量）
 */
@Composable
fun ConflictDialog(
    conflict: ConflictItem,
    index: Int,
    total: Int,
    onResolve: (ConflictResolution) -> Unit,
    onCancel: () -> Unit
) {
    var selected by remember(conflict.srcPath) { mutableStateOf<ConflictAction?>(null) }
    var applyToAll by remember(conflict.srcPath) { mutableStateOf(false) }

    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "文件冲突",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "第 $index / $total 个",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    text = "目标位置已有同名文件：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = conflict.srcName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = conflict.dstPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                ConflictOption(
                    title = "覆盖",
                    subtitle = "用源文件替换目标文件",
                    selected = selected == ConflictAction.OVERWRITE,
                    onClick = { selected = ConflictAction.OVERWRITE }
                )
                Spacer(Modifier.height(8.dp))
                ConflictOption(
                    title = "跳过",
                    subtitle = "不传输这个文件",
                    selected = selected == ConflictAction.SKIP,
                    onClick = { selected = ConflictAction.SKIP }
                )
                Spacer(Modifier.height(8.dp))
                ConflictOption(
                    title = "重命名",
                    subtitle = "新名字：${conflict.autoRenamedName}",
                    selected = selected == ConflictAction.RENAME,
                    onClick = { selected = ConflictAction.RENAME }
                )

                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { applyToAll = !applyToAll }
                ) {
                    Checkbox(
                        checked = applyToAll,
                        onCheckedChange = { applyToAll = it }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "对本次所有冲突应用此选择",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancel) {
                        Text("取消")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val action = selected ?: return@Button
                            onResolve(
                                ConflictResolution(
                                    action = action,
                                    applyToAll = applyToAll,
                                    renamedTo = if (action == ConflictAction.RENAME)
                                        conflict.autoRenamedName else null
                                )
                            )
                        },
                        enabled = selected != null
                    ) {
                        Text("确定")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    val bgColor = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surface

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}