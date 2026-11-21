package com.cmzc.xj_file.app.core.repo

import android.content.Context
import com.cmzc.xj_file.app.core.model.ShortcutDir
import org.json.JSONArray
import org.json.JSONObject

class ShortcutDirRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("xj_shortcut_dirs", Context.MODE_PRIVATE)

    fun load(): List<ShortcutDir> {
        val json = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name", "")
                val path = o.optString("path", "")
                val connectionIdRaw = o.optString("connectionId", "")
                val connectionId = connectionIdRaw.ifBlank { null }
                if (path.isBlank()) null
                else ShortcutDir(name.ifBlank { defaultName(path) }, path, connectionId)
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun save(list: List<ShortcutDir>) {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().apply {
                put("name", d.name)
                put("path", d.path)
                if (d.connectionId != null) put("connectionId", d.connectionId)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    /** 兼容老数据：老版本存的是纯路径字符串列表 */
    private fun defaultName(path: String): String {
        if (path == "/") return "根目录"
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return path
        val last = trimmed.substringAfterLast('/')
        return if (last.isEmpty()) path else last
    }

    private companion object { const val KEY = "shortcut_dirs" }
}