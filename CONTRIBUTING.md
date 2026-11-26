# 一起完善 XJ File

谢谢你愿意看看 XJ File。这个项目是一个 Android 双栏文件管理器，
主要使用 Kotlin + Jetpack Compose 编写。

这份文档主要写给想提 Issue、想改代码、或者想打包自己试试的人。
你没有仓库的写权限也没关系，可以通过 Fork + Pull Request 参与；
它不会限制你使用或修改自己的副本。

如果时间有限，也不必一次看完。最有用的是「架构思路」「许可证」和
「Logo 说明」这几节。

---

## 可以先看看

- [README.md](README.md)：功能、构建方式、已知限制；
- [LICENSE](LICENSE)：XJ File 自身代码的 MIT 许可证；
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)：第三方组件及许可证。

遇到问题时，可以先在 GitHub Issues 里搜一下有没有相同情况。没有的话，
欢迎新建 Issue，附上这些信息会更容易定位：

- 手机型号、Android 版本；
- 应用版本号（如果记得，也写上构建版本）；
- 复现步骤；
- 相关日志或截图。

版本号目前分两层：

- **当前版本**：发布页/文件名使用的版本，例如 `6.0.1.180810042`；
- **构建版本**：APK 内实际写入的版本，例如 `6.0.1.180810042`。

两者不一致时，以 APK 内的构建版本为准。提交问题时把两个都写上最稳妥。

---

## 开发环境

比较舒服的环境是：

- JDK 17
- Android SDK Platform 34
- Android Build Tools
- Windows / macOS / Linux 都可以

Windows 上可以这样构建：

```bat
gradlew.bat --no-watch-fs assembleDebug
```

macOS / Linux：

```bash
./gradlew assembleDebug
```

生成的文件在：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Windows 上建议保留 `--no-watch-fs`，这样 Gradle 的文件系统监听不容易卡在
扫描盘符上。

---

## 架构思路

这个项目已经有一套比较稳定的分层，改动时尽量顺着现有结构走，不需要为了
一个小功能把底层重写。

- **文件系统**：统一走 `core/fs` 的 `RemoteFileSystem` 抽象。
  UI 层尽量只关心“文件/目录”，不要直接拼 SMB / SFTP 协议细节。
- **传输**：复制、移动、压缩包上传都经过 `TransferManager`。
  如果直接另起线程读写文件流，进度、暂停和断点会很难对齐。
- **媒体**：播放逻辑在 `core/playback`，UI 只观察状态。
- **文本**：编码、BOM、换行风格的转换集中在 `core/text`。
  编辑器本身不需要直接操作字节。
- **压缩**：格式定义和写入器在 `core/archive`。
  新增格式时，可以先确认 Android 上有没有可用的写入实现。

代码结构在 README 的「技术栈」和「代码结构」里，可以对照着看。

---

## 代码风格

不用刻意追求和示例完全一样，但尽量让 diff 容易读：

- 沿用现有的 Kotlin 风格和中文注释习惯；
- 复杂逻辑可以拆成小函数，避免把太多分支塞进 Composable；
- UI 状态变化尽量复用现有的 ViewModel / State 流，不必再引入一套状态管理；
- 不相关的格式化改动可以少一些，PR 会更好审；
- 用户可见文案使用简体中文，语气和现有界面保持一致。

---

## 测试

提交前可以跑一下：

```bat
gradlew.bat --no-watch-fs test
```

涉及 UI 或设备能力的改动，真机验证会更有说服力。PR 里可以简单写：

- 测试机型与 Android 版本；
- 测试了哪些路径；
- 哪些情况没有覆盖。

没有覆盖到的部分直接写出来就好，不需要勉强凑“全部通过”。

---

## 新增依赖与许可证

XJ File 自身代码使用 MIT License，但第三方组件仍适用各自许可证。

新增依赖前，可以先确认：

1. 许可证是否允许在 MIT 项目中分发；
2. 是否需要额外的 NOTICE 或版权声明；
3. 是否会显著增大 APK；
4. 是否有 Android 上不可用的 API。

如果新增了依赖，可以顺手同步：

- `THIRD_PARTY_NOTICES.md`；
- `app/src/main/assets/licenses/` 下的许可证文本；
- 应用内「设置 → 关于 → 开源软件声明」的条目。

第三方许可证声明建议保留原样，不要把第三方代码标成 MIT。

当前唯一需要特别处理的是 `jcifs-ng`（LGPL-2.1）。它用于 SMB 共享枚举，
完整源码包和说明已随 APK 提供。修改相关内容时，保留 LGPL 材料即可。

---

## Logo 说明

<img src="app/src/main/res/drawable/xj.png" alt="新金文件管理器 Logo" width="120">

这一点比较重要：

**「新金文件管理器 / XJ File」的应用图标、Logo 及相关品牌图形，
不包含在 MIT License 的授权范围内。**

它们版权归 XJoffice ChenMoZhongChen 所有，只用于标识本项目及其官方发行版。
未经书面许可，不建议把这些图形拿去做其他项目的图标、品牌、宣传或衍生作品。

代码是 MIT 的，但 Logo 不是。如果只想复用代码，替换掉图标和品牌图形即可。

---

## 安全约定

下面这些内容，建议不要提交到仓库：

- `xj.jks`、`*.keystore`、`*.p12`；
- `local.properties` 里的签名密码；
- 真实的 SMB / SFTP 服务器地址、账号、密码；
- 内部交接文档、测试账号、个人路径；
- `app/build/`、`.tmp/`、`app/release/` 等构建产物。

Release 签名只从本地 `local.properties` 或环境变量读取，不建议写死在
`build.gradle.kts` 里。

提交前可以看一眼：

```bash
git status
```

确认暂存区里没有上面这些东西，就放心很多。

---

## 提交 Pull Request

比较顺畅的方式是：

1. 从 `main` 拉一个新分支；
2. 提交信息写清楚一点，例如：
   - `Fix SFTP root listing timeout`
   - `Add BD-Video detection`
   - `Improve text editor encoding detection`
3. 一个 PR 尽量只做一件事；
4. 在 PR 描述里简单写：
   - 改动内容；
   - 测试方式；
   - 是否涉及第三方依赖或许可证；
5. 如果改了界面，附一张截图或录屏会很直观。

提交贡献，表示你同意自己的代码以本项目的 MIT License 发布。
第三方代码仍保留其原始许可证，不会因为提交到本仓库而改变。

---

## 发布相关

正式 Release 由维护者打包和签名。自己改代码时，不需要处理这些。

发布前可以分别核对：

- **当前版本**：发布页/文件名使用的版本；
- **构建版本**：APK 内 `versionName` / `versionCode` 实际写入的版本；
- 发布页同时说明许可证与源码获取方式。

两者不一致时，最好在发布说明里写清楚，避免使用者和贡献者混淆。
