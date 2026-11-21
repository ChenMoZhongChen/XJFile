package com.cmzc.xj_file.app.core.text

import com.ibm.icu.text.CharsetDetector
import com.ibm.icu.text.CharsetMatch
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.MalformedInputException
import java.nio.charset.UnmappableCharacterException
import java.util.Locale

/**
 * 阶段五（5A）的编码内核：字节和文本之间唯一的转换入口，实现冻结契约 [TextCodec]。
 *
 * 为什么三件事放在同一个文件里：
 * - 探测、解码、编码共用同一套「编码名规范化 + BOM + 换行」规则，
 *   分开放迟早出现「探测对了、保存却丢 BOM」这类互相矛盾的行为；
 * - 编辑器 UI 只跟 [EncodingOption] 里的字符串打交道，不直接依赖 ICU4J，
 *   以后换探测算法不用动 UI。
 *
 * 与契约一致的约定：
 * - [decode] 的结果里换行一律是 `\n`，原始风格记录在 [DecodedText.lineEnding]；
 * - [encode] 只按 `\n` 还原换行风格，所以「怎么读进来就能怎么存回去」；
 * - BOM 是文件元数据、不是正文：解码时永远剥掉，保存时按传入的 [TextBom] 决定写不写。
 */
object TextCodecImpl : TextCodec {

    override val supportedEncodings: List<EncodingOption> = listOf(
        EncodingOption("UTF-8", "UTF-8（通用，推荐）"),
        EncodingOption("UTF-16LE", "UTF-16 LE（小端）"),
        EncodingOption("UTF-16BE", "UTF-16 BE（大端）"),
        EncodingOption("UTF-32LE", "UTF-32 LE（小端）"),
        EncodingOption("UTF-32BE", "UTF-32 BE（大端）"),
        EncodingOption("GB18030", "GB18030（简体中文）"),
        EncodingOption("GBK", "GBK（简体中文）"),
        EncodingOption("GB2312", "GB2312（简体中文·旧）"),
        EncodingOption("Big5", "BIG5（繁体中文）"),
        EncodingOption("Shift_JIS", "Shift_JIS（日文）"),
        EncodingOption("EUC-JP", "EUC-JP（日文）"),
        EncodingOption("EUC-KR", "EUC-KR（韩文）"),
        EncodingOption("windows-1252", "Windows-1252（西欧）"),
        EncodingOption("ISO-8859-1", "ISO-8859-1（西欧）"),
    )

