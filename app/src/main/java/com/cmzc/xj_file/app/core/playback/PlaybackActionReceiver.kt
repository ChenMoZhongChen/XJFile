package com.cmzc.xj_file.app.core.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 播放通知上的「播放/暂停」「停止」。
 *
 * 和传输通知同一套思路：用广播而不是把 action 发给前台服务 —— 广播不需要再启动
 * 一次服务，不会踩 Android 12+ 的后台启动限制；动作直接落到 [PlaybackEngine]。
 */
class PlaybackActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            ACTION_TOGGLE -> PlaybackEngine.toggle()
            ACTION_STOP -> PlaybackEngine.stop()
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.cmzc.xj_file.app.action.PLAYBACK_TOGGLE"
        const val ACTION_STOP = "com.cmzc.xj_file.app.action.PLAYBACK_STOP"
    }
}
