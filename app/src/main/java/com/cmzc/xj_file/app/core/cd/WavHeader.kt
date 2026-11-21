package com.cmzc.xj_file.app.core.cd

/**
 * 一条 CD 音轨的「虚拟 wav 头」。
 *
 * 为什么需要它：PC 那边流过来的是裸 PCM，而 Media3 没有裸 PCM 提取器；但 TOC 能提前
 * 算出这条轨的总字节数，于是可以造一个数据长度正确的 RIFF 头，让 WavExtractor
 * 当普通 wav 播 —— 时长、进度条、seek 就都是真实可用的，不用另做一套播放器。
 */
object WavHeader {

    /** CD-DA 固定参数：44.1kHz / 16bit / 立体声，1 扇区正好 2352 字节（1/75 秒）。 */
    const val SECTOR_BYTES = 2352L
    const val SAMPLE_RATE = 44_100
    const val CHANNELS = 2
    const val BITS_PER_SAMPLE = 16

    /** RIFF 头固定 44 字节。 */
    const val SIZE = 44

    /** 44 字节的 wav 头；[pcmBytes] 由 TOC 的扇区数算出，写进 data 块长度。 */
    fun of(pcmBytes: Long): ByteArray {
        val h = ByteArray(SIZE)
        writeAscii(h, 0, "RIFF")
        writeInt(h, 4, (SIZE - 8 + pcmBytes).toInt())
        writeAscii(h, 8, "WAVE")
        writeAscii(h, 12, "fmt ")
        writeInt(h, 16, 16)                                     // fmt 块长度
        writeShort(h, 20, 1)                                    // PCM
        writeShort(h, 22, CHANNELS)
        writeInt(h, 24, SAMPLE_RATE)
        writeInt(h, 28, SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8)   // byteRate
        writeShort(h, 32, CHANNELS * BITS_PER_SAMPLE / 8)        // blockAlign
        writeShort(h, 34, BITS_PER_SAMPLE)
        writeAscii(h, 36, "data")
        writeInt(h, 40, pcmBytes.toInt())
        return h
    }

    private fun writeAscii(target: ByteArray, at: Int, s: String) {
        for (i in s.indices) target[at + i] = s[i].code.toByte()
    }

    /** wav 是小端。 */
    private fun writeInt(target: ByteArray, at: Int, v: Int) {
        target[at] = v.toByte()
        target[at + 1] = (v ushr 8).toByte()
        target[at + 2] = (v ushr 16).toByte()
        target[at + 3] = (v ushr 24).toByte()
    }

    private fun writeShort(target: ByteArray, at: Int, v: Int) {
        target[at] = v.toByte()
        target[at + 1] = (v ushr 8).toByte()
    }
}
