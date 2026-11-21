package com.cmzc.xj_file.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cmzc.xj_file.app.core.settings.AppColorMode
import com.cmzc.xj_file.app.core.settings.AppSettings
import com.cmzc.xj_file.app.core.settings.ColorModule

private val LightColors = lightColorScheme(
    background = LightBg,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onBackground = LightText,
    onSurface = LightText,
    onSurfaceVariant = LightTextSecondary,
    primary = LightPrimary,
    primaryContainer = LightPrimaryContainer,
    outlineVariant = LightDivider
)

private val DarkColors = darkColorScheme(
    background = DarkBg,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = DarkText,
    onSurface = DarkText,
    onSurfaceVariant = DarkTextSecondary,
    primary = DarkPrimary,
    primaryContainer = DarkPrimaryContainer,
    outlineVariant = DarkDivider
)

/** 当前是否深色。MainScreen 里那些手写的 panelBg/headerBg 也要跟着设置走，不能只看系统。 */
val LocalAppDarkTheme = staticCompositionLocalOf { false }

/** 界面动画总开关（设置里默认开）。 */
val LocalAnimationsEnabled = staticCompositionLocalOf { true }

/**
 * 各模块的最终颜色。
 *
 * folder/button = 解析后的颜色（有覆盖用覆盖，否则用各模块默认色）；
 * auto = 背景的反转色，只给「重置」这类必须永远清晰可见的元素用。
 * 三个值都解析成 Color 再往下传，用到的地方不需要再关心「有没有覆盖」。
 */
@Immutable
data class ModuleColors(
    val folder: Color,
    val button: Color,
    val auto: Color,
    val folderDefault: Color,
    val buttonDefault: Color
)

val LocalModuleColors = staticCompositionLocalOf {
    ModuleColors(
        folder = LightPrimary,
        button = LightText,
        auto = LightText,
        folderDefault = LightPrimary,
        buttonDefault = LightText
    )
}

@Composable
fun XjFileTheme(content: @Composable () -> Unit) {
    val mode by AppSettings.colorMode.collectAsStateWithLifecycle()
    val dark = when (mode) {
        AppColorMode.SYSTEM -> isSystemInDarkTheme()
        AppColorMode.LIGHT -> false
        AppColorMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors

    val moduleOverrides by AppSettings.moduleColors.collectAsStateWithLifecycle()
    val animationsEnabled by AppSettings.animations.collectAsStateWithLifecycle()

    // 各模块的「自动」就是它原本的默认色：文件夹一直是主题蓝（和上一版一样），
    // 按钮默认跟随正文色。auto 单独保留为背景反转色，只给「重置」这类必须永远清晰可见的元素用。
    val folderDefault = if (dark) DarkPrimary else LightPrimary
    val buttonDefault = if (dark) DarkText else LightText
    val autoColor = if (dark) DarkText else LightText
    val moduleColors = ModuleColors(
        folder = moduleOverrides[ColorModule.FOLDER]?.let { Color(it) } ?: folderDefault,
        button = moduleOverrides[ColorModule.BUTTON]?.let { Color(it) } ?: buttonDefault,
        auto = autoColor,
        folderDefault = folderDefault,
        buttonDefault = buttonDefault
    )

    CompositionLocalProvider(
        LocalAppDarkTheme provides dark,
        LocalAnimationsEnabled provides animationsEnabled,
        LocalModuleColors provides moduleColors
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = AppTypography,
            content = content
        )
    }
}
