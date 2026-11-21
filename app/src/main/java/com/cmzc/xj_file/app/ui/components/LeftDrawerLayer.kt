package com.cmzc.xj_file.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * 左侧抽屉层（独立模块）。
 *
 * 职责：
 *   - 当 progress > 0 时渲染遮罩 + 面板本体
 *   - 遮罩：点击 → onClose
 *   - 面板：从左侧滑出，translationX 由 progress 控制（0 = 完全关闭，1 = 完全打开）
 *
 * 不依赖、不影响任何其它 UI（包括底部按钮栏）。
 */
@Composable
fun LeftDrawerLayer(
    progress: Float,
    drawerWidth: Dp,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    if (progress <= 0.001f) return

    val density = LocalDensity.current
    val drawerWidthPx = with(density) { drawerWidth.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        // 遮罩
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f * progress))
                .pointerInput(Unit) {
                    detectTapGestures { onClose() }
                }
        )
        // 本体
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(drawerWidth)
                .graphicsLayer {
                    translationX = -drawerWidthPx + drawerWidthPx * progress
                }
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.fillMaxSize(), content = content)
        }
    }
}