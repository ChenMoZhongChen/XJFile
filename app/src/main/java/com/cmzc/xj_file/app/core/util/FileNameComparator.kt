package com.cmzc.xj_file.app.core.util

import java.text.Collator
import java.util.Locale

/**
 * 文件名排序比较器。规则：
 *   1. 符号        按 ASCII 码点
 *   2. 数字        按字符串顺序（0, 1, 10, 180, 3, 38, 7, 9）
 *   3. 英文字母    大小写不敏感混排（a=A, ab=Ab）
 *   4. 中文        按拼音，且整组排在字母之后
 *   5. 其它字符    兜底按原字符
 *
 * 组内顺序：符号 < 数字 < 字母 < 中文 < 其它
 */
object FileNameComparator : Comparator<String> {

    private val pinyinCollator: Collator = Collator.getInstance(Locale.CHINA)

    override fun compare(a: String, b: String): Int {
        val ka = firstGroupOf(a)
        val kb = firstGroupOf(b)
        if (ka != kb) return ka - kb
        // 同组，按组内规则比
        return when (ka) {
            GROUP_SYMBOL -> a.compareTo(b)
            GROUP_DIGIT -> a.compareTo(b)
            GROUP_ALPHA -> a.lowercase().compareTo(b.lowercase())
            GROUP_CJK -> pinyinCollator.compare(a, b)
            else -> a.compareTo(b)
        }
    }

    private fun firstGroupOf(s: String): Int {
        if (s.isEmpty()) return GROUP_OTHER
        val c = s[0]
        return when {
            c.code < 0x30 -> GROUP_SYMBOL
            c.code in 0x30..0x39 -> GROUP_DIGIT
            c.code in 0x41..0x5A || c.code in 0x61..0x7A -> GROUP_ALPHA
            isCjk(c) -> GROUP_CJK
            else -> GROUP_OTHER
        }
    }

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF ||      // CJK 基本区
                code in 0x3400..0x4DBF ||     // 扩展 A
                code in 0xF900..0xFAFF        // 兼容汉字
    }

    private const val GROUP_SYMBOL = 0
    private const val GROUP_DIGIT = 1
    private const val GROUP_ALPHA = 2
    private const val GROUP_CJK = 3
    private const val GROUP_OTHER = 4
}