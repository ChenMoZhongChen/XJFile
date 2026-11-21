# XJ File 项目收尾总结

> 版本：5.9.9.17128076  
> 包名：`com.cmzc.xj_file.app`  
> 平台：Android，`minSdk 29 / compileSdk 34 / targetSdk 34`  
> 技术栈：Kotlin + Jetpack Compose (Material3) + Media3 + JSch + SMBJ  
> 设备验证：`10AD8B2CX3001PA`  
> 收尾日期：2026-11-21

---

## 一、项目定位

XJ File 是一个双栏 Android 文件管理器，除了常规本地文件操作，还把下面几类能力做进同一个应用：

- 本地存储 / SMB / SFTP 统一的双栏浏览；
- 光驱（数据盘、音频 CD、DVD Video）识别与播放；
- 内置视频/音频/图片查看器，支持后台音频播放；
- 远程文件与本地文件之间的传输、断点续传、冲突处理；
- 内置文本编辑器：编码识别、本地/远程编辑保存、语法高亮。

目标不是做一个“文件列表壳”，而是把日常在手机上最常遇到的本地/局域网/光驱/文本场景闭环。

---

## 二、已交付功能

### 1. 双栏文件管理

- 左右两栏独立导航、前进/后退/上级/刷新/路径跳转；
- 本地存储、SMB、SFTP 数据源统一抽象为 `RemoteFileSystem`；
- 复制、移动、删除、重命名、新建文件/文件夹、属性、分享；
- 横滑多选、长按操作菜单、全选、复制路径；
- 最近路径、快捷目录、常用文件夹；
- 任务抽屉：传输批次、进度、暂停/恢复/取消、完成校验；
- 冲突处理：覆盖/跳过/重命名/保留两者；
- 本地与远程传输都走同一套 `TransferManager`，支持断点续传。

### 2. 网络与 SFTP

- SMB：共享枚举、目录浏览、上传下载；
- SFTP：基于 JSch，支持文件浏览与传输；
- SFTP 根目录加速：Windows OpenSSH 的 `/` 在存在挂起光驱时会卡住 `ls`，
  根目录改为直接返回空列表，不再枚举 `/`；
- 所有 SFTP 命令增加总超时，超时后主动断开会话/通道；
- PC 端 Drive 探测改为并行、每盘独立超时，空光驱不再拖垮整个磁盘清单；
- 文件 URL 使用 `//host/...` 形式，避免远程路径被 `File` 误判。

### 3. 光驱 / CD / M-DISC

- 光驱行显示卷标、容量、介质类型；
- 音频 CD：读取 TOC，按扇区数计算每轨时长；
- 音频 CD 播放：使用 PCM/WAV 流式播放，可后台播放；
- DVD Video：检测 `VIDEO_TS`，长按可选择播放方式；
- M-DISC：通过 SCSI `READ DISC STRUCTURE`（0xAD）读取盘片信息，
  识别 MILLEN 等千年盘标识，并在卷标上标注；
- 光驱探测全部按盘并行、单盘超时，避免一个坏光驱导致整页卡死。

### 4. 内置查看器

- 视频/音频播放器：Media3 ExoPlayer；
- 后台音频：`PlaybackService` + `mediaPlayback` 前台服务 + MediaSession + 通知；
- 图片查看器、类相册 4 列网格、混排翻页；
- 视频页底部缩略图条；
- **音频页按 CD 页布局**：封面、进度、上一首/下一首、播放控制；
- 音频播放列表就是当前文件夹的全部音频文件：
  - 显示文件名与时长；
  - 当前项高亮、自动滚动；
  - 点击任意一首直接切换；
  - 时长按行懒加载，读不到显示 `--:--`；
  - CD 音轨仍用 TOC 扇区数计算，不读容器标签；
- **音频 CD 自动切轨**：
  - 只有 `PlaybackSource.Cdda` 音轨播完会自动切到下一轨；
  - 普通音频、视频播完不自动切（用户明确要求只 CD 自动）；
  - 最后一轨播完不循环，停在当前页；
  - 自动切轨和手动「下一首」共用同一套 `goTo`，数据源/通知/列表高亮一起更新；
- 音频页不再重复显示底部缩略图条。

### 5. 压缩

