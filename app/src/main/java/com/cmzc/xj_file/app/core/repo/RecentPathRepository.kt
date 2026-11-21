package com.cmzc.xj_file.app.core.repo

import android.content.Context
import com.cmzc.xj_file.app.core.model.RecentPath
import org.json.JSONArray
import org.json.JSONObject

/**
 * 最近访问路径的持久化（最多 10 个）。
 */
class RecentPathRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("xj_recent", Context.MODE_PRIVATE)

    fun load(): List<RecentPath> {
        val json = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentPath(
                    path = o.getString("path"),
                    sourceId = o.optString("sourceId", "local"),
                    sourceLabel = o.optString("sourceLabel", "本地存储"),
                    timestamp = o.optLong("timestamp", 0L)
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun save(list: List<RecentPath>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("path", r.path)
                put("sourceId", r.sourceId)
                put("sourceLabel", r.sourceLabel)
                put("timestamp", r.timestamp)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object { const val KEY = "recent_list" }
}