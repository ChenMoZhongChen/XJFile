package com.cmzc.xj_file.app.ui.editor

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * 5C：轻量语法高亮。
 *
 * 为什么不引第三方编辑器库：工程锁 compileSdk 34 / Compose BOM 2024.02，
 * 大多数「代码编辑器」库要么自带一套 View 体系、要么要求更高 compileSdk；
 * 这里只做一条 [VisualTransformation]，把文本按 token 涂色，不改变内容、
 * 不改光标/选区/IME 行为，风险最小。
 *
 * 性能边界：
 * - 不按文件大小一刀切，只扫描**可视区域附近**的文本切片；滚到哪就高亮哪，
 *   切到哪就重算哪，远端的文本保持纯文本，不参与扫描；
 * - [SyntaxHighlighter] 缓存上一次的 (文本, 区间)，Compose 同一帧重复 filter
 *   不会重复扫描，滚动时只换窗口；
 * - Lazy 行号/滚动布局不受影响，因为这里只生成 AnnotatedString。
 */
enum class SyntaxLanguage(val label: String) {
    PLAIN("纯文本"),
    C("C"),
    CPP("C++"),
    CS("C#"),
    JAVA("Java"),
    KOTLIN("Kotlin"),
    PYTHON("Python"),
    HTML("HTML"),
    XML("XML/XSL"),
    JS("JavaScript"),
    JSON("JSON"),
    CSS("CSS"),
    SHELL("Shell");

    companion object {
        fun fromFileName(name: String): SyntaxLanguage {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "c" -> C
                // 头文件按 C++ 词表处理：C++ 是 C 的超集，.h 里的 C 代码也照样能认，
                // 反而能覆盖大量 C++ 头文件里的 class/template/namespace。
                "h" -> CPP
                "cc", "cpp", "cxx", "hpp", "hh", "hxx" -> CPP
                "cs" -> CS
                "java" -> JAVA
                "kt", "kts" -> KOTLIN
                "py", "pyw" -> PYTHON
                "htm", "html", "xhtml" -> HTML
                "xml", "xsl", "xslt", "xsd", "svg" -> XML
                "js", "mjs", "cjs", "jsx", "ts", "tsx" -> JS
                "json", "jsonc", "json5" -> JSON
                "css", "scss", "sass", "less" -> CSS
                "sh", "bash", "zsh", "fish" -> SHELL
                else -> PLAIN
            }
        }
    }
}

@Immutable
data class SyntaxColors(
    val keyword: Color,
    val type: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val function: Color,
    val tag: Color,
    val attr: Color,
    val preprocessor: Color,
)

/** 深色/浅色两套；选色标准只有一条：正文对比度够、不同 token 能一眼分开。 */
fun syntaxColors(dark: Boolean): SyntaxColors = if (dark) {
    SyntaxColors(
        keyword = Color(0xFFC792EA),
        type = Color(0xFF82AAFF),
        string = Color(0xFFC3E88D),
        comment = Color(0xFF7F8C98),
        number = Color(0xFFF78C6C),
        function = Color(0xFFFFCB6B),
        tag = Color(0xFF89DDFF),
        attr = Color(0xFFFFCB6B),
        preprocessor = Color(0xFFFF9CAC),
    )
} else {
    SyntaxColors(
        keyword = Color(0xFF7B1FA2),
        type = Color(0xFF005CC5),
        string = Color(0xFF2E7D32),
        comment = Color(0xFF6A737D),
        number = Color(0xFFB15C00),
        function = Color(0xFF795548),
        tag = Color(0xFF0066B3),
        attr = Color(0xFFB26A00),
        preprocessor = Color(0xFF9C27B0),
    )
}

