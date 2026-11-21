package com.cmzc.xj_file.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cmzc.xj_file.app.core.settings.ColorModule
import com.cmzc.xj_file.app.ui.settings.AppearancePage
import com.cmzc.xj_file.app.ui.settings.ColorModePage
import com.cmzc.xj_file.app.ui.settings.ExperimentalPage
import com.cmzc.xj_file.app.ui.settings.ModuleColorPage
import com.cmzc.xj_file.app.ui.settings.ModuleColorsPage
import com.cmzc.xj_file.app.ui.settings.OpenSourceCatalog
import com.cmzc.xj_file.app.ui.settings.OpenSourceDetailPage
import com.cmzc.xj_file.app.ui.settings.OpenSourcePage
import com.cmzc.xj_file.app.ui.theme.LocalAnimationsEnabled
import com.cmzc.xj_file.app.ui.theme.XjFileTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XjFileTheme {
                SettingsScreen()
            }
        }
    }
}

/**
 * 设置页的层级结构。
 *
 * 一级只放入口，不放裸选项；所有实验性开关集中在「实验性内容」里。
 * 用 sealed interface 而不是 enum：模块颜色页需要带一个 ColorModule 参数。
 */
private sealed interface SettingsScreen {
    val title: String

    data object Root : SettingsScreen {
        override val title = "设置"
    }

    data object KeepAlive : SettingsScreen {
        override val title = "后台运行"
    }

    data object Appearance : SettingsScreen {
        override val title = "外观"
    }

    data object ColorMode : SettingsScreen {
        override val title = "颜色模式"
    }

    data object ModuleColors : SettingsScreen {
        override val title = "模块颜色"
    }

    data class ModuleColor(val module: ColorModule) : SettingsScreen {
        override val title: String get() = module.title
    }

    data object Experimental : SettingsScreen {
        override val title = "实验性内容"
    }

    data object Notification : SettingsScreen {
        override val title = "通知"
    }

    data object About : SettingsScreen {
        override val title = "关于"
    }

    data object OpenSource : SettingsScreen {
        override val title = "开源软件声明"
    }

    data class OpenSourceDetail(val id: String) : SettingsScreen {
        override val title: String
            get() = OpenSourceCatalog.byId(id)?.name ?: "开源许可"
    }
}

/** 返回上一级：模块颜色详情 → 模块颜色 → 外观 → 根。 */
private fun parentOf(screen: SettingsScreen): SettingsScreen? = when (screen) {
    SettingsScreen.Root -> null
    SettingsScreen.KeepAlive,
    SettingsScreen.Appearance,
    SettingsScreen.Experimental,
    SettingsScreen.Notification,
    SettingsScreen.About,
    SettingsScreen.OpenSource -> SettingsScreen.Root

    is SettingsScreen.OpenSourceDetail -> SettingsScreen.OpenSource

    SettingsScreen.ColorMode,
    SettingsScreen.ModuleColors -> SettingsScreen.Appearance

    is SettingsScreen.ModuleColor -> SettingsScreen.ModuleColors
}

/**
 * 彩蛋参数：在「设置」标题上连点 15 下、且第一下到最后一下不超过 2.5 秒。
 * 想调容易/难一点，改这两个常量就行。
 */
private const val EGG_TAPS = 15
private const val EGG_WINDOW_MS = 2500L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    var screen by remember { mutableStateOf<SettingsScreen>(SettingsScreen.Root) }
    // 记住方向：前进从右侧滑入，返回从左侧滑入
    var goingBack by remember { mutableStateOf(false) }
    val animationsOn = LocalAnimationsEnabled.current
    val context = LocalContext.current

    // ===== 彩蛋：2.5 秒内连点标题「设置」15 下，进图标预览页 =====
    // 只在根页面生效（子页面的标题不是"设置"）。点歪了就看不出任何反应，符合彩蛋的调性。
    var eggTaps by remember { mutableIntStateOf(0) }
    var eggFirstAt by remember { mutableLongStateOf(0L) }

    fun onTitleTap() {
        if (screen != SettingsScreen.Root) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (eggTaps == 0 || now - eggFirstAt > EGG_WINDOW_MS) {
            eggTaps = 1
            eggFirstAt = now
            return
        }
        eggTaps++
        if (eggTaps >= EGG_TAPS) {
            eggTaps = 0
            context.startActivity(
                android.content.Intent(context, com.cmzc.xj_file.app.ui.debug.DriveIconPreviewActivity::class.java)
            )
        }
    }

    fun goBack() {
        goingBack = true
        screen = parentOf(screen) ?: SettingsScreen.Root
    }

    fun go(target: SettingsScreen) {
        goingBack = false
        screen = target
    }

    // 返回键先回上一级，而不是直接退出设置
    BackHandler(enabled = screen != SettingsScreen.Root) { goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = screen.title,
                        modifier = Modifier.clickable(
                            // 关掉水波纹：彩蛋不该在点的时候给出反馈
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onTitleTap() }
                    )
                },
                navigationIcon = {
                    if (screen != SettingsScreen.Root) {
                        IconButton(onClick = { goBack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 每个子页面进入/退出都带一个横向滑动 + 淡入淡出；
            // 「界面动画」关掉时把时长设为 0，等于没有动画。
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    val direction = if (goingBack) -1 else 1
                    val durationMs = if (animationsOn) 220 else 0
                    (
                        slideInHorizontally(tween(durationMs)) { width -> direction * width } +
                            fadeIn(tween(durationMs))
                        ) togetherWith (
                        slideOutHorizontally(tween(durationMs)) { width -> -direction * width / 4 } +
                            fadeOut(tween(durationMs))
                        )
                },
                label = "settingsPage"
            ) { target ->
                when (target) {
                    SettingsScreen.Root -> SettingsHome(onOpen = { go(it) })
                    SettingsScreen.KeepAlive -> KeepAlivePage()
                    SettingsScreen.Appearance -> AppearancePage(
                        onOpenColorMode = { go(SettingsScreen.ColorMode) },
                        onOpenModuleColors = { go(SettingsScreen.ModuleColors) }
                    )
                    SettingsScreen.ColorMode -> ColorModePage()
                    SettingsScreen.ModuleColors -> ModuleColorsPage(
                        onOpenModule = { go(SettingsScreen.ModuleColor(it)) }
                    )
                    is SettingsScreen.ModuleColor -> ModuleColorPage(target.module)
                    SettingsScreen.Experimental -> ExperimentalPage()
                    SettingsScreen.Notification -> NotificationPage()
                    SettingsScreen.About -> AboutPage()
                    SettingsScreen.OpenSource -> OpenSourcePage(
                        onOpenDetail = { id -> go(SettingsScreen.OpenSourceDetail(id)) }
                    )
                    is SettingsScreen.OpenSourceDetail ->
                        OpenSourceDetailPage(target.id)
                }
            }
        }
    }
}

