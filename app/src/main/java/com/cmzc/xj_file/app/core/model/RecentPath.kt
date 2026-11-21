package com.cmzc.xj_file.app.core.model

/**
 * 最近访问过的路径。
 */
data class RecentPath(
    val path: String,
    val sourceId: String,       // "local" 或 "smb:xxx"
    val sourceLabel: String,    // 显示用（本地存储 / 连接名）
    val timestamp: Long
)