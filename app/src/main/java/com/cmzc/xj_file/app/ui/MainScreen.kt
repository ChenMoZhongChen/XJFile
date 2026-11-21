package com.cmzc.xj_file.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cmzc.xj_file.app.core.fs.PathUtil
import com.cmzc.xj_file.app.core.fs.RemoteFile
import com.cmzc.xj_file.app.core.playback.MediaTypes
import com.cmzc.xj_file.app.core.text.TextCodecImpl
import com.cmzc.xj_file.app.core.text.TextFileTypes
import com.cmzc.xj_file.app.core.model.Connection
import com.cmzc.xj_file.app.core.model.ConnectionType
import com.cmzc.xj_file.app.core.model.RecentPath
import com.cmzc.xj_file.app.core.model.ShortcutDir
import com.cmzc.xj_file.app.core.permission.StoragePermission
import com.cmzc.xj_file.app.core.transfer.TransferManager
import com.cmzc.xj_file.app.core.transfer.TransferStatus
import com.cmzc.xj_file.app.core.util.Formatters
import com.cmzc.xj_file.app.ui.components.TaskDrawer
import com.cmzc.xj_file.app.ui.components.AppBottomBar
import com.cmzc.xj_file.app.ui.components.AppDropdownMenu
import com.cmzc.xj_file.app.ui.components.AppTopBar
import com.cmzc.xj_file.app.ui.components.BottomSheetLayer
import com.cmzc.xj_file.app.ui.components.FilePanel
import com.cmzc.xj_file.app.ui.components.LeftDrawerLayer
import com.cmzc.xj_file.app.ui.components.byEntryName
import com.cmzc.xj_file.app.ui.gallery.GalleryScreen
import com.cmzc.xj_file.app.ui.editor.TextEditorScreen
import com.cmzc.xj_file.app.ui.image.ImageViewerScreen
import com.cmzc.xj_file.app.ui.player.PlayerScreen
import com.cmzc.xj_file.app.ui.theme.LocalAnimationsEnabled
import com.cmzc.xj_file.app.ui.theme.LocalAppDarkTheme
import com.cmzc.xj_file.app.ui.theme.LocalModuleColors
import com.cmzc.xj_file.app.ui.theme.XjFileTheme
import com.cmzc.xj_file.app.vm.MainViewModel
import com.cmzc.xj_file.app.vm.PanelSide
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs


