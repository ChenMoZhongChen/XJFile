package com.cmzc.xj_file.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cmzc.xj_file.app.core.archive.ArchiveLevels
import com.cmzc.xj_file.app.core.archive.ArchiveType
import com.cmzc.xj_file.app.core.archive.CompressRequest
import com.cmzc.xj_file.app.core.util.Formatters

/**
 * 压缩参数面板（从长按菜单的「压缩」进入）。
 *
 * 这里只负责收集参数和展示明细：
 * - 名称：默认由当前选择生成，可改；
 * - 类型：ZIP 可用，7Z/RAR 留了选项位但明确置灰；
 * - 级别：0 仅存储 / 1 快速 / 2 标准 / 3 极限；
 * - 明细：数量、文件合计、输出目录、格式、层级保留说明。
 */
@Composable
fun CompressDialog(
    itemCount: Int,
    hasDirectory: Boolean,
    totalBytes: Long,
    defaultName: String,
    outputDir: String,
    onDismiss: () -> Unit,
    onConfirm: (CompressRequest) -> Unit,
) {
    var name by remember(defaultName) { mutableStateOf(defaultName) }
    var type by remember { mutableStateOf(ArchiveType.ZIP) }
    var level by remember { mutableIntStateOf(2) }
    val currentLevel = ArchiveLevels.ALL.firstOrNull { it.value == level } ?: ArchiveLevels.ALL[2]
    val typeUsable = type.canUseFor(itemCount, hasDirectory)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.93f)
                .widthIn(max = 560.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Text(
                    text = "压缩",
                    fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "把当前选中的内容打包到当前目录",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("压缩包名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(16.dp))
                SectionTitle("压缩类型")
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 7-Zip 同款格式列表，按 5 个一行排；第二行不足 5 个补空位。
                    ArchiveType.values().toList().chunked(5).forEach { rowTypes ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowTypes.forEach { option ->
                                TypeCell(
                                    type = option,
                                    selected = type == option,
                                    selectable = option.canUseFor(itemCount, hasDirectory),
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        if (option.canUseFor(itemCount, hasDirectory)) {
                                            name = replaceExtension(name, type, option)
                                            type = option
                                        }
                                    },
                                )
                            }
                            repeat(5 - rowTypes.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                Text(
                    text = if (typeUsable) type.note else "${type.label}：${type.note}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )

                Spacer(Modifier.height(16.dp))
                SectionTitle("压缩级别")
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ArchiveLevels.ALL.forEach { option ->
                        LevelCell(
                            title = option.title,
                            selected = level == option.value,
                            modifier = Modifier.weight(1f),
                            onClick = { level = option.value },
                        )
                    }
                }
                Text(
                    text = "级别 $level · ${currentLevel.title}：${currentLevel.detail}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )

                Spacer(Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        SectionTitle("明细")
                        Spacer(Modifier.height(6.dp))
                        DetailLine("内容", "$itemCount 项 · 文件合计 ${Formatters.formatSize(totalBytes)}")
                        DetailLine("格式", "${type.label} · ${currentLevel.title}")
                        DetailLine("输出目录", outputDir, monospace = true)
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        Text(
                            text = "保留文件夹层级；空文件夹也会写入。目标同名文件会被拒绝，不会静默覆盖。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.widthIn(min = 4.dp))
                    TextButton(
                        enabled = name.isNotBlank() && typeUsable,
                        onClick = {
                            val trimmed = name.trim()
                            if (trimmed.isNotEmpty()) {
                                val suffix = ".${type.extension}"
                                val finalName = if (trimmed.endsWith(suffix, ignoreCase = true)) {
                                    trimmed
                                } else {
                                    "$trimmed$suffix"
                                }
                                onConfirm(CompressRequest(finalName, type, level))
                            }
                        }
                    ) { Text("开始压缩") }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun DetailLine(label: String, value: String, monospace: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 56.dp),
        )
        Text(
            text = value,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TypeCell(
    type: ArchiveType,
    selected: Boolean,
    selectable: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val enabled = selectable
    val bg = when {
        selected && enabled -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.45f)
    }
    val fg = when {
        selected && enabled -> MaterialTheme.colorScheme.onPrimaryContainer
        enabled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    }
    Column(
        modifier = modifier
            .background(bg, RoundedCornerShape(10.dp))
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(type.label, fontSize = 14.sp, color = fg)
        Text(
            text = when {
                !type.supported -> "暂不可用"
                !selectable -> "仅单文件"
                else -> "可用"
            },
            fontSize = 9.sp,
            color = fg.copy(alpha = 0.78f),
        )
    }
}

/** 切换格式时同步改扩展名，避免用户看到 foo.zip 却选了 7Z。 */
private fun replaceExtension(name: String, oldType: ArchiveType, newType: ArchiveType): String {
    val oldSuffix = ".${oldType.extension}"
    val base = when {
        name.endsWith(oldSuffix, ignoreCase = true) -> name.dropLast(oldSuffix.length)
        name.contains('.') -> name.substringBeforeLast('.')
        else -> name
    }
    return "${base.ifBlank { "archive" }}.${newType.extension}"
}

@Composable
private fun LevelCell(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = modifier
            .background(bg, RoundedCornerShape(9.dp))
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(9.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 12.sp, color = fg)
    }
}
