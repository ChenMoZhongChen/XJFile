package com.cmzc.xj_file.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PathBar(
    sourceLabel: String,
    path: String,
    onClick: () -> Unit = {},
    onCopyPath: (String) -> Unit = {},
    sourceLabelMiddleEllipsis: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val labelMeasurer = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(fontSize = 10.sp) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("path", path))
                    onCopyPath(path)
                }
            )
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        if (sourceLabelMiddleEllipsis) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val maxPx = with(LocalDensity.current) { maxWidth.toPx() }
                val shown = remember(sourceLabel, maxPx) {
                    middleEllipsize(sourceLabel, maxPx, labelStyle, labelMeasurer)
                }
                Text(
                    text = shown,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Text(
                text = sourceLabel,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = path,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        )
    }
}

/** 保留头尾，中间省略；用于压缩包名这类不能只看末尾的短标签。 */
private fun middleEllipsize(
    text: String,
    maxWidthPx: Float,
    style: TextStyle,
    measurer: TextMeasurer,
): String {
    if (text.isEmpty() || !maxWidthPx.isFinite() || maxWidthPx <= 0f) return text
    if (measurer.measure(text, style, maxLines = 1).size.width <= maxWidthPx) return text

    val ellipsis = "…"
    var low = 2
    var high = text.length
    var best = ellipsis
    while (low <= high) {
        val keep = (low + high) ushr 1
        val head = keep / 2
        val tail = keep - head
        if (head <= 0 || tail <= 0) {
            high = keep - 1
            continue
        }
        val candidate = text.take(head) + ellipsis + text.takeLast(tail)
        if (measurer.measure(candidate, style, maxLines = 1).size.width <= maxWidthPx) {
            best = candidate
            low = keep + 1
        } else {
            high = keep - 1
        }
    }
    return best
}