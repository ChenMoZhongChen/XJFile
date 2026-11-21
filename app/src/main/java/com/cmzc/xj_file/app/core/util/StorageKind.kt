package com.cmzc.xj_file.app.core.util

import java.io.File

/**
 * 本机存储的介质类型，只用于「本地存储（SSD）」这类标签。
 *
 * 探测分两步：
 * 1. 首选内核的权威答案 `/sys/block/<设备>/queue/rotational`：
 *    `1` = 旋转介质（HDD），`0` = 非旋转介质（SSD / UFS / eMMC / SD 卡）。
 * 2. 读不到就按「手机内置存储是闪存」处理，返回 SSD。
 *
 * 为什么需要第 2 步：Android 10 以后大多数机型的 SELinux 不让普通 App 读 `/sys/block`
 * （实测这台 vivo，`/sys/block` 连列目录都被拒），所以在手机上第 1 步基本走不通。
 * 但这一步不能省 —— 在 Android 电视盒子 / 工控机这类真挂了机械盘的设备上，
 * 只要系统允许读 sysfs，就能如实标出 HDD。
 *
 * 只探测一次：sysfs 在运行期间不会变，UI 每次重组都去读文件没有意义。
 */
object StorageKind {

    /** "SSD" 或 "HDD" */
    val label: String by lazy { detect() }

    private fun detect(): String {
        val flags = runCatching {
            File("/sys/block").listFiles().orEmpty().mapNotNull { dev ->
                runCatching {
                    File(dev, "queue/rotational").readText().trim().toIntOrNull()
                }.getOrNull()
            }
        }.getOrDefault(emptyList())

        // 只要能读到任意一块底层设备的 rotational，就以它为准
        if (flags.isNotEmpty()) return if (flags.any { it == 1 }) "HDD" else "SSD"

        // 读不到：本地存储挂在 /data 上，Android 的 /data 必然是 f2fs / ext4 这类闪存文件系统
        return "SSD"
    }
}
