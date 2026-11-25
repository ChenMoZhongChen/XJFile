package com.cmzc.xj_file.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cmzc.xj_file.app.core.fs.RemoteFile
import com.cmzc.xj_file.app.core.image.AppIconLoader
import com.cmzc.xj_file.app.core.playback.MediaTypes
import java.io.File

/**
 * 文件列表的图标位：图片/视频优先显示缩略图，失败或非媒体回退到类型图标。
 * 只对当前组合出来的行加载，列表滚出屏幕后不会继续请求。
 */
@Composable
fun FilePreviewIcon(
    item: RemoteFile,
    loadThumbnail: (suspend (RemoteFile, Int) -> Bitmap?)?,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val isVideo = !item.isDirectory && MediaTypes.isVideo(item.name)
    val isImage = !item.isDirectory && MediaTypes.isImage(item.name)
    val isApk = !item.isDirectory && item.name.endsWith(".apk", ignoreCase = true)
    val isExe = !item.isDirectory && item.name.endsWith(".exe", ignoreCase = true)
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    var appIcon by remember(item.path, sizePx) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.path, sizePx, isApk, isExe) {
        if (isApk || isExe) {
            val file = File(item.path)
            appIcon = if (file.isFile) {
                AppIconLoader.load(context, file, sizePx)
            } else {
                runCatching { loadThumbnail?.invoke(item, sizePx) }.getOrNull()
            }
        }
    }

    if (appIcon != null) {
        // 探测到的真实图标保留原始颜色，不受“文件图标颜色”开关影响。
        Image(
            bitmap = appIcon!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(3.dp)),
        )
        return
    }

    if (item.isDirectory || (!isImage && !isVideo) || loadThumbnail == null) {
        FileTypeIcon(
            name = item.name,
            isDirectory = item.isDirectory,
            modifier = modifier,
            size = size,
        )
        return
    }

    val currentLoader by rememberUpdatedState(loadThumbnail)
    var bitmap by remember(item.path, sizePx) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.path, sizePx) {
        bitmap = runCatching { currentLoader(item, sizePx) }.getOrNull()
    }

    if (bitmap == null) {
        FileTypeIcon(
            name = item.name,
            isDirectory = item.isDirectory,
            modifier = modifier,
            size = size,
        )
        return
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0x22000000)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (isVideo) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(8.dp),
                )
            }
        }
    }
}
