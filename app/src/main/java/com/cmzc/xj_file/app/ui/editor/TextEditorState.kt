package com.cmzc.xj_file.app.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * 编辑器的界面状态 + 查找替换逻辑。
 *
 * 为什么单独拆一个文件：
 * 1. 这些逻辑全是纯字符串运算，不依赖文件 IO / 编码，单独放好读也好改；
 * 2. TextEditorScreen 本身已经有顶栏、正文、查找条和三个弹窗，再把逻辑塞进去就是
 *    又一座上千行的屎山，所以「UI 怎么摆」和「文本怎么算」分开。
 *
 * 这里**不做**任何解码：切换编码只是把编码名交给上层（5B 通过 onEncodingSelected 处理），
 * 因为真正的重新解码需要原始字节，UI 层拿不到。
 */
internal class TextEditorState(initialText: String) {

    /** 编辑区内容（含光标/选区，IME 组合输入也靠它传递）。 */
    var value by mutableStateOf(TextFieldValue(initialText))
        private set

    /**
     * 最近一次打开/保存时的文本。
     * 脏标记直接用「当前文本 != 基准」判断，比到处手动维护一个 dirty 布尔值可靠
     * （漏一处就会显示未保存但其实没改，或者改回去之后还显示脏）。
     */
    private var baseline by mutableStateOf(initialText)

    /** 有没有未保存的修改。 */
    val isDirty: Boolean
        get() = value.text != baseline

    /** 查找/替换条是否展开。 */
    var findVisible by mutableStateOf(false)

    /** 查找词。简单子串、不区分大小写（代码/日志里大小写经常记不清，忽略大小写更好用），不做正则。 */
    var findQuery by mutableStateOf("")

    /** 替换词。按普通文本原样插入，不做 $1 之类的正则替换。 */
    var replaceQuery by mutableStateOf("")

    /** 查找/替换的一次性提示（没有找到 / 已替换 N 处），null 表示不显示。 */
    var findHint by mutableStateOf<String?>(null)

    /** 顶栏编码菜单是否展开（菜单本身由 TextEditorScreen 用 AppDropdownMenu 画）。 */
    var encodingMenuExpanded by mutableStateOf(false)

    fun onValueChange(newValue: TextFieldValue) {
        value = newValue
    }

    /** 保存成功后调用：当前文本成为新的「干净」基准。 */
    fun markSaved() {
        baseline = value.text
    }

    /** 打开/收起查找条；打开时如果已经选中一段单行文字，直接拿它当查找词。 */
    fun toggleFind() {
        findVisible = !findVisible
        findHint = null
        if (findVisible && !value.selection.collapsed) {
            val selected = value.text.substring(value.selection.min, value.selection.max)
            // 带换行的选区不适合当查找词，会把查找框撑成多行
            if (selected.isNotEmpty() && '\n' !in selected) findQuery = selected
        }
    }

    /** 「下一个」：从当前选区之后开始找，找不到就绕回开头。 */
    fun findNext() {
        val query = findQuery
        if (query.isEmpty()) {
            findHint = "请先输入要查找的内容"
            return
        }
        // 有选区时从选区尾巴开始，这样连点「下一个」是往前推进而不是原地打转
        val from = if (value.selection.collapsed) value.selection.start else value.selection.end
        val match = EditorFind.next(value.text, query, from)
        if (match == null) {
            findHint = "没有找到"
            return
        }
        select(match)
        findHint = null
    }

    /** 「上一个」：从当前选区之前开始找，找不到就绕回末尾。 */
    fun findPrevious() {
        val query = findQuery
        if (query.isEmpty()) {
            findHint = "请先输入要查找的内容"
            return
        }
        val match = EditorFind.previous(value.text, query, value.selection.min)
        if (match == null) {
            findHint = "没有找到"
            return
        }
        select(match)
        findHint = null
    }

