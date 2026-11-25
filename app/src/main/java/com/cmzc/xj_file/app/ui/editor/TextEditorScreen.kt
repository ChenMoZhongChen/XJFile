package com.cmzc.xj_file.app.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cmzc.xj_file.app.core.text.EncodingOption
import com.cmzc.xj_file.app.core.text.LineEnding
import com.cmzc.xj_file.app.core.text.TextBom
import com.cmzc.xj_file.app.core.text.TextDocument
import com.cmzc.xj_file.app.ui.components.AppDropdownMenu
import com.cmzc.xj_file.app.vm.EditorSavePolicy
import com.cmzc.xj_file.app.vm.EditorSaveResult
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 保存时交给上层的全部信息。
 *
 * [text] 里的换行已经统一成 `\n`，写盘前由 TextCodec.encode 按 [lineEnding] 还原；
 * BOM / 换行风格界面不允许改，原样带回，避免「另存一次文件就变样」。
 */
data class EditorSaveRequest(
    val text: String,
    val encoding: String,
    val bom: TextBom,
    val lineEnding: LineEnding,
)

/** 超过这个字符数改用按行 LazyColumn，避免一个 BasicTextField 装整篇。 */
private const val LARGE_EDITOR_CHARS = 200_000

/**
 * 全屏文本编辑器：顶栏（返回 / 文件名 / 未保存标记 / 查找 / 编码菜单 / 保存）+ 正文 + 查找替换条。
 *
 * 几个刻意的设计决定：
 * 1. **行号不单独滚动**：行号和正文放在同一个 verticalScroll 里，且用同一个
 *    TextStyle（等宽字体 + 同一个 lineHeight）。只要两边行数一样，「第 N 行」天然就在
 *    正文第 N 行的位置上，不需要任何 scroll 偏移同步代码，也不会因为四舍五入慢慢错位。
 * 2. **不自动换行**：旧版 BasicTextField 没有 softWrap 参数。把正文放进 horizontalScroll
 *    后，它的测量宽度变成无界，文本布局的 maxWidth 也就成了 Infinity，只有 `\n` 会换行，
 *    效果等价于 softWrap = false；行号 Text 则直接传 softWrap = false。
 * 3. **不碰编码**：这里只显示 document.encoding，换编码只回调 onEncodingSelected，
 *    真正的重新解码是上层（5B）的事——UI 层没有原始字节。
 * 4. **不做语法高亮**（5C 才做）、不读写文件、不弹英文错误。
 * 5. 大文件按 2 MB 以内考虑：整篇文本是一个 BasicTextField，不做分页/懒加载，
 *    因此也没有自己加大小限制（文件大小判断由 5B 负责）。
 */
