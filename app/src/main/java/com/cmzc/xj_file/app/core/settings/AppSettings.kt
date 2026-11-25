package com.cmzc.xj_file.app.core.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 外观颜色模式。 */
enum class AppColorMode(val key: String, val title: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色")
}

/**
 * 可以单独设置颜色的模块。
 * key 用于 SharedPreferences，title 显示在设置页。
 */
enum class ColorModule(val key: String, val title: String) {
    FOLDER("folder", "文件夹"),
    BUTTON("button", "按钮")
}

/**
 * App 级设置（动画、下拉刷新、外观）。
 *
 * 为什么是 object + StateFlow 而不是塞进某个 ViewModel：
 * 主界面和设置页是两个 Activity，只有单例才能让两边立刻看到同一份值；
 * 每次写入同时落 SharedPreferences，进程被杀也不丢。
 *
 * 「模块颜色」存的是 ARGB；**null 表示自动**（自动 = 该模块原本的默认色，见 Theme.kt）。
 */
object AppSettings {

    private const val PREFS = "xj_app_settings"
    private const val KEY_ANIMATIONS = "animations"
    private const val KEY_PULL_REFRESH = "pull_refresh"
    private const val KEY_COLOR_MODE = "color_mode"
    private const val KEY_COLOR_PREFIX = "color_"
    private const val KEY_M3_PALETTE = "m3_palette"
    private const val KEY_MEDIA_ISOLATION = "media_isolation"
    private const val KEY_FILE_ICON_COLORS = "file_icon_colors"

    private lateinit var prefs: SharedPreferences

    private val _animations = MutableStateFlow(true)
    val animations: StateFlow<Boolean> = _animations.asStateFlow()

    private val _pullToRefresh = MutableStateFlow(false)
    val pullToRefresh: StateFlow<Boolean> = _pullToRefresh.asStateFlow()

    private val _colorMode = MutableStateFlow(AppColorMode.SYSTEM)
    val colorMode: StateFlow<AppColorMode> = _colorMode.asStateFlow()

    private val _moduleColors = MutableStateFlow<Map<ColorModule, Int?>>(emptyMap())
    val moduleColors: StateFlow<Map<ColorModule, Int?>> = _moduleColors.asStateFlow()

    /** 实验性：调色板的预设色换成 Material 3 语义色。 */
    private val _m3Palette = MutableStateFlow(false)
    val m3Palette: StateFlow<Boolean> = _m3Palette.asStateFlow()

    /**
     * 图片与视频是否严格分离（默认开）。
     *
     * 开：图片只在图片之间左右翻、视频只在视频之间切，两种类型互不串门。
     * 关：查看页底部加一条「相册式」缩略图条，能把同目录所有媒体混在一起挑，
     * 点到一个视频就跳到播放器、点到一个图片就跳到看图页。
     */
    private val _mediaIsolation = MutableStateFlow(true)
    val mediaIsolation: StateFlow<Boolean> = _mediaIsolation.asStateFlow()

    /** 文件列表/压缩包列表的类型图标是否使用颜色；关闭后统一灰白色。 */
    private val _fileIconColors = MutableStateFlow(true)
    val fileIconColors: StateFlow<Boolean> = _fileIconColors.asStateFlow()

    /** XjFileApp.onCreate 里调用一次。 */
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _animations.value = prefs.getBoolean(KEY_ANIMATIONS, true)
        _pullToRefresh.value = prefs.getBoolean(KEY_PULL_REFRESH, false)
        _colorMode.value = AppColorMode.values().firstOrNull {
            it.key == prefs.getString(KEY_COLOR_MODE, AppColorMode.SYSTEM.key)
        } ?: AppColorMode.SYSTEM
        _moduleColors.value = ColorModule.values().associateWith { module ->
            // 用 0 当「没有设置」的哨兵：ARGB 里 alpha=0 的透明色当覆盖色没有意义
            prefs.getInt(KEY_COLOR_PREFIX + module.key, 0).takeIf { it != 0 }
        }
        _m3Palette.value = prefs.getBoolean(KEY_M3_PALETTE, false)
        _mediaIsolation.value = prefs.getBoolean(KEY_MEDIA_ISOLATION, true)
        _fileIconColors.value = prefs.getBoolean(KEY_FILE_ICON_COLORS, true)
    }

    fun setAnimations(enabled: Boolean) {
        _animations.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_ANIMATIONS, enabled).apply()
        }
    }

    fun setPullToRefresh(enabled: Boolean) {
        _pullToRefresh.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_PULL_REFRESH, enabled).apply()
        }
    }

    fun setM3Palette(enabled: Boolean) {
        _m3Palette.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_M3_PALETTE, enabled).apply()
        }
    }

    fun setMediaIsolation(enabled: Boolean) {
        _mediaIsolation.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_MEDIA_ISOLATION, enabled).apply()
        }
    }

    fun setFileIconColors(enabled: Boolean) {
        _fileIconColors.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_FILE_ICON_COLORS, enabled).apply()
        }
    }

    fun setColorMode(mode: AppColorMode) {
        _colorMode.value = mode
        if (::prefs.isInitialized) {
            prefs.edit().putString(KEY_COLOR_MODE, mode.key).apply()
        }
    }

    /** argb = null 表示恢复「自动颜色」（背景反转色）。 */
    fun setModuleColor(module: ColorModule, argb: Int?) {
        _moduleColors.value = _moduleColors.value.toMutableMap().apply { put(module, argb) }
        if (::prefs.isInitialized) {
            prefs.edit().apply {
                if (argb == null) remove(KEY_COLOR_PREFIX + module.key)
                else putInt(KEY_COLOR_PREFIX + module.key, argb)
            }.apply()
        }
    }

    fun resetModuleColors() {
        ColorModule.values().forEach { setModuleColor(it, null) }
    }
}
