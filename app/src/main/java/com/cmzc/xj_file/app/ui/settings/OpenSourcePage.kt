package com.cmzc.xj_file.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 开源软件声明目录。行点击进入独立详情页，详情页展示许可证全文和版权归属。
 */
@Composable
fun OpenSourcePage(onOpenDetail: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(14.dp))
        Text(
            text = "XJ File 使用了以下开源软件。感谢这些项目的作者与贡献者。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "XJ File 自身代码以 MIT License 发布；以下为随应用使用的第三方开源组件。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "点击任意条目查看该项目对应的完整许可证文本、版权归属和项目主页。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(18.dp))
        SectionTitle("本应用")
        OpenSourceCatalog.app.forEach { item ->
            OpenSourceRow(item) { onOpenDetail(item.id) }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("运行时库")
        OpenSourceCatalog.runtime.forEach { item ->
            OpenSourceRow(item) { onOpenDetail(item.id) }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("构建与开发工具")
        OpenSourceCatalog.build.forEach { item ->
            OpenSourceRow(item) { onOpenDetail(item.id) }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "许可证全文随 APK 一起提供；本页只做目录索引。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun OpenSourceRow(item: OpenSourceItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = item.name,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = item.licenseLabel,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = item.version,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp),
        )
        Text(
            text = item.description,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
        Text(
            text = item.url,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}