@Composable
fun TextEditorScreen(
    document: TextDocument,
    encodingOptions: List<EncodingOption>,
    onBack: () -> Unit,
    /**
     * 保存由上层真正执行（5B 可能是网络写回）。**只有返回 true 才清脏标记**：
     * 写盘失败/编码不可映射时必须返回 false，让编辑器保留「未保存」，
     * 否则用户按返回会被当成没有修改，直接丢内容。
     */
    onSave: suspend (EditorSaveRequest, EditorSavePolicy) -> EditorSaveResult,
    onEncodingSelected: (String) -> Unit = {},
) {
    // 文档对象换了（换文件 / 换编码重新解码）就重建状态：旧文本、旧基准、查找条
    // 都不再对应当前文件，继续留着只会让脏标记和编码显示错乱。
    val state = remember(document) { TextEditorState(document.text) }
    val scope = rememberCoroutineScope()
    val largeMode = document.text.length >= LARGE_EDITOR_CHARS
    var showDiscardDialog by remember { mutableStateOf(false) }
    // 非空 = 用户选了编码但当前有未保存修改，先弹「会重新解码/丢修改」的确认框
    var pendingEncoding by remember { mutableStateOf<String?>(null) }
    // 保存中/保存失败：失败时保留脏标记，底栏给一句人话
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    // 保存前发现文件被别的程序改过：先让用户决定，不能静默覆盖
    var showSaveConflict by remember { mutableStateOf(false) }

    // 编码菜单显示中文名；编码内核里找不到对应项时退回规范名，保证界面永远有字
    val encodingLabel = remember(document.encoding, encodingOptions) {
        encodingOptions.firstOrNull { it.name == document.encoding }?.label ?: document.encoding
    }

    // 所有「返回」都走这里：有未保存修改先确认，避免手滑丢内容
    fun requestBack() {
        if (state.isDirty) showDiscardDialog = true else onBack()
    }

    /**
     * 保存：**先等上层写成功再清脏标记**。
     *
     * 5A 自己不做写盘，但 5B 会遇到编码不可映射、远端覆盖失败等真实错误；
     * 如果像以前那样先 markSaved() 再回调，失败后返回键不会提示，用户会丢内容。
     * 这里把「保存中」也挡住重复点击，失败只在底栏说人话，不弹英文异常。
     */
    fun saveDocument(policy: EditorSavePolicy = EditorSavePolicy.NORMAL) {
        if (saving) return
        val request = EditorSaveRequest(
            text = state.value.text,
            encoding = document.encoding,
            bom = document.bom,
            lineEnding = document.lineEnding,
        )
        saving = true
        saveError = null
        scope.launch {
            val result = runCatching { onSave(request, policy) }
                .getOrDefault(EditorSaveResult.FAILED)
            when (result) {
                EditorSaveResult.SAVED -> state.markSaved()
                EditorSaveResult.CONFLICT -> showSaveConflict = true
                EditorSaveResult.FAILED -> saveError = "保存失败，当前内容还没有写出去"
            }
            saving = false
        }
    }

    // 换编码：编码没变就什么也不做；有脏内容先确认。
    // 这里只提示「会重新解码」，不做任何解码动作（契约规定解码在 core/text）。
    fun chooseEncoding(name: String) {
        state.encodingMenuExpanded = false
        if (name != document.encoding) {
            if (state.isDirty) pendingEncoding = name else onEncodingSelected(name)
        }
    }

    BackHandler {
        when {
            // 查找条开着时，系统返回先收起它（和大多数编辑器习惯一致），不直接退出编辑页
            state.findVisible -> state.toggleFind()
            state.isDirty -> showDiscardDialog = true
            else -> onBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 一次吃掉状态栏 / 导航栏 / 输入法安全区：键盘弹出时编辑区自动缩到键盘上方，
            // 不用在每一块布局里各写一遍 imePadding，底部也不会被手势条压住。
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        EditorTopBar(
            state = state,
            fileName = document.fileName,
            encodingLabel = encodingLabel,
            currentEncoding = document.encoding,
            encodingOptions = encodingOptions,
            saving = saving,
            onBack = { requestBack() },
            onFindToggle = { state.toggleFind() },
            onSave = { saveDocument() },
            onEncodingOptionClick = { chooseEncoding(it) },
        )
        EditorDivider()
        if (state.findVisible) {
            FindReplaceBar(state = state, onClose = { state.toggleFind() })
            EditorDivider()
        }
        if (largeMode) {
            Text(
                text = "大文件模式：窗口化编辑，语法高亮已关闭",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        if (largeMode) {
            LargeTextEditorBody(
                state = state,
                modifier = Modifier.weight(1f),
            )
        } else {
            EditorBody(
                state = state,
                fileName = document.fileName,
                modifier = Modifier.weight(1f),
            )
        }
        EditorStatusBar(
            state = state,
            encodingLabel = encodingLabel,
            lineEndingLabel = document.lineEnding.label,
            message = when {
                saving -> "正在保存…"
                saveError != null -> saveError
                else -> null
            },
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("未保存的修改") },
            text = { Text("当前内容还没有保存，返回将丢失这些修改。") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onBack()
                }) { Text("放弃修改") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            },
        )
    }

    val switchingTo = pendingEncoding
    if (switchingTo != null) {
        AlertDialog(
            onDismissRequest = { pendingEncoding = null },
            title = { Text("切换编码") },
            text = { Text("切换编码会按新编码重新解码文件，当前未保存的修改将丢失。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingEncoding = null
                    onEncodingSelected(switchingTo)
                }) { Text("继续切换") }
            },
            dismissButton = {
                TextButton(onClick = { pendingEncoding = null }) { Text("取消") }
            },
        )
    }

    if (showSaveConflict) {
        AlertDialog(
            onDismissRequest = { showSaveConflict = false },
            title = { Text("文件已在外部修改") },
            text = {
                Text(
                    "打开这个文件之后，它在磁盘/服务器上又被改过或删除过。" +
                        "直接覆盖会丢掉外部修改；也可以先把当前内容保存成本地副本。"
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        showSaveConflict = false
                        saveDocument(EditorSavePolicy.LOCAL_COPY)
                    }) { Text("保存本地副本") }
                    TextButton(onClick = {
                        showSaveConflict = false
                        saveDocument(EditorSavePolicy.OVERWRITE)
                    }) { Text("仍然覆盖") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveConflict = false }) { Text("取消") }
            },
        )
    }
}

