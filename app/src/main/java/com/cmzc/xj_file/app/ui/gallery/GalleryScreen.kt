package com.cmzc.xj_file.app.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import com.cmzc.xj_file.app.core.image.Thumbnails
import com.cmzc.xj_file.app.core.playback.MediaTypes
import com.cmzc.xj_file.app.core.playback.ViewerPage
import com.cmzc.xj_file.app.ui.components.ViewerTopBar

/**
 * 类相册视图：把焦点栏当前目录里的图片 + 视频铺成 4 列网格。
 *
 * 缩略图复用 [Thumbnails]（和查看页底部缩略图条同一个缓存、同一条远程读取路径），
 * 所以远程目录不会因为"多开了一个视图"就多出一套连接和缓存。
 *
 * 视频格子中间压一个播放小圆标，和图片区分开；点任意一格交给查看页，
 * 查看页返回后仍然停在这个相册里（相册是独立的一层，不会被查看页顶掉）。
 */
@Composable
fun GalleryScreen(
    pages: List<ViewerPage>,
    title: String,
    onBack: () -> Unit,
    onSelect: (ViewerPage) -> Unit,
) {
    BackHandler(onBack = onBack)

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    // 4 列时一格大概多宽（含间距误差），用来决定缩略图解码尺寸
    val cellPx = with(density) { (configuration.screenWidthDp.dp / 4).roundToPx() }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        ViewerTopBar(title = title, onBack = onBack) {
            Text(
                text = "${pages.size} 项",
                color = Color(0xFF9A9A9A),
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 14.dp)
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(pages, key = { it.file.path }) { page ->
                GalleryCell(page = page, sizePx = cellPx, onClick = { onSelect(page) })
            }
        }
    }
}

@Composable
private fun GalleryCell(page: ViewerPage, sizePx: Int, onClick: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(page.file.path) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(page.file.path) {
        bitmap = Thumbnails.load(context, page, sizePx)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)          // 用户要求：1:1 裁切
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFF1A1A1A))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = page.file.name,
                // Crop 就是"裁切铺满"，比例不符的图片不会被压扁
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // 还没解出来 / 解不出来：给个灰图占位，绝不留白块
            Icon(
                imageVector = Icons.Filled.Image,
                contentDescription = null,
                tint = Color(0xFF3A3A3A),
                modifier = Modifier.size(22.dp)
            )
        }
        if (MediaTypes.isVideo(page.file.name)) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "视频",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