@Composable
private fun SettingsHome(onOpen: (SettingsScreen) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsEntry(
            title = "后台运行",
            subtitle = "传输期间不熄屏断网、忽略电池优化",
            onClick = { onOpen(SettingsScreen.KeepAlive) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsEntry(
            title = "外观",
            subtitle = "界面动画、颜色模式与模块配色",
            onClick = { onOpen(SettingsScreen.Appearance) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsEntry(
            title = "实验性内容",
            subtitle = "尚未稳定的功能（下拉刷新等）",
            onClick = { onOpen(SettingsScreen.Experimental) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsEntry(
            title = "通知",
            subtitle = "传输进度通知的权限与开关",
            onClick = { onOpen(SettingsScreen.Notification) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsEntry(
            title = "关于",
            subtitle = "版本信息",
            onClick = { onOpen(SettingsScreen.About) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsEntry(
            title = "开源软件声明",
            subtitle = "使用的开源库、许可证与项目说明",
            onClick = { onOpen(SettingsScreen.OpenSource) }
        )
    }
}

/** 一级菜单的一行：标题 + 一句说明 + 右箭头，点进去才是真正的设置项。 */
@Composable
private fun SettingsEntry(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun KeepAlivePage() {
    val context = LocalContext.current
    var ignoring by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // 用户去系统设置里改完再回来，状态要刷新
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoring = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "传输期间 App 会持有 WakeLock 和 WifiLock，防止息屏后 CPU 休眠、WiFi 降速。" +
                "但厂商的省电策略仍可能清掉后台进程，想更稳就把下面这项允许了。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "忽略电池优化",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (ignoring) "已允许" else "未允许",
                fontSize = 12.sp,
                color = if (ignoring) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        if (!ignoring) {
            Button(onClick = { requestIgnoreBatteryOptimizations(context) }) {
                Text("去允许")
            }
        }
        HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        )
        Text(
            text = "小提示：vivo 手机还要在「i 管家 → 后台高耗电 / 自启动」里把 XJ File 放行，"
                + "否则划掉应用或长时间锁屏后仍可能被清掉（那种情况下传输会中断）。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NotificationPage() {
    val context = LocalContext.current
    val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "传输时会在通知栏显示进度，并提供「暂停全部 / 恢复全部」「取消全部」两个操作。"
                + "关掉通知不影响传输本身，只是看不到进度、也没法从通知栏暂停。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "通知权限",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (enabled) "已开启" else "已关闭",
                fontSize = 12.sp,
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 已经开着就不给按钮了：点了也只是跳到同一个开关，纯属多余
        if (!enabled) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { openAppNotificationSettings(context) }) {
                Text("打开系统通知设置")
            }
        }
    }
}

@Composable
private fun AboutPage() {
    val context = LocalContext.current
    val pkg = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
    }
    val versionName = pkg?.versionName ?: "未知"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        AboutRow("版本", versionName)
        AboutRow("包名", context.packageName)
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return false
    return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
}

/**
 * 弹「忽略电池优化」授权。
 * 个别 ROM 不认 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，退回到电池优化列表页。
 */
private fun requestIgnoreBatteryOptimizations(context: Context) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
    )
    val ok = runCatching { context.startActivity(direct) }.isSuccess
    if (!ok) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

/** 直接跳到本应用的通知设置页（不是全系统的通知列表）。 */
private fun openAppNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        )
    }
}
