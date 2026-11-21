package com.cmzc.xj_file.app.core.text

/**
 * 5A 编码内核的纯 JVM 自测。
 *
 * 为什么不用标准 `test/` 目录：本工程没有单元测试基建（见 HANDOFF §1），
 * 所以做成一个普通 object——任意 debug 入口（或 PC 上用 kotlinc 编译后写个 main）都能直接调用；
 * 不读文件、不碰 Android Context，保证 PC 与真机跑出来的结果一致。
 *
 * [run] 的每一行要么 `OK ...` 要么 `FAIL ...：原因`，保持单行，方便直接打到日志里
 * 一眼看出「哪种编码 / 换行是好的、哪种坏了」；返回列表而不是自己打印，
 * 由调用方决定写日志还是显示到调试页。
 */
object TextCodecSelfTest {

    private val codec: TextCodec = TextCodecImpl

    // 期望的 BOM 字节在测试里自己写一份，不复用实现里的私有常量——
    // 测试的期望值要独立于实现，否则「自己验自己」等于没验。
    private val expectedUtf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val expectedUtf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val expectedUtf16BeBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    private val expectedUtf32LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00)
    private val expectedUtf32BeBom = byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte())

    fun run(): List<String> {
        val lines = mutableListOf<String>()

        // 单项测试统一在这里兜异常：一项挂了不能影响后面几项，否则永远只能看到第一个 FAIL。
        fun test(name: String, body: () -> Unit) {
            lines += try {
                body()
                "OK $name"
            } catch (t: Throwable) {
                "FAIL $name：${t.message ?: t.toString()}"
            }
        }

        test("支持编码清单覆盖 UTF / GB / BIG5 / 日韩 / 西欧") {
            val names = codec.supportedEncodings.map { it.name }
            for (expected in listOf(
                "UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE",
                "GB18030", "GBK", "GB2312", "Big5",
                "Shift_JIS", "EUC-JP", "EUC-KR",
                "windows-1252", "ISO-8859-1",
            )) {
                expect(expected in names, "supportedEncodings 缺少 $expected")
            }
            expect(
                codec.supportedEncodings.all { it.label.isNotBlank() },
                "supportedEncodings 的 label 不能为空（UI 展示用）",
            )
        }

        test("UTF-8：无 BOM 往返 + 自动探测") {
            val text = "第一行 UTF-8\nsecond line 🙂"
            roundtrip(text, "UTF-8", TextBom.NONE, LineEnding.LF, autoDetect = true)
        }

        test("UTF-8 BOM：写出 BOM、自动识别并剥掉") {
            val text = "带 BOM 的 UTF-8\n第二行"
            val bytes = codec.encode(text, "UTF-8", TextBom.UTF8, LineEnding.LF)
            expectStartsWith(expectedUtf8Bom, bytes, "UTF-8 BOM 字节不对")
            roundtrip(text, "UTF-8", TextBom.UTF8, LineEnding.LF, autoDetect = true)
        }

        test("UTF-16LE：BOM + 往返 + 自动探测") {
            val text = "UTF-16LE 文本\n第二行"
            val bytes = codec.encode(text, "UTF-16LE", TextBom.UTF16_LE, LineEnding.LF)
            expectStartsWith(expectedUtf16LeBom, bytes, "UTF-16LE BOM 字节不对")
            expect(
                bytes.size == expectedUtf16LeBom.size + text.length * 2,
                "UTF-16LE 字节数不对：${bytes.size}（期望 ${expectedUtf16LeBom.size + text.length * 2}）",
            )
            roundtrip(text, "UTF-16LE", TextBom.UTF16_LE, LineEnding.LF, autoDetect = true)
        }

        test("UTF-16BE：BOM + 往返 + 自动探测") {
            val text = "UTF-16BE 文本\n第二行"
            val bytes = codec.encode(text, "UTF-16BE", TextBom.UTF16_BE, LineEnding.LF)
            expectStartsWith(expectedUtf16BeBom, bytes, "UTF-16BE BOM 字节不对")
            roundtrip(text, "UTF-16BE", TextBom.UTF16_BE, LineEnding.LF, autoDetect = true)
        }

        test("UTF-16LE 无 BOM：显式编码往返") {
            roundtrip("无 BOM 的 UTF-16LE 文本", "UTF-16LE", TextBom.NONE, LineEnding.LF, autoDetect = false)
        }

        test("UTF-32LE：BOM + 往返 + 自动探测（手写实现）") {
            val text = "UTF-32 文本\n第二行🙂"
            val bytes = codec.encode(text, "UTF-32LE", TextBom.UTF32_LE, LineEnding.LF)
            expectStartsWith(expectedUtf32LeBom, bytes, "UTF-32LE BOM 字节不对")
            expect(
                bytes.size == expectedUtf32LeBom.size + codePointCount(text) * 4,
                "UTF-32LE 字节数不对：${bytes.size}（期望 ${expectedUtf32LeBom.size + codePointCount(text) * 4}）",
            )
            roundtrip(text, "UTF-32LE", TextBom.UTF32_LE, LineEnding.LF, autoDetect = true)
        }

        test("UTF-32BE：BOM + 往返 + 自动探测（手写实现）") {
            val text = "UTF-32BE 文本\n第二行"
            val bytes = codec.encode(text, "UTF-32BE", TextBom.UTF32_BE, LineEnding.LF)
            expectStartsWith(expectedUtf32BeBom, bytes, "UTF-32BE BOM 字节不对")
            roundtrip(text, "UTF-32BE", TextBom.UTF32_BE, LineEnding.LF, autoDetect = true)
        }

        test("GBK：显式编码往返 + CRLF 还原") {
            val text = "简体中文 GBK 测试\n第二行：编码"
            val bytes = codec.encode(text, "GBK", TextBom.NONE, LineEnding.CRLF)
            expectBytesContain(bytes, "\r\n".toByteArray(Charsets.UTF_8), "GBK 保存应写出 CRLF")
            val decoded = codec.decode(bytes, "GBK")
            expect(decoded.text == text, "GBK 往返正文不一致：${decoded.text.shortQuoted()}")
            expect(decoded.encoding == "GBK", "解码返回的编码应为 GBK，实际 ${decoded.encoding}")
            expect(decoded.lineEnding == LineEnding.CRLF, "应识别为 CRLF，实际 ${decoded.lineEnding}")
            expect(!decoded.hadErrors, "合法 GBK 不应有解码错误")
        }

        test("GB18030：能表示 GBK 表示不了的字符") {
            // 㐀（U+3400，CJK 扩展 A）不在 GBK 里，GB18030 用四字节表示；
            // 这一项同时证明 4 字节区没被当成替换字符。
            val text = "简体中文 㐀\n第二行"
            roundtrip(text, "GB18030", TextBom.NONE, LineEnding.LF, autoDetect = false)
            val withExtA = codec.encode(text, "GB18030", TextBom.NONE, LineEnding.LF)
            val withoutExtA = codec.encode("简体中文 \n第二行", "GBK", TextBom.NONE, LineEnding.LF)
            expect(withExtA.size > withoutExtA.size, "GB18030 四字节字符应让结果更长")
        }

        test("GB2312：显式编码往返") {
            roundtrip("简体中文 GB2312 测试\n第二行", "GB2312", TextBom.NONE, LineEnding.LF, autoDetect = false)
        }

        test("BIG5：繁体中文显式编码往返") {
            roundtrip("繁體中文 BIG5 測試\n第二行", "Big5", TextBom.NONE, LineEnding.LF, autoDetect = false)
        }

        test("Shift_JIS：日文显式编码往返") {
            roundtrip("日本語のテスト\n二行目", "Shift_JIS", TextBom.NONE, LineEnding.LF, autoDetect = false)
        }

        test("EUC-JP：日文显式编码往返") {
            roundtrip("日本語 EUC-JP テスト\n二行目", "EUC-JP", TextBom.NONE, LineEnding.LF, autoDetect = false)
        }

        test("EUC-KR：韩文显式编码往返") {
            roundtrip("한국어 테스트\n두 번째 줄", "EUC-KR", TextBom.NONE, LineEnding.LF, autoDetect = false)
        }

        test("windows-1252：西欧字符显式编码往返") {
            roundtrip("Grüße aus Köln €\nsecond line", "windows-1252", TextBom.NONE, LineEnding.LF, autoDetect = false)
        }

        test("ISO-8859-1：西欧字符显式编码往返") {
            roundtrip("Grüße aus Köln\nsecond line", "ISO-8859-1", TextBom.NONE, LineEnding.LF, autoDetect = false)
        }

        test("换行：LF / CRLF / CR 往返且自动识别正确") {
            val text = "第一行\n第二行\n第三行"

            val lfBytes = codec.encode(text, "UTF-8", TextBom.NONE, LineEnding.LF)
            expectBytesContain(lfBytes, "\n".toByteArray(Charsets.UTF_8), "LF 保存结果里没有 \\n")
            expectBytesNotContainByte(lfBytes, 0x0D, "LF 保存结果里不该有 \\r")
            val lfDecoded = codec.decode(lfBytes)
            expect(lfDecoded.lineEnding == LineEnding.LF, "LF 文件应识别为 LF，实际 ${lfDecoded.lineEnding}")
            expect(lfDecoded.text == text, "LF 解码后正文不一致：${lfDecoded.text.shortQuoted()}")

            val crlfBytes = codec.encode(text, "UTF-8", TextBom.NONE, LineEnding.CRLF)
            expectBytesContain(crlfBytes, "\r\n".toByteArray(Charsets.UTF_8), "CRLF 保存结果里没有 \\r\\n")
            val crlfDecoded = codec.decode(crlfBytes)
            expect(crlfDecoded.lineEnding == LineEnding.CRLF, "CRLF 文件应识别为 CRLF，实际 ${crlfDecoded.lineEnding}")
            expect(crlfDecoded.text == text, "CRLF 解码应统一成 \\n，实际=${crlfDecoded.text.shortQuoted()}")

            val crBytes = codec.encode(text, "UTF-8", TextBom.NONE, LineEnding.CR)
            val crDecoded = codec.decode(crBytes)
            expect(crDecoded.lineEnding == LineEnding.CR, "CR 文件应识别为 CR，实际 ${crDecoded.lineEnding}")
            expect(crDecoded.text == text, "CR 解码应统一成 \\n，实际=${crDecoded.text.shortQuoted()}")

            // 混合换行：按出现最多的那种记录，正文照样全部归一成 \n
            val mixed = codec.decode("a\r\nb\r\nc\nd\r".toByteArray(Charsets.UTF_8))
            expect(mixed.lineEnding == LineEnding.CRLF, "混合文件应按最多的 CRLF 记录，实际 ${mixed.lineEnding}")
            expect(mixed.text == "a\nb\nc\nd\n", "混合换行应全部归一成 \\n，实际=${mixed.text.shortQuoted()}")

            // 没有换行时默认记 LF（不影响正文）
            val noBreak = codec.decode("只有一行".toByteArray(Charsets.UTF_8))
            expect(noBreak.lineEnding == LineEnding.LF, "无换行文件应默认记 LF，实际 ${noBreak.lineEnding}")
        }

        test("编码表示不了的字符必须抛 TextEncodeException（不能变 '?'）") {
            val source = "a中b"
            val error = expectEncodeException { codec.encode(source, "ISO-8859-1", TextBom.NONE, LineEnding.LF) }
            expect(error.character == '中', "异常里的字符应为「中」，实际 ${error.character.describeForTest()}")
            expect(error.index == 1, "异常下标应为 1，实际 ${error.index}")
            expect(error.message?.isNotBlank() == true, "异常消息不能为空")
        }

        test("GBK 表示不了 emoji：抛 TextEncodeException 且定位到代理字符") {
            val source = "😀"
            val error = expectEncodeException { codec.encode(source, "GBK", TextBom.NONE, LineEnding.LF) }
            expect(error.index == 0, "异常下标应为 0，实际 ${error.index}")
            expect(error.character == source[0], "异常里的字符应为 emoji 的高位代理")
        }

        test("BOM 与编码不匹配要拒绝（不能写出坏文件）") {
            val rejected = try {
                codec.encode("abc", "UTF-16LE", TextBom.UTF8, LineEnding.LF)
                false
            } catch (e: IllegalArgumentException) {
                true
            }
            expect(rejected, "UTF-16LE + UTF-8 BOM 应被 IllegalArgumentException 拒绝")
        }

        test("非法字节用替换字符兜底并置 hadErrors") {
            val bad = byteArrayOf('A'.code.toByte(), 0xFF.toByte(), 'B'.code.toByte())
            val decoded = codec.decode(bad, "UTF-8")
            expect(decoded.hadErrors, "非法 UTF-8 字节应置 hadErrors")
            expect(decoded.text.contains('\uFFFD'), "应出现 U+FFFD 替换字符，实际=${decoded.text.shortQuoted()}")
            expect(decoded.text.startsWith("A") && decoded.text.endsWith("B"), "替换字符前后的合法内容应保留")
            val good = codec.decode("正常 UTF-8 文本".toByteArray(Charsets.UTF_8), "UTF-8")
            expect(!good.hadErrors, "合法 UTF-8 不应置 hadErrors")
        }

        test("未知编码名要报错，不能静默当 UTF-8") {
            val rejected = try {
                codec.decode("abc".toByteArray(Charsets.UTF_8), "NO-SUCH-ENCODING")
                false
            } catch (e: IllegalArgumentException) {
                true
            }
            expect(rejected, "未知编码名应抛 IllegalArgumentException")
        }

        test("编码名别名（utf8 / cp936 / latin1）能接受并规范化") {
            val utf8Bytes = "别名测试".toByteArray(Charsets.UTF_8)
            val utf8 = codec.decode(utf8Bytes, "utf8")
            expect(utf8.text == "别名测试", "utf8 应能解 UTF-8")
            expect(utf8.encoding == "UTF-8", "utf8 应规范成 UTF-8，实际 ${utf8.encoding}")

            val gbkBytes = codec.encode("简体中文", "GBK", TextBom.NONE, LineEnding.LF)
            expect(codec.decode(gbkBytes, "cp936").text == "简体中文", "cp936（GBK 别名）应能解")

            val latinBytes = codec.encode("Grüße", "ISO-8859-1", TextBom.NONE, LineEnding.LF)
            expect(codec.decode(latinBytes, "latin1").text == "Grüße", "latin1（ISO-8859-1 别名）应能解")
        }

        test("探测：无 BOM 的 UTF-8 中文识别为 UTF-8") {
            val detected = codec.detect("中文 UTF-8 探测".toByteArray(Charsets.UTF_8))
            expect(detected.encoding == "UTF-8", "应探测为 UTF-8，实际 ${detected.encoding}")
            expect(detected.confidence >= 90, "置信度应 >= 90，实际 ${detected.confidence}")
            expect(detected.candidates.firstOrNull() == "UTF-8", "候选第一名应为 UTF-8")
        }

        test("探测：无 BOM 的 ASCII 型 UTF-16LE 能识别") {
            val bytes = codec.encode("Hello UTF-16", "UTF-16LE", TextBom.NONE, LineEnding.LF)
            val detected = codec.detect(bytes)
            expect(detected.encoding == "UTF-16LE", "应探测为 UTF-16LE，实际 ${detected.encoding}")
        }

        test("空文件 / 只有 BOM 的文件解码为空正文") {
            val empty = codec.decode(ByteArray(0))
            expect(empty.text.isEmpty(), "空文件解码结果应为空")
            expect(empty.encoding == "UTF-8", "空文件默认编码应为 UTF-8，实际 ${empty.encoding}")
            expect(empty.bom == TextBom.NONE, "空文件不应有 BOM")
            expect(empty.lineEnding == LineEnding.LF, "空文件换行默认 LF")
            expect(!empty.hadErrors, "空文件不应有解码错误")

            val bomOnly = codec.decode(expectedUtf8Bom)
            expect(bomOnly.text.isEmpty(), "只有 BOM 的文件正文应为空，实际=${bomOnly.text.shortQuoted()}")
            expect(bomOnly.bom == TextBom.UTF8, "应识别出 UTF-8 BOM")
            expect(!bomOnly.hadErrors, "只有 BOM 不应算解码错误")
        }

        return lines
    }

    // ───────────────────────── 断言 / 往返 / 格式化辅助 ─────────────────────────

    /** 双向往返并逐项核对编码 / BOM / 换行 / hadErrors；任何一项不符都带上上下文抛出去。 */
    private fun roundtrip(
        text: String,
        encoding: String,
        bom: TextBom,
        lineEnding: LineEnding,
        autoDetect: Boolean,
    ) {
        val bytes = codec.encode(text, encoding, bom, lineEnding)
        val decoded = if (autoDetect) codec.decode(bytes) else codec.decode(bytes, encoding)
        val context = "编码=$encoding 换行=$lineEnding BOM=$bom"
        expect(decoded.text == text, "$context：正文往返不一致，实际=${decoded.text.shortQuoted()}")
        expect(decoded.encoding == encoding, "$context：解码返回的编码是 ${decoded.encoding}")
        expect(decoded.bom == bom, "$context：解码返回的 BOM 是 ${decoded.bom}")
        expect(decoded.lineEnding == lineEnding, "$context：换行识别成 ${decoded.lineEnding}")
        expect(!decoded.hadErrors, "$context：合法文本不应有解码错误")
    }

    private fun expect(condition: Boolean, message: String) {
        if (!condition) throw IllegalStateException(message)
    }

    private inline fun expectEncodeException(block: () -> ByteArray): TextEncodeException {
        val bytes = try {
            block()
            null
        } catch (e: TextEncodeException) {
            e
        }
        return bytes ?: throw IllegalStateException("应当抛 TextEncodeException，实际编码成功了")
    }

    private fun expectStartsWith(prefix: ByteArray, actual: ByteArray, what: String) {
        if (actual.size < prefix.size) {
            throw IllegalStateException("$what：实际只有 ${actual.size} 字节，连 BOM 都不够")
        }
        for (index in prefix.indices) {
            if (actual[index] != prefix[index]) {
                throw IllegalStateException("$what：第 $index 字节是 0x${actual[index].hexForTest()}，期望 0x${prefix[index].hexForTest()}")
            }
        }
    }

    private fun expectBytesContain(actual: ByteArray, needle: ByteArray, message: String) {
        var found = false
        for (start in 0..actual.size - needle.size) {
            var same = true
            for (offset in needle.indices) {
                if (actual[start + offset] != needle[offset]) {
                    same = false
                    break
                }
            }
            if (same) {
                found = true
                break
            }
        }
        expect(found, message)
    }

    private fun expectBytesNotContainByte(actual: ByteArray, value: Int, message: String) {
        for (byte in actual) {
            if ((byte.toInt() and 0xFF) == value) throw IllegalStateException(message)
        }
    }

    /** 按码点计数（代理对算一个），用来核对 UTF-32 的字节数。 */
    private fun codePointCount(text: String): Int {
        var count = 0
        var index = 0
        while (index < text.length) {
            val char = text[index]
            index += if (Character.isHighSurrogate(char) &&
                index + 1 < text.length &&
                Character.isLowSurrogate(text[index + 1])
            ) {
                2
            } else {
                1
            }
            count++
        }
        return count
    }

    /** 失败信息里的文本压成单行并截断：否则一个换行就会把 `FAIL ...` 撑成好几行，日志没法按行扫。 */
    private fun String.shortQuoted(): String {
        val escaped = replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
        return if (escaped.length <= 40) "「$escaped」" else "「${escaped.take(40)}…」"
    }

    private fun Char.describeForTest(): String = "U+${code.toString(16).uppercase()}"

    private fun Byte.hexForTest(): String = (toInt() and 0xFF).toString(16).padStart(2, '0')
}
