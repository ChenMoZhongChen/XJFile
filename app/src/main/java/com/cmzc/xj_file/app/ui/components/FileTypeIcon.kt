package com.cmzc.xj_file.app.ui.components

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cmzc.xj_file.app.R
import com.cmzc.xj_file.app.core.archive.ArchiveFormats
import com.cmzc.xj_file.app.core.playback.MediaTypes
import com.cmzc.xj_file.app.core.settings.AppSettings
import com.cmzc.xj_file.app.core.text.TextFileTypes
import com.cmzc.xj_file.app.ui.theme.LocalModuleColors
import kotlin.math.roundToInt

/** 文件列表和压缩包列表共用的文件类型图标，颜色区分常见类型。 */
@Composable
fun FileTypeIcon(
    name: String,
    isDirectory: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    val spec = remember(name, isDirectory) { fileIconSpec(name, isDirectory) }
    val colorsEnabled by AppSettings.fileIconColors.collectAsStateWithLifecycle()
    val folderTint = LocalModuleColors.current.folder
    val tint = when {
        isDirectory -> folderTint
        colorsEnabled -> spec.tint
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val ext = name.substringAfterLast('.', "").lowercase()
    if (!isDirectory && ext == "exe") {
        val grayMatrix = remember { ColorMatrix().apply { setToSaturation(0f) } }
        Image(
            painter = painterResource(R.drawable.ic_exe_generic),
            contentDescription = null,
            modifier = modifier.size(size),
            colorFilter = if (colorsEnabled) null else ColorFilter.colorMatrix(grayMatrix),
        )
        return
    }
    val label = if (
        !isDirectory &&
        ext != "apk" &&
        !ArchiveFormats.isArchive(name)
    ) {
        extensionLabel(name, false)
    } else {
        null
    }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val iconPx = with(density) { size.roundToPx() }
    val isApk = !isDirectory && ext == "apk"
    val androidPainter = rememberVectorPainter(Icons.Default.Android)
    val iconPainter = rememberVectorPainter(spec.icon)
    val robotMask = remember(androidPainter, iconPx, isApk, density, layoutDirection) {
        if (!isApk) {
            null
        } else {
            val side = (iconPx * 0.56f).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val imageBitmap = bitmap.asImageBitmap()
            CanvasDrawScope().draw(
                density = density,
                layoutDirection = layoutDirection,
                canvas = Canvas(imageBitmap),
                size = Size(side.toFloat(), side.toFloat()),
            ) {
                with(androidPainter) {
                    draw(Size(side.toFloat(), side.toFloat()))
                }
            }
            imageBitmap
        }
    }
    val cutout = label != null || isApk
    val composed = if (cutout) {
        remember(iconPainter, tint, iconPx, label, robotMask, density, layoutDirection) {
            val androidBitmap = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
            val bitmap = androidBitmap.asImageBitmap()
            CanvasDrawScope().draw(
                density = density,
                layoutDirection = layoutDirection,
                canvas = Canvas(bitmap),
                size = Size(iconPx.toFloat(), iconPx.toFloat()),
            ) {
                with(iconPainter) {
                    draw(
                        size = Size(iconPx.toFloat(), iconPx.toFloat()),
                        colorFilter = ColorFilter.tint(tint),
                    )
                }
            }
            val canvas = android.graphics.Canvas(androidBitmap)
            if (label != null) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.BLACK
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    textAlign = Paint.Align.CENTER
                    val factor = when (label.length) {
                        1 -> 0.38f
                        2 -> 0.30f
                        3 -> 0.24f
                        else -> 0.19f
                    }
                    textSize = iconPx * factor
                }
                val metrics = paint.fontMetrics
                val baseline = iconPx * 0.70f -
                    (metrics.ascent + metrics.descent) / 2f
                canvas.drawText(label, iconPx / 2f, baseline, paint)
            }
            if (robotMask != null) {
                val centerY = iconPx * 0.60f
                val left = (iconPx - robotMask.width) / 2f
                val top = centerY - robotMask.height / 2f
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                }
                canvas.drawBitmap(
                    robotMask.asAndroidBitmap(),
                    null,
                    RectF(left, top, left + robotMask.width, top + robotMask.height),
                    paint,
                )
            }
            bitmap
        }
    } else {
        null
    }
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        if (composed != null) {
            Image(
                bitmap = composed,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = spec.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 常见后缀压成 3~4 个字符的小角标；媒体类型继续用专用图标。 */
private fun extensionLabel(name: String, isDirectory: Boolean): String? {
    if (isDirectory) return null
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) return null
    if (MediaTypes.isImage(name) || MediaTypes.isAudio(name) || MediaTypes.isVideo(name)) {
        return null
    }
    return when (ext) {
        "apk" -> null
        "doc", "docx", "docm", "dot", "dotx" -> "DOC"
        "xls", "xlsx", "xlsm", "xlsb", "xlt", "xltx" -> "XLS"
        "ppt", "pptx", "pptm", "pps", "ppsx", "pot", "potx" -> "PPT"
        "odt" -> "ODT"
        "ods" -> "ODS"
        "odp" -> "ODP"
        "rtf" -> "RTF"
        "pdf" -> "PDF"
        "txt", "text" -> "TXT"
        "md", "markdown" -> "MD"
        "json", "jsonc", "json5" -> "JSON"
        "xml", "xsl", "xslt", "xsd" -> "XML"
        "yaml", "yml" -> "YAML"
        "html", "htm" -> "HTML"
        "css" -> "CSS"
        "js", "mjs", "cjs" -> "JS"
        "ts", "tsx" -> "TS"
        "kt", "kts" -> "KT"
        "py" -> "PY"
        "sh", "bash" -> "SH"
        "zip", "zipx" -> "ZIP"
        "7z" -> "7Z"
        "tar" -> "TAR"
        "tgz" -> "TGZ"
        "gz" -> "GZ"
        "xz" -> "XZ"
        "rar" -> "RAR"
        "iso" -> "ISO"
        "img" -> "IMG"
        "dmg" -> "DMG"
        "bin" -> "BIN"
        "exe" -> "EXE"
        "dll" -> "DLL"
        "so" -> "SO"
        "db", "sqlite", "sqlite3" -> "DB"
        "ttf" -> "TTF"
        "otf" -> "OTF"
        "woff" -> "WOFF"
        "woff2" -> "WOFF"
        "torrent" -> "TOR"
        "vhd", "vhdx" -> "VHD"
        "vmdk" -> "VMDK"
        else -> if (ext.length <= 4) ext.uppercase() else null
    }
}

private data class FileIconSpec(val icon: ImageVector, val tint: Color)

private fun fileIconSpec(name: String, isDirectory: Boolean): FileIconSpec {
    if (isDirectory) {
        return FileIconSpec(Icons.Default.Folder, Color(0xFF4C8DF6))
    }
    val lower = name.lowercase()
    val ext = lower.substringAfterLast('.', "")
    return when {
        ArchiveFormats.isArchive(name) ->
            FileIconSpec(Icons.Default.Archive, Color(0xFFB5793B))
        MediaTypes.isImage(name) ->
            FileIconSpec(Icons.Default.Image, Color(0xFF43A047))
        MediaTypes.isAudio(name) ->
            FileIconSpec(Icons.Default.MusicNote, Color(0xFFE0912F))
        MediaTypes.isVideo(name) || MediaTypes.isExternalPlayable(name) ->
            FileIconSpec(Icons.Default.Movie, Color(0xFF8E5BD9))
        ext == "pdf" ->
            FileIconSpec(Icons.Default.InsertDriveFile, Color(0xFFD64545))
        TextFileTypes.isKnownText(name) || TextFileTypes.shouldOpenAsText(name) ->
            FileIconSpec(Icons.Default.InsertDriveFile, Color(0xFF5B9BD5))
        else ->
            FileIconSpec(Icons.Default.InsertDriveFile, Color(0xFF8A8F98))
    }
}
