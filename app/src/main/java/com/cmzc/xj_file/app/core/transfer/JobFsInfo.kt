package com.cmzc.xj_file.app.core.transfer

import com.cmzc.xj_file.app.core.fs.RemoteFileSystem

/**
 * 续传 / 校验用到的小工具，集中在一处，免得散落。
 */
internal object JobFsInfo {

    /** 路径的父目录（保留结尾斜杠，符合 list() 的约定）。 */
    fun parentOf(path: String): String {
        val trimmed = path.trimEnd('/')
        val idx = trimmed.lastIndexOf('/')
        return if (idx <= 0) "/" else trimmed.substring(0, idx + 1)
    }

    /**
     * 源文件的「身份」：大小 + 修改时间。
     * 修改时间拿不到就填 0，此时 [ResumeStore.find] 只比大小（宁可靠大小也别永远不续）。
     */
    suspend fun identify(fs: RemoteFileSystem, path: String): Pair<Long, Long> {
        val size = runCatching { fs.size(path) }.getOrNull() ?: -1L
        val mtime = runCatching {
            val key = path.trimEnd('/')
            fs.list(parentOf(path)).firstOrNull { it.path.trimEnd('/') == key }?.lastModified ?: 0L
        }.getOrDefault(0L)
        return size to mtime
    }

    /** 日志里用的短哈希。 */
    fun short(sum: String?): String = sum?.take(12) ?: "null"
}
