package com.cmzc.xj_file.app.core.root

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

enum class RootState {
    /** 还没检测。 */
    UNKNOWN,
    /** 没有 su / root 环境，不做任何事。 */
    NOT_ROOTED,
    /** 有 su，但用户拒绝或授权超时。 */
    DENIED,
    /** 已拿到 uid=0。 */
    GRANTED,
}

/**
 * Root 环境检测 / 授权入口。
 *
 * 行为约定：
 * - 没有 root 环境：完全不弹任何东西，不改变界面；
 * - 有 su 环境：后台申请一次 root；授权结果只保存在内存里；
 * - 授权成功后，其他模块可以通过 [runAsRoot] 执行 root 命令。
 *
 * 当前版本没有把 root 权限接到文件浏览/删除上；这里先把能力入口准备好，
 * 后续需要 root 操作时直接复用，不要在每个功能里各写一套 su 调用。
 */
object RootManager {

    private const val TAG = "XjFileRoot"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(RootState.UNKNOWN)
    val state: StateFlow<RootState> = _state.asStateFlow()

    val isGranted: Boolean
        get() = _state.value == RootState.GRANTED

    @Volatile
    private var detectionStarted = false

    /** App 启动时调用一次；没有 root 环境时静默返回。 */
    fun detectAndRequestAsync() {
        if (detectionStarted) return
        detectionStarted = true
        scope.launch {
            if (!hasRootEnvironment()) {
                _state.value = RootState.NOT_ROOTED
                return@launch
            }
            val result = runCatching { requestRoot() }.getOrDefault(false)
            _state.value = if (result) RootState.GRANTED else RootState.DENIED
            if (result) Log.i(TAG, "root granted") else Log.i(TAG, "root not granted")
        }
    }

    private fun hasRootEnvironment(): Boolean {
        val paths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/adb/magisk",
            "/data/adb/ksu",
            "/data/adb/ap",
        )
        if (paths.any { File(it).exists() }) return true
        return runCatching {
            val process = ProcessBuilder("/system/bin/sh", "-c", "command -v su")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (finished) process.exitValue() == 0 else {
                process.destroy()
                false
            }
        }.getOrDefault(false)
    }

    private fun requestRoot(): Boolean {
        val process = ProcessBuilder("su", "-c", "id")
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(8, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            return false
        }
        val output = runCatching {
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")
        return process.exitValue() == 0 && output.contains("uid=0")
    }

    /**
     * 执行 root 命令；未授权返回 null。命令由调用方负责转义/校验，不能拼接用户输入。
     */
    fun runAsRoot(command: String, timeoutSeconds: Long = 15): RootResult? {
        if (!isGranted) return null
        return runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return@runCatching RootResult(-1, "", false)
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            RootResult(process.exitValue(), output, true)
        }.getOrNull()
    }

    data class RootResult(
        val exitCode: Int,
        val output: String,
        val completed: Boolean,
    )
}
