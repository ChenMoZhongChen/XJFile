package com.cmzc.xj_file.app.core.transfer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 传输任务落盘。只保存还没结束的任务；进程被杀后由 TransferManager 恢复。
 *
 * 不保存 fs 对象，只保存 `local` / `smb:host` / `sftp:host` 这种 tag，
 * 启动时由 ViewModel 按连接配置重新建出文件系统。
 */
object TransferStore {

    private const val PREFS = "xj_transfer_tasks"
    private const val KEY = "active_tasks"

    fun save(context: Context, tasks: List<TransferTask>) {
        val active = tasks.filter { it.isActive }
        val array = JSONArray()
        active.forEach { t ->
            array.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("batchId", t.batchId)
                    put("batchName", t.batchName)
                    put("name", t.name)
                    put("srcPath", t.srcPath)
                    put("dstPath", t.dstPath)
                    put("totalBytes", t.totalBytes)
                    put("transferred", t.transferred)
                    put("srcTag", t.srcTag)
                    put("dstTag", t.dstTag)
                    put("resumedFrom", t.resumedFrom)
                    put("isMove", t.isMove)
                    put("topSrcPath", t.topSrcPath)
                }
            )
        }
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, array.toString())
                .apply()
        }
    }

    fun load(context: Context): List<TransferTask> {
        val raw = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
        }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val result = ArrayList<TransferTask>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val srcTag = o.optString("srcTag", "")
            val dstTag = o.optString("dstTag", "")
            if (srcTag.isBlank() || dstTag.isBlank()) continue
            result += TransferTask(
                id = o.optString("id", ""),
                batchId = o.optString("batchId", ""),
                batchName = o.optString("batchName", ""),
                name = o.optString("name", ""),
                srcPath = o.optString("srcPath", ""),
                dstPath = o.optString("dstPath", ""),
                totalBytes = o.optLong("totalBytes", -1L),
                status = TransferStatus.PENDING,
                transferred = o.optLong("transferred", 0L),
                srcTag = srcTag,
                dstTag = dstTag,
                resumedFrom = o.optLong("resumedFrom", 0L),
                isMove = o.optBoolean("isMove", false),
                topSrcPath = o.optString("topSrcPath", ""),
            )
        }
        return result.filter { it.id.isNotBlank() && it.srcPath.isNotBlank() && it.dstPath.isNotBlank() }
    }

    fun clear(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        }
    }
}
