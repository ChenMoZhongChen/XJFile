# XJ File（新金文件管理器）

<a id="zh-cn"></a>

[查看简体中文版本](#zh-cn)

[View English Version](#en-us)

一个 Android 双栏文件管理器，可浏览和修改本地存储、SMB、SFTP，可读取 CD、DVD、BD 与 M-DISC，并内置媒体播放器和文本编辑器。

![Platform](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-2024.02.01-4285F4?logo=jetpackcompose&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)

> 当前版本：`5.9.9.17128712`  
> 构建版本：`5.9.9.17128076`（APK 内实际版本；文件名/发布号可能不同）  
> 包名：`com.cmzc.xj_file.app`  
> 最低系统：Android 10（API 29）  
> 目标 SDK：34

---

## 功能

### 双栏文件管理

- 统一浏览和修改本地存储、SMB、SFTP；
- 左右两栏独立导航、前进/后退/上级/刷新/路径跳转；
- 复制、移动、删除、重命名、新建文件/文件夹、属性、分享；
- 横滑多选、长按操作菜单、全选、复制路径；
- 最近路径、快捷目录、常用文件夹；
- 传输任务抽屉：批次进度、暂停/恢复/取消、完成校验；
- 冲突处理：覆盖、跳过、重命名、保留两者；
- 支持断点续传。

### CD、DVD、BD 与 M-DISC（基于 SFTP 连接的远程介质探测）

- 光盘卷标、容量、介质类型识别；
- 音频 CD 读取 TOC，按扇区数计算每轨时长；
- 音频 CD 播放，支持后台播放；
- DVD / BD 视频检测；
- M-DISC 识别；
- 光盘探测按盘并行并设置超时，空驱动器不会拖垮整个磁盘列表。

### 查看器

- 视频/音频播放器（Media3 ExoPlayer）；
- 后台音频：前台服务 + MediaSession + 通知栏控制；
- 图片查看器、相册网格、图片/视频混排；
- 音频页复用 CD 页布局，播放列表为当前文件夹全部音频；
- 视频页底部缩略图条。

### 文本编辑器

- 编码识别：BOM 优先 + ICU4J + 严格 UTF-8 校验；
- 支持 UTF-8、UTF-16、UTF-32、GB18030、BIG5、Shift_JIS、EUC-JP、EUC-KR、Windows-1252、ISO-8859-1；
- 保留原始 BOM 和换行风格（LF / CR LF / CR）；
- 行号、查找/替换、未保存确认、编码切换、保存；
- 已知文本后缀 + **未知后缀默认按文本打开**；
- 长按「打开方式」可重新选择文本编辑器 / 系统应用；
- Office 系列（含 PDF）、常见二进制、音视频/图片不会误入文本编辑器；
- 远程文本读入内存，不落缓存；保存失败落 `Download/XjFile`；
- 语法高亮覆盖 C/C++/C#/Java/Kotlin/Python、HTML/XML/XSL、JavaScript/TypeScript、JSON、CSS、Shell 等。

### 压缩

- 长按多选/单选 →「压缩」→ 独立参数面板；
- 压缩包名称、类型、级别（仅存储 / 快速 / 标准 / 极限）；
- 明细区显示数量、文件合计大小、输出目录、层级保留说明；
- 类型：ZIP、ZIPX、TAR、TGZ、LZH、7Z、GZ、XZ、ZPAQ、RAR；
- 实际可创建：ZIP、ZIPX、TAR、TGZ、7Z、GZ、XZ；
- GZ/XZ 仅支持单文件；多文件请用 TAR/TGZ；
- LZH、ZPAQ、RAR 暂为无功能占位；
- 本地/远程都可输出；远程压缩包先在本机 cache 生成再上传，避免 SFTP 读写抢车道；
- **解压缩与预览压缩包将在后续版本实现。**

---

## 下载与安装

推荐从 GitHub Releases 下载最新 APK：

**https://github.com/ChenMoZhongChen/XJFile/releases**

也可以自行构建，见下方「构建」。

安装后需要授予「所有文件访问」权限，否则无法浏览本地存储。应用还使用：

- `MANAGE_EXTERNAL_STORAGE`：本地文件管理；
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC`：后台传输；
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`：后台音频播放；
- `POST_NOTIFICATIONS`：传输进度和播放控制通知；
- `WAKE_LOCK`：传输期间保持 CPU/WiFi。

---

## 构建

### 环境

- JDK 17
- Android SDK Platform 34
- Android Build Tools
- Windows / macOS / Linux 均可

### 命令

Windows：

```bat
gradlew.bat --no-watch-fs assembleDebug
```

macOS / Linux：

```bash
./gradlew assembleDebug
```

生成的 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

签名 Release：

```bat
gradlew.bat --no-watch-fs assembleRelease
```

输出：

```text
app/build/outputs/apk/release/app-release.apk
```

Windows 上建议保留 `--no-watch-fs`：Gradle 文件系统监听在某些机器上会卡在
`PosixFileSystemFunctions.listFileSystems` 扫描盘符。

Release 构建需要自己的签名配置；不要把密钥库和密码提交到仓库。

---

## 技术栈

| 用途 | 技术 |
|---|---|
| 语言 / UI | Kotlin、Jetpack Compose、Material 3 |
| 本地/远程文件 | LocalFileSystem、SMBJ、JSch (SFTP)、jcifs-ng |
| 传输 | 自研 TransferManager，前台服务 + 断点续传 |
| 媒体 | AndroidX Media3 / ExoPlayer |
| 文本编码 | ICU4J |
| 压缩 | JDK ZIP + Apache Commons Compress + XZ for Java |
| 图片 | Android ImageDecoder、AndroidSVG、TIFF/ICO/RAW 预览 |

### 代码结构

```text
app/src/main/java/com/cmzc/xj_file/app/
├─ vm/MainViewModel.kt            # 双栏状态、查看器、文本编辑、压缩、传输
├─ core/
│  ├─ fs/                         # 本地 / SMB / SFTP
│  ├─ transfer/                   # 传输、断点、冲突
│  ├─ playback/                   # Media3、前台播放服务、媒体源
│  ├─ text/                       # 编码内核、文本类型
│  ├─ archive/                    # 压缩格式与写入器
│  ├─ disk/                       # 光盘 / 磁盘探测
│  └─ cd/                         # CD TOC、WAV/PCM
├─ ui/
│  ├─ MainScreen.kt               # 主界面
│  ├─ components/                 # 文件栏、任务抽屉、缩略图条
│  ├─ editor/                     # 文本编辑与语法高亮
│  ├─ player/                     # 视频/音频播放页
│  ├─ gallery/                    # 相册网格
│  └─ settings/                   # 设置、开源声明、许可证详情
└─ assets/pc/                     # PC 侧磁盘/光盘/M-DISC 探测脚本
```

---

## 已知限制

- **Office 系列（含 PDF）不内置渲染**：doc、docx、xls、xlsx、ppt、pptx、pdf 仍交系统应用；
- **解压 / 压缩包浏览**：当前只能创建压缩包，不能点进压缩包浏览或解压；
- **RAR / LZH / ZPAQ 创建**：选项保留，但暂不可用；
- **超大文本编辑器**：文本上限 2MB；约 2 万字符以上时，Compose 可能不再显示语法高亮；
- **远程文本保存**：直接覆盖，没有 mtime/size 冲突检测；失败才落本地；
- **传输任务持久化**：进程被系统杀掉后，进行中的传输不会恢复。

---

## 后续版本计划

> **以下功能已列入后续版本，不在当前 5.9.9 中：**
>
> - **PDF 内置渲染**：将在后续版本实现。
> - **解压缩与压缩包预览**：将在后续版本实现。
> - **超大文本编辑器**：将在后续版本有所调整。

---

## 许可证

XJ File **自身代码**使用 [MIT License](LICENSE) 发布。

Copyright (c) 2026 XJoffice ChenMoZhongChen

### Logo 与品牌图形例外

<img src="app/src/main/res/drawable/xj.png" alt="新金文件管理器 Logo" width="120">

本仓库中的「新金文件管理器 / XJ File」应用图标、Logo 及相关品牌图形**不包含在 MIT License 的授权范围内**，版权归 XJoffice ChenMoZhongChen 所有。未经书面许可，不得将这些图形用于其他项目的图标、品牌、宣传或衍生作品；它们仅用于标识本项目及其官方发行版。

第三方组件仍归各自版权人所有，并继续适用其原始许可证，**不因本项目采用 MIT 而被重新授权**。详见：

- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- 应用内「设置 → 关于 → 开源软件声明」；
- APK 内 `assets/licenses/` 下的许可证全文。

### 特别说明：jcifs-ng

`jcifs-ng` 使用 **LGPL-2.1**，是当前唯一的非宽松依赖。它用于 SMB 共享枚举。

本项目按 LGPL-2.1 提供：

- `assets/licenses/lgpl-2.1.txt` 完整许可证；
- jcifs-ng 版权与许可证声明；
- `assets/licenses/sources/jcifs-ng-2.1.10-sources.jar` 完整对应源码；
- `assets/licenses/sources/XJFile-5.9.9.17128076-source.zip` 应用自身源码包；
- `assets/licenses/THIRD_PARTY_NOTICES.txt` 第三方 NOTICE 汇总；
- 应用内详情页「导出源码到 Download」；
- 上游源码地址：<https://github.com/AGNO3/jcifs-ng>

若你分发包含 jcifs-ng 的 APK，请同时遵守 LGPL-2.1 的源码与重新链接要求。
如果希望发行物只包含宽松许可证依赖，应移除或替换 jcifs-ng。

---

## 贡献

欢迎提交 Issue 和 Pull Request。

- 请保持现有双栏 / 文件系统抽象，不要在 UI 层直接拼协议；
- 修改前先看 [CONTRIBUTING.md](CONTRIBUTING.md)；
- 涉及第三方依赖或许可证时，同步更新 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)；
- 不要提交签名密钥、密码、个人服务器地址或测试账号。

---

## 致谢

感谢 AndroidX、Jetpack Compose、Media3、JSch、SMBJ、jcifs-ng、Bouncy Castle、ICU4J、Apache Commons Compress、XZ for Java、AndroidSVG、Kotlin、Gradle 等开源项目的作者与贡献者。

本项目与 7-Zip、WinRAR、WinZip 等产品无隶属关系；文中出现的格式名称仅用于说明兼容性。

---

<a id="en-us"></a>

> 英语翻译来自 DeepSeek。

# XJ File (Xinjin File Manager)

[View Chinese Version](#zh-cn)

An Android dual-pane file manager for browsing and modifying local storage, SMB, and SFTP, reading CD, DVD, BD, and M-DISC optical discs, with a built-in media player and text editor.

![Platform](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-2024.02.01-4285F4?logo=jetpackcompose&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)

> Current version: `5.9.9.17128712`  
> Build version: `5.9.9.17128076` (the version actually written into the APK; file names or release numbers may differ)  
> Package name: `com.cmzc.xj_file.app`  
> Minimum system: Android 10 (API 29)  
> Target SDK: 34

---

## Features

### Dual-pane file management

- Browse and modify files on local storage, SMB, and SFTP;
- Independent navigation in the left and right panes: forward, back, up, refresh, and path jump;
- Copy, move, delete, rename, create files/folders, properties, and share;
- Horizontal-swipe multi-select, long-press action menu, select all, and copy path;
- Recent paths, shortcut folders, and frequently used folders;
- Transfer-task drawer: batch progress, pause/resume/cancel, and completion verification;
- Conflict handling: overwrite, skip, rename, or keep both;
- Resumable transfers.

### CD, DVD, BD, and M-DISC (remote optical-disc detection over SFTP)

- Disc label, capacity, and media-type detection;
- Reads the table of contents (TOC) of audio CDs and calculates each track's duration from its sector count;
- Audio CD playback with background support;
- DVD/BD video detection;
- M-DISC detection;
- Optical-disc probing runs in parallel for each drive and uses timeouts, so a drive with no disc does not stall the entire disk list.

### Viewers

- Video/audio player (Media3 ExoPlayer);
- Background audio: foreground service + MediaSession + notification controls;
- Image viewer, gallery grid, and mixed image/video browsing;
- The audio screen reuses the CD page layout; the playlist contains all audio files in the current folder;
- Thumbnail strip at the bottom of the video screen.

### Text editor

- Encoding detection: BOM first, then ICU4J, with strict UTF-8 validation;
- Supports UTF-8, UTF-16, UTF-32, GB18030, BIG5, Shift_JIS, EUC-JP, EUC-KR, Windows-1252, and ISO-8859-1;
- Preserves the original BOM and line-ending style (LF / CRLF / CR);
- Line numbers, find/replace, unsaved-changes confirmation, encoding switching, and saving;
- Files with known text extensions open in the editor; unknown extensions are treated as text by default;
- Long-press "Open with" to choose the text editor or a system app again;
- Office documents and PDF files, common binary formats, audio/video, and images do not open in the text editor by mistake;
- Remote text is read into memory without a cache file; if saving fails, it falls back to `Download/XjFile`;
- Syntax highlighting covers C/C++/C#/Java/Kotlin/Python, HTML/XML/XSL, JavaScript/TypeScript, JSON, CSS, Shell, and more.

### Compression

- Long-press one or more items → "Compress" → dedicated options panel;
- Archive name, type, and compression level (Store / Fast / Standard / Ultra);
- Details show item count, total size, output directory, and hierarchy-preservation notes;
- Types: ZIP, ZIPX, TAR, TGZ, LZH, 7Z, GZ, XZ, ZPAQ, RAR;
- Currently creatable: ZIP, ZIPX, TAR, TGZ, 7Z, GZ, XZ;
- GZ/XZ support single files only; use TAR/TGZ for multiple files;
- LZH, ZPAQ, and RAR are placeholders without working compression;
- Output works locally and remotely; a remote archive is first created in the local cache and then uploaded, so SFTP reads and writes do not compete;
- **Extraction and archive preview are planned for a future release.**

---

## Download and installation

The latest APK is available from GitHub Releases:

**https://github.com/ChenMoZhongChen/XJFile/releases**

You can also build it yourself; see "Build" below.

After installation, grant "All files access"; otherwise local storage cannot be browsed. The app also uses:

- `MANAGE_EXTERNAL_STORAGE`: local file management;
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC`: background transfers;
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: background audio playback;
- `POST_NOTIFICATIONS`: transfer progress and playback-control notifications;
- `WAKE_LOCK`: keeps the CPU/Wi-Fi active during transfers.

---

## Build

### Requirements

- JDK 17
- Android SDK Platform 34
- Android Build Tools
- Windows, macOS, or Linux

### Commands

Windows:

```bat
gradlew.bat --no-watch-fs assembleDebug
```

macOS / Linux:

```bash
./gradlew assembleDebug
```

Generated APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Signed release:

```bat
gradlew.bat --no-watch-fs assembleRelease
```

Output:

```text
app/build/outputs/apk/release/app-release.apk
```

On Windows, keeping `--no-watch-fs` is recommended: Gradle's file-system watcher can otherwise hang while scanning drive letters in `PosixFileSystemFunctions.listFileSystems`.

A release build needs your own signing configuration; do not commit the keystore or passwords to the repository.

---

## Tech stack

| Purpose | Technology |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| Local/remote files | LocalFileSystem, SMBJ, JSch (SFTP), jcifs-ng |
| Transfers | Custom TransferManager, foreground service + resumable transfers |
| Media | AndroidX Media3 / ExoPlayer |
| Text encoding | ICU4J |
| Compression | JDK ZIP + Apache Commons Compress + XZ for Java |
| Images | Android ImageDecoder, AndroidSVG, TIFF/ICO/RAW preview |

### Code structure

```text
app/src/main/java/com/cmzc/xj_file/app/
├─ vm/MainViewModel.kt            # dual-pane state, viewers, text editor, compression, transfers
├─ core/
│  ├─ fs/                         # local / SMB / SFTP
│  ├─ transfer/                   # transfers, resume, conflicts
│  ├─ playback/                   # Media3, foreground playback service, media sources
│  ├─ text/                       # encoding core, text types
│  ├─ archive/                    # archive formats and writers
│  ├─ disk/                       # disc / disk probing
│  └─ cd/                         # CD TOC, WAV/PCM
├─ ui/
│  ├─ MainScreen.kt               # main screen
│  ├─ components/                 # file panes, task drawer, thumbnail strip
│  ├─ editor/                     # text editor and syntax highlighting
│  ├─ player/                     # video/audio player screens
│  ├─ gallery/                    # gallery grid
│  └─ settings/                   # settings, open-source notices, license details
└─ assets/pc/                     # PC-side disk/disc/M-DISC probe scripts
```

---

## Known limitations

- **No built-in rendering for Office documents and PDF files:** doc, docx, xls, xlsx, ppt, pptx, and pdf files are handed off to system apps;
- **Extraction / archive browsing:** archives can be created but not opened or extracted;
- **RAR / LZH / ZPAQ creation:** the options are present but not functional yet;
- **Very large text files:** the text limit is 2 MB; above roughly 20K characters, Compose may stop showing syntax highlighting;
- **Remote text saving:** files are overwritten directly without mtime/size conflict detection; a failed save falls back to local storage;
- **Transfer persistence:** in-progress transfers do not resume after the system kills the process.

---

## Future roadmap

> **The following features are planned for future releases and are not part of 5.9.9:**
>
> - **Built-in PDF rendering:** planned for a future release.
> - **Extraction and archive preview:** planned for a future release.
> - **Very large text files:** the editor will be adjusted in a future release.

---

## License

XJ File's **own code** is released under the [MIT License](LICENSE).

Copyright (c) 2026 XJoffice ChenMoZhongChen

### Logo and brand graphics exception

<img src="app/src/main/res/drawable/xj.png" alt="Xinjin File Manager logo" width="120">

The "Xinjin File Manager / XJ File" application icon, logo, and related brand graphics in this repository are **not included in the MIT License grant**. Their copyright belongs to XJoffice ChenMoZhongChen. Without written permission, they may not be used as icons, branding, promotional material, or derivative artwork for other projects; they are intended only to identify this project and its official distributions.

Third-party components remain the property of their respective copyright holders and continue to be governed by their original licenses. They are **not re-licensed because this project uses the MIT License**. See:

- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- In the app: "Settings → About → Open-source notices";
- The full license texts under `assets/licenses/` in the APK.

### jcifs-ng

`jcifs-ng` is licensed under **LGPL-2.1** and is currently the only non-permissive dependency. It is used for SMB share enumeration.

This project provides the following under LGPL-2.1:

- The full license at `assets/licenses/lgpl-2.1.txt`;
- The jcifs-ng copyright and license notices;
- The complete corresponding source at `assets/licenses/sources/jcifs-ng-2.1.10-sources.jar`;
- The application source package at `assets/licenses/sources/XJFile-5.9.9.17128076-source.zip`;
- The third-party NOTICE summary at `assets/licenses/THIRD_PARTY_NOTICES.txt`;
- An in-app "Export source to Download" action;
- Upstream source: <https://github.com/AGNO3/jcifs-ng>

If you distribute an APK containing jcifs-ng, you must also comply with LGPL-2.1's source and relinking requirements. If you want a distribution containing only permissive dependencies, remove or replace jcifs-ng.

---

## Contributing

Issues and pull requests are welcome.

- Keep the existing dual-pane / file-system abstraction; do not implement protocol handling directly in the UI layer;
- Read [CONTRIBUTING.md](CONTRIBUTING.md) (currently in Chinese) before making changes;
- When adding third-party dependencies or licenses, update [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) as well;
- Do not commit signing keys, passwords, personal server addresses, or test accounts.

---

## Acknowledgements

Thanks to the authors and contributors of AndroidX, Jetpack Compose, Media3, JSch, SMBJ, jcifs-ng, Bouncy Castle, ICU4J, Apache Commons Compress, XZ for Java, AndroidSVG, Kotlin, Gradle, and other open-source projects.

This project is not affiliated with 7-Zip, WinRAR, WinZip, or similar products; format names are used only to describe compatibility.
