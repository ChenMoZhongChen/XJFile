package com.cmzc.xj_file.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cmzc.xj_file.app.core.model.Connection
import com.cmzc.xj_file.app.core.model.ShortcutDir

/**
 * 添加网络位置的快捷目录。
 *
 * 用户选一个已有连接（用于获取 host + 认证），再填这个连接下的子路径。
 * 如果当前焦点窗口已在某个网络位置里，进入对话框时会自动预填连接和子路径。
 */
@Composable
fun AddNetworkShortcutDialog(
    connections: List<Connection>,
    initialConnId: String? = null,
    initialSubPath: String = "",
    initialName: String = "",
    onDismiss: () -> Unit,
    onAddConnection: () -> Unit,
    onConfirm: (ShortcutDir) -> Unit
) {
    var selectedConnId by remember {
        mutableStateOf(
            initialConnId ?: connections.firstOrNull()?.id ?: ""
        )
    }
    var subPath by remember { mutableStateOf(initialSubPath) }
    var name by remember { mutableStateOf(initialName) }
    var expanded by remember { mutableStateOf(false) }

    val selectedConn = connections.firstOrNull { it.id == selectedConnId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加网络位置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (connections.isEmpty()) {
                    Text("还没有连接，请先添加一个。")
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onAddConnection) {
                        Text("+ 新建连接")
                    }
                } else {
                    Text("选择连接", fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))

                    Box {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = selectedConn?.let { it.name.ifBlank { it.host } } ?: "",
                                onValueChange = {},
                                readOnly = true,
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { expanded = true }) { Text("切换") }
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            connections.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(c.name.ifBlank { c.host }) },
                                    onClick = {
                                        selectedConnId = c.id
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "主机前缀：//${selectedConn?.host ?: "?"}/",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(4.dp))

                    OutlinedTextField(
                        value = subPath,
                        onValueChange = { subPath = it },
                        label = { Text("子路径（相对主机根）") },
                        placeholder = { Text("例如：共享名/子目录/；留空表示主机根") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("显示名称（可留空）") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "完整路径：//${selectedConn?.host ?: "?"}/${subPath.trim('/')}/",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onAddConnection) {
                        Text("+ 新建连接")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val conn = selectedConn ?: return@TextButton
                    val host = conn.host
                    val sub = subPath.trim().trim('/').replace('\\', '/')
                    val fullPath = if (sub.isEmpty()) "//$host/" else "//$host/$sub/"
                    val displayName = name.trim().ifBlank {
                        if (sub.isEmpty()) host else sub.substringAfterLast('/')
                    }
                    onConfirm(ShortcutDir(displayName, fullPath, conn.id))
                },
                enabled = selectedConn != null
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}