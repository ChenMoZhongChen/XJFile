# XJ File 第三方软件声明

XJ File 自身代码以 **MIT License** 发布，全文见根目录 [LICENSE](LICENSE)。
Copyright (c) 2026 XJoffice ChenMoZhongChen.

本项目使用的第三方组件仍归各自版权人所有，并继续适用其原始许可证；
它们不会被 MIT License 重新授权。随 APK 分发的许可证全文位于
[`app/src/main/assets/licenses/`](app/src/main/assets/licenses/)，应用内
「设置 → 关于 → 开源软件声明」也可逐项查看。

## 运行时组件

第三方 NOTICE 汇总已随 APK 提供：

`assets/licenses/THIRD_PARTY_NOTICES.txt`

XJ File 自身源码包也已随 APK 提供，用于 LGPL 的“可重新构建/重新链接”：

`assets/licenses/sources/XJFile-6.1.0.180805737-source.zip`

在应用内「开源软件声明 → XJ File（本应用）」详情页可以导出；jcifs-ng
源码包在 jcifs-ng 详情页导出。

| 组件 | 许可证 | 版权归属 |
|---|---|---|
| AndroidX / Jetpack Compose | Apache-2.0 | Copyright (C) The Android Open Source Project |
| Accompanist SwipeRefresh | Apache-2.0 | Copyright 2021 The Android Open Source Project |
| AndroidX Media3 / ExoPlayer | Apache-2.0 | Copyright (C) The Android Open Source Project |
| Kotlin Coroutines | Apache-2.0 | Copyright 2016-2024 JetBrains s.r.o. |
| JSch (mwiede fork) | BSD-3-Clause | Copyright (c) 2002-2024 Atsuhiko Yamanaka, JCraft, Inc. |
| SMBJ | Apache-2.0 | Copyright 2016-2024 Hierynomus |
| jcifs-ng | **LGPL-2.1** | Copyright (C) 2015-2024 AgNO3 and contributors |
| Bouncy Castle | Bouncy Castle License (MIT-style) | Copyright (c) 2000-2024 The Legion of the Bouncy Castle Inc. |
| AndroidSVG | Apache-2.0 | Copyright 2013-2024 BigBadaboom |
| ICU4J | Unicode License | Copyright © 2016-2024 Unicode, Inc. and others |
| Apache Commons Compress | Apache-2.0 | Copyright 2002-2024 The Apache Software Foundation |
| XZ for Java | Public Domain | Lasse Collin, Igor Pavlov and contributors |
| SLF4J | MIT | Copyright (c) 2004-2024 QOS.ch |

## 构建工具

Kotlin、Gradle、Android Gradle Plugin 使用 Apache-2.0，仅用于构建，
不随 APK 运行时分发。

## LGPL 特别说明

`jcifs-ng` 是 LGPL-2.1 软件，**不适用 MIT License**。本项目以未修改的
Java 库形式使用它。**jcifs-ng 2.1.10 的完整源码包已随 APK 提供**：

`assets/licenses/sources/jcifs-ng-2.1.10-sources.jar`

在应用内「设置 → 关于 → 开源软件声明 → jcifs-ng」详情页可以导出到
`Download/XjFile/`，不需要联网。上游源码地址：

https://github.com/AgNO3/jcifs-ng

分发包含 `jcifs-ng` 的 APK 时，需要同时满足 LGPL-2.1 的要求，包括提供
LGPL 许可证文本、标明库的版权、提供对应源码，并允许替换/重新链接该库。
XJ File 自身源码以 MIT 发布，接收者可以使用对应版本的 `jcifs-ng` 源码和
本项目源码重新构建 APK。若希望整个发行物只包含宽松许可证依赖，
应移除 `jcifs-ng` 或替换为 Apache-2.0 的 SMB 共享枚举实现。

### LGPL-2.1 发布检查清单

1. 随包附完整 LGPL-2.1 文本（`assets/licenses/lgpl-2.1.txt`）。
2. 保留 jcifs-ng 的版权声明、许可证声明和免责声明。
3. 显著说明：本应用使用了 jcifs-ng，该库及其使用受 LGPL-2.1 约束；
   应用内「开源软件声明 → jcifs-ng」已提供该说明。
4. 提供 **jcifs-ng 2.1.10 的完整对应源码**。当前 APK 已内置
   `jcifs-ng-2.1.10-sources.jar`，详情页可导出；若采用外部发行方式，
   也可提供至少三年有效的书面源码获取要约。
5. 提供能够重新链接的“使用该库的作品”的源码/构建方式；Android 的 Java
   代码最终合入 DEX，不能靠替换一个 jar 完成，因此需要能按本项目源码和
   构建脚本，使用修改后的 jcifs-ng 重新构建 APK。
6. 如果修改过 jcifs-ng，必须在修改文件中标明改动和日期，并按 LGPL
   分发修改后的库源码。
7. 不得把 jcifs-ng 重新标注为 MIT 或移除其许可证声明；MIT 只适用于
   XJ File 自身代码。
8. 若只分发 APK、不随附源码，应在下载页/发行说明中提供等价源码获取方式，
   或提供三年有效的书面要约。

当前 APK 已包含 LGPL 全文、版权归属和应用内声明；但如果要公开发布，
仍需把 **jcifs-ng 源码包和可重建源码**作为发行材料准备好。

## 说明

- 第三方组件不会被 MIT License 重新授权。
- 各许可证全文已随 APK 提供；如与项目主页存在差异，以对应版本的许可证原文为准。
- 本文件用于工程合规说明，不构成法律意见。
