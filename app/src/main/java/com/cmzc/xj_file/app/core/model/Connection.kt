package com.cmzc.xj_file.app.core.model

enum class ConnectionType { SMB, SFTP }

/**
 * 统一的连接配置（SMB / SFTP 共用）。
 *
 * - SMB 用：domain / shareName；port 默认 445
 * - SFTP 用：privateKeyPath / passphrase；port 默认 22
 *   - 若 privateKeyPath 为空 → 用密码认证
 *   - 若 privateKeyPath 非空 → 用私钥认证（passphrase 可选）
 */
data class Connection(
    val id: String,
    val type: ConnectionType,
    val name: String,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
    // ---- SMB 专属 ----
    val domain: String = "",
    val shareName: String = "",
    // ---- SFTP 专属 ----
    val privateKeyPath: String = "",
    val passphrase: String = ""
)