/** 通知权限「只问一次」的标记，存在 SharedPreferences 里。 */
private const val KEY_NOTIF_ASKED = "notif_permission_asked"

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(StoragePermission.hasAllFilesAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = StoragePermission.hasAllFilesAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Android 13+ 通知权限：没有它前台服务的进度通知不显示（服务本身照跑，传输不受影响）。
    // 只在能正常用 App 之后问一次；**被拒绝就直接把用户送到本应用的通知设置页**，
    // 因为系统在「不再询问」之后不会再弹，再调 launch 也只是静默失败。
    val uiPrefs = remember {
        context.getSharedPreferences("xj_ui", android.content.Context.MODE_PRIVATE)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }
        }
    }
    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) return@LaunchedEffect
        // 已经问过一次就不再问：拒绝过的用户已经被送到设置页了，反复弹很烦
        if (uiPrefs.getBoolean(KEY_NOTIF_ASKED, false)) return@LaunchedEffect
        uiPrefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    XjFileTheme {
        if (!hasPermission) {
            PermissionScreen(onGrant = { StoragePermission.openAllFilesAccessSettings(context) })
        } else {
            MainContent(vm)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MainContent(vm: MainViewModel) {
    val left by vm.left.collectAsStateWithLifecycle()
    val right by vm.right.collectAsStateWithLifecycle()
    val connections by vm.connections.collectAsStateWithLifecycle()
    val recentPaths by vm.recentPaths.collectAsStateWithLifecycle()
    val shortcutDirs by vm.shortcutDirs.collectAsStateWithLifecycle()
    val lastCreated by vm.lastCreated.collectAsStateWithLifecycle()
    val conflictState by vm.conflict.collectAsStateWithLifecycle()
    val activeBatchId by vm.activeBatchId.collectAsStateWithLifecycle()
    val verifyingBatchId by vm.verifyingBatchId.collectAsStateWithLifecycle()
    val batchInfos by vm.batchInfos.collectAsStateWithLifecycle()
    val allTransferTasks by TransferManager.tasks.collectAsStateWithLifecycle()
    val pausedBatches by TransferManager.pausedBatches.collectAsStateWithLifecycle()
    val pausedTasks by TransferManager.pausedTasks.collectAsStateWithLifecycle()
    val pendingOpen by vm.pendingOpen.collectAsStateWithLifecycle()
    val pendingViewer by vm.pendingViewer.collectAsStateWithLifecycle()
    val pendingEditor by vm.pendingEditor.collectAsStateWithLifecycle()
    val editorNotice by vm.editorNotice.collectAsStateWithLifecycle()
    val gallery by vm.gallery.collectAsStateWithLifecycle()

    var focusedSide by remember { mutableStateOf(PanelSide.LEFT) }
    /** 光驱行长按：非 null 时弹「打开方式」。Triple = 栏位 / 驱动器 / 目录项 */
    var opticalLongPress by remember {
        mutableStateOf<Triple<PanelSide, com.cmzc.xj_file.app.core.disk.DriveInfo, RemoteFile>?>(null)
    }
    var showAddDialog by remember { mutableStateOf(false) }
    var showNewItemDialog by remember { mutableStateOf(false) }
    var showFileActionDialog by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    /** 长按「打开方式」：文本编辑器 / 系统应用。 */
    var openWithTarget by remember { mutableStateOf<Pair<PanelSide, RemoteFile>?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showAttrDialog by remember { mutableStateOf(false) }
    var showAddShortcutDialog by remember { mutableStateOf(false) }
    var addShortcutPath by remember { mutableStateOf("") }
    var addShortcutName by remember { mutableStateOf("") }
    var showAddNetworkShortcutDialog by remember { mutableStateOf(false) }
    var networkShortcutConnId by remember { mutableStateOf<String?>(null) }
    var networkShortcutSubPath by remember { mutableStateOf("") }
    var networkShortcutName by remember { mutableStateOf("") }
    var renameShortcutPath by remember { mutableStateOf<String?>(null) }
    var renameShortcutText by remember { mutableStateOf("") }
    var renameConn by remember { mutableStateOf<Connection?>(null) }
    var renameConnText by remember { mutableStateOf("") }
    var editingPathSide by remember { mutableStateOf<PanelSide?>(null) }
    var editingPathText by remember { mutableStateOf("") }
    // 「常用文件夹」是系统目录的快捷入口，用到的频率远低于「快捷目录」，
    // 默认收起，免得一开抽屉就是九行系统目录把下面的连接挤到屏幕外。
    var commonFoldersExpanded by remember { mutableStateOf(false) }
    var shortcutsExpanded by remember { mutableStateOf(true) }
    // 「回到顶部」用自增计数当信号：列表状态在 FilePanel 内部（每个路径一份），
    // 外面没法直接拿到 LazyListState，就让内部监听这个计数变化。
    var leftScrollTopTick by remember { mutableIntStateOf(0) }
    var rightScrollTopTick by remember { mutableIntStateOf(0) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    // 网络文件先由 ViewModel 下到缓存，这里拿到本地文件后再用系统 Intent 打开
    LaunchedEffect(pendingOpen) {
        val file = pendingOpen ?: return@LaunchedEffect
        openCachedFile(context, file)
        vm.consumePendingOpen()
    }

    // 编辑器保存失败/兜底落盘等提示，统一用 Toast，不抢编辑页的布局
    LaunchedEffect(editorNotice) {
        val message = editorNotice ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        vm.consumeEditorNotice()
    }

    // 本地栏点开：媒体 / 图片进内置查看页（和网络栏同一条链路）；文本/未知后缀进
    // 内置编辑器；其余（Office/PDF/常见二进制）仍交系统打开。
    val openLocal: (PanelSide, RemoteFile) -> Unit = remember(context, vm) {
        { side, item ->
            when {
                // 内置播放器没有提取器的容器（WMV/RM 等）：直接交给系统应用
                MediaTypes.isExternalPlayable(item.name) -> openLocalFile(context, item)
                MediaTypes.isPlayable(item.name) || MediaTypes.isImage(item.name) -> {
                    // 数据源建不出来（理论上本地不会）就退回系统应用
                    if (!vm.openViewerAt(side, item)) openLocalFile(context, item)
                }
                TextFileTypes.shouldOpenAsText(item.name) -> vm.openTextEditor(side, item)
                else -> openLocalFile(context, item)
            }
        }
    }

    // 复制路径提示：系统 Toast，但新的会 cancel 掉旧的，不排队
    val lastCopyToast = remember { mutableStateOf<android.widget.Toast?>(null) }
    val onCopyPath: (String) -> Unit = {
        lastCopyToast.value?.cancel()
        val t = android.widget.Toast.makeText(
            context,
            "路径已复制",
            android.widget.Toast.LENGTH_SHORT
        )
        lastCopyToast.value = t
        t.show()
    }

    val drawerAnim = remember { Animatable(0f) }
    val sheetAnim = remember { Animatable(0f) }
    val taskDrawerAnim = remember { Animatable(0f) }

    val drawerProgress = drawerAnim.value
    val sheetProgress = sheetAnim.value
    val taskDrawerProgress = taskDrawerAnim.value

    val drawerWidthDp = (configuration.screenWidthDp * 0.78f).dp
    val taskDrawerWidthDp = (configuration.screenWidthDp * 0.65f).dp
    val drawerWidthPx = with(density) { drawerWidthDp.toPx() }

    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val sheetHeightFraction = 0.55f
    val sheetMaxHeightPx = screenHeightPx * sheetHeightFraction

    val drawerIsOpen = drawerProgress > 0.01f
    val sheetIsOpen = sheetProgress > 0.01f
    val taskDrawerIsOpen = taskDrawerProgress > 0.01f

    val focusedState = if (focusedSide == PanelSide.LEFT) left else right
    val anySelectionMode = left.selectionMode || right.selectionMode
    val focusedSelection = focusedState.selection
    val focusedSelectionCount = focusedSelection.size

    // 两栏在同一目录时「移动」没有意义（等于改个怪名字再删原文件），按钮置灰。
    // 只在路径变化时算一次：本地路径要解析符号链接，不能每次重组都算。
    val sameDirAsOther = remember(
        left.sourceId, left.currentPath, right.sourceId, right.currentPath
    ) {
        PathUtil.sameLocation(left.sourceId, left.currentPath, right.sourceId, right.currentPath)
    }

    // 主题（颜色模式/动画开关/模块颜色）都由 XjFileTheme 算好放在 CompositionLocal 里，
    // 这里不再直接问系统，否则设置里选「浅色/深色」时手写的那几个背景色不会跟着变。
    val isDark = LocalAppDarkTheme.current
    val animationsOn = LocalAnimationsEnabled.current
    val moduleColors = LocalModuleColors.current

    BackHandler(enabled = sheetIsOpen) {
        scope.launch { sheetAnim.animateTo(0f, tween(if (animationsOn) 200 else 0)) }
    }
    BackHandler(enabled = !sheetIsOpen && drawerIsOpen) {
        scope.launch { drawerAnim.animateTo(0f, tween(if (animationsOn) 200 else 0)) }
    }
    BackHandler(enabled = !sheetIsOpen && !drawerIsOpen && taskDrawerIsOpen) {
        scope.launch { taskDrawerAnim.animateTo(0f, tween(if (animationsOn) 280 else 0)) }
    }
    BackHandler(enabled = !sheetIsOpen && !drawerIsOpen && !taskDrawerIsOpen && anySelectionMode) {
        vm.clearAllSelection()
    }
    BackHandler(enabled = !sheetIsOpen && !drawerIsOpen && !taskDrawerIsOpen && !anySelectionMode) {
        if (focusedState.canGoUp) vm.goUp(focusedSide)
    }

    val panelBg = if (isDark) Color(0xFF141414) else Color(0xFFEFEFEF)
    val headerBg = if (isDark) Color(0xFF050505) else Color(0xFFDCDCDC)
    val textMain = if (isDark) Color(0xFFE6E6E6) else Color(0xFF1A1A1A)
    val textSub = if (isDark) Color(0xFF9A9A9A) else Color(0xFF666666)
    // 「最近」列表里的文件夹图标也跟着「模块颜色 → 文件夹」走
    val folderTint = moduleColors.folder

    val commonFolders: List<Pair<String, File>> = remember {
        val candidates = listOf<Pair<String, File>>(
            "相册" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "相机" to File(Environment.getExternalStorageDirectory(), "DCIM/Camera"),
            "截图" to File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "Screenshots"
            ),
            "下载" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "文档" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "音乐" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "视频" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "蓝牙" to File(Environment.getExternalStorageDirectory(), "Bluetooth"),
            "录音" to File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "Recordings"
            ),
        )
        candidates.filter { (_, f) -> runCatching { f.exists() && f.isDirectory }.getOrDefault(false) }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    AppTopBar(
                        path = focusedState.currentPath,
                        stats = rememberStats(focusedState.items),
                        onMenu = { scope.launch { drawerAnim.animateTo(1f, tween(if (animationsOn) 420 else 0)) } },
                        onScrollToTop = {
                            if (focusedSide == PanelSide.LEFT) leftScrollTopTick++
                            else rightScrollTopTick++
                        },
                        onRefresh = { vm.refresh(focusedSide) },
                        onTasks = {
                            vm.dismissActiveBatchDialog()
                            scope.launch { taskDrawerAnim.animateTo(1f, tween(if (animationsOn) 320 else 0)) }
                        },
                        onSettings = {
                            context.startActivity(
                                android.content.Intent(context, com.cmzc.xj_file.app.SettingsActivity::class.java)
                            )
                        },
                        onGallery = { vm.openGallery(focusedSide) },
                        onPathClick = {
                            editingPathText = focusedState.currentPath
                            editingPathSide = focusedSide
                        },
                        onSwipeRight = {
                            scope.launch { drawerAnim.animateTo(1f, tween(if (animationsOn) 420 else 0)) }
                        },
                        onCopyPath = onCopyPath
                    )
                },
                bottomBar = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .height(48.dp)
                    )
                }
            ) { padding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        FilePanel(
                            state = left,
                            focused = focusedSide == PanelSide.LEFT,
                            onFocus = { focusedSide = PanelSide.LEFT },
                            onClearError = { vm.clearError(PanelSide.LEFT) },
                            onOpen = { item ->
                                val drive = left.drives.byEntryName(item.name)
                                if (drive != null && drive.isOptical) vm.openOpticalDrive(PanelSide.LEFT, item, drive)
                                else if (item.isDirectory) vm.navigate(PanelSide.LEFT, item.path)
                                else if (left.sourceId == "local") openLocal(PanelSide.LEFT, item)
                                else vm.openRemoteFile(PanelSide.LEFT, item)
                            },
                            onLongPress = { item ->
                                focusedSide = PanelSide.LEFT
                                if (!(left.selectionMode && item.path in left.selection)) {
                                    vm.selectSingle(PanelSide.LEFT, item.path)
                                }
                                showFileActionDialog = true
                            },
                            onSwipeSelect = { item ->
                                focusedSide = PanelSide.LEFT
                                vm.swipeSelect(PanelSide.LEFT, item.path)
                            },
                            onDriveLongPress = { drive, item ->
                                focusedSide = PanelSide.LEFT
                                if (drive.optical == com.cmzc.xj_file.app.core.disk.OpticalMedia.DVD_VIDEO) {
                                    opticalLongPress = Triple(PanelSide.LEFT, drive, item)
                                } else {
                                    vm.openOpticalDrive(PanelSide.LEFT, item, drive)
                                }
                            },
                            onToggleSelect = { item ->
                                focusedSide = PanelSide.LEFT
                                vm.toggleSelect(PanelSide.LEFT, item.path)
                            },
                            onPathClick = {
                                editingPathText = left.currentPath
                                editingPathSide = PanelSide.LEFT
                            },
                            onGoUp = { vm.goUp(PanelSide.LEFT) },
                            highlightPath = lastCreated?.let { (side, path) ->
                                if (side == PanelSide.LEFT) path else null
                            },
                            onHighlightConsumed = { vm.clearLastCreated() },
                            onRefresh = { vm.refresh(PanelSide.LEFT) },
                            scrollToTopTick = leftScrollTopTick,
                            onCopyPath = onCopyPath,
                            onDisconnect = { vm.disconnectCurrent(PanelSide.LEFT) }
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        FilePanel(
                            state = right,
                            focused = focusedSide == PanelSide.RIGHT,
                            onFocus = { focusedSide = PanelSide.RIGHT },
                            onClearError = { vm.clearError(PanelSide.RIGHT) },
                            onOpen = { item ->
                                val drive = right.drives.byEntryName(item.name)
                                if (drive != null && drive.isOptical) vm.openOpticalDrive(PanelSide.RIGHT, item, drive)
                                else if (item.isDirectory) vm.navigate(PanelSide.RIGHT, item.path)
                                else if (right.sourceId == "local") openLocal(PanelSide.RIGHT, item)
                                else vm.openRemoteFile(PanelSide.RIGHT, item)
                            },
                            onLongPress = { item ->
                                focusedSide = PanelSide.RIGHT
                                if (!(right.selectionMode && item.path in right.selection)) {
                                    vm.selectSingle(PanelSide.RIGHT, item.path)
                                }
                                showFileActionDialog = true
                            },
                            onSwipeSelect = { item ->
                                focusedSide = PanelSide.RIGHT
                                vm.swipeSelect(PanelSide.RIGHT, item.path)
                            },
                            onDriveLongPress = { drive, item ->
                                focusedSide = PanelSide.RIGHT
                                if (drive.optical == com.cmzc.xj_file.app.core.disk.OpticalMedia.DVD_VIDEO) {
                                    opticalLongPress = Triple(PanelSide.RIGHT, drive, item)
                                } else {
                                    vm.openOpticalDrive(PanelSide.RIGHT, item, drive)
                                }
                            },
                            onToggleSelect = { item ->
                                focusedSide = PanelSide.RIGHT
                                vm.toggleSelect(PanelSide.RIGHT, item.path)
                            },
                            onPathClick = {
                                editingPathText = right.currentPath
                                editingPathSide = PanelSide.RIGHT
                            },
                            onGoUp = { vm.goUp(PanelSide.RIGHT) },
                            highlightPath = lastCreated?.let { (side, path) ->
                                if (side == PanelSide.RIGHT) path else null
                            },
                            onHighlightConsumed = { vm.clearLastCreated() },
                            onRefresh = { vm.refresh(PanelSide.RIGHT) },
                            scrollToTopTick = rightScrollTopTick,
                            onCopyPath = onCopyPath,
                            onDisconnect = { vm.disconnectCurrent(PanelSide.RIGHT) }
                        )
                    }
                }
            }
        }

        BottomSheetLayer(
            progress = sheetProgress,
            heightFraction = sheetHeightFraction,
            backgroundColor = panelBg,
            onClose = { scope.launch { sheetAnim.animateTo(0f, tween(if (animationsOn) 200 else 0)) } }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBg)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { _, dragAmount ->
                                val delta = dragAmount * 2f / sheetMaxHeightPx
                                val newValue = (sheetAnim.value + delta).coerceIn(0f, 1f)
                                scope.launch { sheetAnim.snapTo(newValue) }
                            },
                            onDragEnd = {
                                scope.launch {
                                    val target = if (sheetAnim.value > 0.4f) 1f else 0f
                                    sheetAnim.animateTo(target, tween(if (animationsOn) 200 else 0))
                                }
                            }
                        )
                    }
            ) {
                Text(
                    "最近",
                    fontSize = 14.sp,
                    color = textMain,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            if (recentPaths.isEmpty()) {
                Text(
                    "还没有最近记录\n在任意目录新建、删除或重命名后，痕迹会留在这里",
                    fontSize = 12.sp,
                    color = textSub,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(recentPaths, key = { it.sourceId + "|" + it.path }) { r ->
                        RecentRow(
                            recent = r,
                            textColor = textMain,
                            subTextColor = textSub,
                            folderTint = folderTint,
                            onClick = {
                                vm.navigateToRecent(focusedSide, r)
                                scope.launch { sheetAnim.animateTo(0f, tween(if (animationsOn) 200 else 0)) }
                            }
                        )
                    }
                    item { Spacer(Modifier.height(64.dp)) }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            if (1f - sheetProgress > 0.05f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = (1f - sheetProgress).coerceIn(0f, 1f)
                        }
                ) {
                    AppBottomBar(
                        selectionMode = anySelectionMode,
                        focusedIsLeft = focusedSide == PanelSide.LEFT,
                        canGoBack = focusedState.canGoBack,
                        canGoForward = focusedState.canGoForward,
                        canMove = !sameDirAsOther,
                        onBack = { vm.goBack(focusedSide) },
                        onForward = { vm.goForward(focusedSide) },
                        onAdd = { showNewItemDialog = true },
                        onSwap = { vm.swapSides() },
                        onFocusUp = { vm.goUp(focusedSide) },
                        onSelectAll = { vm.selectAll(focusedSide) },
                        onCopy = { vm.copySelectionToOther(focusedSide) },
                        onMove = { vm.moveSelectionToOther(focusedSide) },
                        onDelete = { showDeleteConfirm = true },
                        onCancelSelection = { vm.clearAllSelection() },
                        onSwipeProgress = { dragAmount ->
                            val delta = -dragAmount * 2f / sheetMaxHeightPx
                            val newValue = (sheetAnim.value + delta).coerceIn(0f, 1f)
                            scope.launch { sheetAnim.snapTo(newValue) }
                        },
                        onSwipeEnd = {
                            scope.launch {
                                val target = if (sheetAnim.value > 0.4f) 1f else 0f
                                sheetAnim.animateTo(target, tween(if (animationsOn) 200 else 0))
                            }
                        }
                    )
                }
            }

            if (sheetProgress > 0.01f && recentPaths.isNotEmpty()) {
                // 这条「清空最近记录」栏是浮在面板上的，必须自带底色：
                // 否则列表最后一行会从垃圾桶后面透出来（看起来像垃圾桶画在记录上）。
                // 没有记录时整条栏也不显示，空面板上放个垃圾桶没有意义。
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .background(panelBg)
                        .navigationBarsPadding()
                        .graphicsLayer {
                            alpha = sheetProgress.coerceIn(0f, 1f)
                        }
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(onClick = { vm.clearRecent() }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "清空最近记录",
                                tint = moduleColors.button
                            )
                        }
                    }
                }
            }
        }

        LeftDrawerLayer(
            progress = drawerProgress,
            drawerWidth = drawerWidthDp,
            onClose = { scope.launch { drawerAnim.animateTo(0f, tween(if (animationsOn) 320 else 0)) } }
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = com.cmzc.xj_file.app.R.drawable.xj),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = Color.Unspecified
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "文件管理器",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    HorizontalDivider()
                }
                item {
                    DrawerItem(
                        icon = Icons.Default.Storage,
                        label = "本地存储",
                        onClick = {
                            scope.launch { drawerAnim.animateTo(0f, tween(if (animationsOn) 200 else 0)) }
                            vm.switchToLocal(focusedSide)
                        }
                    )
                }

                item {
                    HorizontalDivider()
                    CollapsibleHeader(
                        title = "常用文件夹",
                        expanded = commonFoldersExpanded,
                        onClick = { commonFoldersExpanded = !commonFoldersExpanded }
                    )
                }
                if (commonFoldersExpanded) {
                    items(commonFolders) { (name, file) ->
                        DrawerItem(
                            icon = Icons.Default.Folder,
                            label = name,
                            onClick = {
                                scope.launch { drawerAnim.animateTo(0f, tween(if (animationsOn) 200 else 0)) }
                                vm.navigate(focusedSide, PathUtil.normalizeLocal(file.absolutePath))
                            }
                        )
                    }
                }

                item {
                    HorizontalDivider()
                    CollapsibleHeader(
                        title = "快捷目录",
                        expanded = shortcutsExpanded,
                        onClick = { shortcutsExpanded = !shortcutsExpanded }
                    )
                }
                if (shortcutsExpanded) {
                    item(key = "shortcut_add_top") {
                        DrawerItem(
                            icon = Icons.Default.Add,
                            label = "+ 添加目录",
                            onClick = {
                                addShortcutPath = focusedState.currentPath
                                addShortcutName = ""
                                showAddShortcutDialog = true
                            }
                        )
                    }
                    item(key = "shortcut_add_network") {
                        DrawerItem(
                            icon = Icons.Default.Cloud,
                            label = "+ 添加网络位置",
                            onClick = {
                                val src = focusedState.sourceId
                                val path = focusedState.currentPath
                                if (src != "local") {
                                    val cid = src.substringAfter(':', "")
                                    val conn = connections.firstOrNull { it.id == cid }
                                    networkShortcutConnId = cid
                                    networkShortcutSubPath = if (
                                        conn != null && path.startsWith("//${conn.host}/")
                                    ) path.removePrefix("//${conn.host}/") else ""
                                    val sub = networkShortcutSubPath.trimEnd('/')
                                    networkShortcutName =
                                        if (sub.isEmpty()) (conn?.host ?: "")
                                        else sub.substringAfterLast('/')
                                } else {
                                    networkShortcutConnId = null
                                    networkShortcutSubPath = ""
                                    networkShortcutName = ""
                                }
                                showAddNetworkShortcutDialog = true
                            }
                        )
                    }
                    if (shortcutDirs.isNotEmpty()) {
                        item(key = "shortcut_divider") {
                            DashedDivider(
                                modifier = Modifier.padding(
                                    horizontal = 16.dp,
                                    vertical = 6.dp
                                )
                            )
                        }
                    }
                    items(
                        shortcutDirs.sortedWith(
                            compareBy<ShortcutDir> {
                                val t =
                                    connections.firstOrNull { c -> c.id == it.connectionId }?.type
                                when {
                                    !it.isNetwork -> 0
                                    t == ConnectionType.SMB -> 1
                                    t == ConnectionType.SFTP -> 2
                                    else -> 3
                                }
                            }.thenBy { it.name }
                        ),
                        key = { it.path + "|" + (it.connectionId ?: "") }
                    ) { dir ->
                        var showMenu by remember(
                            dir.path,
                            dir.connectionId
                        ) { mutableStateOf(false) }
                        var menuOffset by remember(dir.path, dir.connectionId) {
                            mutableStateOf(
                                IntOffset.Zero
                            )
                        }
                        Box(modifier = Modifier.fillMaxWidth()) {
                            DrawerItem(
                                icon = if (!dir.isNetwork) Icons.Default.Folder
                                else {
                                    val t =
                                        connections.firstOrNull { it.id == dir.connectionId }?.type
                                    when (t) {
                                        ConnectionType.SMB -> Icons.Default.Cloud
                                        ConnectionType.SFTP -> Icons.Default.Language
                                        null -> Icons.Default.Cloud
                                    }
                                },
                                label = dir.name,
                                onClick = {
                                    scope.launch { drawerAnim.animateTo(0f, tween(if (animationsOn) 200 else 0)) }
                                    if (dir.isNetwork) {
                                        val conn =
                                            connections.firstOrNull { it.id == dir.connectionId }
                                        if (conn != null) {
                                            vm.switchToConnection(focusedSide, conn, dir.path)
                                        } else {
                                            android.widget.Toast.makeText(
                                                context,
                                                "这个连接已不存在，请重新添加",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    } else {
                                        vm.navigate(focusedSide, dir.path)
                                    }
                                },
                                onLongPress = { offset ->
                                    menuOffset = offset
                                    showMenu = true
                                },
                                suffix = if (!dir.isNetwork) "本地"
                                else {
                                    val t =
                                        connections.firstOrNull { it.id == dir.connectionId }?.type
                                    when (t) {
                                        ConnectionType.SMB -> "SMB"
                                        ConnectionType.SFTP -> "SFTP"
                                        null -> "?"
                                    }
                                }
                            )
                            AppDropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                offset = with(density) {
                                    DpOffset(menuOffset.x.toDp(), menuOffset.y.toDp())
                                }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("重命名") },
                                    onClick = {
                                        renameShortcutPath = dir.path
                                        renameShortcutText = dir.name
                                        showMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("删除") },
                                    onClick = {
                                        vm.removeShortcutDir(dir.path, dir.connectionId)
                                        showMenu = false
                                    }
                                )
                            }
                        }
                    }
                    if (shortcutDirs.size >= 8) {
                        item(key = "shortcut_divider_bottom") {
                            DashedDivider(
                                modifier = Modifier.padding(
                                    horizontal = 16.dp,
                                    vertical = 6.dp
                                )
                            )
                        }
                        item(key = "shortcut_add_bottom") {
                            DrawerItem(
                                icon = Icons.Default.Add,
                                label = "+ 添加目录",
                                onClick = {
                                    addShortcutPath = focusedState.currentPath
                                    addShortcutName = ""
                                    showAddShortcutDialog = true
                                }
                            )
                        }
                    }
                }

                item {
                    HorizontalDivider()
                    Text(
                        "连接",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(connections, key = { it.id }) { conn ->
                    var showMenu by remember(conn.id) { mutableStateOf(false) }
                    var menuOffset by remember(conn.id) { mutableStateOf(IntOffset.Zero) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        DrawerItem(
                            icon = if (conn.type == ConnectionType.SMB) Icons.Default.Cloud
                            else Icons.Default.Language,
                            label = conn.name.ifBlank { conn.host },
                            onClick = {
                                scope.launch { drawerAnim.animateTo(0f, tween(if (animationsOn) 200 else 0)) }
                                vm.switchToConnection(focusedSide, conn)
                            },
                            onLongPress = { offset ->
                                menuOffset = offset
                                showMenu = true
                            },
                            suffix = when (conn.type) {
                                ConnectionType.SMB -> "SMB"
                                ConnectionType.SFTP -> "SFTP"
                            }
                        )
                        AppDropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            offset = with(density) {
                                DpOffset(menuOffset.x.toDp(), menuOffset.y.toDp())
                            }
                        ) {
                            DropdownMenuItem(
                                text = { Text("重命名") },
                                onClick = {
                                    renameConn = conn
                                    renameConnText = conn.name
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("删除") },
                                onClick = {
                                    vm.deleteConnection(conn.id)
                                    showMenu = false
                                }
                            )
                        }
                    }
                }
                item {
                    DrawerItem(
                        icon = Icons.Default.Add,
                        label = "添加连接",
                        onClick = {
                            scope.launch { drawerAnim.animateTo(0f, tween(if (animationsOn) 200 else 0)) }
                            showAddDialog = true
                        }
                    )
                }
            }
        }
        // 右侧任务抽屉
        TaskDrawer(
            progress = taskDrawerProgress,
            drawerWidth = taskDrawerWidthDp,
            tasks = allTransferTasks,
            pausedBatches = pausedBatches,
            pausedTasks = pausedTasks,
            onClose = { scope.launch { taskDrawerAnim.animateTo(0f, tween(if (animationsOn) 280 else 0)) } },
            onCancelBatch = { batchId -> vm.cancelBatchById(batchId) },
            onCancelTask = { taskId -> vm.cancelSingleTask(taskId) },
            onRemoveBatch = { batchId -> TransferManager.removeBatch(batchId) },
            onToggleBatchPause = { batchId -> TransferManager.toggleBatchPause(batchId) },
            onToggleTaskPause = { taskId -> TransferManager.toggleTaskPause(taskId) }
        )
    }

    // 光驱行长按：视频 DVD 可以强行当数据盘浏览。
    // 为什么需要这个兜底：VIDEO_TS 判断对"只刻了 AUDIO_TS/VIDEO_TS 的数据 DVD"必然误判，
    // 与其猜，不如把选择权交给用户。
    opticalLongPress?.let { (side, drive, item) ->
        AlertDialog(
            onDismissRequest = { opticalLongPress = null },
            title = { Text("打开方式") },
            text = {
                Column {
                    Text(
                        "这张盘被识别为视频 DVD。数据 DVD 里如果只刻了 AUDIO_TS/VIDEO_TS，也会得到同样的判断，" +
                            "所以这里可以自己选打开方式。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "以视频 DVD 方式打开（默认）",
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                opticalLongPress = null
                                vm.openOpticalDrive(side, item, drive)
                            }
                            .padding(vertical = 12.dp)
                    )
                    Text(
                        text = "以数据 DVD 方式打开",
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                opticalLongPress = null
                                vm.navigate(side, item.path)
                            }
                            .padding(vertical = 12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { opticalLongPress = null }) { Text("取消") }
            }
        )
    }

    if (showFileActionDialog && focusedSelectionCount > 0) {
        val isLocal = focusedState.sourceId == "local"
        val singlePath = if (focusedSelectionCount == 1) focusedSelection.first() else null
        val singleIsDir = singlePath?.let { p ->
            focusedState.items.firstOrNull { it.path == p }?.isDirectory ?: false
        } ?: false
        FileActionDialog(
            selectionCount = focusedSelectionCount,
            canRename = focusedSelectionCount == 1,
            canShare = isLocal && singlePath != null && !singleIsDir,
            canOpenWith = singlePath != null && !singleIsDir,
            canMove = !sameDirAsOther,
            onDismiss = {
                showFileActionDialog = false
                if (!left.selectionMode) vm.clearSelection(PanelSide.LEFT)
                if (!right.selectionMode) vm.clearSelection(PanelSide.RIGHT)
            },
            onCopy = {
                showFileActionDialog = false
                vm.copySelectionToOther(focusedSide)
            },
            onMove = {
                showFileActionDialog = false
                vm.moveSelectionToOther(focusedSide)
            },
            onDelete = {
                showFileActionDialog = false
                showDeleteConfirm = true
            },
            onRename = {
                showFileActionDialog = false
                if (focusedSelectionCount == 1) showRenameDialog = true
            },
            onZip = {
                showFileActionDialog = false
                showCompressDialog = true
            },
            onProp = {
                showFileActionDialog = false
                showAttrDialog = true
            },
            onShare = {
                showFileActionDialog = false
                if (isLocal && singlePath != null) shareLocalFile(context, singlePath)
            },
            onOpenWith = {
                showFileActionDialog = false
                val item = singlePath?.let { p ->
                    focusedState.items.firstOrNull { it.path == p }
                }
                if (item != null) {
                    openWithTarget = focusedSide to item
                    // 和 onDismiss 一样收掉长按选中；否则关掉选择框后文件还蓝着
                    if (!left.selectionMode) vm.clearSelection(PanelSide.LEFT)
                    if (!right.selectionMode) vm.clearSelection(PanelSide.RIGHT)
                }
            }
        )
    }

    // 压缩参数面板：从长按菜单进入，保留原选择，确认时才真正开始压缩。
    if (showCompressDialog && focusedSelectionCount > 0) {
        val selectedItems = focusedState.items.filter { it.path in focusedSelection }
        if (selectedItems.isNotEmpty()) {
            CompressDialog(
                itemCount = selectedItems.size,
                hasDirectory = selectedItems.any { it.isDirectory },
                totalBytes = selectedItems.sumOf { it.size.coerceAtLeast(0L) },
                defaultName = defaultArchiveName(selectedItems, focusedState.currentPath),
                outputDir = focusedState.currentPath,
                onDismiss = { showCompressDialog = false },
                onConfirm = { request ->
                    showCompressDialog = false
                    // 先把选择交给 ViewModel（它会同步取 selected 快照），再收选择
                    vm.compressSelection(focusedSide, request)
                    vm.clearSelection(focusedSide)
                },
            )
        }
    }

    // 长按「打开方式」：文本编辑器 / 系统应用。Office/PDF/媒体没有文本选项。
    openWithTarget?.let { (side, item) ->
        val sideIsLocal = (if (side == PanelSide.LEFT) left else right).sourceId == "local"
        OpenWithDialog(
            fileName = item.name,
            textEnabled = TextFileTypes.shouldOpenAsText(item.name),
            onDismiss = { openWithTarget = null },
            onText = {
                openWithTarget = null
                vm.openTextEditor(side, item)
            },
            onSystem = {
                openWithTarget = null
                if (sideIsLocal) openLocalFile(context, item) else vm.openWithSystem(side, item)
            },
        )
    }

    // 删除确认：网络位置没有回收站，误触就是不可恢复的
    if (showDeleteConfirm && focusedSelectionCount > 0) {
        val deleteCount = focusedSelectionCount
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = {
                Column {
                    Text("将永久删除 $deleteCount 项。网络位置没有回收站，删除后无法恢复。")
                    if (deleteCount == 1) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            focusedSelection.first(),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    showFileActionDialog = false
                    vm.deleteSelected(focusedSide)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showRenameDialog && focusedSelectionCount == 1) {
        var newName by remember { mutableStateOf("") }
        val oldName =
            focusedState.items.firstOrNull { it.path == focusedSelection.first() }?.name ?: ""
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = newName.ifEmpty { oldName },
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = if (newName.isBlank()) oldName else newName
                    vm.renameSelected(focusedSide, n)
                    showRenameDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
            }
        )
    }

    if (showAttrDialog) {
        val paths = focusedSelection.toList()
        AlertDialog(
            onDismissRequest = { showAttrDialog = false },
            title = { Text("属性") },
            text = {
                if (paths.size == 1) {
                    val p = paths.first()
                    val item = focusedState.items.firstOrNull { it.path == p }
                    Column {
                        Text("名称：${item?.name ?: p.substringAfterLast('/')}", fontSize = 12.sp)
                        Text("路径：$p", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("大小：${item?.size?.let { Formatters.formatSize(it) } ?: "未知"}",
                            fontSize = 12.sp)
                        Text("修改时间：${item?.lastModified?.let { Formatters.formatTime(it) } ?: "未知"}",
                            fontSize = 12.sp)
                        Text(
                            "权限：${item?.permission ?: "未知"}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "类型：${if (item?.isDirectory == true) "目录" else "文件"}",
                            fontSize = 12.sp
                        )
                    }
                } else {
                    Text("已选择 ${paths.size} 项", fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { showAttrDialog = false }) { Text("关闭") }
            }
        )
    }

    if (showAddShortcutDialog) {
        AlertDialog(
            onDismissRequest = { showAddShortcutDialog = false },
            title = { Text("添加快捷目录") },
            text = {
                Column {
                    OutlinedTextField(
                        value = addShortcutPath,
                        onValueChange = { addShortcutPath = it },
                        label = { Text("完整路径") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = addShortcutName,
                        onValueChange = { addShortcutName = it },
                        label = { Text("显示名称（可留空）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.addShortcutDir(addShortcutName, addShortcutPath)
                    showAddShortcutDialog = false
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddShortcutDialog = false }) { Text("取消") }
            }
        )
    }

    renameShortcutPath?.let { path ->
        AlertDialog(
            onDismissRequest = { renameShortcutPath = null },
            title = { Text("重命名快捷目录") },
            text = {
                OutlinedTextField(
                    value = renameShortcutText,
                    onValueChange = { renameShortcutText = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameShortcutDir(path, renameShortcutText)
                    renameShortcutPath = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameShortcutPath = null }) { Text("取消") }
            }
        )
    }

    renameConn?.let { conn ->
        AlertDialog(
            onDismissRequest = { renameConn = null },
            title = { Text("重命名连接") },
            text = {
                OutlinedTextField(
                    value = renameConnText,
                    onValueChange = { renameConnText = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = renameConnText.trim()
                    if (trimmed.isNotEmpty()) {
                        vm.updateConnection(conn.copy(name = trimmed))
                    }
                    renameConn = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameConn = null }) { Text("取消") }
            }
        )
    }

    if (showAddDialog) {
        AddConnectionDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = {
                vm.addConnection(it)
                showAddDialog = false
            }
        )
    }

    if (showAddNetworkShortcutDialog) {
        AddNetworkShortcutDialog(
            connections = connections,
            initialConnId = networkShortcutConnId,
            initialSubPath = networkShortcutSubPath,
            initialName = networkShortcutName,
            onDismiss = { showAddNetworkShortcutDialog = false },
            onAddConnection = {
                showAddNetworkShortcutDialog = false
                showAddDialog = true
            },
            onConfirm = { dir ->
                vm.addShortcutDir(dir.name, dir.path, dir.connectionId)
                showAddNetworkShortcutDialog = false
            }
        )
    }

    if (showNewItemDialog) {
        NewItemDialog(
            existingNames = focusedState.items.map { it.name },
            onDismiss = { showNewItemDialog = false },
            onConfirm = { name, isFolder ->
                if (isFolder) vm.createFolder(focusedSide, name)
                else vm.createFile(focusedSide, name)
                showNewItemDialog = false
            }
        )
    }

    editingPathSide?.let { side ->
        var text by remember(editingPathText) { mutableStateOf(editingPathText) }
        AlertDialog(
            onDismissRequest = { editingPathSide = null },
            title = { Text("输入路径") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = text.trim()
                    if (p.isNotEmpty()) vm.navigate(side, p)
                    editingPathSide = null
                }) { Text("跳转") }
            },
            dismissButton = {
                TextButton(onClick = { editingPathSide = null }) { Text("取消") }
            }
        )
    }

    // ===== 3C：冲突处理对话框 =====
    conflictState?.let { cs ->
        ConflictDialog(
            conflict = cs.conflict,
            index = cs.index,
            total = cs.total,
            onResolve = { vm.resolveConflict(it) },
            onCancel = { vm.cancelConflict() }
        )
    }

    // ===== 传输进度对话框 =====
    activeBatchId?.let { batchId ->
        val batchTasks = allTransferTasks.filter { it.batchId == batchId }
        if (batchTasks.isNotEmpty()) {
            val totalBytes = batchTasks.sumOf { if (it.totalBytes > 0) it.totalBytes else 0L }
            val transferred = batchTasks.sumOf { it.transferred }
            val progress = if (totalBytes > 0)
                (transferred.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
            val speed = batchTasks.sumOf { it.speedBytesPerSec }
            val currentFileName =
                batchTasks.firstOrNull { it.status == TransferStatus.RUNNING }?.name
            TransferProgressDialog(
                batchName = batchInfos[batchId]?.batchName ?: "传输中",
                progress = progress,
                currentFileName = currentFileName,
                transferredBytes = transferred,
                totalBytes = totalBytes,
                speedBytesPerSec = speed,
                verifying = verifyingBatchId == batchId,
                // 弹窗暂停的是「这一批」，不影响别的批次（预览下载不会被连坐）
                paused = batchId in pausedBatches,
                onBackground = { vm.dismissActiveBatchDialog() },
                onTogglePause = { TransferManager.toggleBatchPause(batchId) },
                onCancel = { vm.cancelActiveBatch() }
            )
        }
    }

    // 类相册视图：整屏 4 列网格。查看页开着时盖在它上面，从查看页返回仍回到相册。
    gallery?.let { g ->
        GalleryScreen(
            pages = g.pages,
            title = g.title,
            onBack = { vm.closeGallery() },
            onSelect = { page -> vm.openGalleryItem(g, page.file) }
        )
    }

    // 阶段四：内置查看页（播放器 / 图片）整屏盖在最上层（抽屉、弹窗都在它下面）。
    // 不做进出动画 —— ExoPlayer 的 Surface 和 Compose 过渡动画同时跑容易黑一帧，
    // 播放这种「进来就该有画面」的场景，直接切换体感反而更好。
    pendingViewer?.let { request ->
        // 缩略图条点到另一个类型时走这里；相册混排时整份列表里就有，直接翻过去即可。
        val onCrossType: (RemoteFile) -> Unit = { file ->
            if (request.mixed) {
                val idx = request.pages.indexOfFirst { it.file.path == file.path }
                if (idx >= 0) vm.viewerGoTo(idx) else vm.openViewerAt(request.side, file)
            } else {
                vm.openViewerAt(request.side, file)
            }
        }
        if (MediaTypes.isImage(request.current.file.name)) {
            ImageViewerScreen(
                pages = request.pages,
                startIndex = request.startIndex,
                strip = request.strip,
                onBack = { vm.closeViewer() },
                onCrossType = onCrossType,
                mixed = request.mixed,
                onPageChanged = { idx -> vm.viewerGoTo(idx) }
            )
        } else {
            PlayerScreen(
                pages = request.pages,
                startIndex = request.startIndex,
                strip = request.strip,
                onBack = { vm.closeViewer() },
                onFallback = { page, reason -> vm.fallbackFromViewer(request, page, reason) },
                onCrossType = onCrossType,
                mixed = request.mixed,
                onPageChanged = { idx -> vm.viewerGoTo(idx) }
            )
        }
    }

    // 阶段五：内置文本编辑器整屏盖在最上面（在查看页/相册之上）。
    // TextEditorScreen 自己处理返回键、未保存确认和保存回调；这里只负责把会话和 VM 接起来。
    pendingEditor?.let { session ->
        TextEditorScreen(
            document = session.document,
            encodingOptions = TextCodecImpl.supportedEncodings,
            onBack = { vm.closeEditor() },
            onSave = { save -> vm.saveEditor(session, save) },
            onEncodingSelected = { name -> vm.changeEditorEncoding(name) },
        )
    }
}

@Composable
private fun DashedDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outlineVariant,
    thickness: Dp = 1.dp,
    dashLength: Dp = 4.dp,
    gapLength: Dp = 4.dp
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
    ) {
        val dashPx = dashLength.toPx()
        val gapPx = gapLength.toPx()
        val strokeWidth = size.height
        var x = 0f
        while (x < size.width) {
            drawLine(
                color = color,
                start = Offset(x, strokeWidth / 2f),
                end = Offset((x + dashPx).coerceAtMost(size.width), strokeWidth / 2f),
                strokeWidth = strokeWidth
            )
            x += dashPx + gapPx
        }
    }
}

