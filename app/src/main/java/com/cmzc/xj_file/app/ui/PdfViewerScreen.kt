package com.cmzc.xj_file.app.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cmzc.xj_file.app.vm.PdfViewerRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/**
 * 内置 PDF 查看器：直接用系统 PdfRenderer，一页一页渲染成 Bitmap。
 * 不依赖第三方 PDF 库，也不把文件交给外部应用。
 */
@Composable
fun PdfViewerScreen(
    request: PdfViewerRequest,
    onClose: () -> Unit,
    onOpenSystem: (() -> Unit)? = null,
) {
    BackHandler(onBack = onClose)

    var renderer by remember(request.file) { mutableStateOf<PdfRenderer?>(null) }
    var pageCount by remember(request.file) { mutableIntStateOf(0) }
    var pageIndex by remember(request.file) { mutableIntStateOf(0) }
    var bitmap by remember(request.file) { mutableStateOf<Bitmap?>(null) }
    var rendering by remember(request.file) { mutableStateOf(false) }
    var error by remember(request.file) { mutableStateOf<String?>(null) }

    DisposableEffect(request.file) {
        var descriptor: ParcelFileDescriptor? = null
        var opened: PdfRenderer? = null
        try {
            descriptor = ParcelFileDescriptor.open(
                request.file,
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            opened = PdfRenderer(descriptor)
            if (opened.pageCount <= 0) {
                error = "PDF 没有页面"
                opened.close()
                descriptor.close()
            } else {
                renderer = opened
                pageCount = opened.pageCount
                error = null
            }
        } catch (t: Throwable) {
            error = t.message ?: "PDF 打开失败"
            runCatching { opened?.close() }
            runCatching { descriptor?.close() }
        }
        onDispose {
            // 渲染在 IO 线程；关闭时等它结束，避免 close 和 render 抢同一个原生句柄。
            synchronized(PDF_RENDER_LOCK) {
                runCatching { opened?.close() }
            }
            runCatching { descriptor?.close() }
        }
    }

    fun go(delta: Int) {
        if (pageCount <= 0) return
        pageIndex = (pageIndex + delta).coerceIn(0, pageCount - 1)
    }

    Surface(
        color = Color.Black,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Color(0xFF111111)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }
                Text(
                    text = request.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (pageCount > 0) "${pageIndex + 1}/$pageCount" else "",
                    color = Color(0xFFBDBDBD),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black)
            ) {
                val maxWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
                val maxHeightPx = with(LocalDensity.current) { maxHeight.toPx() }

                LaunchedEffect(renderer, pageIndex, maxWidthPx, maxHeightPx) {
                    val pdf = renderer ?: return@LaunchedEffect
                    if (maxWidthPx <= 0f || maxHeightPx <= 0f) return@LaunchedEffect
                    rendering = true
                    runCatching {
                        withContext(Dispatchers.IO) {
                            synchronized(PDF_RENDER_LOCK) {
                                renderPage(pdf, pageIndex, maxWidthPx, maxHeightPx)
                            }
                        }
                    }.onSuccess {
                        bitmap = it
                        error = null
                    }.onFailure {
                        error = it.message ?: "PDF 页面渲染失败"
                    }
                    rendering = false
                }

                val current = bitmap
                if (current != null) {
                    Image(
                        bitmap = current.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(pageCount) {
                                var totalDx = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { totalDx = 0f },
                                    onDragEnd = {
                                        if (abs(totalDx) > 80f) go(if (totalDx < 0) 1 else -1)
                                    },
                                    onHorizontalDrag = { _, dragAmount ->
                                        totalDx += dragAmount
                                    }
                                )
                            }
                    )
                }

                if (error != null) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = error ?: "PDF 打不开",
                            color = Color(0xFFE0E0E0),
                            fontSize = 13.sp
                        )
                        if (onOpenSystem != null) {
                            TextButton(onClick = onOpenSystem) {
                                Text("用系统应用打开")
                            }
                        }
                    }
                } else if (rendering && current == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Color(0xFF111111)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { go(-1) }, enabled = pageIndex > 0) {
                    Icon(
                        Icons.Default.ChevronLeft,
                        contentDescription = "上一页",
                        tint = if (pageIndex > 0) Color.White else Color(0xFF555555)
                    )
                }
                Text(
                    text = if (pageCount > 0) "第 ${pageIndex + 1} / $pageCount 页" else "加载中…",
                    color = Color(0xFFBDBDBD),
                    fontSize = 12.sp
                )
                IconButton(onClick = { go(1) }, enabled = pageIndex < pageCount - 1) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "下一页",
                        tint = if (pageIndex < pageCount - 1) Color.White else Color(0xFF555555)
                    )
                }
            }
        }
    }
}

private val PDF_RENDER_LOCK = Any()

private fun renderPage(
    renderer: PdfRenderer,
    index: Int,
    maxWidth: Float,
    maxHeight: Float,
): Bitmap {
    renderer.openPage(index).use { page ->
        val scale = min(
            maxWidth / page.width.coerceAtLeast(1),
            maxHeight / page.height.coerceAtLeast(1)
        )
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(AndroidColor.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }
}
