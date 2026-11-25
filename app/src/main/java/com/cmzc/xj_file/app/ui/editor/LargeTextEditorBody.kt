package com.cmzc.xj_file.app.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/** 一页 = 80 行；LazyColumn 只组合视口附近的页，屏幕外的页不渲染。 */
private const val PAGE_LINES = 80

private data class LargePage(
    val id: Int,
    val start: Int,
    val startLine: Int,
    val text: String,
)

/**
 * 超大文本的窗口化编辑：
 * - 文本切成 80 行的页，LazyColumn 只组合屏幕附近几页；
 * - 每页本身是一个可编辑的 BasicTextField，不是弹窗、不是只读预览；
 * - 页内可以直接点、选、输入，页边界外不组合；
 * - 保存时把各页拼回整篇文本。
 *
 * 这不是把整篇文本塞进一个 TextField，所以几千页的文件也只布局视口附近的几页。
 */
@Composable
internal fun LargeTextEditorBody(
    state: TextEditorState,
    modifier: Modifier = Modifier,
) {
    val pages = remember { mutableStateListOf<LargePage>() }
    val pageValues = remember { mutableStateMapOf<Int, TextFieldValue>() }
    val listState = rememberLazyListState()
    var lastBuiltText by remember { mutableStateOf("") }

    fun rebuildPages(text: String) {
        pages.clear()
        pageValues.clear()
        var offset = 0
        var line = 0
        var id = 0
        val lines = text.split('\n')
        var index = 0
        while (index < lines.size) {
            val end = min(index + PAGE_LINES, lines.size)
            val pageText = buildString {
                for (i in index until end) {
                    if (i > index) append('\n')
                    append(lines[i])
                }
                // 非末页带上换行，拼回去时不会少掉页间换行
                if (end < lines.size) append('\n')
            }
            pages += LargePage(id = id, start = offset, startLine = line, text = pageText)
            pageValues[id] = TextFieldValue(pageText, TextRange(0))
            offset += pageText.length
            line += end - index
            index = end
            id++
        }
        if (pages.isEmpty()) {
            pages += LargePage(0, 0, 0, "")
            pageValues[0] = TextFieldValue("", TextRange(0))
        }
        lastBuiltText = pages.joinToString("") { it.text }
    }

    fun publish(selection: TextRange) {
        val full = pages.joinToString("") { it.text }
        lastBuiltText = full
        state.onValueChange(TextFieldValue(full, selection))
    }

    fun recomputeOffsets() {
        var offset = 0
        var line = 0
        for (i in pages.indices) {
            val p = pages[i]
            pages[i] = p.copy(start = offset, startLine = line)
            offset += p.text.length
            line += p.text.count { it == '\n' }
        }
    }

    fun reindexPages() {
        val old = pages.toList()
        val values = pageValues.toMap()
        pages.clear()
        pages.addAll(old.mapIndexed { index, page -> page.copy(id = index) })
        pageValues.clear()
        pages.forEachIndexed { index, page ->
            pageValues[index] = values[page.id] ?: TextFieldValue(page.text, TextRange(0))
        }
    }

    /** 选区拖到页尾时把下一页并进来，让一次拖选可以跨页。 */
    fun mergeNext(pageIndex: Int, value: TextFieldValue): Boolean {
        val nextIndex = pageIndex + 1
        if (nextIndex !in pages.indices) return false
        val current = pages[pageIndex]
        val next = pages[nextIndex]
        val merged = current.text + next.text
        pages[pageIndex] = current.copy(text = merged)
        pageValues[current.id] = value.copy(
            text = merged,
            selection = TextRange(value.selection.min, current.text.length),
        )
        pages.removeAt(nextIndex)
        pageValues.remove(next.id)
        recomputeOffsets()
        reindexPages()
        val page = pages[pageIndex]
        publish(
            TextRange(
                page.start + value.selection.min,
                page.start + current.text.length,
            )
        )
        return true
    }

    /** 选区拖到页首时把上一页并进来。 */
    fun mergePrevious(pageIndex: Int, value: TextFieldValue): Boolean {
        if (pageIndex <= 0) return false
        val previous = pages[pageIndex - 1]
        val current = pages[pageIndex]
        val shift = previous.text.length
        val merged = previous.text + current.text
        pages[pageIndex - 1] = previous.copy(text = merged)
        pageValues[previous.id] = value.copy(
            text = merged,
            selection = TextRange(value.selection.min + shift, value.selection.max + shift),
        )
        pages.removeAt(pageIndex)
        pageValues.remove(current.id)
        recomputeOffsets()
        reindexPages()
        val page = pages[pageIndex - 1]
        publish(
            TextRange(
                page.start + value.selection.min + shift,
                page.start + value.selection.max + shift,
            )
        )
        return true
    }

    fun caretMergeNext(pageIndex: Int): Boolean {
        val nextIndex = pageIndex + 1
        if (nextIndex !in pages.indices) return false
        val current = pages[pageIndex]
        val next = pages[nextIndex]
        val merged = current.text + next.text
        pages[pageIndex] = current.copy(text = merged)
        pageValues[current.id] = TextFieldValue(
            merged,
            TextRange(current.text.length.coerceIn(0, merged.length)),
        )
        pages.removeAt(nextIndex)
        pageValues.remove(next.id)
        recomputeOffsets()
        reindexPages()
        val page = pages[pageIndex]
        publish(TextRange(page.start + current.text.length.coerceIn(0, merged.length)))
        return true
    }

    fun caretMergePrevious(pageIndex: Int): Boolean {
        if (pageIndex <= 0) return false
        val previous = pages[pageIndex - 1]
        val current = pages[pageIndex]
        val merged = previous.text + current.text
        pages[pageIndex - 1] = previous.copy(text = merged)
        pageValues[previous.id] = TextFieldValue(merged, TextRange(previous.text.length))
        pages.removeAt(pageIndex)
        pageValues.remove(current.id)
        recomputeOffsets()
        reindexPages()
        val page = pages[pageIndex - 1]
        publish(TextRange(page.start + previous.text.length))
        return true
    }

    // 首次进入；以及查找替换/换编码等从外部改了整篇文本时重建页。
    LaunchedEffect(Unit) { rebuildPages(state.value.text) }
    LaunchedEffect(state.value.text) {
        if (state.value.text != lastBuiltText && pages.isNotEmpty()) {
            rebuildPages(state.value.text)
        }
    }

    // 查找选中位置后滚到对应页
    LaunchedEffect(state.value.selection.min) {
        val caret = state.value.selection.min.coerceIn(0, state.value.text.length)
        val pageIndex = pages.indexOfLast { it.start <= caret }.coerceAtLeast(0)
        if (pages.isNotEmpty()) {
            listState.scrollToItem(pageIndex.coerceIn(0, pages.lastIndex))
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
    ) {
        items(pages, key = { it.id }) { page ->
            PageEditor(
                page = page,
                value = pageValues[page.id] ?: TextFieldValue(page.text),
                onMergeNext = { caretMergeNext(page.id) },
                onMergePrevious = { caretMergePrevious(page.id) },
                onValueChange = { newValue ->
                    pageValues[page.id] = newValue
                    val oldText = page.text
                    val delta = newValue.text.length - oldText.length
                    val deltaLines = countLines(newValue.text) - countLines(oldText)
                    pages[page.id] = page.copy(text = newValue.text)
                    // 后续页的偏移/行号跟着当前页的长度变化平移，不重建整份页表
                    for (i in page.id + 1 until pages.size) {
                        val p = pages[i]
                        pages[i] = p.copy(
                            start = (p.start + delta).coerceAtLeast(0),
                            startLine = (p.startLine + deltaLines).coerceAtLeast(0),
                        )
                    }
                    // 拖选/键盘光标到页边界时并页，实现跨页。
                    var merged = false
                    if (!newValue.selection.collapsed &&
                        newValue.selection.max == newValue.text.length
                    ) {
                        merged = mergeNext(page.id, newValue)
                    }
                    if (!merged &&
                        !newValue.selection.collapsed &&
                        newValue.selection.min == 0
                    ) {
                        merged = mergePrevious(page.id, newValue)
                    }
                    if (!merged) {
                        val full = pages.joinToString("") { it.text }
                        lastBuiltText = full
                        state.onValueChange(
                            TextFieldValue(
                                text = full,
                                selection = TextRange(
                                    (page.start + newValue.selection.min)
                                        .coerceIn(0, full.length),
                                    (page.start + newValue.selection.max)
                                        .coerceIn(0, full.length),
                                ),
                            )
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun PageEditor(
    page: LargePage,
    value: TextFieldValue,
    onMergeNext: () -> Boolean,
    onMergePrevious: () -> Boolean,
    onValueChange: (TextFieldValue) -> Unit,
) {
    val codeStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val lineCount = page.text.let { text ->
        val count = countLines(text)
        if (text.endsWith('\n')) (count - 1).coerceAtLeast(1) else count
    }
    val gutter = buildString {
        repeat(lineCount) { i ->
            if (i > 0) append('\n')
            append(page.startLine + i + 1)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = gutter,
            style = codeStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            softWrap = false,
            modifier = Modifier.width(44.dp).padding(end = 6.dp),
        )
        Box(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = codeStyle,
                keyboardOptions = KeyboardOptions(autoCorrect = false),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || !value.selection.collapsed) {
                        false
                    } else if (event.key == Key.DirectionRight &&
                        value.selection.start == value.text.length
                    ) {
                        onMergeNext()
                    } else if (event.key == Key.DirectionLeft && value.selection.start == 0) {
                        onMergePrevious()
                    } else {
                        false
                    }
                },
            )
        }
    }
}

private fun countLines(text: String): Int {
    var lines = 1
    for (c in text) if (c == '\n') lines++
    return lines
}
