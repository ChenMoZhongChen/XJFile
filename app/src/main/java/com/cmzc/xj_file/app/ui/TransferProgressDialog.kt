package com.cmzc.xj_file.app.ui

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cmzc.xj_file.app.core.util.Formatters

/**
 * 传输进度对话框。
 * 点弹窗外空白 = onBackground（等价于后台继续）。
 *
 * 版本 B 起 paused 是「真的原地停住」：半截文件保留、断点落盘，
 * 所以暂停文案要跟用户说清楚「恢复后会接着传」。
 */
@Composable
fun TransferProgressDialog(
    batchName: String,
    progress: Float,
    currentFileName: String?,
    transferredBytes: Long,
    totalBytes: Long,
    speedBytesPerSec: Long,
    verifying: Boolean = false,
    paused: Boolean = false,
    onBackground: () -> Unit,
    onTogglePause: () -> Unit = {},
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = onBackground) {
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
                    text = batchName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = buildSizeText(transferredBytes, totalBytes),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    if (speedBytesPerSec > 0) {
                        Text(
                            text = Formatters.formatSize(speedBytesPerSec) + "/s",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = Formatters.formatBitrateDecimal(speedBytesPerSec),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                if (verifying) {
                    Text(
                        text = "正在校验文件完整性…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = currentFileName ?: "准备中…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 暂停状态单独一行：现在是真的原地停下（不是等当前文件传完），
                // 所以要跟用户说清楚「半截文件已经保住、恢复会接着传」。
                if (paused && !verifying) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "已暂停：已传的部分会保留，恢复后接着传",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2
                    )
                }

                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBackground) {
                        Text("后台继续")
                    }
                    TextButton(onClick = onTogglePause) {
                        Text(if (paused) "恢复" else "暂停")
                    }
                    Button(onClick = onCancel) {
                        Text("取消")
                    }
                }
            }
        }
    }
}

private fun buildSizeText(done: Long, total: Long): String {
    if (total <= 0) return Formatters.formatSize(done) + " / 未知"
    return Formatters.formatSize(done) + " / " + Formatters.formatSize(total)
}