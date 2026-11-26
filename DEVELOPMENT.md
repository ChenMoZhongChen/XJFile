# XJ File 开发文档

> 面向继续开发/维护这份代码的人。  
> 对应版本：`6.1.0.180805737`（versionCode `180805737`）

---

## 1. 开发环境

| 项目 | 要求 |
|---|---|
| JDK | 17 |
| Android SDK | Platform 34 |
| Build Tools | 与 AGP 8.13.0 配套 |
| Gradle | 9.5.0（项目自带 wrapper） |
| Kotlin | 2.0.21 |
| Compose | Material 3 |
| 最低系统 | Android 10（API 29） |

Windows 上建议所有 Gradle 命令都带 `--no-watch-fs`：

```bat
gradlew.bat --no-watch-fs assembleDebug
```

否则某些机器会卡在文件系统监听扫描盘符。

---

## 2. 目录结构

```text
app/src/main/java/com/cmzc/xj_file/app/
├─ MainActivity.kt
├─ SettingsActivity.kt
├─ vm/
│  └─ MainViewModel.kt          # 双栏、传输、查看器、编辑器、压缩、图标的协调层
├─ core/
│  ├─ fs/                       # LocalFileSystem / SmbFileSystem / SftpFileSystem
│  ├─ transfer/                 # TransferManager、TransferStore、断点、冲突
│  ├─ playback/                 # Media3、PlaybackSource、播放服务
│  ├─ text/                     # 编码、文本类型判断
│  ├─ archive/                  # ArchiveReader、ArchiveExtractor、压缩写入
│  ├─ disk/                     # 磁盘/光盘探测
│  ├─ image/                    # ThumbnailLoader、AppIconLoader、RemoteAppIconLoader
│  ├─ settings/                 # AppSettings、SortSettings
│  ├─ root/                     # Root 环境检测/授权入口
│  └─ cd/                       # CD TOC、WAV/PCM
├─ ui/
│  ├─ MainScreen.kt
│  ├─ components/
│  │  ├─ FilePanel.kt
│  │  ├─ FilePreviewIcon.kt
│  │  ├─ FileTypeIcon.kt
│  │  └─ ...
│  ├─ editor/
│  │  ├─ TextEditorScreen.kt
│  │  ├─ TextEditorState.kt
│  │  ├─ LargeTextEditorBody.kt
│  │  └─ ...
│  ├─ player/
│  ├─ gallery/
│  ├─ ArchiveScreen.kt
│  ├─ PdfViewerScreen.kt
│  └─ settings/
└─ assets/pc/                   # PC 侧光盘/磁盘探测脚本
```

---

## 3. 文件系统抽象

`core/fs/RemoteFileSystem.kt` 是所有来源的统一接口：

- `list(path)`
- `download(remote, local, progress)`
- `upload(local, remote, progress)`
- `openInput(path)`
- `openInputFrom(path, offset)`：**服务端偏移读**
- `openOutputFrom(path, offset)`：断点续写
- `sha256(path)`
- `delete/move/rename/mkdir/exists/size/close`

### 关键约定

- `openInputFrom` 必须由协议真正 seek，不能先读后丢；
- SFTP 的流未关闭前，同一 fs 实例不能做其他操作；
- SMB 的 `openInputFrom` 用服务端 offset；
- 本地实现用 `RandomAccessFile`。

远程 APK/EXE 图标、断点续传、边下边播的 seek 都依赖这个接口。

---

## 4. 传输系统

### 4.1 文件

- `core/transfer/TransferManager.kt`
- `core/transfer/TransferStore.kt`

### 4.2 模型

`TransferTask` 关键字段：

```kotlin
id, batchId, batchName, name
srcPath, dstPath
totalBytes, transferred, status
srcTag, dstTag          // local / smb:host / sftp:host
resumedFrom
isMove                  // 是否移动批次
topSrcPath              // 移动批次的顶层源路径
verifying, sha256
```

### 4.3 流程

1. ViewModel 展开目录，得到 `PendingTransfer`；
2. 检测冲突，决定覆盖/跳过/重命名；
3. `TransferManager.enqueue()` 入队，落盘；
4. 最多 3 条车道并行；
5. 单文件完成后计算 SHA-256；
6. 批次结束后，移动批次由 ViewModel 做最终删源；
7. 进程被杀后 `TransferStore` 恢复 PENDING 任务；
8. 恢复的移动任务由 `TransferManager.scheduleRestoredMoveCleanup()` 补删源。

### 4.4 恢复后的移动删源

只有满足以下条件才删源：