/** 顶栏：返回、文件名、未保存标记、查找开关、编码菜单、保存。 */
@Composable
private fun EditorTopBar(
    state: TextEditorState,
    fileName: String,
    encodingLabel: String,
    currentEncoding: String,
    encodingOptions: List<EncodingOption>,
    saving: Boolean,
    onBack: () -> Unit,
    onFindToggle: () -> Unit,
    onSave: () -> Unit,
    onEncodingOptionClick: (String) -> Unit,
) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = fileName,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (state.isDirty) {
            Text(
                text = "未保存",
                fontSize = 11.sp,
                color = accent,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        IconButton(onClick = onFindToggle) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = if (state.findVisible) "收起查找与替换" else "查找与替换",
                tint = if (state.findVisible) accent else secondary,
            )
        }
        // 编码菜单：按钮显示当前编码，点开由 AppDropdownMenu 负责（动画开关也交给它，和全 App 一致）
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = encodingOptions.isNotEmpty()) {
                        state.encodingMenuExpanded = true
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = encodingLabel, fontSize = 12.sp, color = secondary)
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = secondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            AppDropdownMenu(
                expanded = state.encodingMenuExpanded,
                onDismissRequest = { state.encodingMenuExpanded = false },
            ) {
                encodingOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label, fontSize = 14.sp) },
                        onClick = { onEncodingOptionClick(option.name) },
                        trailingIcon = if (option.name == currentEncoding) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "当前编码",
                                    tint = accent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        IconButton(onClick = onSave, enabled = !saving) {
            Icon(
                imageVector = Icons.Filled.Save,
                contentDescription = if (saving) "正在保存" else "保存",
                tint = if (saving) secondary.copy(alpha = 0.4f) else if (state.isDirty) accent else secondary,
            )
        }
    }
}

/** 查找/替换条。纯简单子串，点「替换」时按普通文本插入，不做正则。 */
@Composable
private fun FindReplaceBar(
    state: TextEditorState,
    onClose: () -> Unit,
) {
    val findFocusRequester = remember { FocusRequester() }
    // 展开查找条就把焦点放进「查找内容」，少点一次；只在本次组合时请求一次。
    LaunchedEffect(Unit) { findFocusRequester.requestFocus() }

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.findQuery,
                    onValueChange = {
                        state.findQuery = it
                        state.findHint = null
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(findFocusRequester),
                    singleLine = true,
                    placeholder = { Text("查找内容") },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    // 软键盘上的「搜索」直接当「下一个」，不用腾出手点按钮
                    keyboardActions = KeyboardActions(onSearch = { state.findNext() }),
                )
                TextButton(onClick = { state.findPrevious() }) { Text("上一个") }
                TextButton(onClick = { state.findNext() }) { Text("下一个") }
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭查找与替换",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.replaceQuery,
                    onValueChange = { state.replaceQuery = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("替换为") },
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { state.replaceCurrent() }) { Text("替换") }
                TextButton(onClick = { state.replaceAll() }) { Text("全部替换") }
            }
            val hint = state.findHint
            if (hint != null) {
                Text(
                    text = hint,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }
        }
    }
}

/**
 * 正文区：行号 + 可横向滚动的文本输入。
 *
 * 布局关键：外层只有一个 verticalScrollState，行号 Text 和 BasicTextField 是它同一行里的
 * 两个子项。滚动的是整行内容，所以行号和正文的行位置永远一致，不存在两套滚动值不同步的问题。
 */
