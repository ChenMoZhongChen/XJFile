package com.cmzc.xj_file.app.core.transfer

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 断点记录（断点续传的「tag」）。
 *
 * 一个 tag = 一条「某文件正在往某目标写」的记录，字段是：
 *   srcTag + srcPath          源头（哪个文件系统上的哪个文件）
 *   dstTag + dstPath          目标
 *   srcSize + srcMtime        源文件的身份（源变了就不能续，必须从头来）
 *   offset                    已经写到目标的字节数（= 半截文件长度）
 *   updatedAt                 最后更新时间，用于过期清扫
 *
 * 为什么记大小 + 修改时间当身份：只要源文件在这期间被别的程序改过，
 * 续写就会拼出「前半截是旧内容、后半截是新内容」的坏文件。宁可从头重传。
 *
 * 存储：SharedPreferences 里一个 JSON 数组。数据量很小（每条几百字节），
 * 只在「暂停」「完成」「失败」时写一次，不会成为热路径。
 */
object ResumeStore {

    private const val TAG = "XjFileResume"
    private const val PREFS = "xj_resume"
    private const val KEY = "records"
    /** 超过这个时间的记录当作陈旧，不再用于续传（半截文件多半也被人删了）。 */
    private const val STALE_MS = 7L * 24 * 60 * 60 * 1000

    data class Record(
        val srcTag: String,
        val srcPath: String,
        val dstTag: String,
        val dstPath: String,
        val srcSize: Long,
        val srcMtime: Long,
        val offset: Long,
        val updatedAt: Long
    ) {
        /** 同源同目标 = 同一个 tag。 */
        fun key(): String = "$srcTag|$srcPath|$dstTag|$dstPath"
    }

    private var prefs: android.content.SharedPreferences? = null
    private val cache = LinkedHashMap<String, Record>()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val now = System.currentTimeMillis()
        runCatching {
            val arr = JSONArray(p.getString(KEY, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val r = Record(
                    srcTag = o.optString("srcTag"),
                    srcPath = o.optString("srcPath"),
                    dstTag = o.optString("dstTag"),
                    dstPath = o.optString("dstPath"),
                    srcSize = o.optLong("srcSize", -1L),
                    srcMtime = o.optLong("srcMtime", 0L),
                    offset = o.optLong("offset", 0L),
                    updatedAt = o.optLong("updatedAt", 0L)
                )
                if (now - r.updatedAt < STALE_MS && r.offset > 0) cache[r.key()] = r
            }
        }.onFailure { Log.w(TAG, "读取断点记录失败", it) }
        // 顺手把陈旧记录落盘清掉
        persist()
    }

    /** 找出可以接续的点。srcSize/srcMtime 必须完全对上，否则返回 null（要重传）。 */
    @Synchronized
    fun find(srcTag: String, srcPath: String, dstTag: String, dstPath: String,
             srcSize: Long, srcMtime: Long): Record? {
        // 用 key 的构造方式反查：先拼一个只含四要素的 key
        val probe = Record(srcTag, srcPath, dstTag, dstPath, 0, 0, 0, 0).key()
        val r = cache[probe] ?: return null
        if (r.srcSize != srcSize || (srcMtime > 0 && r.srcMtime != srcMtime)) return null
        if (r.offset <= 0) return null
        return r
    }

    @Synchronized
    fun put(record: Record) {
        if (record.offset <= 0) {
            remove(record.srcTag, record.srcPath, record.dstTag, record.dstPath)
            return
        }
        cache[record.key()] = record
        persist()
    }

    @Synchronized
    fun remove(srcTag: String, srcPath: String, dstTag: String, dstPath: String) {
        val k = Record(srcTag, srcPath, dstTag, dstPath, 0, 0, 0, 0).key()
        if (cache.remove(k) != null) persist()
    }

    /**
     * 按目标路径清断点。取消任务时并不知道源 fs 的 tag（任务已被移除），
     * 但目标路径唯一，够用：同一个目标不可能同时有两个进行中的传输。
     */
    @Synchronized
    fun removeByDstPath(dstPath: String) {
        val hit = cache.values.filter { it.dstPath.trimEnd('/') == dstPath.trimEnd('/') }
        if (hit.isEmpty()) return
        hit.forEach { cache.remove(it.key()) }
        persist()
    }

    /** 供「清空任务记录」一类的操作调用。 */
    @Synchronized
    fun clearAll() {
        cache.clear()
        persist()
    }

    @Synchronized
    fun snapshot(): List<Record> = cache.values.toList()

    private fun persist() {
        val p = prefs ?: return
        runCatching {
            val arr = JSONArray()
            for (r in cache.values) {
                arr.put(
                    JSONObject()
                        .put("srcTag", r.srcTag)
                        .put("srcPath", r.srcPath)
                        .put("dstTag", r.dstTag)
                        .put("dstPath", r.dstPath)
                        .put("srcSize", r.srcSize)
                        .put("srcMtime", r.srcMtime)
                        .put("offset", r.offset)
                        .put("updatedAt", r.updatedAt)
                )
            }
            p.edit().putString(KEY, arr.toString()).apply()
        }.onFailure { Log.w(TAG, "写断点记录失败", it) }
    }
}