- 该 `topSrcPath` 下所有恢复任务仍然存在；
- 所有任务状态都是 `COMPLETED`；
- 没有失败、取消、被用户移除；
- 删除失败只记录日志，不破坏已完成的目标文件。

### 4.5 注意点

- `TransferStore` 只保存活跃任务；
- 恢复时需要 `srcTag` / `dstTag` 能解析到 fs；
- 新任务的删源仍由 `startEnqueue()` 的移动收尾负责；
- 恢复任务只补做恢复场景，不要重复走新任务流程。

---

## 5. 远程 APK/EXE 图标

### 5.1 本地

- `core/image/AppIconLoader.kt`
- APK：先读 APK 资源表，再回退 `PackageManager`；
- EXE：`PeIconReader` 解析 PE 资源，支持 PNG 和 1/4/8/24/32bpp DIB；
- 结果按文件路径/大小/mtime 缓存。

### 5.2 远程

- `core/image/RemoteAppIconLoader.kt`
- 不整包下载；
- APK：读 ZIP 尾部 EOCD → 中央目录 → 候选 `ic_launcher` 等条目 → 读局部头 → 解压；
- EXE：读 PE 头 → 找资源节 → 读资源节 → 拼稀疏临时文件 → 复用本地 PE 解析；
- 使用 `RemoteFileSystem.openInputFrom`；
- `FsRandomReader` 按 64 KB 块缓存，减少 SFTP/SMB 往返；
- 仅当找不到可识别图标时回退默认图标。

### 5.3 颜色

- `FilePreviewIcon` 对真实图标不应用灰度；
- `FileTypeIcon` 才根据 `AppSettings.fileIconColors` 决定合成图标颜色；
- 这个规则不能反过来，否则真实 logo 会被错误去色。

---

## 6. 文件图标

`ui/components/FileTypeIcon.kt`

- 灰色/彩色底层文件页；
- 后缀字母从图标中挖空：
  - 先画到 ARGB 位图；
  - 用 `PorterDuff.Mode.DST_OUT` 挖掉文字/机器人；
- 压缩包不显示后缀文字；
- APK 无真实图标时显示机器人；
- EXE 无真实图标时显示 Windows 通用程序图标；
- 图片/音频/视频保留类型色。

---

## 7. 大文本编辑器

### 7.1 入口

- `TextEditorScreen.kt`
- `LARGE_EDITOR_CHARS = 200_000`
- 文本上限 16 MB

### 7.2 分页

`LargeTextEditorBody.kt`：

- 每页 80 行；
- `LazyColumn` 只组合视口附近页；
- 每页一个 `BasicTextField`；
- 页内直接编辑；
- 编辑后平移后续页 offset/startLine；
- 保存时合并各页。

### 7.3 跨页

- 拖选到页尾：`mergeNext()` 把下一页并入当前页；
- 拖选到页首：`mergePrevious()` 把上一页并入当前页；
- 光标到页边界：`onPreviewKeyEvent` 处理左右方向键，并页后继续；
- 并页后重新编号、重算 offset/startLine；
- 选区只扩展到边界，不会一次选中整页；
- 注意：并页会减少页数，这是为跨页选择付出的代价。

---

## 8. 压缩包

`core/archive/ArchiveReader.kt`

读取：

- ZIP / ZIPX
- TAR
- TGZ
- 7Z
- GZ
- XZ

创建：

- ZIP / ZIPX / TAR / TGZ / 7Z / GZ / XZ

明确不支持：

- RAR 创建/读取
- LZH 创建/读取
- ZPAQ 创建/读取

不要用其他格式“假装”支持。

---

## 9. 文本与编码

`core/text/TextFileTypes.kt` + `core/text/*`

- BOM 优先；
- ICU4J 探测；
- 严格 UTF-8；
- 保留 BOM 和换行风格；
- 远程文本保存比较 mtime/size；
- 保存冲突由 `EditorSavePolicy` 决定：
  - NORMAL
  - OVERWRITE
  - LOCAL_COPY

---

## 10. 排列方式、连接修改与 Root

### 排列方式

- `core/settings/SortSettings.kt`：排序字段/方向/文件夹优先，以及「当前目录覆盖」和「全局默认」；
- `ui/components/SortDialog.kt`：右上角「排列方式」弹窗；
- `MainViewModel.sortItems()` 在 `load()` 时排序；
- `MainViewModel.applySort()` 立即重排当前栏；选择所有目录时同时重排两栏并清空目录覆盖项；
- 默认作用范围是当前焦点目录，持久化到 `xj_sort_settings`。

### 网络连接修改

