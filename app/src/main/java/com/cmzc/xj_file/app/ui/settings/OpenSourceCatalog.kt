package com.cmzc.xj_file.app.ui.settings

/**
 * 开源软件声明目录。
 *
 * 许可证全文放在 assets/licenses/，详情页按 [licenseAsset] 加载；
 * 版权归属单独展示，BSD/MIT 模板里的占位符会在详情页替换成这里的 copyright。
 */
data class OpenSourceItem(
    val id: String,
    val name: String,
    val version: String,
    val licenseLabel: String,
    val licenseAsset: String,
    val description: String,
    val url: String,
    val copyright: String,
    /** LGPL 等需要额外说明源代码/替换方式的条目。 */
    val extraNotice: String? = null,
    /** 随 APK 提供的完整源码包（相对 assets/licenses/）。 */
    val sourceAsset: String? = null,
    val sourceFileName: String? = null,
    val sourceFileCount: Int = 0,
    val sourceBytes: Long = 0,
)

object OpenSourceCatalog {

    val app = listOf(
        OpenSourceItem(
            id = "xjfile",
            name = "XJ File（本应用）",
            version = "6.1.0.180805737",
            licenseLabel = "MIT License",
            licenseAsset = "xjfile-mit.txt",
            description = "XJ File 自身代码；第三方组件仍适用各自许可证。",
            url = "",
            copyright = "Copyright (c) 2026 XJoffice ChenMoZhongChen",
            extraNotice = "XJ File 自身源码以 MIT License 发布。完整源码包已随 APK 提供，" +
                "可导出后按 README 构建；修改 jcifs-ng 后可重新构建 APK。",
            sourceAsset = "sources/XJFile-6.1.0.180805737-source.zip",
            sourceFileName = "XJFile-6.1.0.180805737-source.zip",
            sourceFileCount = 168,
            sourceBytes = 581177,
        ),
    )

