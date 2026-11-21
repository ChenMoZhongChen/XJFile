package com.cmzc.xj_file.app.ui.settings

import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cmzc.xj_file.app.core.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 单个开源项目的许可证详情。
 *
 * 许可证全文来自 assets/licenses/，不依赖网络；BSD/MIT 模板里的占位版权
 * 会替换成该条目自己的 copyright。页面上同时给出项目主页、版权归属和
 * LGPL 条目的源码/替换说明。
 */
@Composable
fun OpenSourceDetailPage(id: String) {
    val item = OpenSourceCatalog.byId(id) ?: return
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val licenseText = remember(item.id) {
        val raw = runCatching {
            context.assets.open("licenses/${item.licenseAsset}")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrElse {
            "未找到该项目的许可证全文，请访问项目主页：${item.url}"
        }
        raw.replace("<year> <owner>", ownerOnly(item.copyright))
            .replace("<year> <copyright holders>", ownerOnly(item.copyright))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(14.dp))
        Text(
            text = item.name,
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = item.version,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = item.licenseLabel,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = item.description,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(10.dp))

        Text(
            text = "版权归属",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = item.copyright,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        item.extraNotice?.let { notice ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = "源代码与替换说明",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = notice,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (item.sourceAsset != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "完整源码",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${item.sourceFileName ?: "sources.jar"} · " +
                    "${item.sourceFileCount} 个文件 · ${Formatters.formatSize(item.sourceBytes)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = "源码包已随 APK 提供，不依赖外网。导出后可用于修改 jcifs-ng 并重新构建 APK。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val dir = File(
                                    Environment.getExternalStoragePublicDirectory(
                                        Environment.DIRECTORY_DOWNLOADS
                                    ),
                                    "XjFile"
                                ).apply { mkdirs() }
                                val dst = File(dir, item.sourceFileName ?: "jcifs-sources.jar")
                                context.assets.open("licenses/${item.sourceAsset}").use { input ->
                                    dst.outputStream().use { output -> input.copyTo(output) }
                                }
                                dst
                            }
                        }
                        result.onSuccess {
                            Toast.makeText(
                                context,
                                "已导出到 ${it.absolutePath}",
                                Toast.LENGTH_LONG
                            ).show()
                        }.onFailure {
                            Toast.makeText(context, "源码导出失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            ) {
                Text("导出源码到 Download")
            }
        }

        Spacer(Modifier.height(10.dp))
        if (item.url.isNotBlank()) {
            Text(
                text = "项目主页",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = item.url,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        runCatching { uriHandler.openUri(item.url) }.onFailure {
                            Toast.makeText(context, "无法打开项目主页", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(top = 4.dp, bottom = 4.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(10.dp))
        Text(
            text = "许可证全文",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Text(
                text = licenseText,
                fontSize = 10.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** BSD/MIT 模板已经带了 "Copyright (c) " 前缀，这里只取年份和权利人。 */
private fun ownerOnly(copyright: String): String = copyright
    .removePrefix("Copyright (c) ")
    .removePrefix("Copyright (C) ")
    .removePrefix("Copyright ")
    .removePrefix("© ")
    .trim()