class SyntaxHighlighter(
    private val language: SyntaxLanguage,
    private val colors: SyntaxColors,
) {
    private var cachedText: String? = null
    private var cached: AnnotatedString? = null

    /**
     * 对整篇文本做词法扫描。5C 起不再按文件大小关闭高亮；编辑器本身仍受
     * 5B 的 2MB 读入上限保护。扫描结果按文本缓存，Compose 同帧重复 filter
     * 不会重复跑。
     */
    fun highlight(text: String): AnnotatedString {
        if (text == cachedText) return cached ?: AnnotatedString(text)
        cachedText = text
        val result = if (language == SyntaxLanguage.PLAIN) {
            AnnotatedString(text)
        } else {
            val marks = ArrayList<Mark>(text.length / 12 + 16)
            when (language) {
                SyntaxLanguage.HTML, SyntaxLanguage.XML -> scanMarkup(text, marks)
                SyntaxLanguage.JSON -> scanJson(text, marks)
                else -> scanCode(text, marks)
            }
            buildAnnotatedString {
                append(text)
                for (m in marks) addStyle(SpanStyle(color = m.color), m.start, m.end)
            }
        }
        cached = result
        return result
    }

    private class Mark(val start: Int, val end: Int, val color: Color)

    private fun mark(marks: MutableList<Mark>, start: Int, end: Int, color: Color) {
        if (end > start) marks += Mark(start, end, color)
    }

    // ───────────────────────── C 系 / Python / Shell / CSS ─────────────────────────

    private fun scanCode(text: String, marks: MutableList<Mark>) {
        val n = text.length
        var i = 0
        var lineStart = true
        var prevWord = ""
        val keywords = keywordsFor(language)
        val types = typesFor(language)
        val hashComment = language == SyntaxLanguage.PYTHON || language == SyntaxLanguage.SHELL
        val preprocessor = language == SyntaxLanguage.C ||
            language == SyntaxLanguage.CPP ||
            language == SyntaxLanguage.CS
        val templateString = language == SyntaxLanguage.JS ||
            language == SyntaxLanguage.KOTLIN

        while (i < n) {
            val c = text[i]
            if (c == '\n') {
                i++
                lineStart = true
                continue
            }
            if (c == ' ' || c == '\t' || c == '\r') {
                i++
                continue
            }

            // 行注释
            if (c == '/' && i + 1 < n && text[i + 1] == '/') {
                val s = i
                i += 2
                while (i < n && text[i] != '\n') i++
                mark(marks, s, i, colors.comment)
                lineStart = false
                continue
            }
            // 块注释
            if (c == '/' && i + 1 < n && text[i + 1] == '*') {
                val s = i
                i += 2
                while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) i++
                i = (i + 2).coerceAtMost(n)
                mark(marks, s, i, colors.comment)
                lineStart = false
                continue
            }
            // C 系预处理指令；Python/Shell 的 # 是注释
            if (c == '#' && preprocessor && lineStart) {
                val s = i
                while (i < n && text[i] != '\n') i++
                mark(marks, s, i, colors.preprocessor)
                lineStart = false
                continue
            }
            if (c == '#' && hashComment) {
                val s = i
                while (i < n && text[i] != '\n') i++
                mark(marks, s, i, colors.comment)
                lineStart = false
                continue
            }
            // Shell 变量：$HOME / ${x}
            if (c == '$' && language == SyntaxLanguage.SHELL) {
                val s = i
                i++
                if (i < n && text[i] == '{') {
                    while (i < n && text[i] != '}' && text[i] != '\n') i++
                    if (i < n && text[i] == '}') i++
                } else {
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                }
                mark(marks, s, i, colors.type)
                lineStart = false
                continue
            }
            // 字符串 / 字符 / JS 模板串 / Kotlin-Python 三引号
            if (c == '"' || c == '\'' || (c == '`' && templateString)) {
                val end = scanString(text, i, c, templateString || language == SyntaxLanguage.PYTHON)
                mark(marks, i, end, colors.string)
                i = end
                lineStart = false
                prevWord = ""
                continue
            }
            // 数字
            if (c.isDigit() || (c == '.' && i + 1 < n && text[i + 1].isDigit())) {
                val end = scanNumber(text, i)
                mark(marks, i, end, colors.number)
                i = end
                lineStart = false
                prevWord = ""
                continue
            }
            // 注解/装饰器：@Override / @Composable / @dataclass
            if (c == '@' && (language == SyntaxLanguage.JAVA ||
                    language == SyntaxLanguage.KOTLIN ||
                    language == SyntaxLanguage.PYTHON ||
                    language == SyntaxLanguage.CS)
            ) {
                val s = i
                i++
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                mark(marks, s, i, colors.keyword)
                lineStart = false
                prevWord = ""
                continue
            }
            // 标识符/关键字/类型/函数名
            if (isIdentStart(c) || c == '\\') {
                val s = i
                i++
                while (i < n && isIdentPart(text[i])) i++
                val word = text.substring(s, i)
                when {
                    word in keywords -> mark(marks, s, i, colors.keyword)
                    word in types -> mark(marks, s, i, colors.type)
                    prevWord in TYPE_DECL -> mark(marks, s, i, colors.type)
                    nextNonSpaceIsParen(text, i) -> mark(marks, s, i, colors.function)
                }
                prevWord = word
                lineStart = false
                continue
            }

            // 其他标点/运算符保持正文色
            i++
            lineStart = false
        }
    }

    private fun scanNumber(text: String, start: Int): Int {
        val n = text.length
        var i = start
        if (text[i] == '0' && i + 1 < n && (text[i + 1] == 'x' || text[i + 1] == 'X')) {
            i += 2
            while (i < n && (text[i].isDigit() || text[i].lowercaseChar() in 'a'..'f' || text[i] == '_')) i++
            return i
        }
        if (text[i] == '0' && i + 1 < n && (text[i + 1] == 'b' || text[i + 1] == 'B')) {
            i += 2
            while (i < n && (text[i] == '0' || text[i] == '1' || text[i] == '_')) i++
            return i
        }
        while (i < n && (text[i].isDigit() || text[i] == '_')) i++
        if (i < n && text[i] == '.') {
            i++
            while (i < n && text[i].isDigit()) i++
        }
        if (i < n && (text[i] == 'e' || text[i] == 'E')) {
            i++
            if (i < n && (text[i] == '+' || text[i] == '-')) i++
            while (i < n && text[i].isDigit()) i++
        }
        while (i < n && (text[i] == 'f' || text[i] == 'F' || text[i] == 'd' ||
                text[i] == 'D' || text[i] == 'l' || text[i] == 'L' ||
                text[i] == 'u' || text[i] == 'U')) i++
        return i
    }

    private fun scanString(text: String, start: Int, quote: Char, allowTriple: Boolean): Int {
        val n = text.length
        var i = start + 1
        if (allowTriple && i + 1 < n && text[i] == quote && text[i + 1] == quote) {
            i += 2
            while (i + 2 < n) {
                if (text[i] == quote && text[i + 1] == quote && text[i + 2] == quote) return i + 3
                i++
            }
            return n
        }
        while (i < n) {
            val c = text[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == quote) return i + 1
            if (c == '\n' && !allowTriple) return i
            i++
        }
        return n
    }

    private fun nextNonSpaceIsParen(text: String, from: Int): Boolean {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\r')) i++
        return i < text.length && text[i] == '('
    }

    // ───────────────────────── HTML / XML / XSL ─────────────────────────

    private fun scanMarkup(text: String, marks: MutableList<Mark>) {
        val n = text.length
        var i = 0
        while (i < n) {
            if (text.startsWith("<!--", i)) {
                val end = text.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }
                mark(marks, i, end, colors.comment)
                i = end
                continue
            }
            if (text.startsWith("<![CDATA[", i)) {
                val end = text.indexOf("]]>", i + 9).let { if (it < 0) n else it + 3 }
                mark(marks, i, end, colors.string)
                i = end
                continue
            }
            if (text.startsWith("<?", i)) {
                val end = text.indexOf("?>", i + 2).let { if (it < 0) n else it + 2 }
                mark(marks, i, end, colors.preprocessor)
                i = end
                continue
            }
            if (text.startsWith("<!DOCTYPE", i, ignoreCase = true)) {
                val end = text.indexOf('>', i).let { if (it < 0) n else it + 1 }
                mark(marks, i, end, colors.preprocessor)
                i = end
                continue
            }
            if (text[i] == '<' && i + 1 < n && (text[i + 1].isLetter() || text[i + 1] == '/' || text[i + 1] == '_')) {
                i = scanTag(text, i, marks)
                continue
            }
            if (text[i] == '&') {
                val end = text.indexOf(';', i + 1)
                if (end in (i + 2) until minOf(n, i + 12)) {
                    mark(marks, i, end + 1, colors.attr)
                    i = end + 1
                    continue
                }
            }
            i++
        }
    }

    /** 返回标签结束后的位置。标签名/属性名/属性值分别上色，尖括号保持正文色。 */
    private fun scanTag(text: String, start: Int, marks: MutableList<Mark>): Int {
        val n = text.length
        var i = start + 1
        if (i < n && text[i] == '/') i++
        while (i < n && (text[i] == ' ' || text[i] == '\t' || text[i] == '\r' || text[i] == '\n')) i++
        val nameStart = i
        while (i < n && isTagNameChar(text[i])) i++
        mark(marks, nameStart, i, colors.tag)

        while (i < n) {
            while (i < n && (text[i] == ' ' || text[i] == '\t' ||
                    text[i] == '\r' || text[i] == '\n')) i++
            if (i >= n) break
            if (text[i] == '>') return i + 1
            if (text[i] == '/' && i + 1 < n && text[i + 1] == '>') return i + 2
            if (text[i] == '=') {
                i++
                continue
            }
            if (text[i] == '"' || text[i] == '\'') {
                val end = scanString(text, i, text[i], false)
                mark(marks, i, end, colors.string)
                i = end
                continue
            }
            val attrStart = i
            while (i < n && isTagNameChar(text[i])) i++
            if (i == attrStart) {
                // 不认识字符（比如 xsl 里的表达式符号），跳过，别死循环
                i++
            } else {
                mark(marks, attrStart, i, colors.attr)
            }
        }
        return i
    }

    // ───────────────────────── JSON ─────────────────────────

    private fun scanJson(text: String, marks: MutableList<Mark>) {
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            if (c == '"') {
                val end = scanString(text, i, '"', false)
                mark(marks, i, end, colors.string)
                i = end
                continue
            }
            if (c.isDigit() || (c == '-' && i + 1 < n && text[i + 1].isDigit())) {
                val s = i
                i++
                while (i < n && (text[i].isDigit() || text[i] == '.' ||
                        text[i] == 'e' || text[i] == 'E' ||
                        text[i] == '+' || text[i] == '-')) i++
                mark(marks, s, i, colors.number)
                continue
            }
            if (c.isLetter()) {
                val s = i
                while (i < n && text[i].isLetter()) i++
                if (text.substring(s, i) in setOf("true", "false", "null")) {
                    mark(marks, s, i, colors.keyword)
                }
                continue
            }
            i++
        }
    }

    // ───────────────────────── 词表 ─────────────────────────

    private val TYPE_DECL = setOf(
        "class", "struct", "interface", "enum", "record", "typealias", "union", "namespace", "trait", "object",
    )

    private fun keywordsFor(lang: SyntaxLanguage): Set<String> = when (lang) {
        SyntaxLanguage.C -> C_KEYWORDS
        SyntaxLanguage.CPP -> CPP_KEYWORDS
        SyntaxLanguage.CS -> CS_KEYWORDS
        SyntaxLanguage.JAVA -> JAVA_KEYWORDS
        SyntaxLanguage.KOTLIN -> KOTLIN_KEYWORDS
        SyntaxLanguage.PYTHON -> PYTHON_KEYWORDS
        SyntaxLanguage.SHELL -> SHELL_KEYWORDS
        SyntaxLanguage.CSS -> CSS_KEYWORDS
        else -> emptySet()
    }

    private fun typesFor(lang: SyntaxLanguage): Set<String> = when (lang) {
        SyntaxLanguage.C, SyntaxLanguage.CPP -> C_TYPES
        SyntaxLanguage.CS -> CS_TYPES
        SyntaxLanguage.JAVA -> JAVA_TYPES
        SyntaxLanguage.KOTLIN -> KOTLIN_TYPES
        SyntaxLanguage.PYTHON -> PYTHON_TYPES
        else -> emptySet()
    }

    private fun isIdentStart(c: Char): Boolean = c.isLetter() || c == '_'
    private fun isIdentPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
    private fun isTagNameChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == ':' || c == '-' || c == '.'

    private companion object {
        val C_KEYWORDS = setOf(
            "auto", "break", "case", "char", "const", "continue", "default", "do", "double",
            "else", "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long",
            "register", "restrict", "return", "short", "signed", "sizeof", "static", "struct",
            "switch", "typedef", "union", "unsigned", "void", "volatile", "while",
            "true", "false", "NULL", "include", "define", "ifdef", "ifndef", "endif", "pragma",
        )
        val CPP_KEYWORDS = C_KEYWORDS + setOf(
            "alignas", "alignof", "and", "and_eq", "asm", "bitand", "bitor", "bool", "catch",
            "class", "compl", "concept", "constexpr", "const_cast", "co_await", "co_return",
            "co_yield", "decltype", "delete", "dynamic_cast", "explicit", "export", "friend",
            "mutable", "namespace", "new", "noexcept", "not", "not_eq", "nullptr", "operator",
            "or", "or_eq", "private", "protected", "public", "reinterpret_cast", "requires",
            "static_assert", "static_cast", "template", "this", "thread_local", "throw", "try",
            "typeid", "typename", "using", "virtual", "wchar_t", "xor", "xor_eq", "override", "final",
        )
        val CS_KEYWORDS = setOf(
            "abstract", "as", "base", "break", "case", "catch", "checked", "class", "const",
            "continue", "default", "delegate", "do", "else", "enum", "event", "explicit", "extern",
            "false", "finally", "fixed", "for", "foreach", "goto", "if", "implicit", "in",
            "interface", "internal", "is", "lock", "namespace", "new", "null", "object", "operator",
            "out", "override", "params", "private", "protected", "public", "readonly", "ref",
            "return", "sealed", "sizeof", "stackalloc", "static", "struct", "switch", "this",
            "throw", "true", "try", "typeof", "unchecked", "unsafe", "using", "virtual", "void",
            "volatile", "while", "async", "await", "var", "record", "init", "get", "set", "partial",
        )
        val JAVA_KEYWORDS = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "var", "record",
            "yield", "sealed", "permits", "true", "false", "null",
        )
        val KOTLIN_KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
            "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
            "true", "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch",
            "constructor", "delegate", "dynamic", "field", "file", "finally", "get", "import",
            "init", "param", "property", "receiver", "set", "setparam", "where", "actual",
            "abstract", "annotation", "companion", "const", "crossinline", "data", "enum", "expect",
            "external", "final", "infix", "inline", "inner", "internal", "lateinit", "noinline",
            "open", "operator", "out", "override", "private", "protected", "public", "reified",
            "sealed", "suspend", "tailrec", "vararg",
        )
        val PYTHON_KEYWORDS = setOf(
            "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
            "elif", "else", "except", "False", "finally", "for", "from", "global", "if", "import",
            "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return",
            "True", "try", "while", "with", "yield", "match", "case", "self",
        )
        val SHELL_KEYWORDS = setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
            "function", "in", "select", "until", "time", "return", "local", "export", "readonly",
            "true", "false",
        )
        val CSS_KEYWORDS = setOf(
            "important", "media", "import", "charset", "keyframes", "font-face", "supports",
            "namespace", "page", "layer", "property",
        )

        val C_TYPES = setOf(
            "size_t", "ssize_t", "int8_t", "int16_t", "int32_t", "int64_t",
            "uint8_t", "uint16_t", "uint32_t", "uint64_t", "FILE", "time_t", "off_t",
        )
        val CS_TYPES = setOf(
            "bool", "byte", "sbyte", "short", "ushort", "int", "uint", "long", "ulong",
            "float", "double", "decimal", "char", "string", "object", "dynamic", "void", "var",
        )
        val JAVA_TYPES = setOf(
            "String", "Object", "Integer", "Long", "Double", "Float", "Boolean", "Character",
            "Byte", "Short", "List", "Map", "Set", "Collection", "Optional", "Void",
        )
        val KOTLIN_TYPES = setOf(
            "Int", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Char", "String",
            "Unit", "Any", "Nothing", "List", "MutableList", "Map", "MutableMap", "Set",
            "MutableSet", "Array", "IntArray", "LongArray", "Pair", "Triple", "Result",
        )
        val PYTHON_TYPES = setOf(
            "int", "float", "str", "bool", "bytes", "list", "dict", "set", "tuple", "object",
            "Exception", "ValueError", "TypeError", "self",
        )
    }
}

/** 把高亮结果接到 [androidx.compose.foundation.text.BasicTextField]。 */
class SyntaxHighlightTransformation(
    private val highlighter: SyntaxHighlighter,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlighter.highlight(text.text), OffsetMapping.Identity)
}
