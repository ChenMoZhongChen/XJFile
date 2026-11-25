package com.cmzc.xj_file.app.vm

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cmzc.xj_file.app.core.fs.LocalFileSystem
import com.cmzc.xj_file.app.core.fs.PathUtil
import com.cmzc.xj_file.app.core.fs.RemoteFile
import com.cmzc.xj_file.app.core.fs.RemoteFileSystem
import com.cmzc.xj_file.app.core.fs.SftpFileSystem
import com.cmzc.xj_file.app.core.fs.SmbFileSystem
import com.cmzc.xj_file.app.core.cd.WavHeader
import com.cmzc.xj_file.app.core.archive.ArchiveExtractor
import com.cmzc.xj_file.app.core.archive.ArchiveEntryInfo
import com.cmzc.xj_file.app.core.archive.ArchiveFormats
import com.cmzc.xj_file.app.core.archive.ArchiveReader
import com.cmzc.xj_file.app.core.archive.ArchiveWriter
import com.cmzc.xj_file.app.core.archive.CompressRequest
import com.cmzc.xj_file.app.core.archive.UnsupportedArchiveException
import com.cmzc.xj_file.app.core.disk.DriveProbe
import com.cmzc.xj_file.app.core.disk.DriveInfo
import com.cmzc.xj_file.app.core.model.Connection
import com.cmzc.xj_file.app.core.model.ConnectionType
import com.cmzc.xj_file.app.core.image.AppIconLoader
import com.cmzc.xj_file.app.core.image.RemoteAppIconLoader
import com.cmzc.xj_file.app.core.image.ThumbnailLoader
import com.cmzc.xj_file.app.core.image.Thumbnails
import com.cmzc.xj_file.app.core.playback.MediaTypes
import com.cmzc.xj_file.app.core.playback.PlaybackSource
import com.cmzc.xj_file.app.core.playback.ViewerPage
import com.cmzc.xj_file.app.core.settings.AppSettings
import com.cmzc.xj_file.app.core.text.TextBom
import com.cmzc.xj_file.app.core.text.TextCodecImpl
import com.cmzc.xj_file.app.core.text.TextDocument
import com.cmzc.xj_file.app.core.text.TextEncodeException
import com.cmzc.xj_file.app.core.text.TextFileTypes
import com.cmzc.xj_file.app.ui.editor.EditorSaveRequest
import com.cmzc.xj_file.app.core.model.RecentPath
import com.cmzc.xj_file.app.core.model.ShortcutDir
import com.cmzc.xj_file.app.core.repo.ConnectionRepository
import com.cmzc.xj_file.app.core.repo.RecentPathRepository
import com.cmzc.xj_file.app.core.repo.ShortcutDirRepository
import com.cmzc.xj_file.app.core.transfer.TransferManager
import com.cmzc.xj_file.app.core.util.StorageKind
import com.cmzc.xj_file.app.core.util.DebugLog
import com.cmzc.xj_file.app.core.transfer.TransferStatus
import com.cmzc.xj_file.app.ui.ConflictAction
import com.cmzc.xj_file.app.ui.ConflictItem
import com.cmzc.xj_file.app.ui.ConflictResolution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import java.io.File

enum class PanelSide { LEFT, RIGHT }

data class PanelUiState(
    val sourceId: String = "local",
    val sourceLabel: String = "本地存储",
    val currentPath: String = PathUtil.LOCAL_ROOT,
    val items: List<RemoteFile> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val selection: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val canGoUp: Boolean = false,
    val history: List<String> = emptyList(),
    val forwardStack: List<String> = emptyList(),
    /**
     * 每完成一次目录加载就 +1（成功、失败都算）。
     *
     * 为什么不用 loading 当信号：本地目录刷新几十毫秒就结束了，Compose 可能一次重组里
     * 只看到 loading=false → false，LaunchedEffect(state.loading) 不会重跑，
     * 下拉刷新的「已刷新」标记就会一直卡住，下一次下拉被无声吞掉。
     * 用只增不减的计数则无论怎么合并都不会丢。
     */
    val loadSeq: Long = 0L,
    /**
     * SFTP 连接根目录上的磁盘清单（卷标/容量/介质类型）。
     * 只有停在根目录时才有内容 —— 它代表"这台机器上有哪些盘"，离开根目录就清空。
     */
    val drives: List<DriveInfo> = emptyList(),
    /** 正在向 PC 要磁盘清单。为 true 时面板显示"正在加载"占位，且不允许点。 */
    val drivesLoading: Boolean = false
) {
    val canGoBack: Boolean get() = history.isNotEmpty()
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()

    /**
     * 文件列表上方那一行来源名。
     *
     * 两个同名连接（同一台机器既开了 SMB 又开了 SFTP）在路径条上原本长得一模一样，
     * 只能靠路径猜；本地则完全看不出是内置闪存还是外接硬盘。所以在这里补一个类型后缀：
     * 本地 → （SSD/HDD），网络 → （SMB/SFTP）。名字本身（sourceLabel）不动，
     * 因为它还要写进「最近」记录，改了就成历史数据里的脏值。
     */
    val sourceDisplayLabel: String
        get() {
            val type = when {
                sourceId == "local" -> StorageKind.label
                sourceId.startsWith("smb:") -> "SMB"
                sourceId.startsWith("sftp:") -> "SFTP"
                else -> null
            } ?: return sourceLabel
            // 连接名本来就叫「SMB」的话，别再显示成「SMB（SMB）」
            return if (sourceLabel.equals(type, ignoreCase = true)) sourceLabel
            else "$sourceLabel（$type）"
        }
}

/** 冲突对话框状态。null 表示当前没有冲突要处理。 */
data class ConflictUiState(
    val conflict: ConflictItem,
    val index: Int,
    val total: Int
)

/**
 * 一次「内置查看」请求（阶段四）。
 *
 * 播放器和图片查看器共用同一个请求/覆盖层：对 MainScreen 来说两者都是
 * 「整屏盖上去、有返回、失败要回退」，没必要做成两套状态。
 * 具体走哪个页面由当前页的扩展名决定。
 *
 * [pages] 是**同类**页面（图片只含图片、视频只含视频），翻页永远不跨类型；
 * [strip] 是底部缩略图条，**总是有内容**：严格分离时就是同类列表，
 * 打开「混合浏览」后换成同目录的全部媒体。
 */
data class ViewerRequest(
    val side: PanelSide,
    val pages: List<ViewerPage>,
    val startIndex: Int,
    val strip: List<ViewerPage>,
    /** 本地文件：万一内置打不开，直接交回系统应用，不用再下载一遍。 */
    val isLocal: Boolean = false,
    /**
     * 相册里点开的：`pages` 是图片 + 视频混排，左右滑动要能跨类型。
     *
     * 普通文件列表点开时仍然是「同类才翻页」，只有相册这一条路是混排 ——
     * 用户要的就是"在相册里连着看"，不是把文件列表也改成混排。
     */
    val mixed: Boolean = false,
) {
    val current: ViewerPage
        get() = pages.getOrElse(startIndex) { pages.first() }
}

data class PdfViewerRequest(
    val file: File,
    val title: String,
)

/**
 * 一次「内置文本编辑」会话。
 *
 * [file] 只用于显示和保存目标路径；真正的原始字节放在 ViewModel 的
 * `editorBytes` 里（不落缓存），切编码时用它重新解码。
 */
data class TextEditorSession(
    val side: PanelSide,
    val file: RemoteFile,
    val document: TextDocument,
    /** 打开时解码遇到过非法字节；UI 可以据此提示「编码可能选错」。 */
    val hadErrors: Boolean = false,
)

/** 保存时要么按原路径覆盖，要么在冲突后明确选择覆盖，或只落本地副本。 */
enum class EditorSavePolicy { NORMAL, OVERWRITE, LOCAL_COPY }

/** 保存结果：冲突不是失败，UI 需要弹窗让用户决定。 */
enum class EditorSaveResult { SAVED, CONFLICT, FAILED }

/**
 * 一次「类相册视图」请求。
 *
 * 只收当前目录里的**图片和视频**（音频不进网格），[pages] 直接复用查看页的结构，
 * 所以点缩略图打开时走的是同一条查看链路，不需要第二套数据源。
 */
data class GalleryRequest(
    val side: PanelSide,
    val title: String,
    val pages: List<ViewerPage>,
)

/**
 * 当前打开的压缩包。targetDir 是打开时所在的面板目录，解压默认落在这里。
 * 本地压缩包直接读原文件；远程压缩包先落到 cache，关闭时清理。
 */
data class ArchiveSession(
    val side: PanelSide,
    val sourceId: String,
    val targetDir: String,
    val archiveName: String,
    val localFile: File,
    val entries: List<ArchiveEntryInfo>,
    val temporary: Boolean,
)

