package com.cmzc.xj_file.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp

/**
 * 底部面板层（独立模块）。
 *
 * 职责：
 *   - 当 progress > 0 时渲染遮罩 + 面板本体
 *   - 遮罩：点击 → onClose
 *   - 面板：从底边滑出，offset 由 progress 控制（0 = 完全关闭，1 = 完全打开）
 *
 * 不依赖、不影响任何其它 UI（包括左侧抽屉）。
 */
@Composable
fun BottomSheetLayer(
    progress: Float,
    heightFraction: Float,
    backgroundColor: Color,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    if (progress <= 0.001f) return

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val sheetHeightPx = screenHeightPx * heightFraction

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
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(heightFraction)
                .offset { IntOffset(0, ((1f - progress) * sheetHeightPx).roundToInt()) }
                .background(backgroundColor),
            content = content
        )
    }
}