package com.snapclear.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.snapclear.app.R
import com.snapclear.app.screenshot.ScreenshotItem
import com.snapclear.app.ui.image.ScreenshotImageLoader
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ScreenshotDetailScreen(item: ScreenshotItem?, onBack: () -> Unit, onCopyDelete: (ScreenshotItem) -> Unit, onDelete: (ScreenshotItem) -> Unit) {
    val context = LocalContext.current; val config = LocalConfiguration.current; val density = context.resources.displayMetrics.density
    val preview by rememberFullImage(item?.uri, (config.screenWidthDp * density).toInt(), (config.screenHeightDp * density).toInt())
    val background = MaterialTheme.colorScheme.background
    val hazeState = remember(background) { HazeState() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize().haze(hazeState)) {
            if (item == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Spacer(Modifier.height(102.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    val previewBitmap = preview.bitmap.takeIf { preview.uri == item.uri }
                    if (previewBitmap != null) Image(previewBitmap.asImageBitmap(), item.displayName, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                    else Box(Modifier.height(240.dp), contentAlignment = Alignment.Center) { Text("加载中", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    InfoRow(R.drawable.ic_detail_file, "文件名", item.displayName)
                    InfoRow(R.drawable.ic_detail_clock, "时间", detailTimeFormat.format(Date(item.dateTaken)))
                    InfoRow(R.drawable.ic_detail_ruler, "尺寸", "${item.width} × ${item.height}")
                    InfoRow(R.drawable.ic_detail_size, "大小", formatFileSize(item.size))
                    InfoRow(R.drawable.ic_detail_path, "路径", item.relativePath, 2)
                }
                Spacer(Modifier.height(168.dp).windowInsetsPadding(WindowInsets.navigationBars))
            }
        }
        ImmersiveTopBar { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { CircularBackButton(hazeState, onBack); Spacer(Modifier.width(10.dp)); Text("截图详情", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) } }
        if (item != null) ImmersiveBottomBar(Modifier.align(Alignment.BottomCenter)) {
            Row(Modifier.widthIn(max = 304.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(
                    { onCopyDelete(item) },
                    Modifier.weight(1.45f).height(46.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Icon(painterResource(R.drawable.ic_action_copy_delete), null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("拷贝并删除", fontWeight = FontWeight.SemiBold) }
                FilledTonalButton(
                    { onDelete(item) },
                    Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Icon(painterResource(R.drawable.ic_action_delete), null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("删除") }
            }
        }
    }
}

@Composable
private fun ImmersiveBottomBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val surface = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(168.dp)
            .background(
                Brush.verticalGradient(
                    *arrayOf(
                        0f to surface.copy(alpha = 0f),
                        .18f to surface.copy(alpha = .03f),
                        .34f to surface.copy(alpha = .10f),
                        .48f to surface.copy(alpha = .24f),
                        .62f to surface.copy(alpha = .44f),
                        .74f to surface.copy(alpha = .66f),
                        .84f to surface.copy(alpha = .82f),
                        .92f to surface.copy(alpha = .94f),
                        1f to surface
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 20.dp, top = 72.dp, end = 20.dp, bottom = 18.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        content()
    }
}

@Composable private fun InfoRow(@DrawableRes icon: Int, label: String, value: String, maxLines: Int = 1) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = maxLines, overflow = TextOverflow.Ellipsis) } }
}

private data class PreviewResult(val uri: Uri?, val bitmap: Bitmap?)

@Composable private fun rememberFullImage(uri: Uri?, width: Int, height: Int): State<PreviewResult> {
    val context = LocalContext.current
    return produceState(PreviewResult(uri, null), uri, width, height) {
        value = PreviewResult(uri, null)
        if (uri != null) {
            val bitmap = withContext(Dispatchers.IO) {
                ScreenshotImageLoader.loadPreview(context.contentResolver, uri, width, height)
            }
            value = PreviewResult(uri, bitmap)
        }
    }
}
private val detailTimeFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
private fun formatFileSize(bytes: Long): String { val kb = bytes / 1024.0; val mb = kb / 1024.0; return when { mb >= 1 -> String.format(Locale.getDefault(), "%.1f MB", mb); kb >= 1 -> String.format(Locale.getDefault(), "%.0f KB", kb); else -> "$bytes B" } }
