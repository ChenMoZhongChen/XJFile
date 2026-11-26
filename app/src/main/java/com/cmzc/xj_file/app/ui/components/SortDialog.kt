package com.cmzc.xj_file.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cmzc.xj_file.app.core.settings.SortDirection
import com.cmzc.xj_file.app.core.settings.SortField
import com.cmzc.xj_file.app.core.settings.SortSpec

/**
 * 排列方式弹窗。
 * 默认作用范围是「仅当前目录」；勾选「所有目录」会写全局设置并清掉目录覆盖项。
 */
@Composable
fun SortDialog(
    initial: SortSpec,
    currentPath: String,
    onDismiss: () -> Unit,
    onApply: (SortSpec, Boolean) -> Unit,
) {
    var field by remember { mutableStateOf(initial.field) }
    var direction by remember { mutableStateOf(initial.direction) }
    var foldersFirst by remember { mutableStateOf(initial.foldersFirst) }
    var allDirectories by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("排列方式") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = currentPath,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("排序字段")
                SortField.values().forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { field = option }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = field == option,
                            onClick = { field = option },
                        )
                        Text(option.label, modifier = Modifier.padding(start = 4.dp))
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text("顺序")
                SortDirection.values().forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { direction = option }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = direction == option,
                            onClick = { direction = option },
                        )
                        Text(option.label, modifier = Modifier.padding(start = 4.dp))
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { foldersFirst = !foldersFirst }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = foldersFirst,
                        onCheckedChange = { foldersFirst = it },
                    )
                    Text("文件夹优先", modifier = Modifier.padding(start = 4.dp))
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("作用范围")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = !allDirectories,
                            onClick = { allDirectories = false },
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = !allDirectories,
                        onClick = { allDirectories = false },
                    )
                    Text("仅当前目录", modifier = Modifier.padding(start = 4.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = allDirectories,
                            onClick = { allDirectories = true },
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = allDirectories,
                        onClick = { allDirectories = true },
                    )
                    Text("所有目录", modifier = Modifier.padding(start = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(
                        SortSpec(field, direction, foldersFirst),
                        allDirectories,
                    )
                }
            ) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