- 抽屉中长按连接，菜单：修改 / 重命名 / 删除；
- 修改复用 `AddConnectionDialog(existing = conn)`；
- 保存走 `MainViewModel.updateConnection()`，会关闭旧 fs 实例，下次打开按新配置重建；
- 可修改名称、IP、端口、用户名、密码、SMB 共享名/域、SFTP 私钥/口令。

### Root

- `core/root/RootManager.kt`；
- 启动时后台检查 `su`/Magisk/KernelSU/APatch 常见路径；
- 没有 root 环境：状态置 `NOT_ROOTED`，不弹任何 UI；
- 有 su：执行一次 `su -c id`，成功置 `GRANTED`，拒绝/超时置 `DENIED`；
- `runAsRoot(command)` 供后续需要 root 的系统操作复用；
- 当前没有把 root 权限接到普通文件浏览/删除上，避免未测试的越权行为。

---

## 11. 常见修改入口

| 需求 | 文件 |
|---|---|
| 文件列表/行 | `ui/components/FilePanel.kt` |
| 排列设置 | `core/settings/SortSettings.kt` / `ui/components/SortDialog.kt` |
| 网络连接编辑 | `ui/AddConnectionDialog.kt` + `MainViewModel.updateConnection()` |
| Root | `core/root/RootManager.kt` |
| 文件图标 | `ui/components/FileTypeIcon.kt` |
| 缩略图 | `core/image/ThumbnailLoader.kt` |
| APK/EXE 图标 | `core/image/AppIconLoader.kt` / `RemoteAppIconLoader.kt` |
| 传输 | `core/transfer/TransferManager.kt` |
| 传输落盘 | `core/transfer/TransferStore.kt` |
| 大文本分页 | `ui/editor/LargeTextEditorBody.kt` |
| 编辑器状态 | `ui/editor/TextEditorState.kt` |
| 压缩包 | `core/archive/ArchiveReader.kt` |
| 文本类型 | `core/text/TextFileTypes.kt` |
| 双栏/业务协调 | `vm/MainViewModel.kt` |

---

## 12. 测试清单

### 排列/连接

- [ ] 当前目录应用排序，切换目录后恢复默认/独立设置
- [ ] 应用所有目录后重启仍保持
- [ ] 名称/大小/时间/类型、升序/降序、文件夹优先
- [ ] 长按连接 → 修改 → 改 IP/用户名/密码/共享名 → 保存
- [ ] Root：没有 root 环境时无任何提示（按约定不主动测试）

### 传输

- [ ] 本地复制大文件
- [ ] 本地移动到另一目录
- [ ] SMB/SFTP 复制
- [ ] 传输中杀进程 → 重启恢复
- [ ] 恢复移动任务 → 全部校验通过后源被删除
- [ ] 删除源失败时目标保留

### 图标

- [ ] 本地 APK 真实图标
- [ ] 本地 EXE 真实图标（PNG/DIB）
- [ ] 无图标 EXE 回退 Windows 通用图标
- [ ] SMB/SFTP 大 APK 图标只读必要段
- [ ] 关闭“文件图标颜色”后真实图标仍为彩色

### 编辑器

- [ ] 200K 字符以上进入大文件模式
- [ ] 页内编辑/保存
- [ ] 跨页拖选
- [ ] 键盘左右跨页
- [ ] 远程保存冲突

### 压缩包

- [ ] ZIP/TAR/TGZ/7Z/GZ/XZ 浏览
- [ ] 单文件/全部解压
- [ ] RAR/LZH/ZPAQ 明确提示不支持

---

## 13. 版本与发布

版本名四段，最后一段必须等于 versionCode：

```text
versionName = 6.1.0.180805737
versionCode = 180805737
```

同步位置：

- `app/build.gradle.kts`
- `README.md`
- `README_FULL.md`
- `THIRD_PARTY_NOTICES.md`
- `CONTRIBUTING.md`
- `ui/settings/OpenSourceCatalog.kt`
- `app/src/main/assets/licenses/sources/XJFile-<version>-source.zip`

构建：

```bat
gradlew.bat --no-watch-fs assembleDebug assembleRelease
```

Release 输出：

```text
app/build/outputs/apk/release/app-release.apk
```

不要提交 keystore、`local.properties` 或签名密码。

---

## 14. 待办

- Office 内置渲染（暂缓）
- RAR / LZH / ZPAQ 创建（暂缓）
- 跨页选择的更多手感调整
- 远程 APK adaptive icon 的更完整解析

---

## 15. 约定

- 不要用 PowerShell 的 `Get-Content` + `Set-Content` 改中文文件，容易乱码；
- 中文文本修改优先用编辑器/`edit`；
- 不要提交密钥、连接密码、个人路径；
- 大文件、远程图标、传输等操作必须走 IO 线程；
- UI 线程只做状态更新。
