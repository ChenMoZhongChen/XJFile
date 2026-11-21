package com.cmzc.xj_file.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cmzc.xj_file.app.core.settings.AppColorMode
import com.cmzc.xj_file.app.core.settings.AppSettings
import com.cmzc.xj_file.app.core.settings.ColorModule
import com.cmzc.xj_file.app.ui.theme.LocalAppDarkTheme
import com.cmzc.xj_file.app.ui.theme.LocalModuleColors

// ============================================================
// 「外观」页
// ============================================================

@Composable
fun AppearancePage(
    onOpenColorMode: () -> Unit,
    onOpenModuleColors: () -> Unit
) {
    val animations by AppSettings.animations.collectAsStateWithLifecycle()
    val mode by AppSettings.colorMode.collectAsStateWithLifecycle()
    val moduleOverrides by AppSettings.moduleColors.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SwitchRow(
            title = "界面动画",
            subtitle = "抽屉、菜单、页面切换、新建弹窗等过渡动画。关闭可节省性能开销（文件栏除外）。",
            checked = animations,
            onCheckedChange = { AppSettings.setAnimations(it) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationRow(
            title = "颜色模式",
            subtitle = "当前：${mode.title}",
            onClick = onOpenColorMode
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationRow(
            title = "模块颜色",
            subtitle = moduleColorSummary(moduleOverrides),
            onClick = onOpenModuleColors
        )
        Spacer(Modifier.height(24.dp))
    }
}

// ============================================================
// 实验性内容（所有实验性开关都收在这里）
// ============================================================

@Composable
fun ExperimentalPage() {
    val pullRefresh by AppSettings.pullToRefresh.collectAsStateWithLifecycle()
    val m3Palette by AppSettings.m3Palette.collectAsStateWithLifecycle()
    val mediaIsolation by AppSettings.mediaIsolation.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SwitchRow(
            title = "下拉刷新",
            subtitle = "在列表顶部继续下拉约两行半可刷新当前目录。默认关闭。",
            checked = pullRefresh,
            onCheckedChange = { AppSettings.setPullToRefresh(it) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SwitchRow(
            title = "启用 M3 配色",
            subtitle = "调色板的预设色换成 Material 3 语义色：紫/粉/红保留相近色，其余位置用 M3 角色补齐。默认关闭。",
            checked = m3Palette,
            onCheckedChange = { AppSettings.setM3Palette(it) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SwitchRow(
            // 开关表达的是「混着翻」（不分离），默认关 = 默认严格分离。
            // 之前写反了：标题叫「严格分离」而开关默认开，用户看到"关掉"以为是分开，
            // 结果点了没反应 —— 语义和默认值必须和用户的理解一致。
            title = "图片与视频混合浏览",
            subtitle = "关闭时查看页底部的缩略图条只列同一类型的文件，图片和视频不会混在一起；" +
                "打开后缩略图条会把两种类型一起列出来，可以直接跨类型挑选。翻页始终只在同类之间进行。默认关闭。",
            checked = !mediaIsolation,
            onCheckedChange = { AppSettings.setMediaIsolation(!it) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "这里的功能尚未稳定，随时可能改动或移除。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

private fun moduleColorSummary(overrides: Map<ColorModule, Int?>): String {
    val custom = overrides.values.count { it != null }
    return if (custom == 0) "全部为自动颜色（模块默认色）" else "已自定义 $custom 项"
}

// ============================================================
// 颜色模式
// ============================================================

@Composable
fun ColorModePage() {
    val current by AppSettings.colorMode.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "选择 App 的外观。这一项只影响 XJ File 自己，不改变手机系统的深色模式。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        AppColorMode.values().forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { AppSettings.setColorMode(mode) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = current == mode,
                    onClick = { AppSettings.setColorMode(mode) }
                )
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        text = mode.title,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when (mode) {
                            AppColorMode.SYSTEM -> "跟随手机系统的深色模式自动切换"
                            AppColorMode.LIGHT -> "始终使用浅色外观"
                            AppColorMode.DARK -> "始终使用深色外观"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ============================================================
// 模块颜色列表
// ============================================================

@Composable
fun ModuleColorsPage(onOpenModule: (ColorModule) -> Unit) {
    val overrides by AppSettings.moduleColors.collectAsStateWithLifecycle()
    val moduleColors = LocalModuleColors.current
    // 「重置」用背景反转色，保证用户把模块色设成任何颜色时它都清晰可见
    val resetColor = MaterialTheme.colorScheme.onBackground

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "每个模块都可以单独设色。「自动」就是各模块原本的默认色：" +
                "文件夹是主题蓝，按钮跟随正文色。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        ColorModule.values().forEach { module ->
            val override = overrides[module]
            val defaultColor = if (module == ColorModule.FOLDER) moduleColors.folderDefault
            else moduleColors.buttonDefault
            val color = override?.let { Color(it) } ?: defaultColor
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenModule(module) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ColorDot(color, sizeDp = 22)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = module.title,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (override == null) "自动（${toHex(defaultColor.toArgb())}）"
                        else toHex(override),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = { AppSettings.resetModuleColors() },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = resetColor),
            border = BorderStroke(1.dp, resetColor.copy(alpha = 0.6f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text("重置")
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ============================================================
// 单个模块的颜色
// ============================================================

@Composable
fun ModuleColorPage(module: ColorModule) {
    val overrides by AppSettings.moduleColors.collectAsStateWithLifecycle()
    val moduleColors = LocalModuleColors.current
    val current = overrides[module]
    val defaultColor = if (module == ColorModule.FOLDER) moduleColors.folderDefault
    else moduleColors.buttonDefault
    val appliedColor = current?.let { Color(it) } ?: defaultColor
    val isDark = LocalAppDarkTheme.current
    val m3Enabled by AppSettings.m3Palette.collectAsStateWithLifecycle()
    val presetRows = if (m3Enabled) M3PresetColors else ClassicPresetColors
    var showPicker by remember(module) { mutableStateOf(false) }

    // 输入框内容：初始为当前自定义色（自动时留空）
    var hexText by remember(module) {
        mutableStateOf(current?.let { toHex(it) } ?: "")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        // 当前效果预览
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (module == ColorModule.FOLDER) Icons.Default.Folder
                    else Icons.Default.Settings,
                    contentDescription = null,
                    tint = appliedColor,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = module.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (current == null) "自动（${toHex(defaultColor.toArgb())}）"
                    else "自定义 ${toHex(current)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = if (m3Enabled) "预设颜色（M3 实验）" else "预设颜色",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        // 预设是 3 列 × 5 行，第 4 列空着正好立一个竖排「重置」。
        // 关键：每行固定按 4 个等宽槽位（72dp）用 SpaceBetween 排，
        // 重置按钮也做成同样 72dp、对齐第 4 列，这样「列与列」的间距完全一致，不会右边挤一条。
        Box(modifier = Modifier.fillMaxWidth()) {
            Column {
                presetRows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        row.forEach { preset ->
                            val argb = preset.color(isDark).toArgb()
                            val presetColor = Color(argb)
                            val selected = current != null && current == argb
                            Column(
                                modifier = Modifier
                                    .width(72.dp)
                                    .clickable {
                                        AppSettings.setModuleColor(module, argb)
                                        hexText = toHex(argb)
                                    }
                                    .padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(presetColor)
                                        .border(
                                            width = if (selected) 3.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant,
                                            shape = CircleShape
                                        )
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = preset.name,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        // 第 4 列留空，由覆盖层里的「重置」占位
                        repeat(4 - row.size) { Spacer(Modifier.width(72.dp)) }
                    }
                }
            }

            // matchParentSize 不参与测量，直接拿到左侧网格的最终高度，
            // 所以按钮可以安全地 fillMaxHeight，不会把页面撑高。
            Box(modifier = Modifier.matchParentSize()) {
                OutlinedButton(
                    onClick = {
                        AppSettings.setModuleColor(module, null)
                        hexText = ""
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .width(72.dp)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onBackground
                    )
                ) {
                    Text(
                        text = "重\n置",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "自定义颜色",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = hexText,
            onValueChange = { input ->
                hexText = input
                // 输入有效就立刻生效（输入过程中不打断）
                parseColorHex(input)?.let { AppSettings.setModuleColor(module, it) }
            },
            label = { Text("十六进制颜色") },
            placeholder = { Text("#FF5722") },
            singleLine = true,
            trailingIcon = {
                val preview = parseColorHex(hexText)?.let { Color(it) }
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(preview ?: Color.Transparent)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .clickable { showPicker = true }
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "支持 #RRGGBB 或 8 位 #AARRGGBB，输入有效颜色后立即生效；点右侧色块可打开调色板。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))
    }

    if (showPicker) {
        ColorPickerPopup(
            initial = appliedColor,
            onColorChange = { color ->
                val argb = color.toArgb()
                AppSettings.setModuleColor(module, argb)
                hexText = toHex(argb)
            },
            onDismiss = { showPicker = false }
        )
    }
}

// ============================================================
// 复用小部件
// ============================================================

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    // 注意：整行**不**可点击。开关必须点到开关本身才生效，
    // 否则用户只是想滚动/看说明时点到这一栏就会误改设置。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NavigationRow(
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
private fun ColorDot(color: Color, sizeDp: Int) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
    )
}

// ============================================================
// 预设颜色与十六进制解析
// ============================================================

private data class PresetColor(val name: String, val light: Color, val dark: Color) {
    fun color(darkTheme: Boolean): Color = if (darkTheme) dark else light
}

private fun classic(name: String, argb: Long) = PresetColor(name, Color(argb), Color(argb))

/**
 * 经典色：Material 2014 五个色系 × 200/500/700，
 * 色值已经按 25% 中性灰混合过（和设计确认过的预览图一致），避免太扎眼。
 */
private val ClassicPresetColors: List<List<PresetColor>> = listOf(
    listOf(
        classic("浅紫", 0xFFBA8EC2),
        classic("紫", 0xFF953DA4),
        classic("深紫", 0xFF7C379A)
    ),
    listOf(
        classic("浅蓝", 0xFF8CB8DB),
        classic("蓝", 0xFF3990D6),
        classic("深蓝", 0xFF3378BE)
    ),
    listOf(
        classic("浅绿", 0xFF9CC09D),
        classic("绿", 0xFF59A35C),
        classic("深绿", 0xFF4A8A4D)
    ),
    listOf(
        classic("浅粉", 0xFFD78BA5),
        classic("粉", 0xFFCF366A),
        classic("深粉", 0xFFB23264)
    ),
    listOf(
        classic("浅红", 0xFFD39494),
        classic("红", 0xFFD75248),
        classic("深红", 0xFFBE4343)
    )
)

/**
 * 实验性 M3 预设：只用 Material 3 兜底色板（Baseline）的语义色。
 * 紫 / 粉 / 红 三组尽量取与经典色相近的角色；
 * M3 没有蓝、绿，剩下的位置用次色、容器色、表面变体、描边这些 M3 角色补满。
 */
private val BaselineLight = lightColorScheme()
private val BaselineDark = darkColorScheme()

private val M3PresetColors: List<List<PresetColor>> = listOf(
    listOf(
        PresetColor("主色", BaselineLight.primary, BaselineDark.primary),
        PresetColor("主色容器", BaselineLight.primaryContainer, BaselineDark.primaryContainer),
        PresetColor("反色主色", BaselineLight.inversePrimary, BaselineDark.inversePrimary)
    ),
    listOf(
        PresetColor("次色", BaselineLight.secondary, BaselineDark.secondary),
        PresetColor("次色容器", BaselineLight.secondaryContainer, BaselineDark.secondaryContainer),
        PresetColor("次容器文字", BaselineLight.onSecondaryContainer, BaselineDark.onSecondaryContainer)
    ),
    listOf(
        PresetColor("第三色", BaselineLight.tertiary, BaselineDark.tertiary),
        PresetColor("第三色容器", BaselineLight.tertiaryContainer, BaselineDark.tertiaryContainer),
        PresetColor("第三容器文字", BaselineLight.onTertiaryContainer, BaselineDark.onTertiaryContainer)
    ),
    listOf(
        PresetColor("错误", BaselineLight.error, BaselineDark.error),
        PresetColor("错误容器", BaselineLight.errorContainer, BaselineDark.errorContainer),
        PresetColor("错误容器文字", BaselineLight.onErrorContainer, BaselineDark.onErrorContainer)
    ),
    listOf(
        PresetColor("表面变体", BaselineLight.surfaceVariant, BaselineDark.surfaceVariant),
        PresetColor("轮廓", BaselineLight.outline, BaselineDark.outline),
        PresetColor("轮廓变体", BaselineLight.outlineVariant, BaselineDark.outlineVariant)
    )
)

/** 解析 #RRGGBB / #AARRGGBB（# 可省）；不合法返回 null。 */
private fun parseColorHex(input: String): Int? {
    val s = input.trim().removePrefix("#")
    if (s.length != 6 && s.length != 8) return null
    if (s.any { it !in "0123456789abcdefABCDEF" }) return null
    val value = s.toLong(16)
    return if (s.length == 6) (0xFF000000L or value).toInt() else value.toInt()
}

private fun toHex(argb: Int): String = "#%06X".format(argb and 0x00FFFFFF)
