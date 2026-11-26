# XJ File（新金文件管理器）

<a id="zh-cn"></a>

[简体中文](#zh-cn) | [English](#en-us)

> 一个为 Android 设计的双栏文件管理器：本地存储、SMB、SFTP，压缩包、PDF、图片、音视频内置查看，CD/DVD/BD/M-DISC 探测，窗口化大文本编辑，以及可恢复的传输队列。

![Version](https://img.shields.io/badge/version-6.1.0.180805737-2C6FDB)
![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![License](https://img.shields.io/badge/license-MIT-yellow)

---

## 目录

- [项目简介](#intro)
- [当前版本](#version)
- [功能总览](#features)
- [双栏文件管理](#dual-pane)
- [传输系统](#transfer)
- [压缩包](#archives)
- [查看器](#viewers)
- [文件图标与缩略图](#icons)
- [文本编辑器](#editor)
- [CD、DVD、BD 与 M-DISC](#optical)
- [设置与外观](#settings)
- [技术栈](#stack)
- [代码结构](#structure)
- [构建](#build)
- [安装与权限](#install)
- [已知限制](#limits)
- [开源与许可证](#license)
- [致谢](#thanks)
- [相关链接](#links)

---

<a id="intro"></a>

## 项目简介

XJ File 是一个 Android 双栏文件管理器：把「本地文件管理」和「远程文件管理」放在同一个界面里完成：

- 左栏和右栏可以分别是本地存储、SMB 共享或 SFTP 主机；
- 复制、移动、压缩、解压、预览、编辑都直接在双栏之间完成；
- 不需要先把远程文件下载到本地再处理；
- 大文件、文本、压缩包、媒体和传输任务都有独立的处理路径，避免一个场景拖慢整个应用。

它不是一个「什么都塞进一个列表」的演示程序，而是按文件类型和来源拆开处理：

- 图片/视频走缩略图和 Media3；
- PDF 走系统 `PdfRenderer`；
- 文本走编码识别和编辑器；
- 压缩包走 Apache Commons Compress / JDK ZIP / XZ；
- 远程 APK/EXE 图标只读取必要字节，不整包下载；
- 传输任务落盘，进程被杀后可以恢复。

---

<a id="version"></a>

## 当前版本

| 项目 | 值 |
|---|---|
| 版本名 | `6.1.0.180805737` |
| versionCode | `180805737` |
| 包名 | `com.cmzc.xj_file.app` |
| 最低系统 | Android 10（API 29） |
| 目标 SDK | 34 |
| 编译 SDK | 34 |
| 当前 Release | `XJFile-6.1.0.180805737-release.apk` |
| Release SHA-256 | `17E9166F269F5ADFDAEAD5D2A28B2E9635828C21E33DA157E398BFFFFA10D012` |

GitHub Releases：

<https://github.com/ChenMoZhongChen/XJFile/releases>

### 6.1 更新重点

- 右上角新增「排列方式」：名称/大小/修改时间/类型、升序/降序、文件夹优先；
- 排列方式可只对当前目录生效，也可对所有目录生效，并永久保存；
- 网络连接长按菜单新增「修改」，可修改名称、IP、端口、用户名、密码、共享名、私钥等；
- 增加 Root 环境检测：没有 root 环境时完全静默；检测到 su 后后台申请一次权限；
- 其余 6.0 能力保持：
  - 大文本编辑器改为 80 行一页的窗口化编辑；
  - 跨页拖选和跨页连续光标；
  - 远程文本保存冲突检测；
  - 压缩包浏览、预览、解压；
  - 内置 PDF 查看器；
  - 图片/视频缩略图按显示像素解码；
  - APK/EXE 真实图标，SMB/SFTP 只读必要字节；
  - 活跃传输任务持久化与恢复后移动补删源。

---

<a id="features"></a>

## 功能总览

| 模块 | 能力 |
|---|---|
| 文件管理 | 双栏、本地/SMB/SFTP、复制/移动/删除/重命名/新建/属性/分享 |
| 排列方式 | 名称/大小/修改时间/类型、升降序、文件夹优先、当前目录/所有目录 |
| 网络连接 | SMB/SFTP 连接管理、长按修改连接详情 |
| 传输 | 多车道、暂停/恢复/取消、冲突处理、断点续传、任务落盘、恢复后移动补删源 |
| 压缩 | ZIP/ZIPX/TAR/TGZ/7Z/GZ/XZ 创建与读取，压缩包内浏览、预览、解压 |
| 查看器 | PDF、图片、相册、音频、视频、后台音频、视频缩略图条 |
| 文本 | 编码识别、语法高亮、查找替换、保存冲突检测、16 MB 上限、窗口化大文件编辑 |
| 图标 | 图片/视频缩略图、APK/EXE 真实图标、本地与 SMB/SFTP 按需读取 |
| 光盘 | CD/DVD/BD/M-DISC 卷标、容量、音频 CD TOC、DVD/BD 视频检测 |
| 外观 | 深/浅色、模块颜色、文件图标颜色开关、动画开关 |

---

<a id="dual-pane"></a>

## 双栏文件管理

- 左右两栏各自维护路径、历史、前进/后退栈和焦点；
- 支持本地存储、SMB、SFTP 三种来源；
- 两栏之间可以直接复制、移动、比较和互相跳转；
- 复制/移动前统一展开目录，保留相对层级；
- 冲突处理：覆盖、跳过、重命名、保留两者；
- 横滑多选、长按菜单、全选、复制路径；
- 最近路径、快捷目录、常用文件夹；
- 目录和文件按名称、大小、时间、类型排序；
- 「排列方式」支持升序/降序、文件夹优先，可只对当前目录或所有目录生效并持久化；
- 网络连接长按可修改名称、IP、端口、用户名、密码、共享名、私钥等；
- 文件属性、分享、打开方式选择；
- 长按「打开方式」可以强制选择文本编辑器或系统应用。

远程路径和本地路径共用同一套文件栏 UI，但底层由不同的 `RemoteFileSystem` 实现处理。

---

<a id="transfer"></a>

## 传输系统

传输由自研 `TransferManager` 负责，不在 UI 协程里直接复制。

### 队列与并发

- 一次复制/移动 = 一个批次；
- 批次内多个文件可以并行传输，默认最多 3 条车道；
- 小文件不会被大文件完全堵住；
- 支持批次级和单文件级暂停/恢复/取消；
- 传输进度、速度、剩余时间显示在任务抽屉。

### 断点续传

- 传输前记录源/目标大小和断点；
- 支持服务端偏移读写的协议可以真正续传；
- 目标协议不支持续写时自动降级为从头传，不会静默截断已有文件；
- 传输完成后计算 SHA-256 做校验；
- 校验不通过时不会把任务标记为完成。

### 冲突处理

覆盖、跳过、自动重命名、保留两者都在入队前决定，避免传到一半再问。

### 进程被杀后的恢复

- 活跃任务会写入 `SharedPreferences`；
- 进程重启后按 `srcTag` / `dstTag` 重新建立文件系统并重新入队；
- 已经完成的文件不会重复传；
- **恢复后的移动任务**：等该顶层源下的所有文件都完成并通过校验后，自动补做删源；
- 任何一个文件失败、取消或被用户移除，都会保留源文件，不执行删源。

---

<a id="archives"></a>

## 压缩包

### 可读取

- ZIP / ZIPX
- TAR
- TGZ / TAR.GZ
- 7Z
- GZ
- XZ

压缩包可以像目录一样点进去浏览，并支持：

- 文本预览/编辑；
- 图片预览；
- PDF 预览；
- 音频/视频播放；
- 单个文件解压；
- 全部解压到对侧文件栏。

### 可创建

- ZIP
- ZIPX
- TAR
- TGZ
- 7Z
- GZ
- XZ

GZ/XZ 只支持单文件；多文件请使用 TAR/TGZ。

本地和远程都可以输出压缩包。远程压缩包先在本地 cache 生成，再上传到目标，避免 SFTP 同一通道同时读写的争用。

### 不支持

- RAR 创建：格式专有，不实现；
- LZH / ZPAQ 创建：格式公开，但当前没有合适的 Android 写入库；
- RAR / LZH / ZPAQ 读取：同样没有内置解析器，会明确提示不支持，不用其他格式冒充。

---

<a id="viewers"></a>

## 查看器

### PDF

- 基于 Android `PdfRenderer`；
- 支持翻页按钮和左右滑动；
- 不依赖外部 PDF 应用；
- 大文件按页渲染，不一次性把全部页面转成图片。

### 图片

- 单张图片查看；
- 相册网格；
- 图片/视频混排；
- 缩放、滑动切换；
- 支持常见图片格式，以及 SVG/TIFF/ICO/RAW 等预览路径。

### 音频 / 视频

- Media3 ExoPlayer；
- 视频页底部缩略图条；
- 音频页复用 CD 页布局；
- 后台音频：前台服务 + MediaSession + 通知栏控制；
- 支持从本地、SMB、SFTP 打开媒体；
- 远程媒体支持边下边播和 seek（由 `PlaybackSource` 统一处理）。

---

<a id="icons"></a>

## 文件图标与缩略图

### 图片/视频缩略图

- 文件列表图标位直接显示缩略图；
- 只对当前组合出来的可见行加载；
- 本地图片使用 `BitmapFactory` 按采样率解码，按实际显示像素的 2 倍取图；
- 视频用 `MediaMetadataRetriever` 取帧；
- 图片并发 3 路，视频并发 1 路；
- 内存缓存最多 160 张小图；
- 滚出屏幕的请求可取消。

### APK / EXE 真实图标

- **本地 APK**：优先直接读 APK 资源表，失败再走 `PackageManager`；
- **本地 EXE**：解析 PE 的 `RT_GROUP_ICON` / `RT_ICON`，支持 PNG 和常见 DIB 位图；
- **SMB / SFTP APK**：只读 ZIP 中央目录，再按偏移读取候选 launcher 图标；
- **SMB / SFTP EXE**：只读 PE 头和资源节，拼成稀疏临时文件解析；
- 不整包下载远程 APK/EXE；
- 没有真实图标才回退默认 APK 机器人或 Windows 通用程序图标。

**颜色规则：**

- 探测到的真实 APK/EXE 图标**始终保留原色**，不受「文件图标颜色」开关影响；
- 软件自己生成的默认图标、文件后缀底色、压缩包/媒体类型图标才受开关影响；
- 开关关闭时，合成图标去彩色，真实图标不动。

### 文件类型颜色

- 文本/代码：蓝色
- PDF：红色
- 压缩包：棕色，不显示 ZIP 字样
- 图片：绿色
- 音频：橙色
- 视频：紫色
- BIN/Office/APK/未知二进制：灰色
- 后缀字母从图标中镂空，不是白色贴字

---

<a id="editor"></a>

## 文本编辑器

### 编码

- BOM 优先；
- ICU4J 编码探测；
- 严格 UTF-8 校验；
- 支持 UTF-8、UTF-16、UTF-32、GB18030、BIG5、Shift_JIS、EUC-JP、EUC-KR、Windows-1252、ISO-8859-1；
- 保留原始 BOM 和换行风格（LF / CRLF / CR）；
- 保存前比较远程文件的 mtime/size，冲突时提供：
  - 保存本地副本；
  - 仍然覆盖；
  - 取消。

### 编辑

- 行号；
- 查找/替换；
- 语法高亮；
- 未保存确认；
- 编码切换；
- 保存；
- 已知文本后缀直接进入编辑器，未知后缀默认也按文本打开；
- Office 系列不会误入文本编辑器；PDF 走内置查看器。

### 大文件模式

超过 20 万字符进入窗口化编辑：

- 每 80 行一页；
- `LazyColumn` 只组合视口附近的页；
- 不做整篇 `BasicTextField`；
- 关闭语法高亮；
- 单文件上限 16 MB；
- 页内直接编辑；
- 支持跨页拖选：拖到页边界时自动并页；
- 支持跨页连续光标：左右方向键到页边界时并页，光标继续移动；
- 保存时把页拼回完整文本。

---

<a id="optical"></a>

## CD、DVD、BD 与 M-DISC

光盘探测通过 SFTP 连接的 PC 侧脚本完成，不依赖 Android 设备直连光驱。

- 识别光盘卷标、容量和介质类型；
- 音频 CD 读取 TOC，按扇区数计算每轨时长；
- 音频 CD 播放，支持后台播放；
- DVD / BD 视频检测；
- M-DISC 识别；
- 按盘并行探测并设置超时，空驱动器不会拖慢整个磁盘列表；
- PC 侧脚本位于 `app/src/main/assets/pc/`。

---

<a id="settings"></a>

## 设置与外观

- 深色/浅色/跟随系统；
- 模块颜色（文件夹、按钮）可覆盖；
- 文件图标颜色开关；
- 动画总开关；
- 常用文件夹；
- 网络连接管理（SMB / SFTP），支持长按修改连接详情；
- Root 环境检测：没有 root 时静默，有 su 时后台申请权限；
- 传输任务抽屉；
- 关于页、开源软件声明、许可证详情；
- 内置源码包导出。

---

<a id="stack"></a>

## 技术栈

| 用途 | 技术 |
|---|---|
| 语言 / UI | Kotlin 2.0.21、Jetpack Compose、Material 3 |
| 本地/远程文件 | LocalFileSystem、SMBJ、JSch（SFTP）、jcifs-ng |
| 传输 | 自研 `TransferManager`，前台服务 + 断点续传 |
| 媒体 | AndroidX Media3 / ExoPlayer |
| 文本编码 | ICU4J |
| 压缩 | JDK ZIP、Apache Commons Compress、XZ for Java |
| 图片 | Android ImageDecoder、AndroidSVG、TIFF/ICO/RAW 预览 |
| 构建 | AGP 8.13.0、Gradle 9.5.0 |

---

<a id="structure"></a>

## 代码结构

```text
app/src/main/java/com/cmzc/xj_file/app/
├─ vm/MainViewModel.kt            # 双栏状态、查看器、编辑器、压缩、传输
├─ core/
│  ├─ fs/                         # 本地 / SMB / SFTP
│  ├─ transfer/                   # 传输、断点、冲突、任务落盘
│  ├─ playback/                   # Media3、前台播放服务、媒体源
│  ├─ text/                       # 编码内核、文本类型
│  ├─ archive/                    # 压缩格式、读取器、写入器
│  ├─ disk/                       # 光盘 / 磁盘探测
│  ├─ image/                      # 缩略图、APK/EXE 图标
│  └─ cd/                         # CD TOC、WAV/PCM
├─ ui/
│  ├─ MainScreen.kt               # 主界面
│  ├─ components/                 # 文件栏、任务抽屉、缩略图条
│  ├─ editor/                     # 文本编辑、语法高亮、大文件分页
│  ├─ player/                     # 视频/音频播放页
│  ├─ gallery/                    # 相册网格
│  └─ settings/                   # 设置、开源声明、许可证
└─ assets/pc/                     # PC 侧磁盘/光盘/M-DISC 探测脚本
```

---

<a id="build"></a>

## 构建

### 环境

- JDK 17
- Android SDK Platform 34
- Android Build Tools
- Windows / macOS / Linux

### Debug

Windows：

```bat
gradlew.bat --no-watch-fs assembleDebug
```

macOS / Linux：

```bash
./gradlew assembleDebug
```

输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Release

```bat
gradlew.bat --no-watch-fs assembleRelease
```

输出：

```text
app/build/outputs/apk/release/app-release.apk
```

Release 需要自己的签名配置；不要把 keystore 和密码提交到仓库。

Windows 上建议保留 `--no-watch-fs`，避免 Gradle 文件系统监听在某些机器上卡住。

---

<a id="install"></a>

## 安装与权限

安装后需要授予「所有文件访问」权限，否则无法浏览本地存储。

主要权限：

- `MANAGE_EXTERNAL_STORAGE`：本地文件管理；
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC`：后台传输；
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`：后台音频；
- `POST_NOTIFICATIONS`：传输和播放通知；
- `WAKE_LOCK`：传输期间保持 CPU/WiFi。

SMB/SFTP 连接配置保存在本机，不随 APK 或源码包分发。

---

<a id="limits"></a>

## 已知限制

- **Office 不内置渲染**：doc、docx、xls、xlsx、ppt、pptx 仍交系统应用；
- **RAR / LZH / ZPAQ 创建**：RAR 专有；LZH/ZPAQ 暂无合适的 Android 写入库；
- **RAR / LZH / ZPAQ 读取**：没有内置解析器，会明确提示不支持；
- **超大文本编辑器**：上限 16 MB；超过 20 万字符关闭语法高亮；
- **远程 APK 图标**：优先按 `ic_launcher` / `app_icon` / `icon` / `logo` 等文件名找候选；只有 adaptive icon、没有 legacy PNG/WebP 的 APK 可能回退默认图标；
- **SMB/SFTP 图标读取**：依赖服务端偏移读；不支持偏移读的协议实现会回退默认图标；
- **光盘探测**：需要 PC 侧脚本和 SFTP 连接，不是 Android 直连光驱；
- **传输恢复**：恢复后移动任务的删源收尾已补上；如果用户在恢复期间移除或取消任务，会保留源文件；
- **大文本跨页**：通过并页实现跨页拖选/光标，跨页后页数会减少，适合选择而不是长期保持分页。

---

<a id="license"></a>

## 开源与许可证

XJ File 自身代码使用 [MIT License](LICENSE) 发布。

Copyright (c) 2026 XJoffice ChenMoZhongChen

### Logo 与品牌图形例外

<img src="app/src/main/res/drawable/xj.png" alt="XJ File Logo" width="120">

「新金文件管理器 / XJ File」应用图标、Logo 及相关品牌图形不包含在 MIT License 授权范围内，版权归 XJoffice ChenMoZhongChen 所有。未经书面许可，不得用于其他项目的图标、品牌、宣传或衍生作品。

### 第三方组件

第三方组件仍归各自版权人所有，并继续适用其原始许可证，不因本项目采用 MIT 而被重新授权。详见：

- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- 应用内「设置 → 关于 → 开源软件声明」
- APK 内 `assets/licenses/`
- 源码包：[app/src/main/assets/licenses/sources/XJFile-6.1.0.180805737-source.zip](app/src/main/assets/licenses/sources/XJFile-6.1.0.180805737-source.zip)

### jcifs-ng

`jcifs-ng` 使用 LGPL-2.1，是当前主要的非宽松依赖，用于 SMB 共享枚举。项目按 LGPL-2.1 提供许可证全文和可重新构建的源码说明。

---

<a id="thanks"></a>

## 致谢

感谢所有第三方开源项目的作者和维护者，尤其是：

- AndroidX / Jetpack Compose
- Media3 / ExoPlayer
- SMBJ
- JSch
- jcifs-ng
- Apache Commons Compress
- XZ for Java
- ICU4J
- AndroidSVG

以及所有参与测试、反馈和提出建议的人。

---

<a id="links"></a>

## 相关链接

- Releases：<https://github.com/ChenMoZhongChen/XJFile/releases>
- 第三方声明：[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- 贡献指南：[CONTRIBUTING.md](CONTRIBUTING.md)
- 许可证：[LICENSE](LICENSE)

<sub>本文档由Agent总结</sub>

---

# XJ File (Xinjin File Manager)

<a id="en-us"></a>

[简体中文](#zh-cn) | [English](#en-us)

> A dual-pane Android file manager for local storage, SMB, and SFTP, with built-in archive browsing, PDF/image/audio/video previews, CD/DVD/BD/M-DISC detection, a windowed large-text editor, and a recoverable transfer queue.

![Version](https://img.shields.io/badge/version-6.1.0.180805737-2C6FDB)
![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![License](https://img.shields.io/badge/license-MIT-yellow)

---

## Contents

- [Introduction](#en-intro)
- [Current Version](#en-version)
- [Feature Overview](#en-features)
- [Dual-pane File Management](#en-dual-pane)
- [Transfer System](#en-transfer)
- [Archives](#en-archives)
- [Viewers](#en-viewers)
- [Icons and Thumbnails](#en-icons)
- [Text Editor](#en-editor)
- [CD, DVD, BD, and M-DISC](#en-optical)
- [Settings and Appearance](#en-settings)
- [Tech Stack](#en-stack)
- [Code Structure](#en-structure)
- [Build](#en-build)
- [Installation and Permissions](#en-install)
- [Known Limitations](#en-limits)
- [License](#en-license)
- [Acknowledgements](#en-thanks)
- [Links](#en-links)

---

<a id="en-intro"></a>

## Introduction

XJ File is a dual-pane file manager that puts local and remote file management in one screen:

- either pane can be local storage, an SMB share, or an SFTP host;
- copy, move, compress, extract, preview, and edit directly between panes;
- no need to download a remote file before working with it;
- large files, text, archives, media, and transfers each have their own processing path, so one scenario does not slow down the whole app.

It is not a demo that puts everything into one list. Files are handled according to type and source:

- images/videos use thumbnails and Media3;
- PDFs use the system `PdfRenderer`;
- text uses encoding detection and the editor;
- archives use Apache Commons Compress / JDK ZIP / XZ;
- remote APK/EXE icons read only the required bytes, not the whole file;
- transfer tasks are persisted and can be restored after process death.

---

<a id="en-version"></a>

## Current Version

| Item | Value |
|---|---|
| Version name | `6.1.0.180805737` |
| versionCode | `180805737` |
| Package | `com.cmzc.xj_file.app` |
| Minimum Android | Android 10 (API 29) |
| Target SDK | 34 |
| Compile SDK | 34 |
| Current Release | `XJFile-6.1.0.180805737-release.apk` |
| Release SHA-256 | `17E9166F269F5ADFDAEAD5D2A28B2E9635828C21E33DA157E398BFFFFA10D012` |

GitHub Releases:

<https://github.com/ChenMoZhongChen/XJFile/releases>

### What's New in 6.1

- New "Sort" entry in the top-right menu: name/size/modified/type, ascending/descending, folders first;
- sort settings can apply to the current directory only or to all directories, and persist;
- the network-connection long-press menu now has "Edit" for name, IP, port, username, password, share name, private key, and more;
- Root detection: completely silent when no root environment exists; when `su` is present, request access once in the background;
- existing 6.0 capabilities remain:
  - windowed large-text editor with 80-line pages;
  - cross-page drag selection and cursor movement;
  - remote text save conflict detection;
  - archive browsing, preview, and extraction;
  - built-in PDF viewer;
  - display-size image/video thumbnails;
  - real APK/EXE icons, with SMB/SFTP reading only necessary bytes;
  - persistent transfers and restored move source cleanup.

---

<a id="en-features"></a>

## Feature Overview

| Module | Capabilities |
|---|---|
| File management | Dual pane, local/SMB/SFTP, copy/move/delete/rename/create/properties/share |
| Sorting | Name/size/modified/type, ascending/descending, folders first, current/all directories |
| Network | SMB/SFTP connection management, long-press editing of connection details |
| Transfers | Multiple lanes, pause/resume/cancel, conflict handling, resume, persistence, restored move cleanup |
| Archives | Create/read ZIP/ZIPX/TAR/TGZ/7Z/GZ/XZ; browse, preview, extract |
| Viewers | PDF, images, gallery, audio, video, background audio, video thumbnail strip |
| Text | Encoding detection, syntax highlighting, find/replace, save conflict handling, 16 MB limit, windowed large files |
| Icons | Image/video thumbnails, real APK/EXE icons, local and SMB/SFTP on-demand reads |
| Optical | CD/DVD/BD/M-DISC label, capacity, audio CD TOC, DVD/BD video detection |
| Appearance | Dark/light theme, module colors, icon color toggle, animation toggle |

---

<a id="en-dual-pane"></a>

## Dual-pane File Management

- Each pane keeps its own path, history, back/forward stacks, and focus;
- local storage, SMB, and SFTP are supported in either pane;
- copy, move, compare, and jump between panes;
- directories are expanded before transfer to preserve relative structure;
- conflict handling: overwrite, skip, rename, keep both;
- horizontal-swipe multi-select, long-press menu, select all, copy path;
- recent paths, shortcut folders, and frequently used folders;
- sorting by name, size, time, or type;
- "Sort" supports ascending/descending and folders-first, with current-directory or all-directory persistence;
- long-press a network connection to edit name, IP, port, username, password, share name, private key, and more;
- file properties, sharing, and open-with selection;
- long-press "Open with" to force the text editor or a system app.

Remote and local paths share the same pane UI, but use different `RemoteFileSystem` implementations underneath.

---

<a id="en-transfer"></a>

## Transfer System

Transfers are managed by the custom `TransferManager`, not by UI coroutines.

### Queue and Concurrency

- one copy/move operation = one batch;
- files within a batch run in parallel, up to three lanes by default;
- small files are not completely blocked by a large file;
- batch-level and file-level pause/resume/cancel;
- progress, speed, and remaining time in the task drawer.

### Resume

- source/target sizes and checkpoints are recorded before transfer;
- protocols that support server-side offset reads/writes can truly resume;
- if the target protocol cannot append, the transfer safely restarts from zero instead of truncating existing data;
- SHA-256 is computed and verified after transfer;
- a task is not marked complete if verification fails.

### Conflicts

Overwrite, skip, auto-rename, and keep-both are decided before queuing, not halfway through a transfer.

### Restore After Process Death

- active tasks are written to `SharedPreferences`;
- after restart, `srcTag` / `dstTag` recreate the file systems and re-queue the tasks;
- already completed files are not transferred again;
- **restored move tasks** delete the source after every file under that top-level source completes and verifies;
- if any file fails, is cancelled, or is removed by the user, the source is preserved.

---

<a id="en-archives"></a>

## Archives

### Read

- ZIP / ZIPX
- TAR
- TGZ / TAR.GZ
- 7Z
- GZ
- XZ

Archives can be browsed like directories:

- text preview/editing;
- image preview;
- PDF preview;
- audio/video playback;
- single-file extraction;
- extract all to the opposite pane.

### Create

- ZIP
- ZIPX
- TAR
- TGZ
- 7Z
- GZ
- XZ

GZ/XZ are single-file formats; use TAR/TGZ for multiple files.

Archives can be created locally or remotely. A remote archive is built in the local cache first and then uploaded, avoiding SFTP read/write contention on one channel.

### Not Supported

- RAR creation: proprietary format, not implemented;
- LZH / ZPAQ creation: open formats, but no suitable Android writer is available;
- RAR / LZH / ZPAQ reading: no built-in parser; the app explicitly reports this rather than pretending.

---

<a id="en-viewers"></a>

## Viewers

### PDF

- Android `PdfRenderer`;
- page buttons and horizontal swipe;
- no external PDF app required;
- pages are rendered on demand.

### Images

- single-image viewer;
- gallery grid;
- mixed image/video browsing;
- zoom and swipe navigation;
- common image formats plus SVG/TIFF/ICO/RAW preview paths.

### Audio / Video

- Media3 ExoPlayer;
- thumbnail strip on the video page;
- the audio page reuses the CD page layout;
- background audio: foreground service + MediaSession + notification controls;
- open media from local, SMB, or SFTP;
- remote playback supports progressive download and seeking through `PlaybackSource`.

---

<a id="en-icons"></a>

## Icons and Thumbnails

### Image/Video Thumbnails

- shown directly in the file-list icon slot;
- only composed/visible rows request thumbnails;
- local images use `BitmapFactory` with `inSampleSize` at 2× the displayed pixel size;
- videos use `MediaMetadataRetriever`;
- three image lanes and one video lane;
- in-memory cache of up to 160 small bitmaps;
- off-screen requests can be cancelled.

### Real APK / EXE Icons

- **Local APK**: read the APK resource table first, then fall back to `PackageManager`;
- **Local EXE**: parse PE `RT_GROUP_ICON` / `RT_ICON`, including PNG and common DIB bitmaps;
- **SMB / SFTP APK**: read the ZIP central directory, then only the candidate launcher entries;
- **SMB / SFTP EXE**: read only the PE header and resource section, then use a sparse temp file for parsing;
- remote APK/EXE files are not downloaded in full;
- if no real icon exists, fall back to the default APK robot or Windows generic application icon.

**Color rules:**

- detected real APK/EXE icons **always keep their original colors**, regardless of the "file icon colors" toggle;
- only synthetic default icons, extension backgrounds, and archive/media type icons follow the toggle;
- when the toggle is off, synthetic icons are desaturated while real icons are unchanged.

### File-type Colors

- text/code: blue
- PDF: red
- archive: brown, no ZIP text
- image: green
- audio: orange
- video: purple
- BIN/Office/APK/unknown binary: gray
- extension letters are cut out of the icon, not drawn as a white sticker

---

<a id="en-editor"></a>

## Text Editor

### Encoding

- BOM priority;
- ICU4J detection;
- strict UTF-8 validation;
- UTF-8, UTF-16, UTF-32, GB18030, BIG5, Shift_JIS, EUC-JP, EUC-KR, Windows-1252, ISO-8859-1;
- preserves the original BOM and newline style (LF / CRLF / CR);
- compares remote mtime/size before saving and offers:
  - save a local copy;
  - overwrite anyway;
  - cancel.

### Editing

- line numbers;
- find/replace;
- syntax highlighting;
- unsaved-change confirmation;
- encoding switch;
- save;
- known text suffixes open directly; unknown suffixes are treated as text by default;
- Office files are not routed to the text editor; PDFs use the built-in viewer.

### Large-file Mode

Above 200K characters:

- 80 lines per page;
- `LazyColumn` composes only pages near the viewport;
- no full-document `BasicTextField`;
- syntax highlighting is disabled;
- maximum file size is 16 MB;
- pages are editable in place;
- cross-page drag selection merges the next/previous page at the boundary;
- continuous cursor movement: left/right at a page boundary merges the adjacent page;
- pages are joined back into the full text on save.

---

<a id="en-optical"></a>

## CD, DVD, BD, and M-DISC

Optical detection runs through PC-side scripts over an SFTP connection; it does not require a directly attached Android optical drive.

- disc label, capacity, and media type;
- audio CD TOC with per-track duration from sector counts;
- audio CD playback, including background playback;
- DVD/BD video detection;
- M-DISC identification;
- parallel probing with timeouts so an empty drive does not slow the disk list;
- PC scripts live in `app/src/main/assets/pc/`.

---

<a id="en-settings"></a>

## Settings and Appearance

- dark/light/follow-system;
- module colors (folders, buttons);
- file icon color toggle;
- global animation toggle;
- frequently used folders;
- network connections (SMB / SFTP), with long-press editing of connection details;
- Root environment detection: silent when no root is present, requests access in the background when `su` exists;
- transfer task drawer;
- About page, open-source notices, license details;
- built-in source package export.

---

<a id="en-stack"></a>

## Tech Stack

| Purpose | Technology |
|---|---|
| Language / UI | Kotlin 2.0.21, Jetpack Compose, Material 3 |
| Local/remote files | LocalFileSystem, SMBJ, JSch (SFTP), jcifs-ng |
| Transfers | Custom `TransferManager`, foreground service, resume |
| Media | AndroidX Media3 / ExoPlayer |
| Text encoding | ICU4J |
| Archives | JDK ZIP, Apache Commons Compress, XZ for Java |
| Images | Android ImageDecoder, AndroidSVG, TIFF/ICO/RAW preview |
| Build | AGP 8.13.0, Gradle 9.5.0 |

---

<a id="en-structure"></a>

## Code Structure

```text
app/src/main/java/com/cmzc/xj_file/app/
├─ vm/MainViewModel.kt            # panes, viewers, editor, archives, transfers
├─ core/
│  ├─ fs/                         # local / SMB / SFTP
│  ├─ transfer/                   # transfer, resume, conflicts, task persistence
│  ├─ playback/                   # Media3, foreground service, media sources
│  ├─ text/                       # encoding core, text types
│  ├─ archive/                    # archive formats, readers, writers
│  ├─ disk/                       # optical/disk detection
│  ├─ image/                      # thumbnails, APK/EXE icons
│  └─ cd/                         # CD TOC, WAV/PCM
├─ ui/
│  ├─ MainScreen.kt               # main screen
│  ├─ components/                 # panes, task drawer, thumbnail strip
│  ├─ editor/                     # text editor, highlighting, paging
│  ├─ player/                     # video/audio pages
│  ├─ gallery/                    # gallery grid
│  └─ settings/                   # settings, notices, licenses
└─ assets/pc/                     # PC-side disk/optical/M-DISC scripts
```

---

<a id="en-build"></a>

## Build

### Requirements

- JDK 17
- Android SDK Platform 34
- Android Build Tools
- Windows / macOS / Linux

### Debug

Windows:

```bat
gradlew.bat --no-watch-fs assembleDebug
```

macOS / Linux:

```bash
./gradlew assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Release

```bat
gradlew.bat --no-watch-fs assembleRelease
```

Output:

```text
app/build/outputs/apk/release/app-release.apk
```

Release builds need your own signing configuration. Do not commit the keystore or passwords.

On Windows, keep `--no-watch-fs` to avoid Gradle file-system watching getting stuck on some machines.

---

<a id="en-install"></a>

## Installation and Permissions

After installation, grant "All files access" (MANAGE_EXTERNAL_STORAGE); without it, local storage cannot be browsed.

Main permissions:

- `MANAGE_EXTERNAL_STORAGE`: local file management;
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC`: background transfers;
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: background audio;
- `POST_NOTIFICATIONS`: transfer/playback notifications;
- `WAKE_LOCK`: keep CPU/Wi-Fi during transfers.

SMB/SFTP connection settings stay on the device and are not included in the APK or source package.

---

<a id="en-limits"></a>

## Known Limitations

- **No built-in Office rendering**: doc, docx, xls, xlsx, ppt, pptx still use system apps;
- **RAR / LZH / ZPAQ creation**: RAR is proprietary; LZH/ZPAQ have no suitable Android writer;
- **RAR / LZH / ZPAQ reading**: no built-in parser; the app reports unsupported;
- **Large text editor**: 16 MB maximum; syntax highlighting is off above 200K characters;
- **Remote APK icons**: candidate icons are found by `ic_launcher` / `app_icon` / `icon` / `logo` names; an adaptive-only APK without a legacy PNG/WebP may fall back to the default icon;
- **SMB/SFTP icon reads**: require server-side offset reads; implementations without it fall back to the default icon;
- **Optical detection**: requires PC-side scripts and an SFTP connection, not a direct Android optical drive;
- **Transfer restore**: restored move cleanup is implemented; if the user removes or cancels a task during restore, the source is preserved;
- **Large-text cross-page**: implemented by merging pages; page count decreases while selecting, which is intended for selection rather than long-term pagination.

---

<a id="en-license"></a>

## License

XJ File's own code is released under the [MIT License](LICENSE).

Copyright (c) 2026 XJoffice ChenMoZhongChen

### Logo and Brand Graphics Exception

The Xinjin File Manager / XJ File application icon, logo, and related brand graphics are **not** covered by the MIT License. Copyright remains with XJoffice ChenMoZhongChen. They may not be used as icons, branding, promotional material, or derivative artwork for other projects without written permission.

### Third-party Components

Third-party components remain under their own licenses and are not re-licensed by this project's MIT license. See:

- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- In-app "Settings → About → Open-source notices"
- APK `assets/licenses/`
- Source package: [app/src/main/assets/licenses/sources/XJFile-6.1.0.180805737-source.zip](app/src/main/assets/licenses/sources/XJFile-6.1.0.180805737-source.zip)

### jcifs-ng

`jcifs-ng` is licensed under LGPL-2.1 and is used for SMB share enumeration. The project provides the full license text and rebuild instructions as required.

---

<a id="en-thanks"></a>

## Acknowledgements

Thanks to the authors and maintainers of the open-source projects used here, especially:

- AndroidX / Jetpack Compose
- Media3 / ExoPlayer
- SMBJ
- JSch
- jcifs-ng
- Apache Commons Compress
- XZ for Java
- ICU4J
- AndroidSVG

And to everyone who tested, reported issues, or contributed suggestions.

---

<a id="en-links"></a>

## Links

- Releases: <https://github.com/ChenMoZhongChen/XJFile/releases>
- Third-party notices: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- Contributing: [CONTRIBUTING.md](CONTRIBUTING.md)
- License: [LICENSE](LICENSE)

<sub>本文档由Agent总结</sub>
