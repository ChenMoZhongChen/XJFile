package com.cmzc.xj_file.app.core.transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 通知栏上的「暂停/恢复」「取消全部」。
 *
 * 为什么用广播而不是把 action 发给 TransferService：
 * - 广播不需要「再启动一次服务」，不会踩 Android 12+ 的后台启动限制；
 * - 服务万一已经停了，广播照样能直接把 TransferManager 的状态改掉。
 *
 * 收到的动作全部落到 TransferManager 上，服务那一侧的收集器会自己把通知刷新过来。
 */
class TransferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            // 通知栏这一个按钮是「全部」粒度：全暂停 ⇄ 全恢复
            ACTION_TOGGLE_PAUSE -> TransferManager.toggleAllPause()
            ACTION_CANCEL_ALL -> TransferManager.cancelAll()
        }
    }

    companion object {
        const val ACTION_TOGGLE_PAUSE = "com.cmzc.xj_file.app.action.TOGGLE_PAUSE"
        const val ACTION_CANCEL_ALL = "com.cmzc.xj_file.app.action.CANCEL_ALL"
    }
}