@Composable
private fun DrawerItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    onLongPress: ((IntOffset) -> Unit)? = null,
    suffix: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier
) {
    val animationsOn = LocalAnimationsEnabled.current
    var highlighted by remember { mutableStateOf(false) }

    val bgColor by animateColorAsState(
        targetValue = if (highlighted)
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
        else
            Color.Transparent,
        animationSpec = tween(durationMillis = if (animationsOn) 220 else 0),
        label = "drawerItemBg"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startPos = down.position
                    var cancelled = false
                    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                    val touchSlop = viewConfiguration.touchSlop

                    val timedOut = withTimeoutOrNull(longPressTimeout) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: return@withTimeoutOrNull
                            if (!change.pressed) return@withTimeoutOrNull
                            val dist = (change.position - startPos).getDistance()
                            if (dist > touchSlop) {
                                cancelled = true
                                return@withTimeoutOrNull
                            }
                        }
                    } == null

                    when {
                        timedOut && !cancelled -> {
                            highlighted = true
                            onLongPress?.invoke(
                                IntOffset(startPos.x.toInt(), startPos.y.toInt())
                            )
                            waitForUpOrCancellation()
                            highlighted = false
                        }
                        !cancelled -> onClick()
                        else -> { }
                    }
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(20.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (!suffix.isNullOrBlank()) {
            Text(
                text = suffix,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CollapsibleHeader(
    title: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun RecentRow(
    recent: RecentPath,
    textColor: Color,
    subTextColor: Color,
    folderTint: Color,
    onClick: () -> Unit
) {
    val displayName = recentDisplayName(recent.path)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickableSimple(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = folderTint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                fontSize = 13.sp,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = recent.path,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = subTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 压缩包默认名：单选沿用文件/文件夹名，多选用当前目录名；统一补 .zip。 */
private fun defaultArchiveName(items: List<RemoteFile>, currentPath: String): String {
    val raw = if (items.size == 1) {
        val item = items.first()
        if (item.isDirectory) {
            item.name
        } else if (item.name.startsWith(".") || !item.name.contains('.')) {
            item.name
        } else {
            item.name.substringBeforeLast('.')
        }
    } else {
        currentPath.trimEnd('/').substringAfterLast('/')
    }
    val base = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim('.', ' ')
        .ifBlank { "archive" }
    return "$base.zip"
}

private fun recentDisplayName(path: String): String {    if (path == "/") return "根目录"
    if (path == "//") return "网络根"
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return path
    if (trimmed.startsWith("//") && !trimmed.removePrefix("//").contains('/')) {
        return trimmed.removePrefix("//")
    }
    val last = trimmed.substringAfterLast('/')
    return if (last.isEmpty()) path else last
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.combinedClickableSimple(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = null)

@Composable
private fun rememberStats(items: List<RemoteFile>): String {
    return remember(items) {
        val folders = items.count { it.isDirectory }
        val files = items.size - folders
        val stat = runCatching {
            StatFs(Environment.getExternalStorageDirectory().absolutePath)
        }.getOrNull()
        val total = stat?.totalBytes ?: 0L
        val avail = stat?.availableBytes ?: 0L
        val used = total - avail
        "文件夹 $folders · 文件 $files · 已用 ${Formatters.formatSize(used)}/${Formatters.formatSize(total)}"
    }
}

private fun openLocalFile(context: Context, item: RemoteFile) {
    if (item.isDirectory) return
    val file = java.io.File(item.path)
    if (!file.exists()) return
    openCachedFile(context, file, item.name)
}

/**
 * 用 FileProvider + ACTION_VIEW 打开一个本地文件。
 * 既用于本来就存在的本地文件，也用于「远程文件已下到 cache/preview/」的情况。
 */
private fun openCachedFile(context: Context, file: java.io.File, displayName: String = file.name) {
    if (!file.exists()) return
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        val mime = context.contentResolver.getType(uri) ?: guessMime(displayName)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "打开方式"))
    } catch (t: Throwable) {
        android.widget.Toast.makeText(context, "无法打开：${t.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun shareLocalFile(context: Context, path: String) {
    val file = java.io.File(path)
    if (!file.exists()) return
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        val mime = context.contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享"))
    } catch (t: Throwable) {
        android.widget.Toast.makeText(context, "分享失败：${t.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun guessMime(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        // 文本 / 文档
        "txt", "log", "md" -> "text/plain"
        "rtf" -> "application/rtf"
        "html", "htm" -> "text/html"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "odt" -> "application/vnd.oasis.opendocument.text"
        "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
        "odp" -> "application/vnd.oasis.opendocument.presentation"
        "epub" -> "application/epub+zip"
        "mobi" -> "application/x-mobipocket-ebook"
        // 图片（含查看器不接管、交给系统的那几种）
        "jpg", "jpeg", "jpe", "jfif" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "avif" -> "image/avif"
        "tif", "tiff" -> "image/tiff"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        // 视频
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "flv" -> "video/x-flv"
        "wmv" -> "video/x-ms-wmv"
        "rm", "rmvb" -> "application/vnd.rn-realmedia"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "mpg", "mpeg", "vob" -> "video/mpeg"
        "3gp" -> "video/3gpp"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        else -> "*/*"
    }
}