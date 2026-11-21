package com.cmzc.xj_file.app.core.fs.smb

import android.util.Log
import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用 jcifs-ng 枚举 SMB 服务器上的共享列表。
 *
 * 过滤掉 Windows 隐藏共享（以 $ 结尾），如 IPC$、ADMIN$、C$、print$ 等，
 * 这些对普通用户/Guest 来说打不开，属于幽灵共享。
 */
object SmbShareEnumerator {

    private const val TAG = "XjFileSMB"

    suspend fun listShares(
        host: String,
        username: String,
        password: String,
        domain: String
    ): List<String> = withContext(Dispatchers.IO) {
        try {
            val base: CIFSContext = SingletonContext.getInstance()

            val ctx: CIFSContext = if (username.isBlank()) {
                val auth = NtlmPasswordAuthenticator(null, "Guest", "")
                base.withCredentials(auth)
            } else {
                val auth = NtlmPasswordAuthenticator(
                    domain.ifBlank { null },
                    username,
                    password
                )
                base.withCredentials(auth)
            }

            val smbFile = SmbFile("smb://$host/", ctx)
            val names: Array<String> = smbFile.list()
            Log.d(TAG, "listShares 原始返回 ${names.size} 个: ${names.toList()}")

            val filtered = names.mapNotNull { raw ->
                val n = raw.trimEnd('/').substringAfterLast('/')
                if (n.isBlank()) null else n
            }
                // 过滤隐藏共享：以 $ 结尾的全部丢掉
                .filter { name -> !name.endsWith("$") }
                .distinct()
                .sortedBy { it.lowercase() }

            Log.d(TAG, "listShares 过滤后 ${filtered.size} 个: $filtered")
            filtered
        } catch (t: Throwable) {
            Log.e(TAG, "listShares 失败", t)
            emptyList()
        }
    }
}