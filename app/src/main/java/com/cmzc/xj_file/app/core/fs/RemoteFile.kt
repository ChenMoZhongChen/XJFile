package com.cmzc.xj_file.app.core.fs

/**
 * 一个文件/文件夹的统一描述。
 * 本地与网络（SMB/FTP）都用这个结构，UI 不感知协议差异。
 */
data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    /** epoch millis，未知填 0 */
    val lastModified: Long,
    /** Unix 权限字符串，如 "-rw-rw----(660)"。网络文件为 null。 */
    val permission: String? = null
)