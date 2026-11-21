package com.cmzc.xj_file.app.core.playback

/**
 * 「哪些文件交给内置查看器」的白名单。
 *
 * 集中在这里、不散落到各处的理由：分流（MainViewModel）、播放页、图片查看器、
 * 相册视图都要用同一份清单，散开以后必然对不上。
 *
 * 分三类，和实际能力对齐（**不是照抄一份后缀表**）：
 * - [VIDEO]/[AUDIO]：Media3 1.3.1 真有提取器的容器 → 内置播放。
 * - [VIDEO_EXTERNAL]：Media3 没有提取器的容器（ASF/WMV/WMA、RealMedia 的 RM/RMVB）
 *   → 识别出来，但**不**硬塞给 ExoPlayer（那只会变成"点了报错"），
 *   而是走原来的"下载/本地文件 → 系统应用打开"路径，交给系统里可能存在的解码器。
 * - [IMAGE]：内置图片查看器认的格式。平台解码器不认的（TIFF/SVG/ICO/RAW 系）
 *   由 `core/image` 里对应的解码器兜底。
 * - [DOC]：这里只做"识别"，打开仍交给系统阅读器（本工程没有文档渲染器）。
 *
 * 故意不在内置播放列表里的格式（列出来是为了以后别再随手加回去）：
 * `wmv`/`wma`/`asf`（微软 ASF 容器）、`rm`/`rmvb`（RealMedia）—— Media3 没有提取器。
 */
object MediaTypes {

    private val VIDEO = setOf(
        "mp4", "m4v", "mov", "mkv", "webm", "avi",
        "ts", "m2ts", "mts", "mpg", "mpeg", "vob",
        "flv", "3gp", "3g2"
    )

    /** 识别但不内置播放：交给系统应用（内置 ExoPlayer 没有这些提取器）。 */
    private val VIDEO_EXTERNAL = setOf("wmv", "wma", "asf", "rm", "rmvb")

    private val AUDIO = setOf(
        "mp3", "flac", "wav", "aac", "m4a",
        "ogg", "oga", "opus", "mka",
        "amr", "ac3", "eac3", "ac4"
    )

    /**
     * 内置图片查看器认的格式。
     *
     * JPEG/PNG/WebP/GIF/BMP/HEIC/HEIF/AVIF 走平台 ImageDecoder；
     * TIFF/SVG/ICO 和 RAW 系（CR2/NEF/ARW/DNG/RAW）平台都不认，
     * 由 `core/image/TiffDecoder` / `SvgDecoder` / `IcoDecoder` / `RawPreview` 兜底
     * （RAW 取内嵌 JPEG 预览，不解析原始传感器数据）。
     */
    private val IMAGE = setOf(
        "jpg", "jpeg", "jpe", "jfif", "png", "webp", "bmp", "gif",
        "heic", "heif", "avif",
        "tif", "tiff", "svg", "ico",
        "raw", "cr2", "nef", "arw", "dng"
    )

    /** 只做识别，打开交给系统阅读器。 */
    private val DOC = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "txt", "md", "rtf", "odt", "ods", "odp",
        "epub", "mobi", "html", "htm"
    )

    private fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** 是不是值得用内置播放器打开（视频或音频）。 */
    fun isPlayable(name: String): Boolean {
        val e = ext(name)
        return e in VIDEO || e in AUDIO
    }

    fun isVideo(name: String): Boolean = ext(name) in VIDEO

    fun isAudio(name: String): Boolean = ext(name) in AUDIO

    /**
     * 认识、但内置播放器解不了的视频/音频容器。
     * 分流时直接走"系统应用打开"，不要拿 ExoPlayer 试一次再失败。
     */
    fun isExternalPlayable(name: String): Boolean = ext(name) in VIDEO_EXTERNAL

    /** 是不是该交给内置图片查看器。 */
    fun isImage(name: String): Boolean = ext(name) in IMAGE

    /** 识别出来的文档类（只用于提示/排序，不决定打开方式）。 */
    fun isDocument(name: String): Boolean = ext(name) in DOC
}