    override fun detect(bytes: ByteArray, hint: String?): EncodingDetection {
        // hint 只是调用方的「猜测」，只有在统计探测靠不住时才采信；
        // BOM 和严格 UTF-8 校验属于硬证据，永远优先于 hint。
        val hintName = hint?.let { resolveEncoding(it, TextBom.NONE)?.name }

        if (bytes.isEmpty()) {
            // 空文件没有任何可判断的内容；UTF-8 是最通用、最不容易二次乱码的默认值。
            val name = hintName ?: "UTF-8"
            return EncodingDetection(name, 100, listOf(name))
        }

        // 1. BOM：文件自己声明的编码，直接采信，不需要猜。
        val bom = detectBom(bytes)
        if (bom != TextBom.NONE) {
            val name = requireNotNull(encodingNameOfBom(bom))
            return EncodingDetection(name, 100, listOf(name))
        }

        // 2. 无 BOM 的 UTF-16：纯 ASCII 内容在 UTF-16 里每个字符都带一个 0 字节，
        //    但这串字节同样能通过严格 UTF-8 校验（NUL 是合法 UTF-8），
        //    所以必须抢在 UTF-8 判断之前，用「隔字节为 0」的特征把它挑出来。
        val guessUtf16 = guessBomlessUtf16(bytes)
        if (guessUtf16 != null) {
            return EncodingDetection(guessUtf16, 85, listOf(guessUtf16, "UTF-8"))
        }

        // 3. 严格 UTF-8 校验：UTF-8 的字节规则很严，GBK/Big5 的常见序列基本过不了校验，
        //    通过校验时基本可以认定是 UTF-8。纯 ASCII 更进一步：UTF-8 是这里最合适的保存格式。
        if (isValidUtf8(bytes)) {
            val hasNonAscii = bytes.any { (it.toInt() and 0xFF) >= 0x80 }
            val candidates = LinkedHashSet<String>()
            candidates += "UTF-8"
            if (!hasNonAscii) {
                // 纯 ASCII 用这些单字节编码解码出来的结果完全一样，放进候选给用户兜底；
                // 但默认保存仍选 UTF-8（ASCII 兼容且不会有歧义）。
                candidates += "ISO-8859-1"
                candidates += "windows-1252"
            }
            hintName?.let { candidates += it }
            return EncodingDetection("UTF-8", if (hasNonAscii) 98 else 100, candidates.toList())
        }

        // 4. 交给 ICU4J 的统计探测（detectAll 已经按置信度从高到低排好序）。
        val candidates = LinkedHashSet<String>()
        var bestName: String? = null
        var bestConfidence = 0
        for (match in detectWithIcu(bytes)) {
            val detectedName = match.name ?: continue
            // 探测器偶尔会给出平台没有对应实现的编码名（比如 ISO-2022-CN 的各种方言名），跳过即可。
            val resolvedName = resolveEncoding(detectedName, TextBom.NONE)?.name ?: continue
            val confidence = match.confidence.coerceIn(0, 100)
            if (bestName == null) {
                bestName = resolvedName
                bestConfidence = confidence
            }
            candidates += resolvedName
            if (candidates.size >= MAX_CANDIDATES) break
        }
        hintName?.let { candidates += it }

        if (bestName == null) {
            // 什么都没认出来（极短文本或二进制内容）：按 hint 或 UTF-8 打开，
            // 让 decode 的 hadErrors 去告诉用户「这文件可能不是文本」。
            val name = hintName ?: "UTF-8"
            return EncodingDetection(name, 10, listOf(name))
        }
        if (hintName != null && hintName != bestName && bestConfidence < HINT_TRUST_THRESHOLD) {
            // 统计探测自己都没把握，而调用方给了明确猜测（例如用户上一次手选的编码）——听调用方的。
            val ordered = listOf(hintName) + candidates.filter { it != hintName }
            return EncodingDetection(hintName, HINT_TRUST_CONFIDENCE, ordered)
        }
        return EncodingDetection(bestName, bestConfidence, candidates.toList())
    }

    override fun decode(bytes: ByteArray, encoding: String?): DecodedText {
        val bom = detectBom(bytes)
        // BOM 是文件头部的元数据，不属于正文；无论最终按哪种编码解，都先按实际存在的长度剥掉。
        val content = if (bom == TextBom.NONE) bytes else bytes.copyOfRange(bomBytes(bom).size, bytes.size)

        // 显式编码按用户选的来（BOM 用于把「UTF-16」这种不带字节序的名字定到具体一端）；
        // 没给显式编码时才用探测结果，此时 BOM 已经在 detect 里优先处理过了。
        val resolved = if (encoding != null) {
            resolveOrThrow(encoding, bom)
        } else {
            resolveOrThrow(detect(bytes).encoding, bom)
        }
        val (rawText, hadErrors) = decodeContent(resolved, content)
        val (text, lineEnding) = normalizeLineEndings(rawText)
        return DecodedText(
            text = text,
            encoding = resolved.name,
            bom = bom,
            lineEnding = lineEnding,
            hadErrors = hadErrors,
        )
    }

    override fun encode(
        text: String,
        encoding: String,
        bom: TextBom,
        lineEnding: LineEnding,
    ): ByteArray {
        val resolved = resolveOrThrow(encoding, bom)

        // BOM 与编码必须成套（UTF-16LE 的 BOM 只能配 UTF-16LE）。写错比不写更糟：
        // 文件会变成「BOM 说 A、内容按 B 编」的双重乱码，所以这里直接拒绝，留给调用方修正。
        val requiredBomEncoding = encodingNameOfBom(bom)
        if (requiredBomEncoding != null && requiredBomEncoding != resolved.name) {
            throw IllegalArgumentException(
                "BOM 与编码不匹配：${resolved.name} 不能带 ${bom.name} 类型的 BOM（这种 BOM 只能配 $requiredBomEncoding）"
            )
        }

        val body = encodeContent(resolved, restoreLineEndings(text, lineEnding))
        val prefix = bomBytes(bom)
        if (prefix.isEmpty()) return body
        val out = ByteArray(prefix.size + body.size)
        prefix.copyInto(out, 0)
        body.copyInto(out, prefix.size)
        return out
    }

