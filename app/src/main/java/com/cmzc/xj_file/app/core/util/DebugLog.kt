package com.cmzc.xj_file.app.core.util

import android.content.Context
import java.io.File

/**
 * 诊断日志（debug 用）。
 *
 * 为什么不用 Logcat：vivo 在真机上会屏蔽应用自己的 Log 输出（换包后连 Log.e 都收不到），
 * 一旦播放出问题就完全瞎了。这里写进应用私有目录，用
 * `adb shell run-as com.cmzc.xj_file.app cat files/xj-debug.log` 就能看到。
 *
 * 只有播放相关代码在写，量很小；超过 2MB 自动清空，不会无限长。
 */
object DebugLog {

    private const val FILE = "xj-debug.log"
    private const val MAX_BYTES = 2L * 1024 * 1024

    fun ev(context: Context, msg: String) {
        runCatching {
            val f = File(context.filesDir, FILE)
            if (f.length() > MAX_BYTES) f.delete()
            val line = "${System.currentTimeMillis()} $msg\n"
            f.appendText(line)
        }
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }
}
