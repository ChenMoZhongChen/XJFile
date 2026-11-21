package com.cmzc.xj_file.app.core.model

/**
 * 用户自定义的快捷目录。
 *
 * @param name 显示名（用户自定义，或默认取路径最后一级）
 * @param path 完整路径。本地目录用 /storage/... 或 /sdcard/...
 *             网络位置用 //host/ 或 //host/share/sub/
 * @param connectionId 非空表示这是一条网络位置，用 ConnectionRepository 里
 *                     对应 id 的 Connection 建立连接。为空表示本地目录。
 */
data class ShortcutDir(
    val name: String,
    val path: String,
    val connectionId: String? = null
) {
    val isNetwork: Boolean get() = !connectionId.isNullOrBlank()
}