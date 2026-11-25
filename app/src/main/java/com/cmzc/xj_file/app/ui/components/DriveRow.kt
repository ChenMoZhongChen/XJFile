package com.cmzc.xj_file.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cmzc.xj_file.app.core.disk.DriveInfo
import com.cmzc.xj_file.app.core.disk.OpticalMedia
import com.cmzc.xj_file.app.core.util.Formatters

/**
 * SFTP 连接根目录里的一行「磁盘」。
 *
 * 版面按用户要求分三行：
 *   1. 图标 + 磁盘名
 *   2. 容量条（**单独占一行、通栏**）
 *   3. 明细「可用 / 总共」
 *
 * 光驱没有容量条（CD 的容量对用户没意义），第二行换成光盘类型。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DriveRow(
    info: DriveInfo,
    selected: Boolean,
    highlighted: Boolean,
    showSelection: Boolean,
    onClick: () -> Unit,
    /**
     * 长按：给「视频 DVD 其实该当数据盘」留一条路。
     * 只靠 VIDEO_TS 判断必然有误判（数据 DVD 里也可能只有 AUDIO_TS/VIDEO_TS），
     * 所以长按由用户自己选打开方式。
     */
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val bg = when {
        highlighted -> MaterialTheme.colorScheme.tertiaryContainer
        selected && showSelection -> MaterialTheme.colorScheme.primaryContainer
        // 不选中时透明，跟随整个文件栏的 focused/unfocused 背景，
        // 否则 SFTP 根目录的磁盘行会永远是一块浅色 surface，和未选中面板不一致。
        else -> Color.Transparent
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DriveIcon(info, Modifier.size(width = 24.dp, height = 33.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = info.displayName,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (info.isOptical) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = opticalLabel(info),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Spacer(Modifier.height(5.dp))
                CapacityBar(
                    usedFraction = if (info.sizeBytes > 0) {
                        1f - (info.freeBytes.toFloat() / info.sizeBytes)
                    } else 0f,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${Formatters.formatSize(info.freeBytes)} / ${Formatters.formatSize(info.sizeBytes)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 磁盘信息还在读的时候占位：**故意不可点**。
 *
 * 之前是直接拿目录列表里的 "C:" 当按钮先画出来，用户点了才去连 SSH，
 * 结果点了没反应或者点错 —— 所以加载期间要明确显示"正在加载"，并且禁掉点击。
 */
@Composable
fun DriveLoadingRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 24.dp, height = 33.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .height(11.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "正在读取磁盘信息…",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun opticalLabel(info: DriveInfo): String {
    val base = when (info.optical) {
        OpticalMedia.NONE -> "无光盘"
        OpticalMedia.CD -> if (info.isAudioCd) "音频 CD" else "数据 CD"
        OpticalMedia.DVD_DATA -> "数据 DVD"
        OpticalMedia.DVD_VIDEO -> "视频 DVD"
        OpticalMedia.BD -> "蓝光光盘"
    }
    // 图标已经是「左半彩色、右半灰」，文字再标一次，免得用户只看到半灰以为是画错了
    return if (info.isMdisc && info.optical != OpticalMedia.NONE) "千年盘 · $base" else base
}

@Composable
private fun CapacityBar(usedFraction: Float, modifier: Modifier = Modifier) {
    val fraction = usedFraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/** 把盘符名（"C:"）映射回 DriveInfo：面板里的条目名就是 "C:"。 */
fun List<DriveInfo>.byEntryName(name: String): DriveInfo? {
    val letter = name.substringBefore(':').trim()
    if (letter.isEmpty()) return null
    return firstOrNull { it.letter.equals(letter, ignoreCase = true) }
}
