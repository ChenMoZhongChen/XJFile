package com.cmzc.xj_file.app.core.disk

import android.content.Context
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

/**
 * 把 `assets/pc` 目录下的 .ps1 打成一条可以直接丢给 ssh 执行的 PowerShell 命令。
 *
 * 为什么要压缩 + 二次 base64：Windows 那边命令要经过 cmd.exe（或被远端 shell 再解析一次），
 * **命令行上限 8191 字符**。我们的脚本（含内嵌 C#）有 6 KB 左右，压完仍有一两千，
 * 所以还要靠 minify 把能省的全省掉。
 *
 * 为什么用 `-EncodedCommand` 而不是 `-Command "..."`：远端默认 shell 可能也是 PowerShell，
 * 它会把命令里的 `$d`、`$r` **先展开成空字符串**，整条命令就废了（踩过，
 * 报的是"ReadToEnd 参数不匹配"这种看不懂的错）。EncodedCommand 是一串 base64，
 * 没有 `$`，任何 shell 都不会动它。
 *
 * 为什么不落盘脚本：设计前提就是「PC 上不装东西、不留文件」。
 */
object PcScript {

    /**
     * @param asset assets/pc 下的文件名
     * @param replacements 占位符替换（如 CD 流脚本的起始 LBA）；用占位符而不是命令行参数，
     *   因为这条路没有参数位
     */
    fun command(context: Context, asset: String, vararg replacements: Pair<String, String>): String {
        var script = context.assets.open("pc/$asset").bufferedReader(Charsets.UTF_8).use { it.readText() }
        for ((key, value) in replacements) script = script.replace(key, value)
        script = minify(script)

        val payload = Base64.getEncoder().encodeToString(deflate(script.toByteArray(Charsets.UTF_8)))
        val bootstrap =
            "\$d=[IO.Compression.DeflateStream]::new([IO.MemoryStream]::new([Convert]::FromBase64String('$payload'))," +
                "[IO.Compression.CompressionMode]::Decompress);" +
                "\$r=[IO.StreamReader]::new(\$d);iex \$r.ReadToEnd()"
        val encoded = Base64.getEncoder().encodeToString(bootstrap.toByteArray(Charsets.UTF_16LE))
        val command = "powershell -NoProfile -NonInteractive -EncodedCommand $encoded"
        // Windows 命令行上限 8191。超了远端只会甩一句「The command line is too long.」，
        // 表现出来是流秒断/静音，极难定位（真机踩过）。这里提前炸，日志里能直接看到长度。
        if (command.length > 8191) {
            throw IllegalStateException("PC 命令过长：${command.length} 字符（上限 8191），脚本 $asset 需要再精简")
        }
        return command
    }

    /**
     * 去掉整行注释、空行和行首缩进。
     *
     * 不只是为了好看：整条命令要挤进 8191 字符，缩进占的是实打实的预算。
     * 缩进对 PowerShell 语义无影响，对这里内嵌的 C# 也无影响。
     */
    private fun minify(script: String): String = script.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .joinToString("\n")

    private fun deflate(bytes: ByteArray): ByteArray {
        // ★ nowrap = true：Java 默认输出的是 zlib 格式（前面 2 字节头 + 尾部校验），
        // 而 .NET 的 DeflateStream 只认裸 deflate —— 不设这一位，远端解压会抛
        // InvalidDataException，报出来是"ReadToEnd 参数不匹配"这种完全看不出问题的错。
        val deflater = Deflater(Deflater.BEST_COMPRESSION, /* nowrap = */ true)
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream(bytes.size / 3)
        val chunk = ByteArray(8 * 1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(chunk)
            if (n <= 0) break
            out.write(chunk, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }
}
