package com.cmzc.xj_file.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cmzc.xj_file.app.core.disk.DriveInfo
import com.cmzc.xj_file.app.core.disk.DriveKind
import com.cmzc.xj_file.app.core.disk.OpticalMedia

/**
 * 驱动器图标。
 *
 * 形状全部是 Canvas 现画的（**不是安卓自带的**），只有 U 盘用 Material 的 Usb 图标。
 * 比例按用户要求做成**竖长**（宽:高 ≈ 22:30），和资源管理器里那种"设备"的观感更接近。
 *
 * 颜色也是用户指定的：CD 绿 / 数据 DVD 紫 / 视频 DVD 黄 / BD 蓝，
 * 千年盘（M-DISC）左半彩色、右半灰。
 */
@Composable
fun DriveIcon(info: DriveInfo, modifier: Modifier = Modifier, width: Dp = 22.dp) {
    val height = width * 1.36f
    if (info.kind == DriveKind.USB) {
        Icon(
            imageVector = Icons.Filled.Usb,
            contentDescription = null,
            tint = UsbColor,
            modifier = modifier.width(width).height(height)
        )
        return
    }
    Canvas(modifier.width(width).height(height)) {
        when (info.kind) {
            DriveKind.OPTICAL -> drawOptical(info)
            DriveKind.SSD -> drawSsd()
            DriveKind.HDD -> drawHdd()
            DriveKind.FLOPPY -> drawFloppy()
            else -> drawGeneric()
        }
    }
}

// ---- 配色（光盘四色由用户指定）----
private val CdColor = Color(0xFF8CE5AB)
private val DvdDataColor = Color(0xFF8D34C5)
private val DvdVideoColor = Color(0xFFE6B325)
private val BdColor = Color(0xFF1565C0)
private val MdiscGray = Color(0xFF9E9E9E)

private val SsdPcb = Color(0xFF2E7D32)
private val SsdPins = Color(0xFFD4AF37)
private val HddBody = Color(0xFF7C8794)
private val FloppyBody = Color(0xFF3F4A5A)
private val UsbColor = Color(0xFF6C8FA6)
private val DetailColor = Color(0x55FFFFFF)

private fun opticalColor(media: OpticalMedia): Color = when (media) {
    OpticalMedia.CD -> CdColor
    OpticalMedia.DVD_DATA -> DvdDataColor
    OpticalMedia.DVD_VIDEO -> DvdVideoColor
    OpticalMedia.BD -> BdColor
    OpticalMedia.NONE -> MdiscGray
}

/**
 * 光盘：按用户给的参考图来 —— 实心彩色盘 + 外缘一圈浅灰描边 +
 * 中心一个「黑圈包着白心」的主轴孔。千年盘左半彩色、右半灰。
 */
private fun DrawScope.drawOptical(info: DriveInfo) {
    val color = opticalColor(info.optical)
    val r = size.minDimension * 0.46f
    val c = center

    // 盘体
    if (info.isMdisc) {
        val half = size.width / 2f
        clipRect(0f, 0f, half, size.height) { drawCircle(color, r, c) }
        clipRect(half, 0f, size.width, size.height) { drawCircle(MdiscGray, r, c) }
    } else {
        drawCircle(color, r, c)
    }

    // 外缘：一圈浅灰描边（参考图里盘边那圈亮边）
    drawCircle(
        color = Color(0xFFE3E3E3),
        radius = r * 0.985f,
        center = c,
        style = Stroke(width = r * 0.035f)
    )

    // 主轴孔：黑圈 → 灰圈 → 白心（参考图里这三层是分开的，灰圈不能太白）
    drawCircle(Color(0xFFDEDEDE), r * 0.185f, c)          // 灰圈
    drawCircle(Color(0xFFFFFFFF), r * 0.105f, c)          // 白心
    drawCircle(
        color = Color(0xFF111111),
        radius = r * 0.20f,
        center = c,
        style = Stroke(width = r * 0.040f)
    )
}

