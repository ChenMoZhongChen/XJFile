package com.cmzc.xj_file.app.ui.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cmzc.xj_file.app.core.disk.DriveInfo
import com.cmzc.xj_file.app.core.disk.DriveKind
import com.cmzc.xj_file.app.core.disk.OpticalMedia
import com.cmzc.xj_file.app.ui.components.DriveIcon
import com.cmzc.xj_file.app.ui.theme.XjFileTheme

/**
 * 只看图标的调试页（临时用，验完就删）。
 * 用 `adb shell am start -n com.cmzc.xj_file.app/.ui.debug.DriveIconPreviewActivity` 启动。
 */
class DriveIconPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XjFileTheme {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    PreviewList()
                }
            }
        }
    }
}

private data class Variant(val title: String, val info: DriveInfo)

private fun sample(
    letter: String,
    label: String,
    kind: DriveKind,
    media: OpticalMedia = OpticalMedia.NONE,
    mdisc: Boolean = false,
    sizeGb: Long = 238,
    freeGb: Long = 104,
) = DriveInfo(
    letter = letter,
    label = label,
    kind = kind,
    optical = media,
    sizeBytes = sizeGb * 1024L * 1024L * 1024L,
    freeBytes = freeGb * 1024L * 1024L * 1024L,
    isMdisc = mdisc,
)

private val variants = listOf(
    Variant("SSD（2.5\" SATA：下绿板 + 上金手指）", sample("C", "Windows10", DriveKind.SSD)),
    Variant("HDD（竖长方形 + 盘片/磁头臂）", sample("D", "SATA_1 HDD", DriveKind.HDD)),
    Variant("USB 存储（Material Usb 图标）", sample("E", "U盘", DriveKind.USB, sizeGb = 59, freeGb = 12)),
    Variant("软盘", sample("A", "软盘", DriveKind.FLOPPY, sizeGb = 1, freeGb = 1)),
    // 音频 CD 和数据 CD 用的是同一个分支（颜色/形状完全一样），所以只留一条
    Variant("CD · 音频/数据共用（#8CE5AB）", sample("L", "CD", DriveKind.OPTICAL, OpticalMedia.CD)),
    Variant("数据 DVD（#8D34C5）", sample("L", "数据DVD", DriveKind.OPTICAL, OpticalMedia.DVD_DATA)),
    Variant("视频 DVD（#E6B325）", sample("L", "视频DVD", DriveKind.OPTICAL, OpticalMedia.DVD_VIDEO)),
    Variant("蓝光 BD（#1565C0）", sample("L", "蓝光", DriveKind.OPTICAL, OpticalMedia.BD)),
    Variant("千年盘 · CD（左绿右灰）", sample("L", "M-DISC CD", DriveKind.OPTICAL, OpticalMedia.CD, mdisc = true)),
    Variant("千年盘 · DVD（左紫右灰）", sample("L", "M-DISC DVD", DriveKind.OPTICAL, OpticalMedia.DVD_DATA, mdisc = true)),
    Variant(
        "千年盘 · 视频 DVD（左黄右灰）",
        sample("L", "M-DISC 视频DVD", DriveKind.OPTICAL, OpticalMedia.DVD_VIDEO, mdisc = true)
    ),
    Variant("千年盘 · BD（左蓝右灰）", sample("L", "M-DISC BD", DriveKind.OPTICAL, OpticalMedia.BD, mdisc = true)),
    Variant("光驱无盘", sample("L", "空光驱", DriveKind.OPTICAL, OpticalMedia.NONE)),
)

@Composable
private fun PreviewList() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("驱动器图标预览", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "左列是实际尺寸 24×33dp，右边是放大 2.5 倍的效果",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(1.dp))
        for (v in variants) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DriveIcon(v.info, Modifier.size(width = 24.dp, height = 33.dp))
                Spacer(Modifier.width(6.dp))
                DriveIcon(v.info, Modifier.size(width = 60.dp, height = 82.dp))
                Spacer(Modifier.width(16.dp))
                Text(
                    text = v.title,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
