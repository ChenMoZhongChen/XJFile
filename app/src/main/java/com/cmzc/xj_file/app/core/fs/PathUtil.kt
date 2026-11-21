package com.cmzc.xj_file.app.core.fs

import java.io.File

object PathUtil {

    const val LOCAL_ROOT = "/storage/emulated/0/"

    fun normalizeLocal(path: String): String {
        var p = path.replace('\\', '/')
        if (!p.startsWith("/")) p = "/$p"
        p = p.replace(Regex("/+"), "/")
        if (!p.endsWith("/")) p += "/"
        return p
    }

    fun normalizeRemote(path: String): String {
        var p = path.replace('\\', '/')
        if (!p.startsWith("//")) p = "//" + p.trimStart('/')
        val prefix = "//"
        var rest = p.removePrefix(prefix)
        rest = rest.replace(Regex("/+"), "/")
        if (rest.isEmpty()) return prefix
        if (!rest.endsWith("/")) rest += "/"
        return prefix + rest
    }

    fun isRemote(path: String): Boolean = path.startsWith("//")
    /**
     * 是否是「远程主机根」——即共享列表视图。
     * 形如 //host/（body 里只有主机名，没有共享名）。
     */
    fun isRemoteHostRoot(path: String): Boolean {
        if (!path.startsWith("//")) return false
        val body = path.removePrefix("//").trimEnd('/')
        if (body.isEmpty()) return false
        return !body.contains('/')
    }

    fun localParent(path: String): String? {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty() || trimmed == "/") return null
        val parent = File(trimmed).parentFile ?: return null
        return normalizeLocal(parent.absolutePath)
    }

    fun remoteParent(path: String): String? {
        val normalized = normalizeRemote(path)
        val body = normalized.removePrefix("//").trimEnd('/')
        if (body.isEmpty()) return null
        val parts = body.split('/').filter { it.isNotEmpty() }
        if (parts.size <= 1) return null
        val parentParts = parts.dropLast(1)
        return "//" + parentParts.joinToString("/") + "/"
    }

    fun joinLocal(parent: String, child: String): String {
        var p = parent.replace('\\', '/')
        if (!p.startsWith("/")) p = "/$p"
        val combined = p.trimEnd('/') + "/" + child.trim('/')
        // 合并连续 "/"（本地路径不能以 "//" 开头）
        return combined.replace(Regex("/+"), "/")
    }

    fun joinRemote(parent: String, child: String): String {
        var p = parent.replace('\\', '/')
        if (!p.startsWith("//")) p = "//" + p.trimStart('/')
        val prefix = "//"
        val rest = p.removePrefix(prefix).trimEnd('/') + "/" + child.trim('/')
        // 合并连续 "/"（但不能破坏开头的 "//"）
        return prefix + rest.replace(Regex("/+"), "/")
    }

    /** 本地路径的规范形式：解析符号链接（/sdcard 与 /storage/emulated/0 会归一到同一处）。 */
    fun canonicalLocal(path: String): String {
        val resolved = runCatching { File(path).canonicalPath }.getOrNull()
        return normalizeLocal(resolved ?: path)
    }

    /**
     * 两个位置是不是同一个目录。
     * 只有同一个数据源（同一个本地 / 同一条连接配置）才可比：
     * 本地解析符号链接后再比，远程按规范化字符串比（已含主机名）。
     */
    fun sameLocation(
        sourceIdA: String,
        pathA: String,
        sourceIdB: String,
        pathB: String
    ): Boolean {
        if (sourceIdA != sourceIdB) return false
        return if (sourceIdA == "local") {
            canonicalLocal(pathA) == canonicalLocal(pathB)
        } else {
            normalizeRemote(pathA) == normalizeRemote(pathB)
        }
    }
}