package com.cmzc.xj_file.app.core.text

/**
 * 阶段五将完整实现编码检测与读写。
 * 这里先占位，定义好接口，避免后期改动 UI / 架构。
 */
interface CharsetCodec {
    /** 支持的编码名称列表（用于下拉选择） */
    val supportedEncodings: List<String>
    /** 检测字节的编码 */
    fun detect(bytes: ByteArray, hint: String? = null): String
    /** 用指定编码解码 */
    fun decode(bytes: ByteArray, charsetName: String): String
    /** 用指定编码编码 */
    fun encode(text: String, charsetName: String): ByteArray
}

object TextCodecStub : CharsetCodec {
    override val supportedEncodings: List<String> = listOf(
        "UTF-8", "UTF-8-BOM", "UTF-16LE", "UTF-16BE",
        "UTF-32LE", "UTF-32BE", "GB18030", "GB2312", "GBK", "GB13000"
    )
    override fun detect(bytes: ByteArray, hint: String?): String = hint ?: "UTF-8"
    override fun decode(bytes: ByteArray, charsetName: String): String =
        String(bytes, charset(charsetName))
    override fun encode(text: String, charsetName: String): ByteArray =
        text.toByteArray(charset(charsetName))

    private fun charset(name: String): java.nio.charset.Charset =
        runCatching { java.nio.charset.Charset.forName(name) }
            .getOrElse { java.nio.charset.StandardCharsets.UTF_8 }
}