/** 2.5 英寸 SATA SSD：上面一排金手指，下面一整块绿色 PCB。 */
private fun DrawScope.drawSsd() {
    val w = size.width * 0.86f
    val h = size.height * 0.80f
    val left = (size.width - w) / 2f
    val top = (size.height - h) / 2f
    // 绿色板身（下面一大坨）
    val bodyTop = top + h * 0.22f
    drawRoundRect(
        SsdPcb,
        Offset(left, bodyTop),
        Size(w, h * 0.78f),
        CornerRadius(w * 0.10f)
    )
    // 板身上的细节：芯片块
    drawRoundRect(
        Color(0xFF1B5E20),
        Offset(left + w * 0.16f, bodyTop + h * 0.20f),
        Size(w * 0.68f, h * 0.24f),
        CornerRadius(w * 0.04f)
    )
    drawRoundRect(
        Color(0xFF1B5E20),
        Offset(left + w * 0.16f, bodyTop + h * 0.52f),
        Size(w * 0.30f, h * 0.18f),
        CornerRadius(w * 0.04f)
    )
    // 顶部金手指
    val pinTop = top
    val pinW = w * 0.86f
    val pinLeft = left + (w - pinW) / 2f
    drawRect(SsdPins, Offset(pinLeft, pinTop), Size(pinW, h * 0.18f))
    // 金手指的缝
    val gap = pinW / 9f
    for (i in 1..8) {
        drawLine(
            Color(0xFFB08D23),
            Offset(pinLeft + gap * i, pinTop),
            Offset(pinLeft + gap * i, pinTop + h * 0.18f),
            strokeWidth = size.width * 0.02f
        )
    }
}

/** 机械硬盘：竖着的长方形，盘片 + 磁头臂。 */
private fun DrawScope.drawHdd() {
    val w = size.width * 0.86f
    val h = size.height * 0.78f
    val left = (size.width - w) / 2f
    val top = (size.height - h) / 2f
    drawRoundRect(HddBody, Offset(left, top), Size(w, h), CornerRadius(w * 0.12f))
    drawCircle(DetailColor, w * 0.30f, Offset(left + w * 0.46f, top + h * 0.44f))
    drawCircle(HddBody, w * 0.10f, Offset(left + w * 0.46f, top + h * 0.44f))
    // 磁头臂
    drawRoundRect(
        DetailColor,
        Offset(left + w * 0.70f, top + h * 0.14f),
        Size(w * 0.07f, h * 0.52f),
        CornerRadius(w * 0.03f)
    )
    // 底部螺丝位
    drawCircle(DetailColor, w * 0.05f, Offset(left + w * 0.20f, top + h * 0.86f))
}

/** 软盘：竖着的小方块 + 顶部金属滑盖 + 下方标签。 */
private fun DrawScope.drawFloppy() {
    val w = size.width * 0.86f
    val h = size.height * 0.78f
    val left = (size.width - w) / 2f
    val top = (size.height - h) / 2f
    drawRoundRect(FloppyBody, Offset(left, top), Size(w, h), CornerRadius(w * 0.08f))
    drawRect(Color(0xFFB0B7C3), Offset(left + w * 0.28f, top), Size(w * 0.44f, h * 0.30f))
    drawRect(Color(0xFFE8EAED), Offset(left + w * 0.14f, top + h * 0.52f), Size(w * 0.72f, h * 0.36f))
    // 写保护小窗
    drawRect(Color(0xFF2B3440), Offset(left + w * 0.74f, top + h * 0.06f), Size(w * 0.12f, h * 0.10f))
}

private fun DrawScope.drawGeneric() {
    val w = size.width * 0.86f
    val h = size.height * 0.70f
    val left = (size.width - w) / 2f
    val top = (size.height - h) / 2f
    drawRoundRect(MdiscGray, Offset(left, top), Size(w, h), CornerRadius(w * 0.10f))
    drawCircle(Color(0x33000000), w * 0.12f, Offset(left + w * 0.26f, top + h * 0.66f))
}
