package com.cmzc.xj_file.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cmzc.xj_file.app.core.image.Thumbnails
import com.cmzc.xj_file.app.core.playback.MediaTypes
import com.cmzc.xj_file.app.core.playback.ViewerPage

/**
 * 查看页底部的「相册式」缩略图条。
 *
 * 只在「图片与视频严格分离」被关掉时出现（实验性内容里那个开关）：
 * 那时候用户可以混着挑同目录的图片和视频，点一格就跳到对应的查看页。
 *
 * 严格分离打开时整条不显示 —— 图片和视频互不串门，就没有"混着挑"的需求。
 */
@Composable
fun MediaFilmstrip(
    items: List<ViewerPage>,
    currentPath: String?,
    onSelect: (ViewerPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // 当前项始终留在可见范围内（翻页后自动跟过去），左留两格当上下文
    LaunchedEffect(currentPath) {
        val index = items.indexOfFirst { it.file.path == currentPath }
        if (index >= 0) {
            runCatching { listState.animateScrollToItem((index - 2).coerceAtLeast(0)) }
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp)
            .background(Color(0xD9000000)),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(items, key = { _, page -> page.file.path }) { _, page ->
            ThumbTile(
                page = page,
                selected = page.file.path == currentPath,
                onClick = { onSelect(page) }
            )
        }
    }
}

@Composable
private fun ThumbTile(page: ViewerPage, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(page.file.path) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(page.file.path) {
        val loaded = Thumbnails.load(context, page, THUMB_PX)
        if (loaded != null) bitmap = loaded.asImageBitmap()
    }

    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(shape)
            .background(Color(0xFF1F1F1F))
            .then(
                if (selected) Modifier.border(2.dp, Color.White, shape)
                else Modifier.border(1.dp, Color(0xFF3A3A3A), shape)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // 视频盖一个播放角标，和图片区分开
        if (!MediaTypes.isImage(page.file.name)) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** 缩略图解码尺寸（像素）。60dp 的格子按 3x 屏算约 180px，取 160 够用。 */
private const val THUMB_PX = 160
