package com.cmzc.xj_file.app.core.permission

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 全盘文件访问权限（MANAGE_EXTERNAL_STORAGE）工具。
 */
object StoragePermission {

    /** 是否已获得全盘访问权限。 */
    fun hasAllFilesAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // Android 10 靠 requestLegacyExternalStorage + WRITE_EXTERNAL_STORAGE
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /** 跳转系统「所有文件访问权限」页面。 */
    fun openAllFilesAccessSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            try {
                context.startActivity(appIntent)
            } catch (t: Throwable) {
                // 某些 ROM 不支持带包名的 Action，退回到列表页
                val listIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                try { context.startActivity(listIntent) } catch (_: Throwable) { }
            }
        }
    }
}