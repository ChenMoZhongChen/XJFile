package com.cmzc.xj_file.app.core.repo

import android.content.Context
import com.cmzc.xj_file.app.core.model.Connection
import com.cmzc.xj_file.app.core.model.ConnectionType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 连接配置的持久化（SMB + SFTP 统一存储）。
 *
 * 兼容旧数据：
 *   - 旧版本 key = "smb_list"，字段无 type
 *   - 本版本 key = "smb_list"（沿用），字段 type 缺省视为 SMB
 *   - 首次读入老数据时，自动填充 type=SMB / port=445
 */
class ConnectionRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("xj_connections", Context.MODE_PRIVATE)

    fun loadList(): List<Connection> {
        val json = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val typeStr = o.optString("type", "SMB")
                val type = runCatching { ConnectionType.valueOf(typeStr) }
                    .getOrDefault(ConnectionType.SMB)
                Connection(
                    id = o.getString("id"),
                    type = type,
                    name = o.optString("name", ""),
                    host = o.getString("host"),
                    port = o.optInt("port", if (type == ConnectionType.SMB) 445 else 22),
                    username = o.optString("username", ""),
                    password = o.optString("password", ""),
                    domain = o.optString("domain", ""),
                    shareName = o.optString("shareName", ""),
                    privateKeyPath = o.optString("privateKeyPath", ""),
                    passphrase = o.optString("passphrase", "")
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun save(conn: Connection) {
        val list = loadList().toMutableList()
        val idx = list.indexOfFirst { it.id == conn.id }
        if (idx >= 0) list[idx] = conn else list.add(conn)
        persist(list)
    }

    fun delete(id: String) {
        persist(loadList().filterNot { it.id == id })
    }

    private fun persist(list: List<Connection>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("type", c.type.name)
                put("name", c.name)
                put("host", c.host)
                put("port", c.port)
                put("username", c.username)
                put("password", c.password)
                put("domain", c.domain)
                put("shareName", c.shareName)
                put("privateKeyPath", c.privateKeyPath)
                put("passphrase", c.passphrase)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object { const val KEY = "smb_list" }
}