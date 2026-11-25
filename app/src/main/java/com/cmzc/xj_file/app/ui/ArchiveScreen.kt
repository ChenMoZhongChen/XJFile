package com.cmzc.xj_file.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cmzc.xj_file.app.core.archive.ArchiveEntryInfo
import com.cmzc.xj_file.app.core.playback.MediaTypes
import com.cmzc.xj_file.app.core.text.TextFileTypes
import com.cmzc.xj_file.app.core.util.Formatters
import com.cmzc.xj_file.app.ui.components.FileTypeIcon
import com.cmzc.xj_file.app.ui.components.PathBar
import com.cmzc.xj_file.app.vm.ArchiveSession
import com.cmzc.xj_file.app.vm.ArchiveTextPreview

/**
 * 压缩包浏览页。
 *
 * 这是只读的内容列表：目录可以继续点进去，文件可以单独解压；
 * 顶部「解压全部」把整个包解到打开压缩包时所在的面板目录。
 * 解压成功后由 ViewModel 刷新面板，页面不自动关闭，方便继续看其他条目。
 */
@Composable
fun ArchiveScreen(
    session: ArchiveSession,
    busy: Boolean,
    progress: Pair<Int, Int>,
    focused: Boolean,
    onFocus: () -> Unit,
    currentDir: String,
    onCurrentDirChange: (String) -> Unit,
    onBack: () -> Unit,
    onExtractEntry: (ArchiveEntryInfo) -> Unit,
    onPreviewEntry: (ArchiveEntryInfo) -> Unit,
) {
    var actionEntry by remember { mutableStateOf<ArchiveEntryInfo?>(null) }

    val currentEntries = remember(session.entries, currentDir) {
        session.entries.filter { it.parent == currentDir }
            .sortedWith(compareByDescending<ArchiveEntryInfo> { it.isDirectory }.thenBy { it.name })
    }

    val goBack: () -> Unit = {
        if (currentDir.isEmpty()) onBack()
        else onCurrentDirChange(currentDir.substringBeforeLast('/', ""))
    }

    BackHandler(onBack = goBack)

    Surface(
        color = if (focused) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    onFocus()
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 和普通文件栏使用同一个 PathBar 组件，背景、字号、内边距保持一致。
            PathBar(
                sourceLabel = archiveBaseName(session.archiveName),
                path = if (currentDir.isEmpty()) "/" else "/${currentDir.trim('/')}/",
                onClick = {},
                sourceLabelMiddleEllipsis = true,
            )

            if (busy) {
                val total = progress.second.coerceAtLeast(1)
                LinearProgressIndicator(
                    progress = { progress.first.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "正在解压 ${progress.first}/$total",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (currentEntries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "这个目录是空的",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item(key = "..") {
                        ArchiveParentRow(onClick = goBack)
                    }
                    items(currentEntries, key = { it.path }) { entry ->
                        ArchiveEntryRow(
                            entry = entry,
                            busy = busy,
                            onClick = {
                                if (entry.isDirectory) onCurrentDirChange(entry.path)
                                else if (canPreview(entry.name)) onPreviewEntry(entry)
                            },
                            onLongClick = { actionEntry = entry },
                        )
                    }
                }
            }
        }
    }

    actionEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { actionEntry = null },
            title = { Text(entry.name, fontSize = 15.sp) },
            text = {
                Text(
                    if (entry.isDirectory) "这是一个文件夹，可以解压到对侧文件栏。"
                    else "选择预览或解压到对侧文件栏。",
                    fontSize = 12.sp,
                )
            },
            confirmButton = {
                Row {
                    if (!entry.isDirectory && canPreview(entry.name)) {
                        TextButton(onClick = {
                            actionEntry = null
                            onPreviewEntry(entry)
                        }) { Text("预览") }
                    }
                    TextButton(onClick = {
                        actionEntry = null
                        onExtractEntry(entry)
                    }) { Text(if (entry.isDirectory) "解压此目录" else "解压") }
                }
            },
            dismissButton = {
                TextButton(onClick = { actionEntry = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ArchiveParentRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTypeIcon(
            name = "",
            isDirectory = true,
            size = 18.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text("..", fontSize = 12.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchiveEntryRow(
    entry: ArchiveEntryInfo,
    busy: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = !busy,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FileTypeIcon(
            name = entry.name,
            isDirectory = entry.isDirectory,
            modifier = Modifier.padding(top = 2.dp),
            size = 18.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = Formatters.formatTime(entry.lastModified),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!entry.isDirectory && entry.size > 0) {
                    Text(
                        text = Formatters.formatSize(entry.size),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun canPreview(name: String): Boolean =
    TextFileTypes.shouldOpenAsText(name) ||
        TextFileTypes.isPdf(name) ||
        MediaTypes.isImage(name) ||
        MediaTypes.isPlayable(name)

/** 压缩包名去掉后缀；.tar.gz 这类复合后缀整个去掉。 */
private fun archiveBaseName(name: String): String {
    val lower = name.lowercase()
    val suffixes = listOf(
        ".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst",
        ".tgz", ".tbz2", ".txz", ".zipx", ".zip", ".7z",
        ".rar", ".tar", ".gz", ".xz", ".bz2", ".zst",
    )
    val suffix = suffixes.firstOrNull { lower.endsWith(it) }
    return if (suffix != null) name.dropLast(suffix.length) else name
}

/** 压缩包内文本条目的内置只读预览，不经过系统编辑器。 */
@Composable
fun ArchiveTextPreviewDialog(
    preview: ArchiveTextPreview,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .heightIn(max = 640.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preview.name,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "内置预览 · ${preview.encoding}" +
                                if (preview.hadErrors) " · 含无法识别字节" else "",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onClose) { Text("关闭") }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                SelectionContainer {
                    Text(
                        text = preview.text,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