- 长按多选/单选 →「压缩」→ 独立参数面板：
  - 压缩包名称（默认沿用文件/文件夹名，多选沿用当前目录名）；
  - 压缩类型共十种，按 5 个一行：ZIP、ZIPX、TAR、TGZ、LZH、7Z、GZ、XZ、ZPAQ、RAR；
  - 可创建：ZIP、ZIPX、TAR、TGZ、7Z、GZ、XZ；
  - 不可创建：LZH、ZPAQ（无 Android 写入库）、RAR（专有格式，按用户要求仅补位）；
  - GZ/XZ 仅支持单文件，多选时标注「仅单文件」；
  - 压缩级别：0 仅存储 / 1 快速 / 2 标准 / 3 极限；
  - 明细区：选中项数、文件合计大小、格式/级别、输出目录、层级保留说明；
- 实际写入：
  - ZIP / ZIPX / 7Z / TAR / TGZ 支持多文件、目录递归、空目录保留；
  - GZ / XZ 只压缩单个文件（多选时置灰提示用 TGZ）；
  - 本地文件直接在当前目录生成；
  - 远程文件先在 cache 生成，再 `writeFrom` 上传，避免 SFTP 读写抢车道；
  - 中文文件名 UTF-8；同名目标文件拒绝覆盖；
  - 失败只删临时包，不在目标留半截文件。

### 6. 设置与开源声明

- 设置根菜单：后台运行、外观、实验性内容、通知、关于、**开源软件声明**；
- 开源软件声明页只列运行时库和构建工具（不含 PC 侧外部工具）：
  - 名称、版本、许可证、用途说明、项目主页；
  - 点击任意条目进入独立详情页；
  - 详情页含：版权归属、项目主页、许可证全文、LGPL 条目的源码/替换说明；
  - 许可证全文放在 `assets/licenses/` 随 APK 分发，不依赖网络；
  - jcifs-ng 2.1.10 完整源码包放在 `assets/licenses/sources/`，
    详情页可导出到 `Download/XjFile/`；
  - XJ File 自身源码包也放在 `assets/licenses/sources/`，用于 LGPL 的重建/重链接；
  - 第三方 NOTICE 汇总放在 `assets/licenses/THIRD_PARTY_NOTICES.txt`；
  - 覆盖 AndroidX/Compose、Accompanist、Media3、JSch、SMBJ、jcifs-ng、
    BouncyCastle、AndroidSVG、ICU4J、Commons Compress、XZ、Coroutines、SLF4J、
    Kotlin、Gradle、AGP。

### 7. 文本编辑器（5A / 5B）

- 编码识别：BOM 优先 + ICU4J `CharsetDetector` + 严格 UTF-8 校验；
- 支持 UTF-8/UTF-16/UTF-32、GBK/GB18030、BIG5、日文、韩文、西文等；
- 保留原始 BOM 和换行风格（LF/CRLF/CR）；
- 行号、查找/替换、未保存确认、编码切换、保存；
- 文件列表接入：
  - 已知文本后缀 + **未知后缀默认进文本编辑器**；
  - 长按任意单文件「打开方式」→ 文本编辑器 / 系统应用；
  - Office/PDF、常见二进制、音视频/图片不误入文本编辑器；
  - 未知后缀二进制做字节嗅探，提示「这看起来不是文本文件」；
- 远程文本：
  - 直接读进内存，不落缓存；
  - 保存时覆盖回原路径；
  - 覆盖失败落 `Download/XjFile`，并用 Toast 给出完整路径；
- 本地文本：直接读写原文件；保存失败同样有兜底路径；
- 编辑器保存回调返回真实成功/失败，失败不清除未保存状态；
- 手动换编码时 BOM 重置为无，避免 BOM 与编码不匹配。

### 8. 语法高亮（5C）

- 纯 `VisualTransformation` 实现，不改变文本、光标、选区、查找替换；
- 支持语言：
  - C / C++ / C# / Java / Kotlin / Python；
  - HTML / HTM / XHTML；
  - XML / XSL / XSLT / XSD / SVG；
  - JavaScript / TypeScript / JSX / TSX；
  - JSON / CSS / SCSS / Shell；
- 高亮内容：关键字、类型、字符串、注释、数字、函数名、预处理指令、注解；
- HTML/XML/XSL：标签、属性、属性值、注释、CDATA、`<?...?>`、DOCTYPE、实体；
- 深色/浅色两套配色，跟随应用主题；
- 不再设置 512KB 高亮上限；`SyntaxHighlighter` 对传入文本做完整词法扫描并缓存结果。

### 9. 许可证与 MIT

- **XJ File 自身代码**：MIT License，版权人 **XJoffice ChenMoZhongChen**，全文见根目录 [LICENSE](LICENSE)；
- 第三方组件保留原许可证，不被 MIT 重新授权，详见
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)；
- AndroidX/Compose、Accompanist、Media3、Coroutines、SMBJ、AndroidSVG、
  Commons Compress、Kotlin、Gradle、AGP：Apache-2.0；
