package com.cmzc.xj_file.app.core.text

/**
 * 文本编码内核的公共契约（5A 冻结）。
 *
 * 这个文件是「编码实现（core/text）」和「编辑器 UI（ui/editor）」之间唯一的界面，
 * 两边并行开发时都不要改它；需要加字段/改签名先找 lead。
 *
 * 注意 [DecodedText.text] 里的换行统一成 `\n`，方便 Compose 编辑；
 * 原始换行风格记录在 [DecodedText.lineEnding]，保存时由 [TextCodec.encode] 还原。
 */

/** 字节序标记。保存时按这个值决定要不要写回 BOM。 */
enum class TextBom {
    NONE,
    UTF8,
    UTF16_LE,
    UTF16_BE,
    UTF32_LE,
    UTF32_BE,
}

/** 原始文件的换行风格；混合文件按出现最多的那种记录。 */
enum class LineEnding(val sequence: String, val label: String) {
    LF("\n", "LF"),
    CRLF("\r\n", "CRLF"),
    CR("\r", "CR"),
}

/** 编码下拉项。[name] 是 Java/ICU 规范名，[label] 只用于界面显示。 */
data class EncodingOption(
    val name: String,
    val label: String,
)

/** 检测结果。[confidence] 0..100；[candidates] 按可信度从高到低。 */
data class EncodingDetection(
    val encoding: String,
    val confidence: Int,
    val candidates: List<String>,
)

/**
 * 解码结果。
 *
 * - [hadErrors] 表示解码时遇到过非法字节（用了替换字符兜底）；UI 要提示用户
 *   「这个编码可能选错了」，不能静默当成正常文本；
 * - [bom] 是原文件里实际存在的 BOM，保存时原样写回（用户手动改编码除外）。
 */
data class DecodedText(
    val text: String,
    val encoding: String,
    val bom: TextBom,
    val lineEnding: LineEnding,
    val hadErrors: Boolean,
)

/** 编码时遇到目标字符集表示不了的字符。UI 用 [character]/[index] 定位提示。 */
class TextEncodeException(
    val character: Char,
    val index: Int,
    message: String,
) : Exception(message)

interface TextCodec {

    /** 下拉列表里的编码（规范名 + 展示名），至少覆盖 UTF/GB/BIG5/日韩常见编码。 */
    val supportedEncodings: List<EncodingOption>

    /**
     * 探测字节的编码。有 BOM 时 BOM 优先；否则用 ICU4J 的 CharsetDetector，
     * 再叠加 UTF-8 严格校验等规则。
     */
    fun detect(bytes: ByteArray, hint: String? = null): EncodingDetection

    /**
     * 解码。`encoding == null` 时先 detect；显式传入时按用户选择解。
     * 文本里的换行统一成 `\n`，非法字节用替换字符兜底并置 hadErrors。
     */
    fun decode(bytes: ByteArray, encoding: String? = null): DecodedText

    /**
     * 编码。`text` 里的 `\n` 按 [lineEnding] 还原；[bom] 非 NONE 时写在最前面。
     * 目标编码表示不了的字符要抛 [TextEncodeException]，绝不能静默变成 '?'。
     */
    fun encode(
        text: String,
        encoding: String,
        bom: TextBom,
        lineEnding: LineEnding,
    ): ByteArray
}

/** 编辑器打开一份文本时需要的全部初始信息。 */
data class TextDocument(
    val fileName: String,
    val text: String,
    val encoding: String,
    val bom: TextBom,
    val lineEnding: LineEnding,
)