    // ───────────────────────── 编码名规范化 ─────────────────────────

    /**
     * 把「Java 规范名 / Java 别名 / ICU 探测器名字 / 旧版本下拉项名字」统一成规范名。
     *
     * 为什么允许别名：文件里的编码是外面世界给的，用户和探测器都可能报出
     * `utf8`、`GBK`、`Big5`、`windows-1252` 等不同写法，不统一就必然出现
     * 「明明能解却提示编码不存在」。
     */
    private fun canonicalEncodingName(label: String, bom: TextBom): String? {
        val raw = label.trim()
        if (raw.isEmpty()) return null
        val key = raw.uppercase(Locale.ROOT)

        // 不带字节序的 UTF-16/UTF-32：有 BOM 就跟 BOM，没有 BOM 按 Unicode 标准默认大端（和 Java 一致）。
        // 必须先拦下来，否则交给平台会得到「UTF-16」，让后面的 BOM 逻辑无从判断该写哪一端。
        if (key == "UTF-16" || key == "UTF16") {
            return if (bom == TextBom.UTF16_LE) "UTF-16LE" else "UTF-16BE"
        }
        if (key == "UTF-32" || key == "UTF32") {
            return if (bom == TextBom.UTF32_LE) "UTF-32LE" else "UTF-32BE"
        }

        ENCODING_ALIASES[key]?.let { return it }

        // 其它名字交给平台：ICU 探测器和用户都可能给出下拉列表之外的名字
        //（ISO-8859-15、windows-1251、KOI8-R…），只要平台自己认得就允许用，
        // 别把「能解的编码」挡在门外——用户要求的是「尽量支持任何编码」。
        val charset = try {
            Charset.forName(raw)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return when (charset.name().uppercase(Locale.ROOT)) {
            "UTF-8" -> "UTF-8"
            "UTF-16LE" -> "UTF-16LE"
            "UTF-16BE" -> "UTF-16BE"
            // 平台把泛型 UTF-16/UTF-32 报成什么名字，这里再定一次字节序。
            "UTF-16" -> "UTF-16BE"
            "UTF-32", "UTF32" -> "UTF-32BE"
            "UTF-32LE" -> "UTF-32LE"
            "UTF-32BE" -> "UTF-32BE"
            else -> charset.name()
        }
    }

    private fun resolveEncoding(label: String, bom: TextBom): ResolvedEncoding? {
        val name = canonicalEncodingName(label, bom) ?: return null
        if (name == "UTF-32LE" || name == "UTF-32BE") {
            // UTF-32 走手写实现：不依赖平台 Charset 是否有对应解码器
            //（Android 上不保证有，JVM 各发行版也不一致），顺便让 BOM/坏字节处理和别的编码保持一致。
            return ResolvedEncoding(name, charset = null, littleEndian = name == "UTF-32LE")
        }
        val charset = try {
            Charset.forName(name)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return ResolvedEncoding(name, charset)
    }

    private fun resolveOrThrow(label: String, bom: TextBom): ResolvedEncoding =
        resolveEncoding(label, bom) ?: throw IllegalArgumentException(
            "不支持的文本编码「${label.trim()}」；请从编码列表里选一个，或改用 UTF-8"
        )

    // ───────────────────────── 解码 ─────────────────────────

    private fun decodeContent(encoding: ResolvedEncoding, bytes: ByteArray): Pair<String, Boolean> {
        val charset = encoding.charset ?: return decodeUtf32(encoding.littleEndian, bytes)
        return try {
            // 先严格解一遍：只有严格模式才会告诉我们「这些字节是不是真的合法」，
            // 这是 hadErrors 唯一可靠的来源——直接 REPLACE 解码后去查替换字符，
            // 会把正文里本来就有的 U+FFFD 也误判成「编码选错了」。
            val strict = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            strict.decode(ByteBuffer.wrap(bytes)).toString() to false
        } catch (e: CharacterCodingException) {
            // 真出错才退回宽松模式，保证「编码选错也能打开文件」，同时在 UI 上提示用户。
            val lenient = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            lenient.decode(ByteBuffer.wrap(bytes)).toString() to true
        }
    }

    /** UTF-32 的定长 4 字节解码；非法码点 / 末尾残缺按 REPLACE 兜底并记 hadErrors。 */
    private fun decodeUtf32(littleEndian: Boolean, bytes: ByteArray): Pair<String, Boolean> {
        val builder = StringBuilder(bytes.size / 4)
        var hadErrors = false
        var index = 0
        while (index + 4 <= bytes.size) {
            val codePoint = if (littleEndian) {
                (bytes[index].toInt() and 0xFF) or
                    ((bytes[index + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[index + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[index + 3].toInt() and 0xFF) shl 24)
            } else {
                ((bytes[index].toInt() and 0xFF) shl 24) or
                    ((bytes[index + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[index + 2].toInt() and 0xFF) shl 8) or
                    (bytes[index + 3].toInt() and 0xFF)
            }
            if (codePoint in 0..0x10FFFF && codePoint !in 0xD800..0xDFFF) {
                builder.appendCodePoint(codePoint)
            } else {
                builder.append(REPLACEMENT_CHAR)
                hadErrors = true
            }
            index += 4
        }
        if (index < bytes.size) {
            // 末尾还剩 1~3 字节：UTF-32 要求 4 字节对齐，残缺部分按非法字节处理。
            builder.append(REPLACEMENT_CHAR)
            hadErrors = true
        }
        return builder.toString() to hadErrors
    }

    // ───────────────────────── 编码 ─────────────────────────

    private fun encodeContent(encoding: ResolvedEncoding, text: String): ByteArray {
        val charset = encoding.charset ?: return encodeUtf32(encoding.littleEndian, text, encoding.name)
        val encoder = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = CharBuffer.wrap(text)
        val encoded = try {
            // encode(CharBuffer) 是 JDK 自带的便捷方法：内部会处理 ByteBuffer 增长和 flush。
            // 出错时 input 的读位置已经停在第一个表示不了的字符上，正好用来定位。
            encoder.encode(input)
        } catch (e: UnmappableCharacterException) {
            throw encodingFailure(encoding.name, text, input.position())
        } catch (e: MalformedInputException) {
            throw encodingFailure(encoding.name, text, input.position())
        }
        val out = ByteArray(encoded.remaining())
        encoded.get(out)
        return out
    }

    /** 手写 UTF-32 编码；代理字符按码点处理，孤立的代理字符无法编码，按契约抛 [TextEncodeException]。 */
    private fun encodeUtf32(littleEndian: Boolean, text: String, encodingName: String): ByteArray {
        val buffer = ByteBuffer.allocate(text.length * 4 + 4)
        var index = 0
        while (index < text.length) {
            val first = text[index]
            val codePoint: Int
            val width: Int
            if (Character.isHighSurrogate(first) &&
                index + 1 < text.length &&
                Character.isLowSurrogate(text[index + 1])
            ) {
                codePoint = Character.toCodePoint(first, text[index + 1])
                width = 2
            } else if (Character.isSurrogate(first)) {
                // 孤立的高/低代理字符不是合法 Unicode，任何编码都表示不了，直接按位置报错。
                throw encodingFailure(encodingName, text, index)
            } else {
                codePoint = first.code
                width = 1
            }
            if (littleEndian) {
                buffer.put(codePoint.toByte())
                buffer.put((codePoint ushr 8).toByte())
                buffer.put((codePoint ushr 16).toByte())
                buffer.put((codePoint ushr 24).toByte())
            } else {
                buffer.put((codePoint ushr 24).toByte())
                buffer.put((codePoint ushr 16).toByte())
                buffer.put((codePoint ushr 8).toByte())
                buffer.put(codePoint.toByte())
            }
            index += width
        }
        buffer.flip()
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    /** 把契约里「内部只有 `\n`」的文本按目标风格还原；先归一可防调用方漏归一导致 `\r\r\n`。 */
    private fun restoreLineEndings(text: String, lineEnding: LineEnding): String {
        val normalized = if (text.indexOf('\r') >= 0) {
            text.replace("\r\n", "\n").replace('\r', '\n')
        } else {
            text
        }
        if (lineEnding == LineEnding.LF) return normalized
        return normalized.replace("\n", lineEnding.sequence)
    }

    // ───────────────────────── 换行 / BOM / 探测辅助 ─────────────────────────

    /** 统计原始换行风格，并把文本归一成只用 `\n`；两条信息一次遍历拿到。 */
    private fun normalizeLineEndings(text: String): Pair<String, LineEnding> {
        var crlfCount = 0
        var lfCount = 0
        var crCount = 0
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '\r' -> if (index + 1 < text.length && text[index + 1] == '\n') {
                    crlfCount++
                    index++ // \r\n 只算一个换行
                } else {
                    crCount++
                }

                '\n' -> lfCount++
            }
            index++
        }
        val lineEnding = when {
            crlfCount == 0 && lfCount == 0 && crCount == 0 -> LineEnding.LF // 没有换行时按 LF 记录，不影响正文
            // 平局时优先 CRLF：混合换行的文件多半来自 Windows 编辑器，按 CRLF 保存产生的二次改动最少。
            crlfCount >= lfCount && crlfCount >= crCount -> LineEnding.CRLF
            lfCount >= crCount -> LineEnding.LF
            else -> LineEnding.CR
        }

        // 统一成 \n：Compose 的文本编辑只按 \n 断行，混着 \r 会出现「行尾多一个看不见字符」的怪行。
        val builder = StringBuilder(text.length)
        var cursor = 0
        while (cursor < text.length) {
            val ch = text[cursor]
            if (ch == '\r') {
                builder.append('\n')
                if (cursor + 1 < text.length && text[cursor + 1] == '\n') cursor++ // \r\n 只留一个 \n
            } else {
                builder.append(ch)
            }
            cursor++
        }
        return builder.toString() to lineEnding
    }

    private fun detectBom(bytes: ByteArray): TextBom = when {
        // UTF-32LE 的 BOM 前两字节和 UTF-16LE 完全相同，必须先查更长的 UTF-32，
        // 否则 FF FE 00 00 会被误认成「UTF-16LE 的 BOM + 一个 NUL 字符」。
        startsWith(bytes, UTF32_LE_BOM) -> TextBom.UTF32_LE
        startsWith(bytes, UTF32_BE_BOM) -> TextBom.UTF32_BE
        startsWith(bytes, UTF16_LE_BOM) -> TextBom.UTF16_LE
        startsWith(bytes, UTF16_BE_BOM) -> TextBom.UTF16_BE
        startsWith(bytes, UTF8_BOM) -> TextBom.UTF8
        else -> TextBom.NONE
    }

    private fun bomBytes(bom: TextBom): ByteArray = when (bom) {
        TextBom.NONE -> NO_BYTES
        TextBom.UTF8 -> UTF8_BOM
        TextBom.UTF16_LE -> UTF16_LE_BOM
        TextBom.UTF16_BE -> UTF16_BE_BOM
        TextBom.UTF32_LE -> UTF32_LE_BOM
        TextBom.UTF32_BE -> UTF32_BE_BOM
    }

    /** 该 BOM 唯一匹配的规范编码名；NONE 返回 null。 */
    private fun encodingNameOfBom(bom: TextBom): String? = when (bom) {
        TextBom.NONE -> null
        TextBom.UTF8 -> "UTF-8"
        TextBom.UTF16_LE -> "UTF-16LE"
        TextBom.UTF16_BE -> "UTF-16BE"
        TextBom.UTF32_LE -> "UTF-32LE"
        TextBom.UTF32_BE -> "UTF-32BE"
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (index in prefix.indices) {
            if (bytes[index] != prefix[index]) return false
        }
        return true
    }

    /**
     * 严格的 UTF-8 字节校验（不接受过长编码、不接受代理区码点）。
     *
     * 自己按字节扫而不是用 CharsetDecoder 严格解码，是为了大文件也不用先解出一整份
     * String 再丢掉——探测只是给一个编码名，没必要付两份内存。
     */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index].toInt() and 0xFF
            if (first < 0x80) {
                index++
                continue
            }
            val extra: Int
            val minCodePoint: Int
            var codePoint: Int
            if ((first and 0xE0) == 0xC0) {
                extra = 1
                minCodePoint = 0x80
                codePoint = first and 0x1F
            } else if ((first and 0xF0) == 0xE0) {
                extra = 2
                minCodePoint = 0x800
                codePoint = first and 0x0F
            } else if ((first and 0xF8) == 0xF0) {
                extra = 3
                minCodePoint = 0x10000
                codePoint = first and 0x07
            } else {
                return false // 0x80~0xBF 不能做首字节，0xF8+ 是非法首字节
            }
            if (index + extra >= bytes.size) return false // 后面的续字节不够
            for (offset in 1..extra) {
                val next = bytes[index + offset].toInt() and 0xFF
                if ((next and 0xC0) != 0x80) return false
                codePoint = (codePoint shl 6) or (next and 0x3F)
            }
            if (codePoint < minCodePoint || codePoint > 0x10FFFF) return false
            if (codePoint in 0xD800..0xDFFF) return false
            index += extra + 1
        }
        return true
    }

    /**
     * 无 BOM 的 UTF-16 启发式：只认「隔一个字节一个 0」这种 ASCII 为主的文本。
     *
     * 阈值故意定得很严——漏判最多退回 ICU 探测，误判则会把普通文本打开成满屏乱码；
     * 中文等非 ASCII 的无 BOM UTF-16 识别不了（真实文件几乎都会带 BOM，记事本等都写 BOM）。
     */
    private fun guessBomlessUtf16(bytes: ByteArray): String? {
        if (bytes.size < 8 || bytes.size % 2 != 0) return null
        val units = bytes.size / 2
        var zeroAtEven = 0
        var zeroAtOdd = 0
        for (index in 0 until bytes.size step 2) {
            if (bytes[index].toInt() == 0) zeroAtEven++
            if (bytes[index + 1].toInt() == 0) zeroAtOdd++
        }
        // 小端：低字节在前，ASCII 文本的高字节落在奇数位；大端反过来。
        if (zeroAtOdd >= units * 4 / 5 && zeroAtEven <= units / 20) return "UTF-16LE"
        if (zeroAtEven >= units * 4 / 5 && zeroAtOdd <= units / 20) return "UTF-16BE"
        return null
    }

    private fun detectWithIcu(bytes: ByteArray): List<CharsetMatch> = try {
        val detector = CharsetDetector()
        detector.setText(bytes)
        detector.detectAll().toList()
    } catch (e: Exception) {
        // 探测只是「尽量准」，ICU 内部异常不应该让打开文件直接失败；交给调用方按 UTF-8 兜底。
        emptyList()
    }

    // ───────────────────────── 错误信息 ─────────────────────────

    /**
     * 构造表示不了某个字符时的异常。
     *
     * 注意这里绝不退回 '?'：静默替换会把用户的原文永久改坏，而契约要求 UI 能定位到具体字符。
     */
    private fun encodingFailure(encodingName: String, text: String, rawIndex: Int): TextEncodeException {
        if (text.isEmpty()) {
            return TextEncodeException(REPLACEMENT_CHAR, 0, "编码 $encodingName 无法表示这段文本")
        }
        val index = rawIndex.coerceIn(0, text.lastIndex)
        val char = text[index]
        // 代理对要按一个完整字符提示，否则用户看到的是半个代理码位，根本找不到是哪里的字。
        if (Character.isHighSurrogate(char) &&
            index + 1 < text.length &&
            Character.isLowSurrogate(text[index + 1])
        ) {
            val codePoint = Character.toCodePoint(char, text[index + 1])
            return TextEncodeException(
                character = char,
                index = index,
                message = "编码 $encodingName 表示不了字符「${String(Character.toChars(codePoint))}」" +
                    "（${describeCodePoint(codePoint)}，第 ${index + 1} 个字符）；请换其它编码保存，或先删掉它",
            )
        }
        val description = if (Character.isSurrogate(char)) describeCodePoint(char.code) else describeChar(char)
        return TextEncodeException(
            character = char,
            index = index,
            message = "编码 $encodingName 表示不了字符 $description（第 ${index + 1} 个字符）；请换其它编码保存，或先删掉它",
        )
    }

    private fun describeCodePoint(codePoint: Int): String =
        String.format(Locale.ROOT, "U+%04X", codePoint)

    private fun describeChar(char: Char): String = when {
        char == '\n' -> "换行"
        char == '\r' -> "回车"
        char == '\t' -> "制表符"
        char.code < 0x20 || char.code == 0x7F || Character.isSurrogate(char) ->
            "「${describeCodePoint(char.code)}」"

        else -> "「$char」"
    }
}

/** 候选列表最多给 UI 这么多项，再多下拉框也没人看。 */
private const val MAX_CANDIDATES = 8

/** 统计探测低于这个置信度时，宁可信调用方给的 hint。 */
private const val HINT_TRUST_THRESHOLD = 50

/** 采信 hint 时给它的置信度：比「没把握的统计探测」高，但不假装是 BOM 那种硬证据。 */
private const val HINT_TRUST_CONFIDENCE = 55

private const val REPLACEMENT_CHAR = '\uFFFD'

private val NO_BYTES = ByteArray(0)

/** UTF-8 BOM：EF BB BF */
private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

/** UTF-16LE BOM：FF FE */
private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

/** UTF-16BE BOM：FE FF */
private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

/** UTF-32LE BOM：FF FE 00 00（前两字节和 UTF-16LE 相同，判定时必须先查它） */
private val UTF32_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00)