- JSch：BSD-3-Clause；SLF4J：MIT；ICU4J：Unicode License；
  Bouncy Castle：MIT-style；XZ：Public Domain；
- **jcifs-ng：LGPL-2.1，唯一非宽松依赖**：应用自身仍可 MIT，但分发包含
  jcifs-ng 的 APK 时必须满足 LGPL（许可证、源码、替换/重新链接）；
  APK 已内置 2.1.10 完整源码包，详情页可导出；若要求“整个发行物只含
  宽松许可证”，需要移除或替换 jcifs-ng。

---

## 三、关键设计取舍

### 1. 为什么用统一 `RemoteFileSystem`

本地、SMB、SFTP 的浏览/复制/移动/删除/传输都收敛到同一接口，
新增协议只需要实现 `openInput / openInputFrom / writeFrom / list / size / delete / rename` 等能力，
上层文件栏和传输器不需要感知协议差异。

### 2. 为什么传输单独做 `TransferManager`

文件管理器最容易失控的是“复制到一半”的状态。传输器独立管理：
批次、任务、进度、暂停、断点、冲突、取消、校验；
UI 只观察状态，不直接操作流。

### 3. 为什么音频页复用 CD 布局

CD 页的信息密度和操作路径已经被验证过：封面 + 进度 + 上一首/下一首 + 列表。
普通音频直接复用同一套布局，只把“音轨列表”换成“当前文件夹音频列表”，
学习成本最低，也避免维护两套播放页。

### 4. 为什么文本先读内存

用户明确要求远程文本不落缓存。2MB 以内直接读进内存，
编辑/切编码都基于原始字节，保存时才写回；
超过 2MB 在打开阶段就拒绝，避免把手机内存拖死。

### 5. 为什么语法高亮不引第三方库

工程锁 Compose BOM 2024.02 / compileSdk 34，
多数代码编辑器库要求更高 compileSdk 或自带 View 体系。
自写词法扫描 + `VisualTransformation` 风险最小，不改变编辑行为。

---

## 四、主要代码地图

```text
app/src/main/java/com/cmzc/xj_file/app/
├─ MainActivity.kt
├─ SettingsActivity.kt                 # 设置页层级、关于、开源软件声明入口
├─ vm/
│  └─ MainViewModel.kt                 # 双栏状态、查看器、文本编辑会话、传输入口
├─ core/
│  ├─ fs/                              # LocalFileSystem / SftpFileSystem / SmbFileSystem / PathUtil
│  ├─ transfer/                        # TransferManager、断点、校验
│  ├─ playback/                        # MediaTypes、PlaybackEngine、PlaybackService、ViewerPage
│  ├─ image/                           # 图片/音频元数据
│  ├─ text/                            # 编码内核、TextCodec、TextFileTypes
│  ├─ archive/                         # ArchiveType / 级别 / ZIP 写入器
│  ├─ disk/                            # 光驱探测、DriveProbe、DriveInfo
│  └─ cd/                              # CD TOC、WAV/PCM
├─ ui/
│  ├─ MainScreen.kt                    # 主界面、文件栏、打开方式、压缩入口、编辑器覆盖层
│  ├─ components/FilePanel.kt          # 文件栏、行、多选、长按
│  ├─ FileActionDialog.kt              # 长按操作菜单
│  ├─ CompressDialog.kt                # 压缩名称/类型/级别/明细
│  ├─ OpenWithDialog.kt                # 文本编辑器 / 系统应用
│  ├─ editor/                          # TextEditorScreen、TextEditorState、SyntaxHighlighter
│  ├─ settings/OpenSourcePage.kt       # 开源软件声明目录
│  ├─ settings/OpenSourceDetailPage.kt # 版权归属 + 许可证全文
│  ├─ settings/OpenSourceCatalog.kt    # 开源项目/版本/许可证索引
│  ├─ player/                          # PlayerScreen、AudioPlayerSurface、MediaFilmstrip
│  ├─ image/                           # ImageViewerScreen
│  └─ gallery/                         # GalleryScreen
└─ assets/pc/                          # drives.ps1 / optical.ps1 / mdisc.ps1 等 PC 侧探测脚本
```

---

## 五、构建与验证

### 构建

```bat
gradlew.bat --no-watch-fs assembleDebug --console=plain
gradlew.bat --no-watch-fs assembleRelease --console=plain
```

