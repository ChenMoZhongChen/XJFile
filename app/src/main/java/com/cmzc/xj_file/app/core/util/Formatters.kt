package com.cmzc.xj_file.app.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formatters {

    private val timeFmt = SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault())

    fun formatTime(epochMillis: Long): String {
        if (epochMillis <= 0) return ""
        return timeFmt.format(Date(epochMillis))
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB")
        var v = bytes.toDouble() / 1024.0
        var i = 0
        while (v >= 1024.0 && i < units.size - 1) { v /= 1024.0; i++ }
        return String.format(Locale.US, "%.2f %s", v, units[i])
    }

    /**
     * 字节速率 → 二进制位速率。例：28 MiB/s → "224.00 Mib/s"
     * 1024 进制，单位 Kib/s、Mib/s、Gib/s、Tib/s。
     */
    fun formatBitrateBinary(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return "0 bps"
        val bitsPerSec = bytesPerSec.toDouble() * 8.0
        if (bitsPerSec < 1024.0) return String.format(Locale.US, "%.0f bps", bitsPerSec)
        val units = arrayOf("Kib/s", "Mib/s", "Gib/s", "Tib/s")
        var v = bitsPerSec / 1024.0
        var i = 0
        while (v >= 1024.0 && i < units.size - 1) { v /= 1024.0; i++ }
        return String.format(Locale.US, "%.2f %s", v, units[i])
    }

    /**
     * 字节速率 → 十进制位速率。例：28 MiB/s → "235.00 Mbps"
     * 1000 进制，单位 Kbps、Mbps、Gbps、Tbps。
     */
    fun formatBitrateDecimal(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return "0 bps"
        val bitsPerSec = bytesPerSec.toDouble() * 8.0
        if (bitsPerSec < 1000.0) return String.format(Locale.US, "%.0f bps", bitsPerSec)
        val units = arrayOf("Kbps", "Mbps", "Gbps", "Tbps")
        var v = bitsPerSec / 1000.0
        var i = 0
        while (v >= 1000.0 && i < units.size - 1) { v /= 1000.0; i++ }
        return String.format(Locale.US, "%.2f %s", v, units[i])
    }
}