@Composable
private fun EditorBody(
    state: TextEditorState,
    fileName: String,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // 正文和行号必须用同一个 TextStyle（等宽 + 同一个 lineHeight），这是行号对齐的前提
    val codeStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val gutterStyle = codeStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    val cursorColor = MaterialTheme.colorScheme.primary

    // 语法高亮只改绘制样式，不改文本/光标/选区；5C 起不再按文件大小关闭。
    val dark = com.cmzc.xj_file.app.ui.theme.LocalAppDarkTheme.current
    val language = remember(fileName) { SyntaxLanguage.fromFileName(fileName) }
    val highlighter = remember(language, dark) {
        SyntaxHighlighter(language, syntaxColors(dark))
    }
    val highlightTransformation = remember(highlighter) {
        SyntaxHighlightTransformation(highlighter)
    }
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var textViewportWidth by remember { mutableIntStateOf(0) }
    var lastTextLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val text = state.value.text
    val lineCount = remember(text) { countLines(text) }
    val lineNumbers = remember(lineCount) { buildLineNumbers(lineCount) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { viewportSize = it }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(verticalScrollState)
                // 底部留一点空：最后一行能滚到视野中间，不会被屏幕底边卡住
                .padding(top = 8.dp, bottom = 24.dp)
        ) {
            Text(
                text = lineNumbers,
                style = gutterStyle,
                softWrap = false,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .drawBehind {
                        // 行号列右侧画一条分隔线；行号 Text 的高度就是整篇文本的高度
                        val stroke = 1.dp.toPx()
                        val x = size.width - stroke / 2f
                        drawLine(
                            color = dividerColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = stroke,
                        )
                    }
                    .padding(start = 10.dp, end = 10.dp)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .onSizeChanged { textViewportWidth = it.width }
                    .horizontalScroll(horizontalScrollState)
            ) {
                BasicTextField(
                    value = state.value,
                    onValueChange = { state.onValueChange(it) },
                    modifier = Modifier
                        // 文本比屏幕窄时也把输入区撑满，点内容右侧的空白就能落光标
                        .widthIn(min = with(density) { textViewportWidth.toDp() }),
                    textStyle = codeStyle,
                    visualTransformation = highlightTransformation,
                    // 代码/配置被输入法自动纠错改字是灾难，直接关掉
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                    cursorBrush = SolidColor(cursorColor),
                    onTextLayout = { lastTextLayout = it },
                )
            }
        }
    }

    // 光标跟随：打字、点「下一个/上一个」改变选区后，把光标所在位置拉回可视区。
    // 只监听文本/选区/视口变化——用户自己拖动滚动时选区不变，所以不会被强行拽回来。
    // 用瞬时 scrollTo 而不是动画：打字跟手更重要；也不用担心「关闭动画后还有动画」。
    LaunchedEffect(lastTextLayout, state.value.selection, viewportSize, textViewportWidth) {
        val layout = lastTextLayout ?: return@LaunchedEffect
        if (viewportSize.height <= 0 || textViewportWidth <= 0) return@LaunchedEffect
        val caret = state.value.selection.min
            .coerceIn(0, layout.layoutInput.text.length)
        val rect = layout.getCursorRect(caret)
        val margin = with(density) { 32.dp.toPx() }

        var targetY = verticalScrollState.value.toFloat()
        if (rect.top < targetY + margin) {
            targetY = rect.top - margin
        } else if (rect.bottom > targetY + viewportSize.height - margin) {
            targetY = rect.bottom - viewportSize.height + margin
        }
        verticalScrollState.scrollTo(targetY.roundToInt())

        var targetX = horizontalScrollState.value.toFloat()
        if (rect.left < targetX + margin) {
            targetX = rect.left - margin
        } else if (rect.right > targetX + textViewportWidth - margin) {
            targetX = rect.right - textViewportWidth + margin
        }
        horizontalScrollState.scrollTo(targetX.roundToInt())
    }
}

/** 底部状态栏：光标行列 + 这次保存会用的编码/换行，方便用户知道保存出去是什么样子。 */
@Composable
private fun EditorStatusBar(
    state: TextEditorState,
    encodingLabel: String,
    lineEndingLabel: String,
    message: String?,
) {
    val text = state.value.text
    val caret = state.value.selection.min.coerceIn(0, text.length)
    // 行列是从文本头数 \n 算的：2 MB 以内每次按键扫一遍可以接受，
    // 换来的是不用把 TextLayoutResult 往上层传，这里逻辑最简单。
    val position = remember(text, caret) {
        var line = 1
        var lineStart = 0
        var index = 0
        while (index < caret) {
            if (text[index] == '\n') {
                line++
                lineStart = index + 1
            }
            index++
        }
        line to (caret - lineStart + 1)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "第 ${position.first} 行，第 ${position.second} 列",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (message != null) {
            Text(
                text = message,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Text(
            text = "$encodingLabel · 换行 $lineEndingLabel",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 顶栏 / 查找条 / 状态栏之间的细分隔线。 */
@Composable
private fun EditorDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/** 行数 = `\n` 的个数 + 1；最后一行没有换行符也算一行。 */
private fun countLines(text: String): Int {
    var lines = 1
    for (index in text.indices) {
        if (text[index] == '\n') lines++
    }
    return lines
}

/**
 * 生成整块行号文本 `"1\n2\n…\nN"`。
 *
 * 为什么是一整块 Text 而不是每行一个 Composable：2 MB 的文件可能有几万行，
 * 几万个 Composable 光组合就要好久；整块 Text 只有一次布局。而且它和正文行数一致、
 * 行高一致，行号对齐完全由文本布局保证，不需要写同步逻辑。
 */
private fun buildLineNumbers(lineCount: Int): String {
    val digits = lineCount.toString().length + 1
    val capacity = (lineCount.toLong() * digits).coerceAtMost(1_048_576L).toInt()
    val builder = StringBuilder(capacity)
    for (line in 1..lineCount) {
        if (line > 1) builder.append('\n')
        builder.append(line)
    }
    return builder.toString()
}
