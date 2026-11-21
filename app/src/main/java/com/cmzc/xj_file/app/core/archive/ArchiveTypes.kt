package com.cmzc.xj_file.app.core.archive

/**
 * 对齐 7-Zip「添加到压缩包」的常见格式列表（去掉 ISO/EXE）。
 *
 * 能真正写入的：
 * - ZIP / ZIPX：JDK ZIP（ZIPX 用 ZIP 兼容容器，扩展名 .zipx）；
 * - TAR / TGZ：Commons Compress + GZIP；
 * - 7Z：Commons Compress + XZ；
 * - GZ / XZ：单文件压缩。
 *
 * LZH / ZPAQ / RAR 只保留选项位：
 * - LZH / ZPAQ 没有可用的 Android 写入库；
 * - RAR 创建格式专有，按用户要求只补位、不做功能。
 * 不用 ZIP 冒充，避免生成“扩展名对、内容错”的文件。
 */
enum class ArchiveType(
    val label: String,
    val extension: String,
    val supported: Boolean,
    val singleFileOnly: Boolean,
    val note: String,
) {
    ZIP("ZIP", "zip", true, false, "多文件/目录，兼容性最好"),
    ZIPX("ZIPX", "zipx", true, false, "ZIP 兼容容器，扩展名 .zipx"),
    TAR("TAR", "tar", true, false, "只打包不压缩，多文件/目录"),
    TGZ("TGZ", "tgz", true, false, "TAR + GZIP，多文件/目录"),
    LZH("LZH", "lzh", false, false, "暂无 Android 写入库，暂不可用"),
    SEVEN_Z("7Z", "7z", true, false, "LZMA2 压缩，多文件/目录"),
    GZ("GZ", "gz", true, true, "单文件压缩；多选请用 TGZ"),
    XZ("XZ", "xz", true, true, "单文件 LZMA2 压缩；多选请用 TGZ"),
    ZPAQ("ZPAQ", "zpaq", false, false, "暂无 Android 写入库，暂不可用"),
    RAR("RAR", "rar", false, false, "RAR 创建格式专有，仅补位，暂不实现"),
    ;

    /** 这个格式能不能处理当前选择（单文件压缩格式不支持多选/文件夹）。 */
    fun canUseFor(itemCount: Int, hasDirectory: Boolean): Boolean =
        supported && (!singleFileOnly || (itemCount == 1 && !hasDirectory))
}

/** 压缩级别：0 仅存储、1 快速、2 标准、3 极限。 */
data class CompressionLevel(
    val value: Int,
    val title: String,
    val detail: String,
)

object ArchiveLevels {
    val ALL = listOf(
        CompressionLevel(0, "仅存储", "不压缩，速度最快"),
        CompressionLevel(1, "快速", "轻度压缩，速度较快"),
        CompressionLevel(2, "标准", "推荐，速度和体积平衡"),
        CompressionLevel(3, "极限", "压缩率最高，速度最慢"),
    )

    /** UI 的 0..3 映射到 ZIP 的 0..9。 */
    fun zipLevel(level: Int): Int = when (level.coerceIn(0, 3)) {
        0 -> 0
        1 -> 1
        2 -> 6
        else -> 9
    }

    /** GZIP 也用 Deflater 的 0..9 等级。 */
    fun gzipLevel(level: Int): Int = zipLevel(level)

    /** XZ 的预设 0..9。 */
    fun xzPreset(level: Int): Int = zipLevel(level)

    /** BZIP2 的块大小 1..9；0 级也至少 1，仍会做基础压缩。 */
    fun bzip2Block(level: Int): Int = when (level.coerceIn(0, 3)) {
        0 -> 1
        1 -> 3
        2 -> 6
        else -> 9
    }
}

data class CompressRequest(
    val name: String,
    val type: ArchiveType,
    val level: Int,
)
