package com.cmzc.xj_file.app.ui.editor

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cmzc.xj_file.app.core.text.LineEnding
import com.cmzc.xj_file.app.core.text.TextBom
import com.cmzc.xj_file.app.core.text.TextCodecImpl
import com.cmzc.xj_file.app.core.text.TextCodecSelfTest
import com.cmzc.xj_file.app.core.text.TextDocument
import com.cmzc.xj_file.app.ui.theme.XjFileTheme

/**
 * 5A 的开发预览入口：
 * - 编辑器 UI 没有接文件列表（那是 5B），先给一个能真机点开的样例；
 * - 编码自测直接跑 TextCodecSelfTest，不依赖文件。
 *
 * ★ 这个 Activity 不是用户功能，5B 接入文件列表后可以删掉/改成 debug 入口。
 *   验收期间曾临时 exported=true 方便 adb 启动，现已改回 false。
 */
class TextEditorPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XjFileTheme {
                PreviewRoot()
            }
        }
    }
}

@Composable
private fun PreviewRoot() {
    val context = LocalContext.current
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var encoding by rememberSaveable { mutableStateOf("UTF-8") }
    var testLines by remember { mutableStateOf<List<String>>(emptyList()) }

    if (showEditor) {
        TextEditorScreen(
            document = TextDocument(
                fileName = "样例-编码.txt",
                text = SAMPLE_TEXT,
                encoding = encoding,
                bom = TextBom.NONE,
                lineEnding = LineEnding.CRLF,
            ),
            encodingOptions = TextCodecImpl.supportedEncodings,
            onBack = { showEditor = false },
            onSave = { req, _ ->
                Toast.makeText(
                    context,
                    "保存回调：${req.encoding} / ${req.bom} / ${req.lineEnding}，${req.text.length} 字符",
                    Toast.LENGTH_LONG
                ).show()
                com.cmzc.xj_file.app.vm.EditorSaveResult.SAVED
            },
            onEncodingSelected = { name ->
                encoding = name
                Toast.makeText(context, "请求切换编码：$name（5B 负责重新解码）", Toast.LENGTH_SHORT).show()
            },
        )
        return
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("5A · 编码内核 + 编辑器预览", fontSize = 20.sp)
            Text("这里只做开发验收，不是最终用户入口。", fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showEditor = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("打开编辑器样例") }
            Button(
                onClick = {
                    testLines = runCatching { TextCodecSelfTest.run() }
                        .getOrElse { listOf("FAIL 自测异常：${it.javaClass.simpleName}") }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("运行编码自测") }
            if (testLines.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("自测结果", fontSize = 14.sp)
                testLines.forEach { line ->
                    Text(
                        text = line,
                        fontSize = 12.sp,
                        color = if (line.startsWith("OK")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            }
        }
    }
}

private val SAMPLE_TEXT = buildString {
    append(
        """
        简体中文：你好，世界。
        繁體中文：你好，世界。
        日本語：こんにちは。
        한국어: 안녕하세요.
        Emoji: 编码探测 🙂 / 🚀

        class Demo {
            fun greet(name: String): String {
                return "Hello, ${'$'}name"
            }
        }

        长行测试：${"0123456789abcdef".repeat(8)}
        Tab 测试：	a	b	c
        空行上面和下面都有。

        行号应该和正文一起滚动，不能各滚各的。
        """.trimIndent()
    )
    // 5A 验收要能真正滚动：补到 120+ 行，检查行号跟正文是否一起走。
    append("\n")
    for (line in 1..120) {
        append("\n第 $line 行：line number scroll test 0123456789")
    }
}
