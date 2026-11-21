package com.cmzc.xj_file.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.cmzc.xj_file.app.ui.theme.LocalAnimationsEnabled

/**
 * 新建文件/文件夹对话框。
 *
 * 注意这里**没有用 AlertDialog**：AlertDialog 走系统 Dialog 窗口，开关动画关不掉。
 * 改成同一个窗口里的 Popup 覆盖层——「界面动画」开着时自己做一个缩放淡入，
 * 关掉时直接出现，符合「关闭动画后全 App 无动画」的要求。
 *
 * 输入框下面实时显示「点这个按钮最终会创建成什么名字」：
 * 名称为空时用默认名（新建文件 / 新建文件夹），重名时自动加 " (1)"、(" (2)")…
 * 并且**按钮真正传给 ViewModel 的就是这里显示的名字**，显示与创建必须完全一致。
 */
@Composable
fun NewItemDialog(
    existingNames: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (name: String, isFolder: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val animationsOn = LocalAnimationsEnabled.current

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    val typed = name.trim()
    // 同目录已有名称（统一小写比较）：Windows/SMB 不区分大小写，宁可多改一次名也别覆盖
    val takenLower = remember(existingNames) {
        existingNames.map { it.lowercase() }.toSet()
    }
    val fileCandidate = remember(typed, takenLower) {
        nextAvailableName(if (typed.isEmpty()) "新建文件" else typed, takenLower)
    }
    val folderCandidate = remember(typed, takenLower) {
        nextAvailableName(if (typed.isEmpty()) "新建文件夹" else typed, takenLower)
    }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 遮罩放成卡片的兄弟节点：点遮罩关闭，点卡片不会误关
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )

            var appear by remember { mutableStateOf(!animationsOn) }
            LaunchedEffect(Unit) {
                if (animationsOn) appear = true
            }

            val card: @Composable () -> Unit = {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "新建",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("名称（可留空）") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "［文件］将创建为「$fileCandidate」",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "［文件夹］将创建为「$folderCandidate」",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = onDismiss) { Text("取消") }
                            Spacer(Modifier.width(2.dp))
                            TextButton(onClick = { onConfirm(fileCandidate, false) }) {
                                Text("新建文件")
                            }
                            Spacer(Modifier.width(2.dp))
                            TextButton(onClick = { onConfirm(folderCandidate, true) }) {
                                Text("新建文件夹")
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.align(Alignment.Center)) {
                if (animationsOn) {
                    AnimatedVisibility(
                        visible = appear,
                        enter = fadeIn(tween(150)) +
                            scaleIn(initialScale = 0.94f, animationSpec = tween(150))
                    ) {
                        card()
                    }
                } else {
                    card()
                }
            }
        }
    }
}

/**
 * 生成不与现有条目冲突的名字：重名就依次试 "base (1)"、"base (2)"…
 * 传进来的 takenLower 必须已经是小写。
 */
private fun nextAvailableName(base: String, takenLower: Set<String>): String {
    if (base.lowercase() !in takenLower) return base
    var index = 1
    while (true) {
        val candidate = "$base ($index)"
        if (candidate.lowercase() !in takenLower) return candidate
        index++
    }
}