/** UTF-32BE BOM：00 00 FE FF */
private val UTF32_BE_BOM = byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte())

/**
 * 解析后的编码。[charset] 为 null 表示 UTF-32，走本文件自带的手写实现。
 * 只放字符串不放 Charset 的别名表见 [ENCODING_ALIASES]，别在类初始化阶段强依赖某个平台的 Charset 是否存在。
 */
private class ResolvedEncoding(
    val name: String,
    val charset: Charset?,
    val littleEndian: Boolean = false,
)

/**
 * 编码名别名表：把各种写法统一成上面 [TextCodecImpl.supportedEncodings] 里的规范名。
 *
 * 为什么只存字符串：某些平台可能缺某个 Charset（比如 UTF-32），
 * 如果这里存 Charset 实例，类初始化就会直接失败，连 UTF-8 都用不了。
 */
private val ENCODING_ALIASES: Map<String, String> = run {
    val map = HashMap<String, String>()
    fun register(canonical: String, vararg aliases: String) {
        map[canonical.uppercase(Locale.ROOT)] = canonical
        for (alias in aliases) map[alias.uppercase(Locale.ROOT)] = canonical
    }
    register("UTF-8", "UTF8", "UTF_8", "UTF-8-BOM", "UTF8-BOM")
    register("UTF-16LE", "UTF16LE", "UTF_16LE", "UTF-16-LE")
    register("UTF-16BE", "UTF16BE", "UTF_16BE", "UTF-16-BE")
    register("UTF-32LE", "UTF32LE", "UTF_32LE", "UTF-32-LE")
    register("UTF-32BE", "UTF32BE", "UTF_32BE", "UTF-32-BE")
    register("GB18030", "GB-18030", "GB_18030")
    register("GBK", "CP936", "MS936", "WINDOWS-936")
    register("GB2312", "GB-2312", "EUC-CN")
    register("Big5", "BIG-5", "CP950", "WINDOWS-950")
    register("Shift_JIS", "SHIFT-JIS", "SJIS", "MS_KANJI")
    register("EUC-JP", "EUCJP")
    register("EUC-KR", "EUCKR")
    register("windows-1252", "CP1252", "WINDOWS1252")
    register("ISO-8859-1", "ISO8859-1", "ISO_8859-1", "LATIN1", "LATIN-1")
    map
}