- 必须加 `--no-watch-fs`：否则 Windows 上 Gradle 可能在
  `PosixFileSystemFunctions.listFileSystems` 扫描盘符时挂住。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- Release APK：`app/build/outputs/apk/release/app-release.apk`
- Release 版本：`5.9.9.17128076` / `versionCode=17128076`
- Release 大小：**70,461,895 字节**
- Release SHA-256：`2FE9E4038BD75C8384527A484B4F890F400F3385056B62B7D812D5CCC0AD68AB`
- Release 签名已验证（APK Signature Scheme v2），证书
  `CN=ChenMoZhongChen, O=XJ(XRT), OU=XJ_APK`。
- Debug 已安装到测试机，`lastUpdateTime=2026-11-21 23:23:05`。
- 应用图标由 `XJFile.png` 生成：自适应图标 foreground + 各密度 legacy/round mipmap。

### 已验证场景

| 场景 | 结果 |
|---|---|
| 本地 UTF-8 文本编辑保存 | 中文/emoji 正常，CRLF 保留 |
| 本地 GBK 文本编辑保存 | 41 字节，GBK 可读，CRLF 保留 |
| 未知后缀 `.foo` | 默认进文本编辑器，LF 正确 |
| 未知后缀二进制 `.foo` | 提示「这看起来不是文本文件」 |
| `.bin` / `.pdf` | 不进文本编辑器；PDF 打开方式只有系统应用 |
| SMB 远程文本编辑保存 | PC 端文件字节更新，无缓存落盘 |
| SFTP 远程文本编辑保存 | PC 端文件字节更新 |
| 远程只读文件保存 | 覆盖失败 → `Download/XjFile/...` |
| 音频页（3 个 WAV，3/8/12 秒） | 文件夹音频列表、时长、切歌、自动滚动正常 |
| 普通音频 1 秒播完 | **不会**自动切到下一首，仍停在原曲（用户要求只 CD 自动） |
| CD 自动切轨 | 代码已接入 `STATE_ENDED`；测试机当前无音频 CD，未做真实盘复测 |
| 本地多选 ZIP（2 文件） | 生成 `zip-test.zip`，条目/CRC 正常，级别 2 有效 |
| 本地文件夹 ZIP（sub） | 保留 `sub/` 目录和 `sub/c.txt` |
| 本地多选 7Z | 生成 7Z，文件头 magic 正常；PowerShell 环境无 7z 命令可解包复验 |
| 本地多选 TAR | `tar -tf` 可列出条目，内容可读 |
| 本地多选 TGZ | Python `tarfile r:gz` 可列出条目 |
| 本地单文件 GZ | 解压后内容与原文件完全一致 |
| 本地单文件 XZ | Python `lzma` 解压后内容与原文件完全一致 |
| SMB 远程多选 ZIP | 压缩包回传 PC，条目/CRC 正常 |
| 设置 → 开源软件声明 | 关于下方入口可见，运行时/构建工具列表与许可证说明可滚动查看 |
| 开源声明条目详情 | JSch 详情页加载 BSD 全文，版权行无重复；许可证资源随 APK 分发 |
| 529KB / 1.2 万行纯文本 | 可打开、可滚动（行号/正文同步） |
| C++ / Kotlin / Python / HTML / XSL 高亮 | 关键字/字符串/注释/数字/标签等分色正常 |

### 已清理

- 测试音频、测试文本、测试代码、测试压缩包、截图、UI dump 已删除；
- 手机 `DSHcache` 只保留原有文件；
- PC 临时目录只保留 `cd-track2.wav`、`cd_stream_remote.ps1`；
- 远程测试文件已删除；
- 应用已停止，不留播放服务。

---

## 六、占位与未实现清单

### 用户能点到的真占位

- **LZH / ZPAQ / RAR 创建**：选项都在格式面板里，但置灰「暂不可用」：
  LZH/ZPAQ 没有 Android 写入库，RAR 创建格式专有；
  按用户要求 RAR 只补位、不做功能。
- **解压 / 压缩包浏览**：压缩包可以创建，但还不能点开压缩包浏览或解压。
- **`TextCodecStub.kt`**：5A 早期留下的空壳接口，没有进入实际调用链，
  真正使用的是 `TextCodecImpl`；属于可删除的死代码，不是用户功能。

### 明确未做（不是按钮占位，但功能缺失）

- **Office / PDF 内置预览**：`MediaTypes.DOC` 只做识别，打开仍交系统应用；
  没有 doc/docx/xls/xlsx/ppt/pptx/pdf 渲染器。
