package com.cmzc.xj_file.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * 调色板：上面是「饱和度 × 明度」的色块（随色相变化），下面是色相条。
 *
 * 点输入框右边的预览块打开；拖动时实时生效（和手输十六进制一样，不需要确认）。
 * 用 Popup 而不是 Dialog：Popup 没有系统窗口动画，符合「关掉动画后全 App 无动画」。
 */
@Composable
fun ColorPickerPopup(
    initial: Color,
    onColorChange: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    val initialHsv = remember(initial) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) }
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }
    val current = Color.hsv(hue, saturation, value)

    val onChange = rememberUpdatedState(onColorChange)
    fun emit() {
        onChange.value(Color.hsv(hue, saturation, value))
    }

    val hueColors = listOf(
        Color.Red, Color.Yellow, Color.Green, Color.Cyan,
        Color.Blue, Color.Magenta, Color.Red
    )

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
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

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "选色",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(12.dp))

                    // 色块：横向是饱和度（白 → 纯色），纵向是明度（透明 → 黑）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.35f)
                            .clip(RoundedCornerShape(10.dp))
                            .pointerInputColorArea { x, y, w, h ->
                                saturation = (x / w).coerceIn(0f, 1f)
                                value = (1f - y / h).coerceIn(0f, 1f)
                                emit()
                            }
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(
                                Brush.horizontalGradient(
                                    listOf(Color.White, Color.hsv(hue, 1f, 1f))
                                )
                            )
                            drawRect(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black)
                                )
                            )
                        }
                        Canvas(Modifier.fillMaxSize()) {
                            val center = Offset(
                                saturation * size.width,
                                (1f - value) * size.height
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 10.dp.toPx(),
                                center = center,
                                style = Stroke(width = 2.dp.toPx())
                            )
                            drawCircle(
                                color = Color.Black.copy(alpha = 0.55f),
                                radius = 12.dp.toPx(),
                                center = center,
                                style = Stroke(width = 1.dp.toPx())
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "色相",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .pointerInputColorArea { x, _w, w, _h ->
                                hue = ((x / w) * 360f).coerceIn(0f, 359.9f)
                                emit()
                            }
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(Brush.horizontalGradient(hueColors))
                        }
                        Canvas(Modifier.fillMaxSize()) {
                            val x = (hue / 360f) * size.width
                            drawLine(
                                color = Color.White,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 3.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = Color.Black.copy(alpha = 0.4f),
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(current)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = toHex(current.toArgb()),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onDismiss) { Text("完成") }
                    }
                }
            }
        }
    }
}

/**
 * 取色区域的拖拽处理：按下和移动都按「坐标 / 区域宽高」归一化回传。
 * 单独抽出来是为了让色块和色相条共用同一套按下 + 拖动逻辑。
 */
private fun Modifier.pointerInputColorArea(
    onPosition: (x: Float, y: Float, width: Float, height: Float) -> Unit
): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            onPosition(down.position.x, down.position.y, size.width.toFloat(), size.height.toFloat())
            down.consume()
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                onPosition(
                    change.position.x,
                    change.position.y,
                    size.width.toFloat(),
                    size.height.toFloat()
                )
                change.consume()
            }
        }
    }
)

private fun toHex(argb: Int): String = "#%06X".format(argb and 0x00FFFFFF)
