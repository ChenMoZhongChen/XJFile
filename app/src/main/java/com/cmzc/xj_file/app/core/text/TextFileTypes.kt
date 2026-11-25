package com.cmzc.xj_file.app.core.text

import com.cmzc.xj_file.app.core.playback.MediaTypes

/**
 * 5B 的「什么文件该进内置文本编辑器」规则。
 *
 * 用户定的规则是：**未知后缀默认按文本打开**，右键/长按再选「打开方式」。
 * 所以这里不能只做一份文本后缀白名单，必须同时排出：
 * - Office / PDF：用户明确说不做 Office，仍交系统应用；
 * - 音视频/图片：已经由内置查看器接管，不能被文本编辑器抢走；
 * - 常见二进制（zip/apk/exe…）：未知后缀默认文本，但这些不是"未知"，
 *   直接丢进编辑器只会显示一堆乱码，交系统应用更合理。
 *
 * 最终 [shouldOpenAsText] 为 true 的文件，打开前还会做一次字节嗅探
 * （[looksBinary]），防止没有后缀的二进制硬闯编辑器。
 */
object TextFileTypes {

    /** 文本编辑器允许读入内存的最大字节数。大文件走按行编辑器。 */
    const val MAX_EDIT_BYTES: Long = 16L * 1024 * 1024
    const val MAX_EDIT_LABEL: String = "16 MB"

    /** 明确按文本处理的代码/配置/日志/网页后缀（5C 高亮也围绕这些）。 */
    private val TEXT = setOf(
        "txt", "text", "log", "md", "markdown", "rst",
        "json", "jsonc", "json5", "xml", "xsl", "xslt", "xsd", "svg",
        "yaml", "yml", "toml", "ini", "conf", "cfg", "properties", "env",
        "csv", "tsv", "sql", "graphql", "gql",
        "html", "htm", "xhtml", "css", "scss", "sass", "less",
        "js", "mjs", "cjs", "jsx", "ts", "tsx", "vue", "svelte",
        "kt", "kts", "java", "groovy", "gradle", "scala",
        "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "cs", "py", "pyw",
        "rb", "go", "rs", "swift", "m", "mm", "php", "pl", "pm", "lua",
        "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "psm1", "vbs",
        "asm", "s", "ini", "reg", "diff", "patch",
        "tex", "bib", "srt", "vtt", "ass", "ssa", "lrc",
        "gitignore", "gitattributes", "editorconfig", "dockerfile", "makefile",
    )

    /** Office / PDF：用户明确不做，永远交系统应用。 */
    private val OFFICE = setOf(
        "pdf", "doc", "docx", "docm", "dot", "dotx",
        "xls", "xlsx", "xlsm", "xlsb", "xlt", "xltx",
        "ppt", "pptx", "pptm", "pps", "ppsx", "pot", "potx",
        "odt", "ods", "odp", "rtf", "pages", "numbers", "key",
        "vsd", "vsdx", "pub", "one", "wps", "et", "dps",
    )

    /** 常见二进制：不是"未知后缀"，别默认进文本编辑器。 */
    private val BINARY = setOf(
        "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst",
        "apk", "aab", "jar", "class", "dex", "so", "dll", "exe", "msi",
        "bin", "iso", "img", "dmg", "vhd", "vhdx", "vmdk", "qcow2",
        "db", "sqlite", "sqlite3", "mdb", "accdb", "torrent",
        "epub", "mobi", "azw", "azw3",
        "ttf", "otf", "woff", "woff2", "eot",
        "psd", "ai", "sketch", "fig", "blend", "obj", "fbx", "stl", "glb",
        "dwg", "dxf", "pak", "dat", "cache", "lock",
    )

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isKnownText(name: String): Boolean =
        extensionOf(name) in TEXT ||
            // 无后缀的常见文本文件名（Dockerfile/Makefile/.gitignore 等）
            name.lowercase() in setOf("dockerfile", "makefile", "cmakelists.txt")

    fun isOffice(name: String): Boolean = extensionOf(name) in OFFICE

    fun isPdf(name: String): Boolean = extensionOf(name) == "pdf"

    fun isKnownBinary(name: String): Boolean {
        val ext = extensionOf(name)
        return ext in BINARY || ext in OFFICE
    }

    /**
     * 点开时是否默认进文本编辑器。
     *
     * 已知文本 → 是；未知后缀 → 是（用户要求）；Office/PDF、常见二进制、
     * 音视频/图片 → 否，继续走原来的系统应用/内置查看器。
     */
    fun shouldOpenAsText(name: String): Boolean {
        val ext = extensionOf(name)
        if (ext in OFFICE || ext in BINARY) return false
        if (MediaTypes.isPlayable(name) || MediaTypes.isImage(name) ||
            MediaTypes.isExternalPlayable(name)
        ) {
            return false
        }
        return true
    }

    /**
     * 字节嗅探：只对**未知后缀**调用（已知文本允许包含少量异常字节）。
     *
     * 有 UTF-16/32 BOM 的文本天然带 NUL，不能按 NUL 判死；
     * 常见容器/可执行文件按魔数直接判二进制；剩下按前 4KB 的 NUL 比例兜底。
     */
    fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        // BOM 先排除 UTF-16/32 文本
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) return false
        if (startsWith(bytes, 0xFF, 0xFE) || startsWith(bytes, 0xFE, 0xFF)) return false
        if (startsWith(bytes, 0x00, 0x00, 0xFE, 0xFF)) return false

        if (startsWith(bytes, 0x25, 0x50, 0x44, 0x46)) return true // %PDF
        if (startsWith(bytes, 0x50, 0x4B, 0x03, 0x04)) return true // zip/apk/docx
        if (startsWith(bytes, 0x52, 0x61, 0x72, 0x21)) return true // Rar!
        if (startsWith(bytes, 0x37, 0x7A, 0xBC, 0xAF)) return true // 7z
        if (startsWith(bytes, 0x1F, 0x8B)) return true // gzip
        if (startsWith(bytes, 0x89, 0x50, 0x4E, 0x47)) return true // PNG
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) return true // JPEG
        if (startsWith(bytes, 0x47, 0x49, 0x46, 0x38)) return true // GIF
        if (startsWith(bytes, 0x42, 0x4D)) return true // BMP
        if (startsWith(bytes, 0x7F, 0x45, 0x4C, 0x46)) return true // ELF
        if (startsWith(bytes, 0x4D, 0x5A)) return true // MZ/PE
        if (startsWith(bytes, 0xD0, 0xCF, 0x11, 0xE0)) return true // OLE

        val sample = minOf(bytes.size, 4096)
        var nul = 0
        for (i in 0 until sample) {
            if (bytes[i] == 0.toByte()) nul++
        }
        return nul * 100 / sample >= 10
    }

    private fun startsWith(bytes: ByteArray, vararg prefix: Int): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) {
            if ((bytes[i].toInt() and 0xFF) != prefix[i]) return false
        }
        return true
    }
}