- **超大文本编辑器虚拟化**：目前整篇文本是一个 `BasicTextField`；
  2MB 读取上限、约 2 万字符以上高亮会失效，见下文限制。
- **远程文本保存冲突检测**：直接覆盖，失败才落 `Download/XjFile`；
  没有比较 mtime/size 后提示。
- **传输任务持久化**：进程被系统杀掉后，进行中的传输不会恢复
  （`TransferManager` 明确不做重启恢复）。
- **FTP / WebDAV / HTTP / DLNA**：目前只有本地、SMB、SFTP；
  `PlaybackSource.Direct` 留了 HTTP 播放的口子，但文件管理协议没接。
- **文件排序 / 搜索 / 过滤**：文件栏目前固定默认顺序，没有排序菜单和全文搜索。
- **压缩包/归档浏览**：zip/rar/7z 只当普通文件，不能点进去浏览。
- **CD 抓轨 / 转码**：只做 CD 播放，没有抓轨成 WAV/FLAC/MP3。
- **`TextEditorPreviewActivity`**：开发验收入口，`exported=false`，不是用户功能。

---

## 七、已知限制 / 风险

1. **Office / PDF 不做渲染**：doc/docx/xls/xlsx/ppt/pptx/pdf 仍交系统应用。
2. **文本编辑器上限 2MB**：远程文本走内存，超过 2MB 打开阶段拒绝；
   这是 5B 定的安全边界。
3. **语法高亮在长文本上存在 Compose 控件限制**：
   `SyntaxHighlighter` 本身不再设大小上限，但当前 Compose `BasicTextField`
   对很长的文本（实测约 2 万字符 / 500 行以上）可能不再应用
   `VisualTransformation` 的 Span，表现为纯文本颜色。
   单文件 6KB 左右及以下的高亮验证正常；
   若要做到任意大小都彩色，需要后续实现分块/虚拟化编辑器，
   这是 5C 之后最大的技术债。
4. **没有冲突检测**：远程文本保存是直接覆盖，失败才落 `Download/XjFile`；
   没有比较 mtime/size 后再提示。
5. **LZH / ZPAQ / RAR / 解压未做**：ZIP/ZIPX/TAR/TGZ/7Z/GZ/XZ 可创建；
   LZH/ZPAQ/RAR 置灰；还没有压缩包浏览、解压和递归解压。
6. **CD 自动切轨已接、未用真实 CD 复测**：测试光驱当前没有音频 CD；
   普通音频「播完不自动切」已用 1 秒 WAV 实测通过。
   自动切轨只在 `PlaybackSource.Cdda` 下生效，最后一轨不循环。
7. **远程音频时长是懒加载**：读不到标签显示 `--:--`，SMB/SFTP 上可能稍慢。
8. **APK 体积偏大**：ICU4J 编码库 + 压缩格式库，APK 约 88MB；
   如果后续在意体积，可换轻量编码探测、拆分 ABI 或按需打包。
9. **`TextEditorPreviewActivity` 仍保留**：`exported=false`，
   仅作为开发预览入口，不是用户入口。
10. **SFTP 根盘清单首次加载慢**：Windows 上要等各盘探测完成；
    已做超时和并行，但无法做到零等待。

---

## 八、后续建议

优先级从高到低：

1. **分块/虚拟化文本编辑器**：解决 2 万字符以上高亮丢失和超大行数排版卡顿；
   可考虑按行窗口渲染 + 输入层 overlay，或自定义编辑器。
2. **Office/PDF 预览**：至少 PDF 内置渲染；Office 可先接系统转换/第三方库。
3. **文本保存冲突检测**：保存前比对远程 mtime/size，冲突时提示覆盖/另存。
4. **压缩功能**：zip 创建/解压，接入现有传输和冲突框架。
5. **APK 瘦身**：评估 ICU4J 替换、资源压缩、按需打包。
6. **发布准备**：签名 release、版本号、隐私说明、崩溃收集。

---

## 九、结论

项目当前已经是一个可日常使用的双栏文件管理器：

- 本地/SMB/SFTP 浏览与传输闭环；
- 光驱/CD/DVD/M-DISC 识别与播放闭环；
- 视频/音频/图片查看与后台音频闭环；
- 文本编辑：编码、本地/远程保存、打开方式、异常兜底闭环；
- ZIP 压缩：多选参数面板、级别、明细、本地/远程写入闭环；
- 语法高亮覆盖主流代码/标记语言。

剩余问题集中在“超大文本编辑器的渲染架构”和“Office/PDF 预览”，
不影响普通文件管理、传输、播放和常规代码文件编辑。
