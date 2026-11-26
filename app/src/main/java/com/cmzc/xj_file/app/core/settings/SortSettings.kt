package com.cmzc.xj_file.app.core.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class SortField(val key: String, val label: String) {
    NAME("name", "名称"),
    SIZE("size", "大小"),
    MODIFIED("modified", "修改时间"),
    TYPE("type", "类型")
}

enum class SortDirection(val key: String, val label: String) {
    ASC("asc", "升序"),
    DESC("desc", "降序")
}

data class SortSpec(
    val field: SortField = SortField.NAME,
    val direction: SortDirection = SortDirection.ASC,
    val foldersFirst: Boolean = true,
) {
    fun encode(): String = "${field.key}|${direction.key}|$foldersFirst"

    companion object {
        fun decode(raw: String?): SortSpec {
            val parts = raw?.split('|') ?: return SortSpec()
            if (parts.size < 3) return SortSpec()
            val field = SortField.values().firstOrNull { it.key == parts[0] } ?: SortField.NAME
            val dir = SortDirection.values().firstOrNull { it.key == parts[1] } ?: SortDirection.ASC
            return SortSpec(field, dir, parts[2].toBooleanStrictOrNull() ?: true)
        }
    }
}

/**
 * 文件列表排序设置。
 *
 * 默认只有一份全局设置；用户在某个目录单独设置时，为该目录保存覆盖项。
 * 选择“所有目录”时清空所有覆盖项，回到一份全局设置。
 */
object SortSettings {

    private const val PREFS = "xj_sort_settings"
    private const val KEY_GLOBAL = "global"
    private const val KEY_DIRS = "dir_specs"

    private lateinit var prefs: SharedPreferences

    private val _global = MutableStateFlow(SortSpec())
    val global: StateFlow<SortSpec> = _global.asStateFlow()

    private val _perDirectory = MutableStateFlow<Map<String, SortSpec>>(emptyMap())

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _global.value = SortSpec.decode(prefs.getString(KEY_GLOBAL, null))
        _perDirectory.value = runCatching {
            val raw = prefs.getString(KEY_DIRS, "{}") ?: "{}"
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { key ->
                    put(key, SortSpec.decode(obj.optString(key)))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun key(sourceId: String, path: String): String = "$sourceId\n$path"

    fun specFor(sourceId: String, path: String): SortSpec =
        _perDirectory.value[key(sourceId, path)] ?: _global.value

    fun set(
        sourceId: String,
        path: String,
        spec: SortSpec,
        allDirectories: Boolean,
    ) {
        if (allDirectories) {
            _global.value = spec
            _perDirectory.value = emptyMap()
        } else {
            _perDirectory.value = _perDirectory.value.toMutableMap().apply {
                put(key(sourceId, path), spec)
            }
        }
        if (!::prefs.isInitialized) return
        val dirs = JSONObject()
        _perDirectory.value.forEach { (k, v) -> dirs.put(k, v.encode()) }
        prefs.edit()
            .putString(KEY_GLOBAL, _global.value.encode())
            .putString(KEY_DIRS, dirs.toString())
            .apply()
    }
}
