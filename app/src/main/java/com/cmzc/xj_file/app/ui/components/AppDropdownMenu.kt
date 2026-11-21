package com.cmzc.xj_file.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.cmzc.xj_file.app.ui.theme.LocalAnimationsEnabled

/**
 * 下拉菜单的统一入口。
 *
 * Material3 的 [DropdownMenu] 自己带缩放 + 淡入淡出动画，关不掉；
 * 「界面动画」关闭时要做到全 App 无动画，所以这里在关闭时换成一个**没有过渡动画**的 Popup，
 * 打开/关闭都是瞬间出现。开着的时候仍用原生 DropdownMenu（外观、定位完全一致）。
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    content: @Composable ColumnScope.() -> Unit
) {
    if (LocalAnimationsEnabled.current) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            offset = offset,
            content = content
        )
        return
    }

    if (!expanded) return

    val density = LocalDensity.current
    val offsetPx = with(density) { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) }
    val positionProvider = remember(offsetPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                // 没有 offset：右对齐锚点、弹在下方（右上角三点菜单就是这样）
                // 有 offset：锚点左上角 + offset（抽屉里长按弹出的菜单用坐标定位）
                val raw = if (offsetPx == IntOffset.Zero) {
                    IntOffset(
                        anchorBounds.right - popupContentSize.width,
                        anchorBounds.bottom
                    )
                } else {
                    IntOffset(anchorBounds.left + offsetPx.x, anchorBounds.top + offsetPx.y)
                }
                // 别超出窗口边缘
                return IntOffset(
                    raw.x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    raw.y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                )
            }
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 3.dp
        ) {
            // 必须用 IntrinsicSize.Max：Popup 的宽度约束是整个窗口，
            // 直接放 Column 的话 DropdownMenuItem 的 fillMaxWidth 会把菜单撑满全屏。
            // 这也是 Material3 的 DropdownMenu 内部用的同一招，宽度才能和带动画时一致。
            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .padding(vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                content = content
            )
        }
    }
}