/** 压缩包内文本条目的内置只读预览。 */
data class ArchiveTextPreview(
    val name: String,
    val text: String,
    val encoding: String,
    val hadErrors: Boolean,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ConnectionRepository(app)
    private val recentRepo = RecentPathRepository(app)
    private val shortcutRepo = ShortcutDirRepository(app)
    private val localFs = LocalFileSystem()
    private val remoteSystems = mutableMapOf<String, RemoteFileSystem>()
    /** 远程文件列表的缩略图串行读取：SftpFileSystem 在流关闭前不能并发操作。 */
    private val remoteThumbnailMutex = Mutex()
    private val remoteAppIconMutex = Mutex()

    private val anchorBySide = mutableMapOf<PanelSide, String>()

    private val _connections = MutableStateFlow<List<Connection>>(emptyList())
    val connections: StateFlow<List<Connection>> = _connections.asStateFlow()

    private val _recentPaths = MutableStateFlow<List<RecentPath>>(emptyList())
    val recentPaths: StateFlow<List<RecentPath>> = _recentPaths.asStateFlow()

    private val _shortcutDirs = MutableStateFlow<List<ShortcutDir>>(emptyList())
    val shortcutDirs: StateFlow<List<ShortcutDir>> = _shortcutDirs.asStateFlow()

    private val _left = MutableStateFlow(PanelUiState())
    val left: StateFlow<PanelUiState> = _left.asStateFlow()

    private val _right = MutableStateFlow(PanelUiState())
    val right: StateFlow<PanelUiState> = _right.asStateFlow()

    /** 最近一次新建的文件/文件夹，用于 UI 自动滚动 + 高亮。null 表示没有。 */
    private val _lastCreated = MutableStateFlow<Pair<PanelSide, String>?>(null)
    val lastCreated: StateFlow<Pair<PanelSide, String>?> = _lastCreated.asStateFlow()

    /** 当前待处理的冲突。null 表示没有冲突。 */
    private val _conflict = MutableStateFlow<ConflictUiState?>(null)
    val conflict: StateFlow<ConflictUiState?> = _conflict.asStateFlow()

    /** 内部：本次批量冲突会话。null 表示没有进行中的会话。 */
    private var conflictSession: ConflictSession? = null
    /**
     * 每个传输批次的元数据。一次复制/移动 = 一个批次。
     * UI 需要它才能从 batchId 反查：批次显示名、是复制还是移动、源/目标栏侧。
     * 内存内保存，不持久化。
     */
    data class BatchInfo(
        val batchId: String,
        val batchName: String,
        val isMove: Boolean,
        val srcSide: PanelSide,
        val dstSide: PanelSide
    )

    /** 批次元数据表，UI 用来查。 */
    private val _batchInfos = MutableStateFlow<Map<String, BatchInfo>>(emptyMap())
    val batchInfos: StateFlow<Map<String, BatchInfo>> = _batchInfos.asStateFlow()
    /** 当前弹进度对话框的批次 ID。null 表示没有弹窗。 */
    private val _activeBatchId = MutableStateFlow<String?>(null)
    val activeBatchId: StateFlow<String?> = _activeBatchId.asStateFlow()

    /** 当前正在做收尾校验的批次 ID。null 表示没有校验在进行。 */
    private val _verifyingBatchId = MutableStateFlow<String?>(null)
    val verifyingBatchId: StateFlow<String?> = _verifyingBatchId.asStateFlow()

    /** 远程文件下载到缓存后待打开。MainScreen 观察后启动系统 Intent。null 表示没有。 */
    private val _pendingOpen = MutableStateFlow<File?>(null)
    val pendingOpen: StateFlow<File?> = _pendingOpen.asStateFlow()

    /** 待内置查看的文件（阶段四：播放器 / 图片查看器）。非 null 时 MainScreen 盖一层。 */
    private val _pendingViewer = MutableStateFlow<ViewerRequest?>(null)
    val pendingViewer: StateFlow<ViewerRequest?> = _pendingViewer.asStateFlow()

    /** 内置 PDF 查看器；非 null 时 MainScreen 盖一层。 */
    private val _pendingPdf = MutableStateFlow<PdfViewerRequest?>(null)
    val pendingPdf: StateFlow<PdfViewerRequest?> = _pendingPdf.asStateFlow()

    /** 类相册视图：非 null 时盖一层 4 列网格。和查看页可以叠加（查看页在上）。 */
    private val _gallery = MutableStateFlow<GalleryRequest?>(null)
    val gallery: StateFlow<GalleryRequest?> = _gallery.asStateFlow()

    /** 当前打开的压缩包；null 表示没有压缩包页面。 */
    private val _pendingArchive = MutableStateFlow<ArchiveSession?>(null)
    val pendingArchive: StateFlow<ArchiveSession?> = _pendingArchive.asStateFlow()

    /** 压缩包解压中状态和进度。 */
    private val _archiveBusy = MutableStateFlow(false)
    val archiveBusy: StateFlow<Boolean> = _archiveBusy.asStateFlow()

    private val _archiveProgress = MutableStateFlow(0 to 0)
    val archiveProgress: StateFlow<Pair<Int, Int>> = _archiveProgress.asStateFlow()

    private val _archiveNotice = MutableStateFlow<String?>(null)
    val archiveNotice: StateFlow<String?> = _archiveNotice.asStateFlow()

    private val _archiveTextPreview = MutableStateFlow<ArchiveTextPreview?>(null)
    val archiveTextPreview: StateFlow<ArchiveTextPreview?> = _archiveTextPreview.asStateFlow()

    /** 压缩包只读句柄只在 ViewModel 内保存，Compose 不持有 IO 资源。 */
    private var archiveReader: ArchiveReader? = null

    /** 内置文本编辑器：非 null 时盖一层整屏编辑器。 */
    private val _pendingEditor = MutableStateFlow<TextEditorSession?>(null)
    val pendingEditor: StateFlow<TextEditorSession?> = _pendingEditor.asStateFlow()

    /** 编辑器的一句话提示（覆盖失败落盘路径、编码可能不对等），MainScreen 用 Toast 显示。 */
    private val _editorNotice = MutableStateFlow<String?>(null)
    val editorNotice: StateFlow<String?> = _editorNotice.asStateFlow()

    /**
     * 编辑器打开时的原始字节 / 数据源。
     *
     * 用户要求远程文件读进内存、不落缓存；切编码也要从**原始字节**重新解，
     * 而不是拿已经解过的 String 再转，所以这里单独留一份。
     */
    private var editorBytes: ByteArray? = null
    private var editorSourceId: String? = null
    /** 打开时的远程/本地文件基线，用于保存前冲突检测。 */
    private var editorBaseMtime: Long = 0L
    private var editorBaseSize: Long = -1L

    /** 每个批次对应的目标文件系统，取消时用来删目标文件。 */
    private val batchDstFs = mutableMapOf<String, RemoteFileSystem>()

    /** 每个批次在目标位置建过的目录，取消时用来清理空目录。 */
    private val batchDirs = mutableMapOf<String, MutableList<String>>()
    /** 本次会话内已经预定出去的（重命名后）目标路径，防止两个冲突撞同一个新名字。 */
    private val reservedDstPaths = mutableSetOf<String>()

    // ===== 3C 冲突处理用的内部数据 =====

    /** 一个待传项（目录或文件）。 */
    private class PendingTransfer(
        val srcFs: RemoteFileSystem,
        val srcPath: String,
        val srcName: String,
        val dstFs: RemoteFileSystem,
        val dstPath: String,
        val totalBytes: Long,
        val isDirectory: Boolean
    )

    /** 一个已经入队的文件传输项，用于移动收尾时校验大小。 */
    private class TransferredFile(
        val taskId: String,
        val srcFs: RemoteFileSystem,
        val srcPath: String,
        val dstFs: RemoteFileSystem,
        val dstPath: String
    )

    /** 顶层选中项。taskIndexes 记录它在 tasks 列表里对应的文件索引。 */
    private class PendingTopItem(
        val srcFs: RemoteFileSystem,
        val srcPath: String,
        val taskIndexes: MutableList<Int> = mutableListOf()
    )

    /** 一个冲突：pendingIndex 指向 tasks 列表中的项。 */
    private class PendingConflict(
        val pendingIndex: Int,
        val autoRenamedName: String,
        /** 目标就是源文件本身（同目录、没改名）：只能重命名，绝不能覆盖 */
        val isSelf: Boolean = false
    )

    /** 本次批量的冲突会话。 */
    private class ConflictSession(
        val batchId: String,
        val batchName: String,
        val side: PanelSide,
        val dstSide: PanelSide,
        val isMove: Boolean,
        val srcFs: RemoteFileSystem,
        val dstFs: RemoteFileSystem,
        val topItems: List<PendingTopItem>,
        val tasks: List<PendingTransfer>,
        val conflicts: List<PendingConflict>,
        val decisions: MutableMap<Int, ConflictResolution>,
        var cursor: Int
    )

    init {
        _connections.value = repo.loadList()
        _recentPaths.value = recentRepo.load()
        _shortcutDirs.value = shortcutRepo.load()
        // 连接配置就绪后，让 TransferManager 把上次没传完的任务重新入队。
        TransferManager.registerRestoreResolver { tag -> fsForRestoreTag(tag) }
        refresh(PanelSide.LEFT)
        refresh(PanelSide.RIGHT)

        // 「图片与视频混着翻」可能在设置页被改（那是另一个 Activity），
        // 回来时查看页还开着，这里跟着刷新，免得用户以为开关没生效。
        viewModelScope.launch {
            AppSettings.mediaIsolation.collect { refreshViewerStrip() }
        }

        // 监听传输任务：清理已结束批次的临时记录。
        // 弹窗关闭改由 startEnqueue 的 finally 负责（避免在校验期间误关）。
        viewModelScope.launch {
            TransferManager.tasks.collect { list ->
                val keys = batchDstFs.keys.toList()
                for (bid in keys) {
                    val bt = list.filter { it.batchId == bid }
                    val still = bt.any {
                        it.status == TransferStatus.PENDING || it.status == TransferStatus.RUNNING
                    }
                    if (bt.isNotEmpty() && !still) {
                        batchDstFs.remove(bid)
                        batchDirs.remove(bid)
                    }
                }
            }
        }
    }

    private fun stateOf(side: PanelSide) = if (side == PanelSide.LEFT) _left else _right

    private fun fsFor(sourceId: String): RemoteFileSystem {
        if (sourceId == "local") return localFs
        remoteSystems[sourceId]?.let { return it }
        error("数据源未初始化: $sourceId")
    }

    private fun buildFs(conn: Connection): RemoteFileSystem = when (conn.type) {
        ConnectionType.SMB -> SmbFileSystem(
            host = conn.host, port = conn.port,
            username = conn.username, password = conn.password, domain = conn.domain
        )
        ConnectionType.SFTP -> SftpFileSystem(
            host = conn.host, port = conn.port,
            username = conn.username, password = conn.password,
            privateKeyPath = conn.privateKeyPath, passphrase = conn.passphrase,
            cacheDir = getApplication<Application>().cacheDir
        )
    }

    private fun fsForTag(tag: String): RemoteFileSystem? {
        if (tag == "local") return localFs
        val type = tag.substringBefore(':', "").uppercase()
        val host = tag.substringAfter(':', "")
        if (host.isBlank()) return null
        val conn = _connections.value.firstOrNull {
            it.host == host && it.type.name == type
        } ?: return null
        val sourceId = "${conn.type.name.lowercase()}:${conn.id}"
        return remoteSystems.getOrPut(sourceId) { buildFs(conn) }
    }

    /** 恢复持久化传输任务时按 tag 找/建文件系统。 */
    private fun fsForRestoreTag(tag: String): RemoteFileSystem? =
        runCatching { fsForTag(tag) }.getOrNull()

    fun refresh(side: PanelSide, clearLastCreated: Boolean = true) {
        val s = stateOf(side).value
        // 手动刷新必须绕过磁盘清单的 30 秒缓存：刚插/刚换碟时用户按刷新就是要重新探测。
        if (s.sourceId.startsWith("sftp:")) {
            DriveProbe.invalidate(s.sourceId)
        }
        load(
            side, s.sourceId, s.sourceLabel, s.currentPath,
            pushHistory = false,
            clearLastCreated = clearLastCreated
        )
    }

    fun navigate(side: PanelSide, path: String) {
        val s = stateOf(side).value
        load(side, s.sourceId, s.sourceLabel, path, pushHistory = true)
    }

    fun goUp(side: PanelSide) {
        val s = stateOf(side).value
        val parent = if (PathUtil.isRemote(s.currentPath)) PathUtil.remoteParent(s.currentPath)
        else PathUtil.localParent(s.currentPath)
        if (parent != null) load(side, s.sourceId, s.sourceLabel, parent, pushHistory = true)
    }

    fun goBack(side: PanelSide) {
        val flow = stateOf(side)
        val s = flow.value
        if (s.history.isEmpty()) return
        val target = s.history.last()
        flow.update { it.copy(history = it.history.dropLast(1), forwardStack = it.forwardStack + it.currentPath) }
        load(side, s.sourceId, s.sourceLabel, target, pushHistory = false)
    }

    fun goForward(side: PanelSide) {
        val flow = stateOf(side)
        val s = flow.value
        if (s.forwardStack.isEmpty()) return
        val target = s.forwardStack.last()
        flow.update { it.copy(history = it.history + it.currentPath, forwardStack = it.forwardStack.dropLast(1)) }
        load(side, s.sourceId, s.sourceLabel, target, pushHistory = false)
    }

    fun navigateToRecent(side: PanelSide, recent: RecentPath) {
        val sourceId = recent.sourceId
        val sourceLabel = recent.sourceLabel
        if (sourceId != "local" && !remoteSystems.containsKey(sourceId)) {
            val connId = sourceId.substringAfter(':')
            val conn = _connections.value.firstOrNull { it.id == connId }
            if (conn == null) {
                stateOf(side).update { it.copy(history = emptyList(), forwardStack = emptyList()) }
                load(side, "local", "本地存储", PathUtil.LOCAL_ROOT, pushHistory = false)
                return
            }
            remoteSystems[sourceId] = buildFs(conn)
        }
        load(side, sourceId, sourceLabel, recent.path, pushHistory = true)
    }

    fun switchToLocal(side: PanelSide) {
        stateOf(side).update { it.copy(history = emptyList(), forwardStack = emptyList()) }
        load(side, "local", "本地存储", PathUtil.LOCAL_ROOT, pushHistory = false)
    }

    fun switchToConnection(
        side: PanelSide,
        conn: Connection,
        explicitPath: String? = null
    ) {
        val sourceId = "${conn.type.name.lowercase()}:${conn.id}"
        if (!remoteSystems.containsKey(sourceId)) {
            remoteSystems[sourceId] = buildFs(conn)
        }
        val initialPath = explicitPath
            ?.takeIf { it.isNotBlank() }
            ?: when (conn.type) {
                ConnectionType.SMB -> if (conn.shareName.isNotBlank())
                    PathUtil.normalizeRemote("//${conn.host}/${conn.shareName}/")
                else "//${conn.host}/"
                ConnectionType.SFTP -> PathUtil.normalizeRemote("//${conn.host}/")
            }
        stateOf(side).update { it.copy(history = emptyList(), forwardStack = emptyList()) }
        load(side, sourceId, conn.name.ifBlank { conn.host }, initialPath, pushHistory = false)
    }

    private fun load(
        side: PanelSide,
        sourceId: String,
        sourceLabel: String,
        path: String,
        pushHistory: Boolean = true,
        clearLastCreated: Boolean = true
    ) {
        if (clearLastCreated) {
            _lastCreated.value = null
        }
        val flow = stateOf(side)
        flow.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val fs = fsFor(sourceId)
                val items = fs.list(path)
                val parent = if (PathUtil.isRemote(path)) PathUtil.remoteParent(path)
                else PathUtil.localParent(path)
                flow.update { st ->
                    val samePath = st.currentPath == path
                    val doPush = pushHistory && !samePath
                    val newHistory = if (doPush) st.history + st.currentPath else st.history
                    val newForward = if (doPush) emptyList<String>() else st.forwardStack
                    st.copy(
                        sourceId = sourceId,
                        sourceLabel = sourceLabel,
                        currentPath = path,
                        items = items,
                        loading = false,
                        error = null,
                        selection = emptySet(),
                        selectionMode = false,
                        canGoUp = parent != null,
                        history = newHistory,
                        forwardStack = newForward,
                        loadSeq = st.loadSeq + 1,
                        // 磁盘清单只属于根目录；先清掉，探针回来再填
                        drives = emptyList(),
                        drivesLoading = false
                    )
                }
                maybeProbeDrives(side, sourceId, path)
                anchorBySide.remove(side)
            } catch (t: Throwable) {
                flow.update {
                    it.copy(
                        loading = false,
                        error = t.message ?: t.javaClass.simpleName,
                        loadSeq = it.loadSeq + 1
                    )
                }
            }
        }
    }

    /**
     * 点了「光驱」那一行：按盘里是什么分流。
     *
     * - 没盘 → 提示，不报错崩；
     * - 音频 CD → 交给 CD 播放页（音轨不是文件，不能当目录进）；
     * - 视频光盘（有 VIDEO_TS）→ 明确告诉用户不支持，而不是让他点进去看见一堆 VOB 打不开；
     * - 其它（数据盘）→ 正常当目录浏览。
     */
    fun openOpticalDrive(side: PanelSide, item: RemoteFile, drive: DriveInfo) {
        if (!drive.hasMedia) {
            stateOf(side).update { it.copy(error = "光驱里没有光盘") }
            return
        }
        if (drive.isAudioCd) {
            openAudioCd(side, drive)
            return
        }
        // 视频光盘在探测阶段就认出来了（有 VIDEO_TS 目录），不用等点进去才发现
        if (drive.optical == com.cmzc.xj_file.app.core.disk.OpticalMedia.DVD_VIDEO) {
            stateOf(side).update { it.copy(error = "这是视频光盘（DVD-Video），暂不支持播放") }
            return
        }
        navigate(side, item.path)
    }

    /**
     * 点开音频 CD：把 TOC 里的每条音轨包装成播放页的一页。
     *
     * 为什么复用 ViewerRequest 而不是新做一个 CD 页面：用户要求「UI 和放音乐一样」。
     * 复用之后封面占位、标题、进度条、上一首/下一首全是现成的，也不会多出一套要维护的界面；
     * 音频页本来就没有倍速控件，所以「禁止调倍速」也天然满足。
     */
    private fun openAudioCd(side: PanelSide, drive: DriveInfo) {
        val st = stateOf(side).value
        val sftp = runCatching { fsFor(st.sourceId) }.getOrNull() as? SftpFileSystem
        if (sftp == null) {
            stateOf(side).update { it.copy(error = "音频 CD 需要走 SFTP 连接") }
            return
        }
        if (drive.tracks.isNotEmpty()) {
            showAudioCd(side, sftp, drive)
            return
        }
        // 探测阶段没读到 TOC（刚插碟 / 光驱忙）：这里补一次，读完直接开播放页
        stateOf(side).update { it.copy(loading = true) }
        viewModelScope.launch {
            val fetched = try {
                DriveProbe.loadTracks(getApplication(), sftp, listOf(drive.letter))
            } catch (t: Throwable) {
                null
            } finally {
                stateOf(side).update { it.copy(loading = false) }
            }
            val entry = fetched?.get(drive.letter.uppercase())
            if (entry == null || entry.second.isEmpty()) {
                stateOf(side).update { it.copy(error = "没能读到 CD 音轨信息") }
                return@launch
            }
            showAudioCd(side, sftp, drive.copy(audioCd = entry.first, tracks = entry.second))
        }
    }

    /** 拿到音轨表之后，把每条轨包装成播放页的一项（复用音频页，不另做 UI）。 */
    private fun showAudioCd(side: PanelSide, sftp: SftpFileSystem, drive: DriveInfo) {
        // 每次打开 CD 清一遍诊断日志，出问题时看到的只有这一次的记录
        DebugLog.clear(getApplication())
        val pages = drive.tracks.map { track ->
            val file = RemoteFile(
                name = "音轨 ${track.number}",
                path = "cdda://${drive.letter}/${track.number}",
                isDirectory = false,
                size = WavHeader.SIZE + track.pcmBytes,
                lastModified = 0L,
            )
            ViewerPage(file, PlaybackSource.Cdda(sftp, drive.letter, track, file.name))
        }
        _pendingViewer.value = ViewerRequest(
            side = side,
            pages = pages,
            startIndex = 0,
            // CD 没有缩略图条：音轨不是文件，缩略图条在这里既没意义也没数据
            strip = emptyList(),
            isLocal = false,
        )
    }

    /**
     * 停在 SFTP 连接根目录时，向 PC 要一份磁盘清单（异步，不挡列表显示）。
     *
     * 慢（实测一次约 3 秒）：它是 SSH 上去跑一段 PowerShell，所以绝不能挡着目录加载。
     * 回来时如果用户已经走了，就丢掉结果，别把旧清单填到新目录上。
     */
    private fun maybeProbeDrives(side: PanelSide, sourceId: String, path: String) {
        if (!sourceId.startsWith("sftp:")) return
        val sftp = runCatching { fsFor(sourceId) }.getOrNull() as? SftpFileSystem ?: return
        val isRoot = path.trimEnd('/').equals(sftp.rootPath.trimEnd('/'), ignoreCase = true)
        if (!isRoot) return
        stateOf(side).update { if (it.currentPath == path) it.copy(drivesLoading = true) else it }
        viewModelScope.launch {
            val list = try {
                DriveProbe.load(getApplication(), sftp, sourceId)
            } finally {
                // 无论成败都要收回「加载中」，否则面板会一直不可点
                stateOf(side).update { st ->
                    if (st.sourceId == sourceId && st.currentPath == path) {
                        st.copy(drivesLoading = false)
                    } else st
                }
            } ?: return@launch
            stateOf(side).update { st ->
                if (st.sourceId == sourceId && st.currentPath == path) st.copy(drives = list) else st
            }
        }
    }

    private fun recordRecent(path: String, sourceId: String, sourceLabel: String) {
        val list = _recentPaths.value.toMutableList()
        list.removeAll { it.path == path && it.sourceId == sourceId }
        list.add(0, RecentPath(path, sourceId, sourceLabel, System.currentTimeMillis()))
        val limited = list.take(10)
        _recentPaths.value = limited
        recentRepo.save(limited)
    }

    fun clearRecent() {
        _recentPaths.value = emptyList()
        recentRepo.save(emptyList())
    }

    fun clearLastCreated() {
        _lastCreated.value = null
    }

    fun addShortcutDir(name: String, path: String, connectionId: String? = null) {
        val p = path.trim()
        if (p.isEmpty()) return
        val n = name.trim().ifBlank { defaultNameFor(p) }
        val list = _shortcutDirs.value.toMutableList()
        if (list.any { it.path == p && it.connectionId == connectionId }) return
        list.add(ShortcutDir(n, p, connectionId))
        _shortcutDirs.value = list
        shortcutRepo.save(list)
    }

    fun addNetworkShortcut(conn: Connection) {
        repo.save(conn)
        _connections.value = repo.loadList()

        val initialPath = when (conn.type) {
            ConnectionType.SMB -> if (conn.shareName.isNotBlank())
                PathUtil.normalizeRemote("//${conn.host}/${conn.shareName}/")
            else "//${conn.host}/"
            ConnectionType.SFTP -> PathUtil.normalizeRemote("//${conn.host}/")
        }
        val name = conn.name.ifBlank { conn.host }

        val list = _shortcutDirs.value.toMutableList()
        if (list.any { it.path == initialPath && it.connectionId == conn.id }) return
        list.add(ShortcutDir(name, initialPath, conn.id))
        _shortcutDirs.value = list
        shortcutRepo.save(list)
    }

    fun removeShortcutDir(path: String, connectionId: String? = null) {
        val list = _shortcutDirs.value.filterNot {
            it.path == path && it.connectionId == connectionId
        }
        _shortcutDirs.value = list
        shortcutRepo.save(list)
    }

    fun renameShortcutDir(path: String, newName: String, connectionId: String? = null) {
        val n = newName.trim()
        if (n.isEmpty()) return
        val list = _shortcutDirs.value.map {
            if (it.path == path && it.connectionId == connectionId) it.copy(name = n) else it
        }
        _shortcutDirs.value = list
        shortcutRepo.save(list)
    }

    private fun defaultNameFor(path: String): String {
        if (path == "/") return "根目录"
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return path
        val last = trimmed.substringAfterLast('/')
        return if (last.isEmpty()) path else last
    }

    fun selectSingle(side: PanelSide, path: String) {
        stateOf(side).update { it.copy(selection = setOf(path), selectionMode = false) }
        anchorBySide[side] = path
    }

    fun swipeSelect(side: PanelSide, path: String) {
        val st = stateOf(side).value
        val items = st.items
        val anchor = anchorBySide[side]

        if (anchor == null) {
            stateOf(side).update {
                it.copy(selection = it.selection + path, selectionMode = true)
            }
            anchorBySide[side] = path
            return
        }

        val aIdx = items.indexOfFirst { it.path == anchor }
        val bIdx = items.indexOfFirst { it.path == path }
        if (aIdx < 0 || bIdx < 0) {
            stateOf(side).update {
                it.copy(selection = it.selection + path, selectionMode = true)
            }
            anchorBySide[side] = path
            return
        }
        val lo = minOf(aIdx, bIdx)
        val hi = maxOf(aIdx, bIdx)
        val range = items.subList(lo, hi + 1).map { it.path }.toSet()
        stateOf(side).update {
            it.copy(selection = it.selection + range, selectionMode = true)
        }
        anchorBySide.remove(side)
    }

    fun toggleSelect(side: PanelSide, path: String) {
        val flow = stateOf(side)
        flow.update { st ->
            val sel = st.selection.toMutableSet()
            if (!sel.add(path)) sel.remove(path)
            st.copy(selection = sel, selectionMode = sel.isNotEmpty())
        }
    }

    fun clearSelection(side: PanelSide) {
        stateOf(side).update { it.copy(selection = emptySet(), selectionMode = false) }
        anchorBySide.remove(side)
    }

    /** 全选当前栏所有项。 */
    fun selectAll(side: PanelSide) {
        val st = stateOf(side).value
        val all = st.items.map { it.path }.toSet()
        stateOf(side).update { it.copy(selection = all, selectionMode = all.isNotEmpty()) }
        anchorBySide.remove(side)
    }

    fun clearAllSelection() {
        clearSelection(PanelSide.LEFT)
        clearSelection(PanelSide.RIGHT)
    }

    fun clearError(side: PanelSide) {
        stateOf(side).update { it.copy(error = null) }
    }

    fun swapSides() {
        val l = _left.value
        val r = _right.value
        _left.update { it.copy(history = emptyList(), forwardStack = emptyList()) }
        _right.update { it.copy(history = emptyList(), forwardStack = emptyList()) }
        load(PanelSide.LEFT, r.sourceId, r.sourceLabel, r.currentPath, pushHistory = false)
        load(PanelSide.RIGHT, l.sourceId, l.sourceLabel, l.currentPath, pushHistory = false)
    }

    fun createFolder(side: PanelSide, name: String) {
        val s = stateOf(side).value
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                val fs = fsFor(s.sourceId)
                val fullPath = joinPath(s.currentPath, trimmed)
                val normalizedFullPath = if (PathUtil.isRemote(fullPath))
                    PathUtil.normalizeRemote(fullPath)
                else PathUtil.normalizeLocal(fullPath)
                fs.mkdir(normalizedFullPath)
                recordRecent(s.currentPath, s.sourceId, s.sourceLabel)
                _lastCreated.value = side to normalizedFullPath
                refresh(side, clearLastCreated = false)
            } catch (t: Throwable) {
                stateOf(side).update { it.copy(error = "新建文件夹失败：${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    fun createFile(side: PanelSide, name: String) {
        val s = stateOf(side).value
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                val fs = fsFor(s.sourceId)
                val fullPath = joinPath(s.currentPath, trimmed)
                val normalizedFullPath = if (PathUtil.isRemote(fullPath))
                    PathUtil.normalizeRemote(fullPath)
                else fullPath
                val tmp = File.createTempFile(
                    "xj_empty_", null,
                    getApplication<android.app.Application>().cacheDir
                )
                tmp.writeBytes(ByteArray(0))
                try { fs.upload(tmp, normalizedFullPath) { _, _ -> } } finally { tmp.delete() }
                recordRecent(s.currentPath, s.sourceId, s.sourceLabel)
                _lastCreated.value = side to normalizedFullPath
                refresh(side, clearLastCreated = false)
            } catch (t: Throwable) {
                stateOf(side).update { it.copy(error = "新建文件失败：${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    /**
     * 把当前选中的文件/文件夹压缩到当前目录。
     *
     * 先在本机 cache 里生成压缩包，再一次性写到目标目录：
     * - 避免 SFTP 读写抢同一条车道；
     * - 失败时只删临时包，不会在目标留半截文件；
     * - 远端目标走 writeFrom，本地目标直接 copy。
     *
     * 目标同名文件直接拒绝，不做「静默覆盖」——压缩包通常比原文件更贵重。
     */
    fun compressSelection(side: PanelSide, request: CompressRequest) {
        val s = stateOf(side).value
        val selected = s.items.filter { it.path in s.selection }
        if (selected.isEmpty()) return
        val hasDirectory = selected.any { it.isDirectory }
        if (!request.type.canUseFor(selected.size, hasDirectory)) {
            stateOf(side).update {
                it.copy(error = "压缩失败：${request.type.note}")
            }
            return
        }
        val archiveName = normalizeArchiveName(request.name, request.type)
        val target = joinPath(s.currentPath, archiveName)
        val tempDir = File(getApplication<Application>().cacheDir, "compress").apply { mkdirs() }
        val temp = File(tempDir, "${UUID.randomUUID()}.${request.type.extension}")
        stateOf(side).update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val fs = fsFor(s.sourceId)
                if (fs.exists(target)) {
                    stateOf(side).update { it.copy(error = "压缩失败：同名文件已存在「$archiveName」") }
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    ArchiveWriter.create(fs, selected, temp, request.type, request.level)
                }
                withContext(Dispatchers.IO) {
                    if (fs is LocalFileSystem) {
                        val dst = File(target)
                        dst.parentFile?.mkdirs()
                        temp.copyTo(dst, overwrite = true)
                    } else {
                        temp.inputStream().use { input ->
                            fs.writeFrom(input, target) { _, _ -> }
                        }
                    }
                }
                recordRecent(s.currentPath, s.sourceId, s.sourceLabel)
                _lastCreated.value = side to target
                refresh(side, clearLastCreated = false)
            } catch (t: Throwable) {
                stateOf(side).update {
                    it.copy(error = "压缩失败：${t.message ?: t.javaClass.simpleName}")
                }
            } finally {
                runCatching { temp.delete() }
                stateOf(side).update { it.copy(loading = false) }
            }
        }
    }

    /** 压缩包名只允许当前目录下的一个文件名；非法字符替换，扩展名统一成当前格式。 */
    private fun normalizeArchiveName(raw: String, type: com.cmzc.xj_file.app.core.archive.ArchiveType): String {
        val suffix = ".${type.extension}"
        val cleaned = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim('.', ' ')
        val withoutKnownSuffix = if (cleaned.endsWith(suffix, ignoreCase = true)) {
            cleaned.dropLast(suffix.length)
        } else if (cleaned.substringAfterLast('.', "").lowercase() in
            setOf("zip", "zipx", "tar", "tgz", "lzh", "7z", "gz", "xz", "zpaq", "rar")
        ) {
            cleaned.substringBeforeLast('.')
        } else {
            cleaned
        }
        return "${withoutKnownSuffix.ifBlank { "archive" }}$suffix"
    }

    fun deleteSelected(side: PanelSide) {
        val s = stateOf(side).value
        val toDelete = s.selection.toList()
        if (toDelete.isEmpty()) return
        viewModelScope.launch {
            try {
                val fs = fsFor(s.sourceId)
                var failCount = 0
                var firstError: String? = null
                toDelete.forEach { path ->
                    try {
                        fs.delete(path)
                    } catch (t: Throwable) {
                        failCount++
                        if (firstError == null) {
                            firstError = "${path.substringAfterLast('/')}: " +
                                (t.message ?: t.javaClass.simpleName)
                        }
                    }
                }
                recordRecent(s.currentPath, s.sourceId, s.sourceLabel)
                clearSelection(side)
                refresh(side)
                if (failCount > 0) {
                    stateOf(side).update {
                        it.copy(error = "删除完成，但有 $failCount 项失败：${firstError ?: "未知原因"}")
                    }
                }
            } catch (t: Throwable) {
                stateOf(side).update { it.copy(error = "删除失败：${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    fun renameSelected(side: PanelSide, newName: String) {
        val s = stateOf(side).value
        if (s.selection.size != 1) return
        val path = s.selection.first()
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                val fs = fsFor(s.sourceId)
                fs.rename(path, trimmed)
                recordRecent(s.currentPath, s.sourceId, s.sourceLabel)
                clearSelection(side)
                refresh(side)
            } catch (t: Throwable) {
                stateOf(side).update { it.copy(error = "重命名失败：${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    // ================= 阶段三：复制 / 移动（含 3C 冲突处理） =================

    fun copySelectionToOther(side: PanelSide) = startTransfer(side, isMove = false)

    fun moveSelectionToOther(side: PanelSide) = startTransfer(side, isMove = true)

    /**
     * 统一的复制/移动入口。
     * 1. 递归收集待传项
     * 2. 检测目标同名冲突
     * 3. 无冲突 → 直接入队；有冲突 → 弹对话框逐个询问
     */
    private fun startTransfer(side: PanelSide, isMove: Boolean) {
        // 若上一次批量还没处理完，先丢弃
        if (conflictSession != null) {
            conflictSession = null
            _conflict.value = null
        }
        reservedDstPaths.clear()

        val srcState = stateOf(side).value
        if (srcState.selection.isEmpty()) return
        val dstSide = if (side == PanelSide.LEFT) PanelSide.RIGHT else PanelSide.LEFT
        val dstState = stateOf(dstSide).value

        val srcFs = fsFor(srcState.sourceId)
        val dstFs = fsFor(dstState.sourceId)
        val dstDir = dstState.currentPath

        val selected = srcState.items.filter { it.path in srcState.selection }
        if (selected.isEmpty()) return

        // 同目录 / 自嵌套保护。
        // 同目录「复制」是合法需求（再复制一份，靠自动重命名生成 "xxx (1).x"），不拦。
        // 同目录「移动」等于把文件改成一个怪名字再删原文件，没有意义 —— 按钮已置灰，这里再兜一层。
        if (srcState.sourceId == dstState.sourceId) {
            val srcDir = canonPath(srcState.sourceId, srcState.currentPath)
            val dstDirNorm = canonPath(dstState.sourceId, dstState.currentPath)
            if (isMove && srcDir == dstDirNorm) {
                stateOf(side).update {
                    it.copy(error = "源目录和目标目录相同，移动没有意义，请改用重命名")
                }
                return
            }
            // 不能把目录复制/移动进它自己里面（会无限套娃）
            val intoItself = selected.any { item ->
                item.isDirectory &&
                    dstDirNorm.startsWith(canonPath(srcState.sourceId, item.path))
            }
            if (intoItself) {
                stateOf(side).update {
                    it.copy(error = "不能把目录${if (isMove) "移动" else "复制"}到它自己的子目录里")
                }
                return
            }
        }

        viewModelScope.launch {
            try {
                val tasks = mutableListOf<PendingTransfer>()
                val topItems = mutableListOf<PendingTopItem>()
                for (item in selected) {
                    val top = PendingTopItem(srcFs, item.path)
                    topItems.add(top)
                    collectTransfers(srcFs, item, dstFs, dstDir, tasks, top)
                }

                val allConflicts = detectConflicts(tasks)

                // 「目标就是源文件本身」（同目录复制）不弹窗、不给覆盖选项：直接改成 xxx (1).x。
                // 其余的冲突才需要问用户。
                val decisions = mutableMapOf<Int, ConflictResolution>()
                val conflictsToAsk = allConflicts.filter { c ->
                    if (c.isSelf) {
                        decisions[c.pendingIndex] = ConflictResolution(
                            action = ConflictAction.RENAME,
                            applyToAll = false,
                            renamedTo = c.autoRenamedName
                        )
                        false
                    } else {
                        true
                    }
                }

                val batchId = UUID.randomUUID().toString()
                val opName = if (isMove) "移动" else "复制"
                val batchName = "$opName ${selected.size} 项"
                val session = ConflictSession(
                    batchId = batchId,
                    batchName = batchName,
                    side = side,
                    dstSide = dstSide,
                    isMove = isMove,
                    srcFs = srcFs,
                    dstFs = dstFs,
                    topItems = topItems,
                    tasks = tasks,
                    conflicts = conflictsToAsk,
                    decisions = decisions,
                    cursor = 0
                )

                if (conflictsToAsk.isEmpty()) {
                    startEnqueue(session)
                } else {
                    conflictSession = session
                    showConflictAtIndex(0)
                }
            } catch (t: Throwable) {
                stateOf(side).update {
                    it.copy(error = "${if (isMove) "移动" else "复制"}失败：${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * 递归展开一个 item，把待传项加入 out。顶层项 topItem 记录属于它的文件索引。
     */
    private suspend fun collectTransfers(
        srcFs: RemoteFileSystem,
        item: RemoteFile,
        dstFs: RemoteFileSystem,
        dstDirPath: String,
        out: MutableList<PendingTransfer>,
        topItem: PendingTopItem
    ) {
        val dstChild = joinPath(dstDirPath, item.name)
        if (item.isDirectory) {
            val dstChildDir = dstChild.trimEnd('/') + "/"
            out.add(
                PendingTransfer(
                    srcFs = srcFs,
                    srcPath = item.path,
                    srcName = item.name,
                    dstFs = dstFs,
                    dstPath = dstChildDir,
                    totalBytes = 0L,
                    isDirectory = true
                )
            )
            val children = runCatching { srcFs.list(item.path) }.getOrDefault(emptyList())
            for (child in children) {
                collectTransfers(srcFs, child, dstFs, dstChildDir, out, topItem)
            }
        } else {
            val index = out.size
            out.add(
                PendingTransfer(
                    srcFs = srcFs,
                    srcPath = item.path,
                    srcName = item.name,
                    dstFs = dstFs,
                    dstPath = dstChild,
                    totalBytes = item.size,
                    isDirectory = false
                )
            )
            topItem.taskIndexes.add(index)
        }
    }

    /**
     * 目标目录里已有的名字。统一按小写存：
     * Windows/SMB 上 `A.txt` 和 `a.txt` 是同一个文件，宁可多改一次名，也不能冒覆盖的风险。
     */
    private class DirNames(val keys: MutableSet<String>) {
        fun has(name: String): Boolean = keys.contains(name.lowercase())
        fun claim(name: String) {
            keys.add(name.lowercase())
        }
    }

    /**
     * 检测冲突。
     *
     * 以前是每个文件问一次 `exists()`：1,848 个文件 = 1,848 次往返 ≈ 19 秒。
     * 现在每个**目标目录**只列一次，在内存里比对：
     *   - 目标目录还不存在 → 一定没有同名文件，零请求（复制整个文件夹到新位置就靠这条）
     *   - 目录存在但列不出来（权限/网络）→ 退回逐文件 `exists()`，慢但稳
     *   - 目录条目超过 MAX_LISTED_NAMES → 同样退回逐文件查，别把内存吃爆
     * 每个名字（包括自动改名后的）都会立刻写回集合，保证同一批里不会自己覆盖自己。
     */
    private suspend fun detectConflicts(tasks: List<PendingTransfer>): List<PendingConflict> {
        val result = mutableListOf<PendingConflict>()

        // 目标目录 → 该目录所在的 fs（同一个目录必然来自同一个 fs 实例）
        val dirFs = LinkedHashMap<String, RemoteFileSystem>()
        for (t in tasks) {
            if (t.isDirectory) continue
            dirFs[t.dstPath.substringBeforeLast('/', "")] = t.dstFs
        }

        // 每个目标目录列一次，建名字集合；建不了就不放进去，后面退回逐个查
        val listings = mutableMapOf<String, DirNames>()
        for ((dir, fs) in dirFs) {
            if (dir.isEmpty()) continue
            val dirPath = if (PathUtil.isRemote(dir)) PathUtil.normalizeRemote(dir)
            else PathUtil.normalizeLocal(dir)
            val listed = runCatching { fs.list(dirPath) }.getOrNull()
            when {
                listed == null -> {
                    // 列不出来：目录压根不存在 → 一定是空的；存在但没权限 → 退回逐个查
                    val dirExists = runCatching { fs.exists(dirPath) }.getOrDefault(false)
                    if (!dirExists) listings[dir] = DirNames(mutableSetOf())
                }
                listed.size > MAX_LISTED_NAMES -> Unit            // 太大，退回逐个查
                else -> listings[dir] = DirNames(listed.mapTo(mutableSetOf()) { it.name.lowercase() })
            }
        }

        for ((index, t) in tasks.withIndex()) {
            if (t.isDirectory) continue
            val dir = t.dstPath.substringBeforeLast('/', "")
            val name = t.dstPath.substringAfterLast('/')
            val names = listings[dir]

            val conflict: Boolean
            val autoName: String
            if (names != null) {
                conflict = names.has(name)
                autoName = if (conflict) nextFreeName(names, name) else name
                // 认领：同批次后面同名文件就能看见这个名字了（左右互搏就死在这）
                names.claim(autoName)
            } else {
                conflict = runCatching { t.dstFs.exists(t.dstPath) }.getOrDefault(false)
                autoName = if (conflict) autoRenameNameByExists(t.dstFs, t.dstPath) else name
            }
            if (!conflict) continue

            val parent = t.dstPath.substringBeforeLast('/', "")
            val reserved = if (parent.isEmpty()) autoName else "$parent/$autoName"
            reservedDstPaths.add(reserved)
            result.add(
                PendingConflict(
                    pendingIndex = index,
                    autoRenamedName = autoName,
                    isSelf = canonPathForFs(t.srcFs, t.srcPath) ==
                        canonPathForFs(t.dstFs, t.dstPath)
                )
            )
        }
        return result
    }

    /**
     * 在内存集合里算下一个空名字，规则跟 Windows 资源管理器一致：
     *
     *   a.txt（已有同名）        → a (1).txt
     *   a (1).txt（已有同名）    → a (2).txt   ← 识别已有的 " (n)" 并续号
     *   a.txt（已有 a (1).txt）  → a (2).txt   ← 从 1 往上找第一个空位
     */
    private fun nextFreeName(names: DirNames, name: String): String {
        val dotIdx = name.lastIndexOf('.')
        val rawBase = if (dotIdx > 0) name.substring(0, dotIdx) else name
        val ext = if (dotIdx > 0) name.substring(dotIdx) else ""
        val existing = Regex("""^(.*) \((\d+)\)$""").find(rawBase)
        val base = existing?.groupValues?.get(1) ?: rawBase
        var i = existing?.groupValues?.get(2)?.toIntOrNull()?.plus(1) ?: 1
        while (i < 100000) {
            val candidate = "$base ($i)$ext"
            if (!names.has(candidate)) return candidate
            i++
        }
        return "$base ($i)$ext"
    }

    /**
     * 计算自动重命名，规则跟 Windows 资源管理器一致：
     *
     *   a.txt（已有同名）        → a (1).txt
     *   a (1).txt（已有同名）    → a (2).txt   ← 识别已有的 " (n)" 并续号，不叠成 "a (1) (1).txt"
     *   a.txt（已有 a (1).txt）  → a (2).txt   ← 从 1 往上找第一个空位
     */
    /**
     * 兜底版的自动改名：目标目录太大或列不出来时用，靠逐个 `exists()` 找空名字。
     * 正常路径走 [nextFreeName]（纯内存），不会走到这里。
     */
    private suspend fun autoRenameNameByExists(fs: RemoteFileSystem, dstPath: String): String {
        val lastSlash = dstPath.lastIndexOf('/')
        val dir = if (lastSlash > 0) dstPath.substring(0, lastSlash) else ""
        val name = dstPath.substring(lastSlash + 1)
        val dotIdx = name.lastIndexOf('.')
        val rawBase = if (dotIdx > 0) name.substring(0, dotIdx) else name
        val ext = if (dotIdx > 0) name.substring(dotIdx) else ""

        // 名字本身已经带 " (n)"：剥掉后缀，从 n+1 开始续（Windows 行为）
        val existing = Regex("""^(.*) \((\d+)\)$""").find(rawBase)
        val base = existing?.groupValues?.get(1) ?: rawBase
        var i = existing?.groupValues?.get(2)?.toIntOrNull()?.plus(1) ?: 1

        while (i < 100000) {
            val candidateName = "$base ($i)$ext"
            val candidatePath = if (dir.isEmpty()) candidateName else "$dir/$candidateName"
            val exists = runCatching { fs.exists(candidatePath) }.getOrDefault(false)
            if (!exists && candidatePath !in reservedDstPaths) {
                return candidateName
            }
            i++
        }
        return "$base ($i)$ext"
    }

    private fun showConflictAtIndex(index: Int) {
        val session = conflictSession ?: return
        val pc = session.conflicts.getOrNull(index) ?: return
        val pt = session.tasks.getOrNull(pc.pendingIndex) ?: return
        _conflict.value = ConflictUiState(
            conflict = ConflictItem(
                srcPath = pt.srcPath,
                srcName = pt.srcName,
                dstPath = pt.dstPath,
                dstName = pt.dstPath.substringAfterLast('/'),
                isDirectory = pt.isDirectory,
                autoRenamedName = pc.autoRenamedName
            ),
            index = index + 1,
            total = session.conflicts.size
        )
    }

    /** 用户在冲突对话框上点了「确定」。 */
    fun resolveConflict(resolution: ConflictResolution) {
        val session = conflictSession ?: return
        val currentConflict = session.conflicts.getOrNull(session.cursor) ?: return
        session.decisions[currentConflict.pendingIndex] = resolution

        if (resolution.applyToAll) {
            // 对后面的每个冲突，用各自自动算好的名字，不能共用同一个字符串
            for (i in session.cursor + 1 until session.conflicts.size) {
                val c = session.conflicts[i]
                val perConflict = when (resolution.action) {
                    ConflictAction.RENAME -> resolution.copy(
                        renamedTo = c.autoRenamedName
                    )
                    else -> resolution
                }
                session.decisions[c.pendingIndex] = perConflict
            }
            session.cursor = session.conflicts.size
        } else {
            session.cursor++
        }

        if (session.cursor >= session.conflicts.size) {
            startEnqueue(session)
        } else {
            showConflictAtIndex(session.cursor)
        }
    }

    /** 用户在冲突对话框上点了「取消」：整批取消，不入队。 */
    fun cancelConflict() {
        val session = conflictSession
        conflictSession = null
        _conflict.value = null
        if (session != null) {
            clearSelection(session.side)
        }
    }

    /** 关闭进度弹窗，任务继续（后台运行）。 */
    fun dismissActiveBatchDialog() {
        _activeBatchId.value = null
    }

    /**
     * 点开网络栏里的一个文件。
     *
     * **分流在阶段四加的**：能播的媒体（[MediaTypes.isPlayable]）交给内置播放页边下边播；
     * 图片（[MediaTypes.isImage]）交给内置图片查看器，直接从网络流解码、不落盘。
     * 其余（PDF / zip / 文档 / 未知类型）仍然走「整份下载到缓存 → 系统应用打开」那条老路。
     * 只分流、不替换 —— 老路是文档的唯一出路，也是内置页失败时的回退目标。
     */
    fun openRemoteFile(side: PanelSide, item: RemoteFile) {
        if (!item.isDirectory && ArchiveFormats.isArchive(item.name)) {
            openArchive(side, item)
            return
        }
        // 认识、但 Media3 没有提取器（WMV/WMA/ASF/RM/RMVB）：直接走"下载 → 系统应用"，
        // 别让内置播放器先失败一次（用户只会看到"打不开"）。
        if (!item.isDirectory && MediaTypes.isExternalPlayable(item.name)) {
            downloadAndOpen(side, item)
            return
        }
        if (!item.isDirectory && TextFileTypes.isPdf(item.name)) {
            openPdfRemote(side, item)
            return
        }
        if (!item.isDirectory &&
            (MediaTypes.isPlayable(item.name) || MediaTypes.isImage(item.name)) &&
            openViewerAt(side, item)
        ) {
            return
        }
        // 5B：文本/未知后缀默认进内置编辑器。Office/PDF/常见二进制/媒体在这里返回 false，
        // 继续走「下载 → 系统应用」那条老路。
        if (!item.isDirectory && TextFileTypes.shouldOpenAsText(item.name)) {
            openTextEditor(side, item)
            return
        }
        downloadAndOpen(side, item)
    }

    /**
     * 5B：把文件读进内存并用内置编辑器打开。
     *
     * 用户明确要求：远程文件**不落缓存**，直接读进内存；保存时再传回去。
     * 大文件会切到按行编辑器；内存上限放到 16 MB，超过仍拒绝。
     */
    fun openTextEditor(side: PanelSide, item: RemoteFile) {
        if (item.isDirectory) return
        val state = stateOf(side).value
        if (item.size > TextFileTypes.MAX_EDIT_BYTES) {
            stateOf(side).update { it.copy(error = "文件太大，暂不支持在编辑器里打开（超过 ${TextFileTypes.MAX_EDIT_LABEL}）") }
            return
        }
        stateOf(side).update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val fs = fsFor(state.sourceId)
                val bytes = readTextBytes(fs, item.path)
                // 未知后缀默认文本，但二进制不能硬塞给编辑器；已知文本允许少量坏字节。
                if (!TextFileTypes.isKnownText(item.name) && TextFileTypes.looksBinary(bytes)) {
                    stateOf(side).update { it.copy(error = "这看起来不是文本文件") }
                    return@launch
                }
                val decoded = runCatching { TextCodecImpl.decode(bytes) }.getOrElse {
                    stateOf(side).update { it.copy(error = "读取失败：这个文件解不开") }
                    return@launch
                }
                editorBytes = bytes
                editorSourceId = state.sourceId
                editorBaseMtime = item.lastModified
                editorBaseSize = item.size
                DebugLog.ev(
                    getApplication(),
                    "editor-open ${item.path} baseSize=${item.size} baseMtime=${item.lastModified}"
                )
                _pendingEditor.value = TextEditorSession(
                    side = side,
                    file = item,
                    document = TextDocument(
                        fileName = item.name,
                        text = decoded.text,
                        encoding = decoded.encoding,
                        bom = decoded.bom,
                        lineEnding = decoded.lineEnding,
                    ),
                    hadErrors = decoded.hadErrors,
                )
                if (decoded.hadErrors) {
                    _editorNotice.value = "这个文件可能有无法识别的字节，已按最可能的编码打开"
                }
            } catch (t: Throwable) {
                stateOf(side).update {
                    it.copy(
                        error = if (t is TextTooLargeException) {
                            "文件太大，暂不支持在编辑器里打开（超过 ${TextFileTypes.MAX_EDIT_LABEL}）"
                        } else {
                            "打开失败：读不到这个文件"
                        }
                    )
                }
            } finally {
                stateOf(side).update { it.copy(loading = false) }
            }
        }
    }

    /** 编辑器保存：本地/远程都覆盖原文件；覆盖失败落本地副本兜底。 */
    suspend fun saveEditor(
        session: TextEditorSession,
        request: EditorSaveRequest,
        policy: EditorSavePolicy = EditorSavePolicy.NORMAL,
    ): EditorSaveResult {
        val bytes = try {
            TextCodecImpl.encode(request.text, request.encoding, request.bom, request.lineEnding)
        } catch (e: TextEncodeException) {
            _editorNotice.value = "保存失败：有字符在当前编码里表示不了，请换成 UTF-8"
            return EditorSaveResult.FAILED
        } catch (t: Throwable) {
            // 不把底层异常名/错误码甩给用户；编码内核的详细信息只在日志里
            _editorNotice.value = "保存失败：这个文件当前编码不能保存，请换一个编码"
            return EditorSaveResult.FAILED
        }

        if (policy == EditorSavePolicy.LOCAL_COPY) {
            return saveEditorLocalCopy(session.file.name, bytes)
        }

        val sourceId = editorSourceId ?: stateOf(session.side).value.sourceId
        val fs = runCatching { fsFor(sourceId) }.getOrNull()
        if (fs != null) {
            if (policy == EditorSavePolicy.NORMAL) {
                val changed = withContext(Dispatchers.IO) {
                    runCatching { editorFileChanged(fs, session.file.path) }.getOrDefault(false)
                }
                if (changed) {
                    _editorNotice.value = "保存前检测到文件已被其他程序修改"
                    return EditorSaveResult.CONFLICT
                }
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ByteArrayInputStream(bytes).use { input ->
                        fs.writeFrom(input, session.file.path) { _, _ -> }
                    }
                }.isSuccess
            }
            if (ok) {
                editorBytes = bytes
                refreshEditorBaseline(fs, session.file.path, bytes.size.toLong())
                return EditorSaveResult.SAVED
            }
        }
        // 覆盖失败：不把用户内容丢掉，落本地文件并在界面上给完整路径
        return saveEditorLocalCopy(session.file.name, bytes)
    }

    private suspend fun saveEditorLocalCopy(name: String, bytes: ByteArray): EditorSaveResult {
        val fallback = withContext(Dispatchers.IO) {
            runCatching { writeFallback(name, bytes) }.getOrNull()
        }
        if (fallback != null) {
            editorBytes = bytes
            _editorNotice.value = "已保存本地副本：${fallback.absolutePath}"
            return EditorSaveResult.SAVED
        }
        _editorNotice.value = "保存失败：原文件写不进去，本地副本也存不了"
        return EditorSaveResult.FAILED
    }

    /** 基线不一致 = 打开后有人改过/删过；宁可弹冲突，也不静默覆盖。 */
    private suspend fun editorFileChanged(fs: RemoteFileSystem, path: String): Boolean {
        val parent = path.trimEnd('/').substringBeforeLast('/', "") + "/"
        val current = fs.list(parent).firstOrNull {
            it.path.trimEnd('/') == path.trimEnd('/')
        } ?: return true
        val sizeChanged = editorBaseSize >= 0 && current.size != editorBaseSize
        val timeChanged = editorBaseMtime > 0 &&
            current.lastModified > 0 &&
            current.lastModified != editorBaseMtime
        if (sizeChanged || timeChanged) {
            DebugLog.ev(
                getApplication(),
                "editor-conflict $path base=${editorBaseSize}/${editorBaseMtime} " +
                    "current=${current.size}/${current.lastModified}"
            )
        }
        return sizeChanged || timeChanged
    }

    private suspend fun refreshEditorBaseline(fs: RemoteFileSystem, path: String, writtenSize: Long) {
        val parent = path.trimEnd('/').substringBeforeLast('/', "") + "/"
        val current = runCatching {
            fs.list(parent).firstOrNull { it.path.trimEnd('/') == path.trimEnd('/') }
        }.getOrNull()
        editorBaseSize = current?.size ?: writtenSize
        editorBaseMtime = current?.lastModified ?: 0L
    }

    /** 用户手动换编码：用原始字节重新解码，BOM 重置为 NONE，避免保存时 BOM/编码不匹配。 */
    fun changeEditorEncoding(name: String) {
        val session = _pendingEditor.value ?: return
        val bytes = editorBytes ?: return
        viewModelScope.launch {
            val decoded = runCatching { TextCodecImpl.decode(bytes, name) }.getOrElse {
                _editorNotice.value = "这个编码打不开这个文件"
                return@launch
            }
            _pendingEditor.value = session.copy(
                document = session.document.copy(
                    text = decoded.text,
                    encoding = decoded.encoding,
                    bom = TextBom.NONE,
                    lineEnding = decoded.lineEnding,
                ),
                hadErrors = decoded.hadErrors,
            )
            if (decoded.hadErrors) {
                _editorNotice.value = "这个编码下仍有无法识别的字节，保存前建议换一个编码"
            }
        }
    }

    fun closeEditor() {
        _pendingEditor.value = null
        editorBytes = null
        editorSourceId = null
        editorBaseMtime = 0L
        editorBaseSize = -1L
    }

    fun consumeEditorNotice() {
        _editorNotice.value = null
    }

    /** 「打开方式」里选系统应用；远程文件需要先下到缓存。 */
    fun openWithSystem(side: PanelSide, item: RemoteFile) {
        downloadAndOpen(side, item)
    }

    /**
     * 读文件到内存，硬上限 16 MB：先看 item.size，未知大小再按字节数截。
     * 不能直接 `readBytes()` 读整个远程文件——用户说的「往内存堆」只适用于文本。
     */
    private suspend fun readTextBytes(fs: RemoteFileSystem, path: String): ByteArray =
        withContext(Dispatchers.IO) {
            val max = TextFileTypes.MAX_EDIT_BYTES
            fs.openInput(path).use { input ->
                val out = ByteArrayOutputStream(minOf(max, 64L * 1024).toInt())
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > max) throw TextTooLargeException()
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        }

    private fun writeFallback(name: String, bytes: ByteArray): File {
        // 优先落到用户能看到的 Download/XjFile；外置存储不可用（被卸载/没权限）再退应用缓存。
        // 用户要求「覆盖失败再存盘」，存进 /data 里的 cache 他根本找不到，所以先试外置下载目录。
        val dir = runCatching {
            File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                "XjFile"
            )
        }.getOrNull()?.takeIf { it.isDirectory || it.mkdirs() }
            ?: File(getApplication<Application>().cacheDir, "text-fallback").apply { mkdirs() }
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "text.txt" }
        val dest = File(dir, "${System.currentTimeMillis()}-$safeName")
        dest.writeBytes(bytes)
        return dest
    }

    private class TextTooLargeException : Exception()

    /**
     * 打开内置查看页（视频/图片都走这里）。
     *
     * 返回 false 表示数据源建不出来（连接断了之类），调用方应该退回老路。
     * 也会被「缩略图条点了另一个类型」时复用 —— 那时候就是一次跨类型的切换。
     */
    fun openViewerAt(side: PanelSide, item: RemoteFile): Boolean {
        val request = buildViewerRequest(side, item) ?: return false
        _pendingViewer.value = request
        return true
    }

    /** 本地 PDF：直接交给内置 PdfRenderer。 */
    fun openPdfLocal(side: PanelSide, item: RemoteFile) {
        val file = File(item.path)
        if (!file.isFile) {
            stateOf(side).update { it.copy(error = "PDF 不存在或读不到") }
            return
        }
        _pendingPdf.value = PdfViewerRequest(file, item.name)
    }

    /**
     * 远程 PDF：先整份下载到 cacheDir/pdf-preview，再交给内置查看器。
     * PDF 需要可随机读取的本地文件，不能像文本/图片那样直接读远程流。
     */
    fun openPdfRemote(side: PanelSide, item: RemoteFile) {
        if (item.isDirectory) return
        val state = stateOf(side).value
        stateOf(side).update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val fs = fsFor(state.sourceId)
                val dir = File(getApplication<Application>().cacheDir, "pdf-preview")
                    .apply { mkdirs() }
                val safeName = item.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .ifBlank { "document.pdf" }
                val dst = File(dir, "${UUID.randomUUID()}-$safeName")
                withContext(Dispatchers.IO) {
                    fs.openInput(item.path).use { input ->
                        dst.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                stateOf(side).update { it.copy(loading = false) }
                _pendingPdf.value = PdfViewerRequest(dst, item.name)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                stateOf(side).update {
                    it.copy(
                        loading = false,
                        error = "PDF 下载失败：${t.message ?: t.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    fun openPdfFile(file: File, title: String) {
        if (!file.isFile) return
        _pendingPdf.value = PdfViewerRequest(file, title)
    }

    fun closePdf() {
        _pendingPdf.value = null
    }

    /**
     * 组装一次查看请求。
     *
     * 两个列表的区别：
     * - `pages`（可翻页的）：**只收同一种类型**。图片列表里绝不会混进视频，
     *   反过来也一样 —— 用户明确要求「看着看着图片蹦出个视频」不能发生。
     * - `strip`（底部缩略图条）：**总是有**。严格分离时它就是同类列表（和 pages 一样），
     *   混着翻时换成同目录的全部媒体，于是可以跨类型挑。
     */
    private fun buildViewerRequest(side: PanelSide, item: RemoteFile): ViewerRequest? {
        val state = stateOf(side).value
        // 三种类型各自成组：图片 / 视频 / 音频。
        // 之前把「音频 + 视频」都当 playable 算一类，结果看视频点"下一个"会跳到 mp3，
        // 对文件管理器来说这属于串门，必须拆开。
        val kind = when {
            MediaTypes.isImage(item.name) -> 1
            MediaTypes.isAudio(item.name) -> 2
            else -> 3
        }
        val all = state.items.filter { !it.isDirectory }

        val sameType = all.filter {
            when (kind) {
                1 -> MediaTypes.isImage(it.name)
                2 -> MediaTypes.isAudio(it.name)
                else -> MediaTypes.isVideo(it.name)
            }
        }.let { list -> if (list.any { it.path == item.path }) list else list + item }

        val pages = sameType.map { file ->
            ViewerPage(file, sourceOf(state, file) ?: return null)
        }
        if (pages.isEmpty()) return null
        val start = pages.indexOfFirst { it.file.path == item.path }.coerceAtLeast(0)

        return ViewerRequest(
            side = side,
            pages = pages,
            startIndex = start,
            strip = if (AppSettings.mediaIsolation.value) {
                // 严格分离：缩略图条就是同类列表本身，一点别的类型都不会出现
                pages
            } else {
                mixedStrip(side, item.path.substringBeforeLast('/'))
            },
            isLocal = state.sourceId == "local"
        )
    }

    /**
     * 文件列表图标位的图片/视频缩略图。
     * 本地直接读；远程串行读，避免 SFTP 通道被多个缩略图同时占用。
     */
    suspend fun loadThumbnail(side: PanelSide, item: RemoteFile, sizePx: Int): Bitmap? {
        if (item.isDirectory) return null
        val name = item.name.lowercase()
        val appIconFile = name.endsWith(".apk") || name.endsWith(".exe")
        if (!appIconFile && !MediaTypes.isImage(item.name) && !MediaTypes.isVideo(item.name)) {
            return null
        }
        val state = stateOf(side).value
        if (appIconFile) {
            if (state.sourceId == "local") {
                val file = File(item.path)
                if (!file.isFile) return null
                return AppIconLoader.load(getApplication(), file, sizePx)
            }
            return loadRemoteAppIcon(state, item, sizePx)
        }
        if (state.sourceId == "local") {
            val file = File(item.path)
            if (!file.isFile) return null
            return if (MediaTypes.isImage(item.name)) {
                ThumbnailLoader.loadImage(file, sizePx)
            } else {
                ThumbnailLoader.loadVideo(file, sizePx)
            }
        }
        val source = sourceOf(state, item) ?: return null
        val page = ViewerPage(item, source)
        return remoteThumbnailMutex.withLock {
            Thumbnails.load(getApplication(), page, sizePx)
        }
    }

    /**
     * 远程 APK/EXE 的图标。只读 ZIP 中央目录/PE 资源节，不再把整份文件拉下来。
     */
    private suspend fun loadRemoteAppIcon(
        state: PanelUiState,
        item: RemoteFile,
        sizePx: Int,
    ): Bitmap? {
        if (item.size <= 0) return null
        val fs = fsFor(state.sourceId) ?: return null
        val ext = item.name.substringAfterLast('.', "").lowercase()
        if (ext != "apk" && ext != "exe") return null
        return remoteAppIconMutex.withLock {
            RemoteAppIconLoader.load(
                fs = fs,
                path = item.path,
                size = item.size,
                extension = ext,
                targetPx = sizePx,
                tempDir = getApplication<Application>().cacheDir,
            )
        }
    }

    /** 一个文件的数据源。本地直接读文件，网络走 fs（连接没建好就返回 null，让上层退回老路）。 */
    private fun sourceOf(state: PanelUiState, file: RemoteFile): PlaybackSource? =
        if (state.sourceId == "local") {
            PlaybackSource.Direct(File(file.path), file.name)
        } else {
            runCatching { fsFor(state.sourceId) }.getOrNull()
                ?.let { PlaybackSource.Remote(it, file.path, file.size, file.name) }
        }

    /** 混着翻时缩略图条的内容：同目录里的全部媒体（图片 + 视频/音频）。 */
    private fun mixedStrip(side: PanelSide, anchorDir: String): List<ViewerPage> {
        val state = stateOf(side).value
        return state.items
            .asSequence()
            .filter { !it.isDirectory }
            .filter { MediaTypes.isImage(it.name) || MediaTypes.isPlayable(it.name) }
            .filter { it.path.substringBeforeLast('/') == anchorDir }
            .mapNotNull { file -> sourceOf(state, file)?.let { ViewerPage(file, it) } }
            .toList()
    }

    /**
     * 开关一变就把已经打开的查看页更新掉。
     *
     * 设置页是另一个 Activity，改完回到 App 时查看页还开着 —— 不刷新的话
     * 用户看到的是"开关点了没反应，必须退出重进"。只替换 strip、不动 pages，
     * 这样查看页的翻页位置和播放进度都不会跳。
     */
    private fun refreshViewerStrip() {
        val request = _pendingViewer.value ?: return
        val strip = if (AppSettings.mediaIsolation.value) {
            request.pages
        } else {
            mixedStrip(request.side, request.current.file.path.substringBeforeLast('/'))
        }
        if (strip.map { it.file.path } == request.strip.map { it.file.path }) return
        _pendingViewer.value = request.copy(strip = strip)
    }

    /** 查看页点返回 / 手势返回：只关页面，播放器/位图由页面自己释放。 */
    fun closeViewer() {
        _pendingViewer.value = null
    }

    /**
     * 打开「类相册视图」：焦点栏当前目录里的图片 + 视频，4 列方形缩略图。
     *
     * 数据源复用 [sourceOf]，和查看页/缩略图条同一条路：本地直接读文件，
     * 远程走 fs 的随机读，不为网格另写一套。
     */
    fun openGallery(side: PanelSide) {
        val st = stateOf(side).value
        val pages = st.items
            .filter { !it.isDirectory && (MediaTypes.isImage(it.name) || MediaTypes.isVideo(it.name)) }
            .mapNotNull { file -> sourceOf(st, file)?.let { ViewerPage(file, it) } }
        if (pages.isEmpty()) {
            stateOf(side).update { it.copy(error = "这个目录里没有图片或视频") }
            return
        }
        _gallery.value = GalleryRequest(side, st.currentPath, pages)
    }

    /** 关闭类相册视图（查看页如果开着，会先回到相册）。 */
    fun closeGallery() {
        _gallery.value = null
    }

    /**
     * 打开当前文件栏里的压缩包。
     *
     * 本地压缩包直接读原文件；远程压缩包先下载到 cache，避免 SMB/SFTP
     * 边读边解压时占用同一条通道。兼容格式见 [ArchiveFormats]。
     */
    fun openArchive(side: PanelSide, item: RemoteFile) {
        if (item.isDirectory) return
        val format = ArchiveFormats.kindOf(item.name)
        if (format == null) {
            val label = ArchiveFormats.unsupportedLabel(item.name)
            stateOf(side).update {
                it.copy(error = if (label != null) "$label 压缩包暂不支持读取" else "这不是可识别的压缩包")
            }
            return
        }
        val panel = stateOf(side).value
        val sourceId = panel.sourceId
        val targetDir = panel.currentPath
        stateOf(side).update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            var temp: File? = null
            try {
                val fs = fsFor(sourceId)
                val local = if (sourceId == "local") {
                    File(item.path)
                } else {
                    val dir = File(
                        File(getApplication<Application>().cacheDir, "archive-open"),
                        UUID.randomUUID().toString()
                    ).apply { mkdirs() }
                    val safeName = item.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        .ifBlank { "archive" }
                    val dst = File(dir, safeName)
                    fs.download(item.path, dst) { _, _ -> }
                    temp = dst
                    dst
                }
                if (!local.isFile) error("压缩包不存在或读不到")
                val reader = withContext(Dispatchers.IO) {
                    ArchiveReader.open(local, item.name)
                }
                if (reader.entries.isEmpty()) {
                    reader.close()
                    error("压缩包是空的")
                }
                archiveReader?.close()
                archiveReader = reader
                _pendingArchive.value = ArchiveSession(
                    side = side,
                    sourceId = sourceId,
                    targetDir = targetDir,
                    archiveName = item.name,
                    localFile = local,
                    entries = reader.entries,
                    temporary = sourceId != "local",
                )
                _archiveNotice.value = null
                _archiveProgress.value = 0 to 0
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                temp?.parentFile?.let { runCatching { it.deleteRecursively() } }
                val message = when (t) {
                    is UnsupportedArchiveException -> t.message ?: "暂不支持这个压缩包"
                    else -> "打开压缩包失败：${t.message ?: t.javaClass.simpleName}"
                }
                stateOf(side).update { it.copy(error = message) }
            } finally {
                stateOf(side).update { it.copy(loading = false) }
            }
        }
    }

    fun closeArchive() {
        val session = _pendingArchive.value
        _pendingArchive.value = null
        _archiveTextPreview.value = null
        runCatching { archiveReader?.close() }
        archiveReader = null
        _archiveBusy.value = false
        _archiveProgress.value = 0 to 0
        if (session?.temporary == true) {
            session.localFile.parentFile?.let { runCatching { it.deleteRecursively() } }
        }
    }

    fun closeArchiveTextPreview() {
        _archiveTextPreview.value = null
    }

    /**
     * 预览单个条目。优先走内置：
     * - 文本 -> 只读文本预览；
     * - 图片/可播放音视频 -> 内置查看页/播放器；
     * - 其余（PDF/Office/未知二进制）才交给系统查看器。
     */
    fun previewArchiveEntry(entry: ArchiveEntryInfo) {
        if (entry.isDirectory) return
        val session = _pendingArchive.value ?: return
        val reader = archiveReader ?: return
        viewModelScope.launch {
            try {
                val dir = File(
                    File(getApplication<Application>().cacheDir, "archive-preview"),
                    UUID.randomUUID().toString()
                ).apply { mkdirs() }
                val safeName = entry.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .ifBlank { "preview" }
                val dst = File(dir, safeName)
                withContext(Dispatchers.IO) {
                    reader.openEntry(entry).use { input ->
                        dst.outputStream().use { output -> input.copyTo(output) }
                    }
                }

                if (TextFileTypes.shouldOpenAsText(entry.name) &&
                    dst.length() <= TextFileTypes.MAX_EDIT_BYTES
                ) {
                    val bytes = withContext(Dispatchers.IO) { dst.readBytes() }
                    if (!TextFileTypes.looksBinary(bytes)) {
                        val decoded = runCatching { TextCodecImpl.decode(bytes) }.getOrNull()
                        if (decoded != null) {
                            _archiveTextPreview.value = ArchiveTextPreview(
                                name = entry.name,
                                text = decoded.text,
                                encoding = decoded.encoding,
                                hadErrors = decoded.hadErrors,
                            )
                            return@launch
                        }
                    }
                }

                if (TextFileTypes.isPdf(entry.name)) {
                    _pendingPdf.value = PdfViewerRequest(dst, entry.name)
                    return@launch
                }

                if (MediaTypes.isImage(entry.name)) {
                    // 同一压缩包目录下的图片全部放进查看页，缩略图条才能滑动切换；
                    // 不预解压，PlaybackSource.ArchiveEntry 按需从压缩包里读。
                    val images = session.entries
                        .filter {
                            !it.isDirectory &&
                                it.parent == entry.parent &&
                                MediaTypes.isImage(it.name)
                        }
                        .let { list ->
                            if (list.any { it.path == entry.path }) list else list + entry
                        }
                    val pages = images.map { image ->
                        val file = RemoteFile(
                            name = image.name,
                            path = "${session.localFile.absolutePath}#${image.path}",
                            isDirectory = false,
                            size = image.size,
                            lastModified = image.lastModified,
                        )
                        ViewerPage(
                            file = file,
                            source = PlaybackSource.ArchiveEntry(
                                archiveFile = session.localFile,
                                archiveName = session.archiveName,
                                entry = image,
                                sizeBytes = image.size,
                                displayName = image.name,
                            ),
                        )
                    }
                    val start = pages.indexOfFirst { it.file.path.endsWith("#${entry.path}") }
                        .coerceAtLeast(0)
                    _pendingViewer.value = ViewerRequest(
                        side = session.side,
                        pages = pages,
                        startIndex = start,
                        strip = pages,
                        isLocal = true,
                    )
                    return@launch
                }

                if (MediaTypes.isPlayable(entry.name)) {
                    val file = RemoteFile(
                        name = entry.name,
                        path = dst.absolutePath,
                        isDirectory = false,
                        size = dst.length(),
                        lastModified = dst.lastModified(),
                    )
                    val page = ViewerPage(file, PlaybackSource.Direct(dst, entry.name))
                    _pendingViewer.value = ViewerRequest(
                        side = session.side,
                        pages = listOf(page),
                        startIndex = 0,
                        strip = listOf(page),
                        isLocal = true,
                    )
                    return@launch
                }

                // 内置没有对应渲染器：PDF/Office/未知二进制退回系统查看器。
                _pendingOpen.value = dst
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _archiveNotice.value = "预览失败：${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    /** 解压全部条目（目录条目会先建好）。 */
    fun extractArchiveAll() {
        val session = _pendingArchive.value ?: return
        extractArchive(session.entries)
    }

    /** 解压单个条目；目录条目会带上它下面的所有子项。 */
    fun extractArchiveEntry(entry: ArchiveEntryInfo) {
        val session = _pendingArchive.value ?: return
        val items = if (entry.isDirectory) {
            session.entries.filter { it.path == entry.path || it.path.startsWith(entry.path + "/") }
        } else {
            listOf(entry)
        }
        extractArchive(items)
    }

    private fun extractArchive(entries: List<ArchiveEntryInfo>) {
        val session = _pendingArchive.value ?: return
        val reader = archiveReader ?: return
        if (_archiveBusy.value || entries.isEmpty()) return
        // 双栏的默认语义：压缩包在左栏时解压到右栏，在右栏时解压到左栏。
        val targetSide = if (session.side == PanelSide.LEFT) PanelSide.RIGHT else PanelSide.LEFT
        val targetState = stateOf(targetSide).value
        _archiveBusy.value = true
        _archiveProgress.value = 0 to entries.size
        viewModelScope.launch {
            try {
                val fs = fsFor(targetState.sourceId)
                val result = withContext(Dispatchers.IO) {
                    ArchiveExtractor.extract(
                        reader = reader,
                        entries = entries,
                        targetFs = fs,
                        targetDir = targetState.currentPath,
                    ) { done, total -> _archiveProgress.value = done to total }
                }
                refresh(targetSide, clearLastCreated = false)
                val text = buildString {
                    append("已解压 ${result.extracted} 个文件")
                    if (result.directories > 0) append("、${result.directories} 个目录")
                    if (result.renamed > 0) append("；${result.renamed} 个同名项已自动改名")
                    if (result.errors.isNotEmpty()) append("；${result.errors.size} 个失败")
                }
                _archiveNotice.value = text
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _archiveNotice.value = "解压失败：${t.message ?: t.javaClass.simpleName}"
            } finally {
                _archiveBusy.value = false
            }
        }
    }

    fun consumeArchiveNotice() {
        _archiveNotice.value = null
    }

    /**
     * 从相册点开某一项：整份列表（图片 + 视频混排）原样交给查看页。
     *
     * 关键点：**不按类型过滤**。这样左右滑就能从图片滑到视频、再滑回图片，
     * 视频页和图片页各自切换渲染（见 MainScreen 里的类型分派）。
     */
    fun openGalleryItem(request: GalleryRequest, file: RemoteFile) {
        val index = request.pages.indexOfFirst { it.file.path == file.path }.coerceAtLeast(0)
        _pendingViewer.value = ViewerRequest(
            side = request.side,
            pages = request.pages,
            startIndex = index,
            strip = request.pages,
            isLocal = stateOf(request.side).value.sourceId == "local",
            mixed = true,
        )
    }

    /**
     * 查看页里换到了另一页（相册混排跨类型时用）。
     *
     * 只改 startIndex，不重建 pages/strip：图片页和视频页看到的是同一份列表，
     * 重建会把另一个页面正在用的数据源（以及播放器里的解码器）全丢掉。
     */
    fun viewerGoTo(index: Int) {
        val request = _pendingViewer.value ?: return
        if (index !in request.pages.indices || index == request.startIndex) return
        _pendingViewer.value = request.copy(startIndex = index)
    }

    /**
     * 内置查看失败（解码不支持 / 网络断）后的回退。
     *
     * [page] 是**失败的那一页**（翻页后失败的未必是刚点开的那一个）。
     * 网络文件回退到「下载 → 系统应用」，本地文件直接把路径丢给系统应用 ——
     * 两种都不能让用户停在一个黑屏上。栏顶留一句人话提示，避免用户以为 App 死了。
     */
    fun fallbackFromViewer(request: ViewerRequest, page: ViewerPage, reason: String) {
        // 请求已经换人了（用户退出又重新打开）就不要再回退，免得把新页面顶掉
        if (_pendingViewer.value !== request) return
        _pendingViewer.value = null
        // CD 音轨不是文件：回退到「下载 → 系统应用」只会去下载一个不存在的路径，
        // 所以直接收掉播放页，在栏顶说一句人话。
        if (page.source is PlaybackSource.Cdda) {
            stateOf(request.side).update { it.copy(error = "CD 播放失败：光驱没读到数据") }
            return
        }
        // 界面只说人话：错误码/编码名留在查看页的日志里。
        // 用户明确要求「别在界面上看到任何编码」「能支持就支持」，所以这里做一次归类。
        val why = when {
            reason.startsWith("ERROR_CODE_IO") || reason.contains("读取") -> "读取失败"
            reason.startsWith("ERROR_CODE_PARSING") || reason.startsWith("ERROR_CODE_DECODING") ||
                reason.contains("解") -> "格式打不开"
            else -> "打不开"
        }
        stateOf(request.side).update { it.copy(error = "$why，已改用系统应用打开") }
        if (request.isLocal) {
            // 本地文件没必要再下到缓存，直接让 MainScreen 用 FileProvider 打开
            _pendingOpen.value = File(page.file.path)
        } else {
            downloadAndOpen(request.side, page.file)
        }
    }

    /**
     * 下载网络文件到 cacheDir/preview/，下完再交给 UI 用 FileProvider 打开。
     * 远程路径（//host/...）喂给 java.io.File 是不存在的，只能先落到本地缓存。
     * 复用 TransferManager，顺带白拿进度和任务抽屉。
     */
    private fun downloadAndOpen(side: PanelSide, item: RemoteFile) {
        val s = stateOf(side).value
        val srcFs = runCatching { fsFor(s.sourceId) }.getOrNull() ?: return
        // 每次打开用独立子目录：不同目录下的同名文件（如两个 IMG_001.jpg）不会互相覆盖，
        // 也不会覆盖掉系统看图/播放器正在读的那个缓存文件。
        val dir = File(
            File(getApplication<Application>().cacheDir, "preview"),
            UUID.randomUUID().toString()
        ).apply { mkdirs() }
        val dst = File(dir, item.name)
        val batchId = UUID.randomUUID().toString()
        val batchName = "打开 ${item.name}"
        // 下载期间让栏顶那条 2dp 进度条亮起来，否则大文件会有一段时间像是「点了没反应」
        stateOf(side).update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val taskId = TransferManager.enqueue(
                    batchId = batchId,
                    batchName = batchName,
                    displayName = item.name,
                    srcFs = srcFs,
                    srcPath = item.path,
                    dstFs = localFs,
                    dstPath = dst.path,
                    totalBytes = item.size
                )
                // 注意：不能要求「任务必须还在列表里」，否则任务被移除时这个协程会永久挂住
                TransferManager.allSettled(setOf(taskId)).first { it }
                val status = TransferManager.tasks.value.firstOrNull { it.id == taskId }?.status
                if (status == TransferStatus.COMPLETED) {
                    _pendingOpen.value = dst
                } else {
                    stateOf(side).update { it.copy(error = "打开失败：下载未完成") }
                }
                // 打开完把这次临时批次清掉，别堆在任务抽屉里
                TransferManager.removeBatch(batchId)
            } catch (t: Throwable) {
                stateOf(side).update {
                    it.copy(error = "打开失败：${t.message ?: t.javaClass.simpleName}")
                }
            } finally {
                stateOf(side).update { it.copy(loading = false) }
            }
        }
    }

    /** UI 打开完待打开的文件后调用，避免同一个文件被反复打开。 */
    fun consumePendingOpen() {
        _pendingOpen.value = null
    }

    /**
     * 取消批次里的单个文件任务。不影响该批次的其他文件。
     * 会删掉这个文件已经写了一半的目标文件。
     */
    fun cancelSingleTask(taskId: String) {
        val task = TransferManager.tasks.value.firstOrNull { it.id == taskId } ?: return
        // 版本 B：暂停中的任务状态已是 PENDING，但它带着断点，**取消它才算真取消**
        if (task.status != TransferStatus.PENDING && task.status != TransferStatus.RUNNING) return

        val dstFs = batchDstFs[task.batchId]
        val dstPath = task.dstPath
        val wasPaused = TransferManager.isPaused(task)

        TransferManager.removeTask(taskId)
        // 取消 = 彻底不要了：断点作废。暂停中的任务删文件反而会毁掉断点，所以只在非暂停时删半截文件。
        runCatching { com.cmzc.xj_file.app.core.transfer.ResumeStore.removeByDstPath(dstPath) }
        if (!wasPaused && dstFs != null) {
            viewModelScope.launch {
                delay(300)
                runCatching { dstFs.delete(dstPath) }
            }
        }
    }


    /**
     * 取消指定的批次。和 cancelActiveBatch 逻辑一样，但指定 batchId。
     * 给右侧任务抽屉的「取消」按钮用。
     */
    fun cancelBatchById(batchId: String) {
        val taskPaths = TransferManager.tasks.value
            .filter { it.batchId == batchId }
            .map { it.dstPath }
        val dstFs = batchDstFs[batchId]
        val dirs = batchDirs[batchId]?.toList().orEmpty()

        TransferManager.cancelBatch(batchId)
        if (_activeBatchId.value == batchId) {
            _activeBatchId.value = null
        }

        if (dstFs == null) {
            batchDstFs.remove(batchId)
            batchDirs.remove(batchId)
            return
        }

        viewModelScope.launch {
            delay(300)
            for (p in taskPaths) {
                runCatching { dstFs.delete(p) }
            }
            dirs.sortedByDescending { it.count { c -> c == '/' } }.forEach { d ->
                runCatching { dstFs.delete(d) }
            }
            batchDstFs.remove(batchId)
            batchDirs.remove(batchId)
        }
    }


    /**
     * 取消当前弹窗对应的批次。
     * 截断任务，然后清理本次批次已经落到目标的文件（含半截）和建过的目录。
     * 源文件不动（移动也一样，因为移动 = 复制 + 成功后删源，取消时源本来还在）。
     */
    fun cancelActiveBatch() {
        val id = _activeBatchId.value ?: return

        // 快照：取消前先记下这个批次涉及的所有目标路径
        val taskPaths = TransferManager.tasks.value
            .filter { it.batchId == id }
            .map { it.dstPath }
        val dstFs = batchDstFs[id]
        val dirs = batchDirs[id]?.toList().orEmpty()

        TransferManager.cancelBatch(id)
        _activeBatchId.value = null

        if (dstFs == null) {
            batchDstFs.remove(id)
            batchDirs.remove(id)
            return
        }

        viewModelScope.launch {
            // 给正在跑的 IO 一点时间，让它 ensureActive 抛异常、关闭文件流
            delay(300)

            // 删文件：所有状态都试一遍，不存在的会被 runCatching 吃掉
            for (p in taskPaths) {
                runCatching { dstFs.delete(p) }
            }
            // 删目录：深的先删，这样内层空了、外层才能删掉
            dirs.sortedByDescending { it.count { c -> c == '/' } }.forEach { d ->
                runCatching { dstFs.delete(d) }
            }

            batchDstFs.remove(id)
            batchDirs.remove(id)
        }
    }

    /**
     * 断开当前栏的网络连接（只在共享列表视图下有用）。
     * 断开后，该栏回到本地存储根目录。
     */
    fun disconnectCurrent(side: PanelSide) {
        val s = stateOf(side).value
        val sourceId = s.sourceId
        if (sourceId == "local") return
        val fs = remoteSystems.remove(sourceId)
        if (fs != null) {
            viewModelScope.launch { runCatching { fs.close() } }
        }
        stateOf(side).update { it.copy(history = emptyList(), forwardStack = emptyList()) }
        load(side, "local", "本地存储", PathUtil.LOCAL_ROOT, pushHistory = false)
    }

    /**
     * 所有决策就绪后，按决策开始入队。
     * 覆盖：直接入队；跳过：略过；重命名：替换目标名后再入队。
     */
    private fun startEnqueue(session: ConflictSession) {
        _conflict.value = null
        conflictSession = null

        // 登记批次元数据（UI 层要用）
        _batchInfos.update { it + (session.batchId to BatchInfo(
            batchId = session.batchId,
            batchName = session.batchName,
            isMove = session.isMove,
            srcSide = session.side,
            dstSide = session.dstSide
        )) }

        // 登记目标 fs，取消时用来清理
        batchDstFs[session.batchId] = session.dstFs

        // 弹出进度对话框
        _activeBatchId.value = session.batchId

        viewModelScope.launch {
            val ownedIds = mutableSetOf(session.batchId)
            try {
                // 1. 建目录
                val dirsCreated = mutableListOf<String>()
                for (t in session.tasks) {
                    if (t.isDirectory) {
                        runCatching { t.dstFs.mkdir(t.dstPath) }
                        dirsCreated.add(t.dstPath)
                    }
                }
                batchDirs[session.batchId] = dirsCreated

                // 2. 入队文件任务
                val topItemFiles = mutableMapOf<PendingTopItem, MutableList<TransferredFile>>()
                val allTaskIds = mutableSetOf<String>()

                for ((index, t) in session.tasks.withIndex()) {
                    if (t.isDirectory) continue
                    val decision = session.decisions[index]
                    val dstPath = when (decision?.action) {
                        ConflictAction.SKIP -> continue
                        ConflictAction.RENAME -> {
                            val name = decision.renamedTo?.takeIf { it.isNotBlank() }
                                ?: t.dstPath.substringAfterLast('/')
                            val parent = t.dstPath.substringBeforeLast('/', "")
                            if (parent.isEmpty()) name else "$parent/$name"
                        }
                        else -> t.dstPath
                    }
                    // 兜底：目标仍然是源文件本身时绝不写入 —— 打开覆盖流会把源截断
                    if (canonPathForFs(t.srcFs, t.srcPath) == canonPathForFs(t.dstFs, dstPath)) {
                        continue
                    }
                    val top = session.topItems.firstOrNull { index in it.taskIndexes }
                    val id = TransferManager.enqueue(
                        batchId = session.batchId,
                        batchName = session.batchName,
                        displayName = t.srcName,
                        srcFs = t.srcFs,
                        srcPath = t.srcPath,
                        dstFs = t.dstFs,
                        dstPath = dstPath,
                        totalBytes = t.totalBytes,
                        isMove = session.isMove,
                        topSrcPath = top?.srcPath ?: t.srcPath,
                    )
                    allTaskIds.add(id)
                    val file = TransferredFile(
                        taskId = id,
                        srcFs = t.srcFs,
                        srcPath = t.srcPath,
                        dstFs = t.dstFs,
                        dstPath = dstPath
                    )
                    for (top in session.topItems) {
                        if (index in top.taskIndexes) {
                            topItemFiles.getOrPut(top) { mutableListOf() }.add(file)
                            break
                        }
                    }
                }

                // 3. 等这批任务全部结束（被用户移除的任务也算结束，否则这里会永久挂起）
                if (allTaskIds.isNotEmpty()) {
                    TransferManager.allSettled(allTaskIds).first { it }
                }

                // 4. 移动：sha256 校验通过才删源；不对就重传，还不对就保留两边
                if (session.isMove) {
                    val snapshot = TransferManager.tasks.value
                    for (top in session.topItems) {
                        val allFiles = topItemFiles[top] ?: continue
                        // 被用户单独取消的文件已经从列表里删了，当作"没参与这次传输"忽略
                        val files = allFiles.filter { f ->
                            snapshot.any { it.id == f.taskId }
                        }
                        // 这个顶层项一个文件都没剩（全被取消了），跳过
                        if (files.isEmpty()) continue

                        val allOk = files.all { f ->
                            snapshot.firstOrNull { it.id == f.taskId }?.status == TransferStatus.COMPLETED
                        }
                        if (!allOk) continue

                        // Task 内部已经算过 sha256 并校验过一次；这里再独立复核一遍（状态栏同步显示「校验中」）。
                        // 只要有一个文件不过，就**不删源** —— 这是「移动」的安全底线。
                        setVerifying(true)
                        val bad = verifyIntegrity(files)
                        setVerifying(false)

                        if (bad.isEmpty()) {
                            runCatching { top.srcFs.delete(top.srcPath) }
                            continue
                        }

                        // 有问题的文件：删掉坏目标 → 重传一次 → 再校验
                        for (f in bad) {
                            runCatching { f.dstFs.delete(f.dstPath) }
                        }
                        val retryOk = retryTransfer(bad, session, ownedIds)
                        if (!retryOk) {
                            stateOf(session.side).update {
                                it.copy(error = "移动重传失败：${bad.size} 个文件传输失败，源与目标均保留")
                            }
                            continue
                        }

                        setVerifying(true)
                        val stillBad = verifyIntegrity(bad)
                        setVerifying(false)
                        if (stillBad.isEmpty()) {
                            runCatching { top.srcFs.delete(top.srcPath) }
                        } else {
                            stateOf(session.side).update {
                                it.copy(error = "移动校验失败：${stillBad.size} 个文件 sha256 不一致，源与目标均保留")
                            }
                        }
                    }
                }

                clearSelection(session.side)
                refresh(session.side)
                refresh(session.dstSide)
            } catch (t: Throwable) {
                stateOf(session.side).update {
                    it.copy(error = "${if (session.isMove) "移动" else "复制"}失败：${t.message ?: t.javaClass.simpleName}")
                }
            } finally {
                setVerifying(false)
                if (_activeBatchId.value in ownedIds) {
                    _activeBatchId.value = null
                }
            }
        }
    }

    /**
     * 切换「校验中」状态。用 activeBatchId 作为 key，自动跟随当前显示的批次。
     */
    private fun setVerifying(verifying: Boolean) {
        _verifyingBatchId.value = if (verifying) _activeBatchId.value else null
    }


    /**
     * 逐个文件复核：大小 + sha256（源哈希拿不到就只比大小）。
     * 返回「不一致」的列表。每个文件读两遍（源一遍、目标一遍），
     * 局域网千兆下 1 GB 约 10 秒级 —— 只在「移动」收尾时跑，复制不跑。
     */
    private suspend fun verifyIntegrity(files: List<TransferredFile>): List<TransferredFile> {
        val bad = mutableListOf<TransferredFile>()
        for (f in files) {
            val srcSize = runCatching { f.srcFs.size(f.srcPath) }.getOrNull()
            val dstSize = runCatching { f.dstFs.size(f.dstPath) }.getOrNull()
            if (srcSize == null || dstSize == null || srcSize != dstSize) {
                bad.add(f)
                continue
            }
            val srcSum = runCatching { f.srcFs.sha256(f.srcPath) }.getOrNull()
            if (srcSum == null) continue // 源算不了哈希：只能信任大小
            val dstSum = runCatching { f.dstFs.sha256(f.dstPath) }.getOrNull()
            if (dstSum == null || !srcSum.equals(dstSum, ignoreCase = true)) {
                bad.add(f)
            }
        }
        return bad
    }

    /**
     * 重传出错的文件（新批次）。返回 true 表示重传全部成功。
     */
    private suspend fun retryTransfer(
        files: List<TransferredFile>,
        session: ConflictSession,
        ownedIds: MutableSet<String>
    ): Boolean {
        if (files.isEmpty()) return true
        val retryBatchId = UUID.randomUUID().toString()
        ownedIds.add(retryBatchId)
        val retryBatchName = "移动重传 ${files.size} 项"

        _batchInfos.update { it + (retryBatchId to BatchInfo(
            batchId = retryBatchId,
            batchName = retryBatchName,
            isMove = true,
            srcSide = session.side,
            dstSide = session.dstSide
        )) }
        batchDstFs[retryBatchId] = session.dstFs
        _activeBatchId.value = retryBatchId

        val retryTaskIds = mutableSetOf<String>()
        for (f in files) {
            // 重传前查一次源大小，让进度条有总数。
            // size() 返回 Long?，用 ?: -1L 兜成非空 Long。
            val totalBytes: Long = runCatching { f.srcFs.size(f.srcPath) }.getOrNull() ?: -1L
            val id = TransferManager.enqueue(
                batchId = retryBatchId,
                batchName = retryBatchName,
                displayName = f.srcPath.substringAfterLast('/'),
                srcFs = f.srcFs,
                srcPath = f.srcPath,
                dstFs = f.dstFs,
                dstPath = f.dstPath,
                totalBytes = totalBytes
            )
            retryTaskIds.add(id)
        }

        if (retryTaskIds.isEmpty()) return true

        // 同样把「任务被移除」当成已结束，否则重传这里也会永久挂住
        TransferManager.allSettled(retryTaskIds).first { it }

        val snapshot = TransferManager.tasks.value
        return retryTaskIds.all { id ->
            snapshot.firstOrNull { it.id == id }?.status == TransferStatus.COMPLETED
        }
    }


    private fun joinPath(parent: String, name: String): String =
        if (PathUtil.isRemote(parent)) PathUtil.joinRemote(parent, name)
        else PathUtil.joinLocal(parent, name)

    /**
     * 判断「是不是同一个目录」用的规范形式。
     *
     * 本地路径必须过一遍 canonicalPath：`/sdcard/xxx` 和 `/storage/emulated/0/xxx`
     * 是同一个目录的两种写法（中间隔着符号链接），只做字符串归一化会漏判。
     */
    private fun canonPath(sourceId: String, path: String): String =
        canonPathForFs(if (sourceId == "local") localFs else null, path)

    /** 同上，但按 fs 实例判断本地/远程（冲突检测里手上只有 fs 对象）。 */
    private fun canonPathForFs(fs: RemoteFileSystem?, path: String): String =
        if (fs is LocalFileSystem) PathUtil.canonicalLocal(path)
        else PathUtil.normalizeRemote(path)

    fun addConnection(conn: Connection) {
        repo.save(conn)
        _connections.value = repo.loadList()
    }

    fun updateConnection(conn: Connection) {
        val sourceId = "${conn.type.name.lowercase()}:${conn.id}"
        remoteSystems.remove(sourceId)?.let { fs ->
            viewModelScope.launch { runCatching { fs.close() } }
        }
        repo.save(conn)
        _connections.value = repo.loadList()
    }

    fun deleteConnection(id: String) {
        val keysToRemove = remoteSystems.keys.filter { it.endsWith(":$id") }
        keysToRemove.forEach { key ->
            remoteSystems.remove(key)?.let { fs ->
                viewModelScope.launch { runCatching { fs.close() } }
            }
        }
        repo.delete(id)
        _connections.value = repo.loadList()
    }

    override fun onCleared() {
        super.onCleared()
        val list = remoteSystems.values.toList()
        remoteSystems.clear()
        // onCleared 跑在主线程，而 SMB/SFTP 的 close() 是网络操作：
        // 在这里 runBlocking 等它，网络一慢就卡住退页面，甚至 ANR。
        // ViewModel 已经销毁、不关心结构化并发，所以扔到自己的 IO scope 里关。
        cleanupScope.launch {
            list.forEach { runCatching { it.close() } }
        }
    }

    companion object {
        /** 只用于「ViewModel 已经销毁但连接还得关」，不跟随任何生命周期取消。 */
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * 目标目录条目超过这个数就不在内存里建名字集合了，退回逐文件 `exists()`。
         * 5 万的字符串集合大约是几 MB，低端机上还算安全。
         */
        private const val MAX_LISTED_NAMES = 50_000
    }
}