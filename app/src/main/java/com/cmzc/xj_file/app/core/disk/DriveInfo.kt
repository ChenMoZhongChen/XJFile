package com.cmzc.xj_file.app.core.disk

/** 驱动器的介质类型（决定图标长什么样）。 */
enum class DriveKind { SSD, HDD, FLOPPY, USB, OPTICAL, UNKNOWN }

/**
 * 光驱里那张盘的种类 —— 用户定的四种颜色就是按这个分的：
 * CD 绿、数据 DVD 紫、视频 DVD 黄、BD 蓝；千年盘（M-DISC）再叠一层灰。
 */
enum class OpticalMedia { NONE, CD, DVD_DATA, DVD_VIDEO, BD }

/**
 * 音频 CD 上的一条音轨。
 *
 * CD-DA 的地址单位是扇区（1 扇区 = 2352 字节裸 PCM，75 扇区 = 1 秒），
 * 所以轨长直接用扇区数表示，换算成字节/时长都在这里做，播放层不再重复算。
 */
data class CdTrack(
    val number: Int,
    val startLba: Long,
    val sectors: Long,
) {
    val pcmBytes: Long get() = sectors * 2352L
    val durationMs: Long get() = sectors * 1000L / 75L
}

/**
 * Windows 侧的一块卷/驱动器。
 *
 * 这些信息 SFTP 协议本身给不了 —— 文件系统只告诉你"有个目录叫 C:"，
 * 不知道它叫 Windows10、多大、还剩多少、是 SSD 还是 HDD、光驱里插的是什么盘。
 * 所以由 PC 侧跑 PowerShell 报上来（见 [DriveProbe]）。
 */
data class DriveInfo(
    /** 盘符单字母，如 "C" */
    val letter: String,
    /** 卷标（用户起的磁盘名），可能为空 */
    val label: String,
    val kind: DriveKind,
    val optical: OpticalMedia = OpticalMedia.NONE,
    val sizeBytes: Long = 0,
    val freeBytes: Long = 0,
    /** 千年盘（M-DISC）：图标左半彩色、右半灰 */
    val isMdisc: Boolean = false,
    val busType: String = "",
    /**
     * 光驱根目录有没有 VIDEO_TS（= 视频 DVD）。
     *
     * 单独留一位而不是直接折进 [optical]：第二段 SCSI 只能说明「物理介质是 DVD」，
     * 合并时如果拿它无条件覆盖，刚认出来的视频 DVD 又会被打回数据 DVD（真机 BUG）。
     */
    val videoTs: Boolean = false,
    /** TOC 判定：所有音轨都是音频轨。比卷标 "Audio CD" 可靠（数据 CD 也可能叫这名）。 */
    val audioCd: Boolean = false,
    /** 音频 CD 的音轨表（由 PC 侧 READ_TOC_EX 读出）。非音频 CD 为空。 */
    val tracks: List<CdTrack> = emptyList(),
) {
    val isOptical: Boolean get() = kind == DriveKind.OPTICAL

    /** 有没有盘。固定盘永远算有；光驱要看是否读到介质。 */
    val hasMedia: Boolean get() = if (isOptical) optical != OpticalMedia.NONE else sizeBytes > 0

    /** 面板里显示的名字：有卷标就「卷标 (C:)」，没有就「C:」。 */
    val displayName: String
        get() = if (label.isNotBlank()) "$label ($letter:)" else "$letter:"

    /** 音频 CD：TOC 读到了就信 TOC（数据 CD 也可能被 Windows 标成 "Audio CD"）；读不到再退回卷标。 */
    val isAudioCd: Boolean
        get() = isOptical && optical == OpticalMedia.CD && when {
            tracks.isNotEmpty() -> audioCd
            else -> label.equals("Audio CD", ignoreCase = true)
        }
}
