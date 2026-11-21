package com.cmzc.xj_file.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cmzc.xj_file.app.core.model.Connection
import com.cmzc.xj_file.app.core.model.ConnectionType
import java.util.UUID

@Composable
fun AddConnectionDialog(
    existing: Connection? = null,
    onDismiss: () -> Unit,
    onConfirm: (Connection) -> Unit
) {
    var type by remember { mutableStateOf(existing?.type ?: ConnectionType.SMB) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember {
        mutableStateOf(
            (existing?.port ?: if (type == ConnectionType.SFTP) 22 else 445).toString()
        )
    }
    var user by remember { mutableStateOf(existing?.username ?: "") }
    var pass by remember { mutableStateOf(existing?.password ?: "") }
    var domain by remember { mutableStateOf(existing?.domain ?: "") }
    var shareName by remember { mutableStateOf(existing?.shareName ?: "") }
    var privateKeyPath by remember { mutableStateOf(existing?.privateKeyPath ?: "") }
    var passphrase by remember { mutableStateOf(existing?.passphrase ?: "") }

    val onTypeChange: (ConnectionType) -> Unit = { newType ->
        type = newType
        if (port.isBlank() || port == "445" || port == "22") {
            port = if (newType == ConnectionType.SMB) "445" else "22"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "添加连接" else "编辑连接") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Row {
                    FilterChip(
                        selected = type == ConnectionType.SMB,
                        onClick = { onTypeChange(ConnectionType.SMB) },
                        label = { Text("SMB") }
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = type == ConnectionType.SFTP,
                        onClick = { onTypeChange(ConnectionType.SFTP) },
                        label = { Text("SFTP") }
                    )
                }
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称（可选）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("主机 / IP") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("端口") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it },
                    label = { Text("用户名") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )

                if (type == ConnectionType.SMB) {
                    OutlinedTextField(
                        value = shareName, onValueChange = { shareName = it },
                        label = { Text("共享名（可选，留空则自动列共享）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                    OutlinedTextField(
                        value = pass, onValueChange = { pass = it },
                        label = { Text("密码（留空表示匿名）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                    OutlinedTextField(
                        value = domain, onValueChange = { domain = it },
                        label = { Text("域（可选）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                }

                if (type == ConnectionType.SFTP) {
                    OutlinedTextField(
                        value = privateKeyPath, onValueChange = { privateKeyPath = it },
                        label = { Text("私钥文件路径（留空则用密码）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                    if (privateKeyPath.isNotBlank()) {
                        OutlinedTextField(
                            value = passphrase, onValueChange = { passphrase = it },
                            label = { Text("私钥口令（可选）") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        )
                    } else {
                        OutlinedTextField(
                            value = pass, onValueChange = { pass = it },
                            label = { Text("密码") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (host.isBlank()) return@TextButton
                    val p = port.toIntOrNull() ?: if (type == ConnectionType.SMB) 445 else 22
                    onConfirm(
                        Connection(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            type = type,
                            name = name.ifBlank { host },
                            host = host.trim(),
                            port = p,
                            username = user,
                            password = pass,
                            domain = domain,
                            shareName = shareName.trim(),
                            privateKeyPath = privateKeyPath.trim(),
                            passphrase = passphrase
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}