    /**
     * 「替换」：当前选中的正好是查找词时才替换它，替换完自动定位到下一处，
     * 连续点「替换」就是逐个替换。没选中时先定位到下一处，提示用户再点一次。
     */
    fun replaceCurrent() {
        val query = findQuery
        if (query.isEmpty()) {
            findHint = "请先输入要查找的内容"
            return
        }
        val text = value.text
        val selection = value.selection
        // 选中的区间必须和查找词完全一样（忽略大小写）才能替换，避免误改别的内容
        val selectionMatches = !selection.collapsed &&
            selection.length == query.length &&
            text.regionMatches(selection.min, query, 0, query.length, ignoreCase = true)
        if (!selectionMatches) {
            val match = EditorFind.next(text, query, selection.min)
            if (match == null) {
                findHint = "没有找到"
                return
            }
            select(match)
            findHint = "已定位到匹配处，再点一次「替换」即可"
            return
        }

        val newText = text.replaceRange(selection.min, selection.max, replaceQuery)
        val caret = selection.min + replaceQuery.length
        // 只在替换结果**之后**找下一处：不能环回。
        // 否则「abc → abc!」会把刚插入的 abc 又选中，连点替换变成 abc!!、abc!!!。
        val nextMatch = EditorFind.next(newText, query, caret)?.takeIf { it.first >= caret }
        value = if (nextMatch != null) {
            TextFieldValue(newText, TextRange(nextMatch.first, nextMatch.last + 1))
        } else {
            // 后面没有了：光标留在替换结果后面，不硬跳回开头
            TextFieldValue(newText, TextRange(caret))
        }
        findHint = "已替换 1 处"
    }

    /**
     * 「全部替换」：一次扫描算完，替换词按普通文本插入。
     *
     * 为什么不用 String.replace(ignoreCase)：那样要先统计次数就得扫两遍，而且用正则做
     * 忽略大小写容易把替换词里的 $、\ 当成转义；这里手动 indexOf 扫描，计数和结果一趟完成。
     */
    fun replaceAll() {
        val query = findQuery
        if (query.isEmpty()) {
            findHint = "请先输入要查找的内容"
            return
        }
        val text = value.text
        val builder = StringBuilder(text.length)
        var count = 0
        var copiedTo = 0
        var index = text.indexOf(query, 0, ignoreCase = true)
        while (index >= 0) {
            builder.append(text, copiedTo, index).append(replaceQuery)
            copiedTo = index + query.length
            count++
            index = text.indexOf(query, copiedTo, ignoreCase = true)
        }
        if (count == 0) {
            findHint = "没有找到"
            return
        }
        builder.append(text, copiedTo, text.length)
        // 全文被替换后原来的光标位置已经没有意义，夹到合法范围内即可
        val caret = value.selection.min.coerceIn(0, builder.length)
        value = TextFieldValue(builder.toString(), TextRange(caret))
        findHint = "已替换 $count 处"
    }

    private fun select(range: IntRange) {
        // 程序改选区时 IME 里旧的组合输入已经失效，所以换一个新的 TextFieldValue（composition 为空）
        value = TextFieldValue(value.text, TextRange(range.first, range.last + 1))
    }
}

/**
 * 纯字符串的查找工具：简单子串 + 不区分大小写，不做正则（用户要求）。
 *
 * 不预先建全文匹配表：2 MB 文本每敲一次键都建表太浪费内存和时间；
 * 「上一个/下一个」时用 indexOf / lastIndexOf 扫一遍就够。
 */
internal object EditorFind {

    /**
     * 从 [from] 往后找第一处；到文末还没找到就绕回开头。
     * 返回的是闭区间下标（含头含尾），方便直接转成 TextRange。
     */
    fun next(text: String, query: String, from: Int): IntRange? {
        if (query.isEmpty() || text.isEmpty()) return null
        val start = from.coerceIn(0, text.length)
        val index = text.indexOf(query, start, ignoreCase = true)
        if (index >= 0) return index until index + query.length
        val wrapped = text.indexOf(query, 0, ignoreCase = true)
        return if (wrapped >= 0) wrapped until wrapped + query.length else null
    }

    /** 从 [from] 之前往前找第一处；到文首还没找到就绕回末尾。 */
    fun previous(text: String, query: String, from: Int): IntRange? {
        if (query.isEmpty() || text.isEmpty()) return null
        val start = (from - 1).coerceAtMost(text.length - 1)
        var index = if (start >= 0) text.lastIndexOf(query, start, ignoreCase = true) else -1
        if (index < 0) index = text.lastIndexOf(query, text.length - 1, ignoreCase = true)
        return if (index >= 0) index until index + query.length else null
    }
}