    val runtime = listOf(
        OpenSourceItem(
            id = "androidx",
            name = "AndroidX / Jetpack Compose",
            version = "Core 1.12.0 · Lifecycle 2.7.0 · Activity 1.8.2 · Compose BOM 2024.02.01 · Material3",
            licenseLabel = "Apache License 2.0",
            licenseAsset = "apache-2.0.txt",
            description = "Android 基础组件、声明式 UI、Material 3 组件与图标。",
            url = "https://developer.android.com/jetpack/androidx",
            copyright = "Copyright (C) The Android Open Source Project",
        ),
        OpenSourceItem(
            id = "accompanist",
            name = "Accompanist SwipeRefresh",
            version = "0.32.0",
            licenseLabel = "Apache License 2.0",
            licenseAsset = "apache-2.0.txt",
            description = "文件列表的下拉刷新容器。",
            url = "https://github.com/google/accompanist",
            copyright = "Copyright 2021 The Android Open Source Project",
        ),
        OpenSourceItem(
            id = "media3",
            name = "AndroidX Media3 / ExoPlayer",
            version = "1.3.1",
            licenseLabel = "Apache License 2.0",
            licenseAsset = "apache-2.0.txt",
            description = "视频、音频播放与媒体数据源（含 CD 音频流）。",
            url = "https://github.com/androidx/media",
            copyright = "Copyright (C) The Android Open Source Project",
        ),
        OpenSourceItem(
            id = "coroutines",
            name = "Kotlin Coroutines",
            version = "1.7.3",
            licenseLabel = "Apache License 2.0",
            licenseAsset = "apache-2.0.txt",
            description = "协程调度、IO 线程与异步任务。",
            url = "https://github.com/Kotlin/kotlinx.coroutines",
            copyright = "Copyright 2016-2024 JetBrains s.r.o.",
        ),
        OpenSourceItem(
            id = "jsch",
            name = "JSch (mwiede fork)",
            version = "0.2.20",
            licenseLabel = "BSD 3-Clause",
            licenseAsset = "bsd-3-clause.txt",
            description = "SFTP 连接、文件传输与远程命令通道。",
            url = "https://github.com/mwiede/jsch",
            copyright = "Copyright (c) 2002-2024 Atsuhiko Yamanaka, JCraft, Inc.",
        ),
        OpenSourceItem(
            id = "smbj",
            name = "SMBJ",
            version = "0.13.0",
            licenseLabel = "Apache License 2.0",
            licenseAsset = "apache-2.0.txt",
            description = "SMB/CIFS 共享浏览与文件读写。",
            url = "https://github.com/hierynomus/smbj",
            copyright = "Copyright 2016-2024 Hierynomus",
        ),
        OpenSourceItem(
            id = "jcifs",
            name = "jcifs-ng",
            version = "2.1.10",
            licenseLabel = "LGPL 2.1",
            licenseAsset = "lgpl-2.1.txt",
            description = "SMB 共享枚举与 NTLM 认证支持。",
            url = "https://github.com/AgNO3/jcifs-ng",
            copyright = "Copyright (C) 2015-2024 AgNO3 and contributors",
            extraNotice = "jcifs-ng 以未修改的 Java 库形式随本应用分发。完整源码包已随 APK 提供，" +
                "可在下方导出；上游源码地址为 https://github.com/AgNO3/jcifs-ng 。" +
                "如需替换或重新链接该库，可使用导出的源码与本项目源码重新构建 APK。",
            sourceAsset = "sources/jcifs-ng-2.1.10-sources.jar",
            sourceFileName = "jcifs-ng-2.1.10-sources.jar",
            sourceFileCount = 450,
            sourceBytes = 705958,
        ),
        OpenSourceItem(
            id = "bouncycastle",
            name = "Bouncy Castle",
            version = "bcprov-jdk18on 1.77",
            licenseLabel = "Bouncy Castle License",
            licenseAsset = "bouncycastle.txt",
            description = "加密算法与证书支持，供 SFTP/SMB 使用。",
            url = "https://www.bouncycastle.org/",
            copyright = "Copyright (c) 2000-2024 The Legion of the Bouncy Castle Inc.",
        ),
        OpenSourceItem(
            id = "androidsvg",
            name = "AndroidSVG",
            version = "1.4",
            licenseLabel = "Apache License 2.0",
            licenseAsset = "apache-2.0.txt",
            description = "SVG 图片解码与显示。",
            url = "https://github.com/BigBadaboom/androidsvg",
            copyright = "Copyright 2013-2024 BigBadaboom",
        ),
        OpenSourceItem(
            id = "icu",
            name = "ICU4J",
            version = "75.1",
            licenseLabel = "Unicode License",
            licenseAsset = "unicode.txt",
            description = "文本编码探测与字符集转换。",
            url = "https://unicode-org.github.io/icu/",
            copyright = "Copyright © 2016-2024 Unicode, Inc. and others",
        ),
        OpenSourceItem(
            id = "commons-compress",
            name = "Apache Commons Compress",
            version = "1.21",
            licenseLabel = "Apache License 2.0",
            licenseAsset = "apache-2.0.txt",
            description = "TAR/TGZ/7Z 压缩包写入。",
            url = "https://commons.apache.org/proper/commons-compress/",
            copyright = "Copyright 2002-2024 The Apache Software Foundation",
        ),
        OpenSourceItem(
            id = "xz",
            name = "XZ for Java",
            version = "1.9",
            licenseLabel = "Public Domain",
            licenseAsset = "xz-public-domain.txt",
            description = "7Z 的 LZMA2 与单文件 XZ 压缩。",
            url = "https://tukaani.org/xz/java.html",
            copyright = "Public domain; original authors include Lasse Collin and Igor Pavlov",
        ),
        OpenSourceItem(
            id = "slf4j",
            name = "SLF4J Android",
            version = "1.7.36",
            licenseLabel = "MIT License",
            licenseAsset = "mit.txt",
            description = "SMBJ 的日志门面实现。",
            url = "https://www.slf4j.org/",
            copyright = "Copyright (c) 2004-2024 QOS.ch",
        ),
    )

    val build = listOf(
        OpenSourceItem(
            id = "kotlin",
            name = "Kotlin",
            version = "2.0.21",
            licenseLabel = "Apache License 2.0",
            licenseAsset = "apache-2.0.txt",
            description = "应用与 Gradle 构建脚本语言。",
            url = "https://kotlinlang.org/",
            copyright = "Copyright 2010-2024 JetBrains s.r.o.",
        ),
        OpenSourceItem(
            id = "gradle",
            name = "Gradle",
            version = "9.5.0",
            licenseLabel = "Apache License 2.0",
            licenseAsset = "apache-2.0.txt",
            description = "项目构建、依赖解析与打包。",
            url = "https://gradle.org/",
            copyright = "Copyright 2007-2024 Gradle, Inc.",
        ),
        OpenSourceItem(
            id = "agp",
            name = "Android Gradle Plugin",
            version = "8.13.0",
            licenseLabel = "Apache License 2.0",
            licenseAsset = "apache-2.0.txt",
            description = "Android 构建、资源处理和 DEX 打包。",
            url = "https://developer.android.com/build",
            copyright = "Copyright (C) The Android Open Source Project",
        ),
    )

    val all: List<OpenSourceItem> = app + runtime + build

    fun byId(id: String): OpenSourceItem? = all.firstOrNull { it.id == id }
}
