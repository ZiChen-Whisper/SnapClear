package com.snapclear.app.ui

import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.material.card.MaterialCardView
import com.snapclear.app.R
import com.snapclear.app.screenshot.ScreenshotItem
import com.snapclear.app.ui.image.ScreenshotImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

enum class ScreenshotViewMode { CARD, LIST }

@Composable
fun ScreenshotCard(item: ScreenshotItem, viewMode: ScreenshotViewMode, onCopyDelete: (Uri) -> Unit,
    onDelete: (Uri) -> Unit, onClick: (Uri, View) -> Unit) {
    val density = LocalDensity.current; val thumbnail by rememberThumbnail(item.uri, if (viewMode == ScreenshotViewMode.CARD) 360 else 180, item.thumbnailCacheToken())
    val latestClick by rememberUpdatedState(onClick)
    val latestCopy by rememberUpdatedState(onCopyDelete); val latestDelete by rememberUpdatedState(onDelete)
    val surface = MaterialTheme.colorScheme.surface.toArgb(); val variant = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val onSurface = MaterialTheme.colorScheme.onSurface.toArgb(); val onVariant = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val primary = MaterialTheme.colorScheme.primary.toArgb(); val onPrimary = MaterialTheme.colorScheme.onPrimary.toArgb()
    val pad = with(density) { 8.dp.roundToPx() }; val radius = with(density) { 18.dp.toPx() }
    key(viewMode, surface, variant, onSurface, onVariant, primary, onPrimary) {
        AndroidView(factory = { ctx -> MaterialCardView(ctx).apply card@{
            setRadius(radius); setCardBackgroundColor(surface); cardElevation = 0f
            val root = LinearLayout(ctx).apply { orientation = if (viewMode == ScreenshotViewMode.CARD) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(pad, pad, pad, pad) }
            val imageSize = with(density) { (if (viewMode == ScreenshotViewMode.CARD) 154.dp else 48.dp).roundToPx() }
            val imageCard = MaterialCardView(ctx).apply {
                this.radius = with(density) { 14.dp.toPx() }; setCardBackgroundColor(variant); cardElevation = 0f
                layoutParams = if (viewMode == ScreenshotViewMode.CARD) LinearLayout.LayoutParams(-1, imageSize) else LinearLayout.LayoutParams(imageSize, imageSize)
                addView(ImageView(ctx).apply { tag = TAG_IMAGE; scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = ViewGroup.LayoutParams(-1, -1) })
                isClickable = true; setOnClickListener {
                    (this@card.tag as? ScreenshotCardBinding)?.let { binding ->
                        binding.onClick(binding.uri, this@card)
                    }
                }
            }
            root.addView(imageCard); root.addView(Space(ctx).apply { layoutParams = LinearLayout.LayoutParams(with(density) { (if (viewMode == ScreenshotViewMode.CARD) 1.dp else 12.dp).roundToPx() }, 1) })
            if (viewMode == ScreenshotViewMode.CARD) {
                val details = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, with(density) { 6.dp.roundToPx() }, 0, 0); layoutParams = LinearLayout.LayoutParams(-1, -2) }
                details.addView(label(ctx, TAG_NAME, onSurface, 12f)); details.addView(label(ctx, TAG_TIME, onVariant, 10f))
                val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, with(density) { 6.dp.roundToPx() }, 0, 0) }
                actions.addView(actionButton(ctx, "拷贝并删除", R.drawable.ic_action_copy_delete, primary, onPrimary, with(density) { 98.dp.roundToPx() }) {
                    (this@card.tag as? ScreenshotCardBinding)?.let { it.onCopyDelete(it.uri) }
                })
                actions.addView(Space(ctx).apply { layoutParams = LinearLayout.LayoutParams(with(density) { 5.dp.roundToPx() }, 1) })
                actions.addView(actionButton(ctx, "", R.drawable.ic_action_delete, variant, onVariant, with(density) { 32.dp.roundToPx() }) {
                    (this@card.tag as? ScreenshotCardBinding)?.let { it.onDelete(it.uri) }
                })
                details.addView(actions); root.addView(details)
            } else {
                val text = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
                text.addView(label(ctx, TAG_NAME, onSurface, 14f)); text.addView(label(ctx, TAG_TIME, onVariant, 11f)); root.addView(text)
                val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                actions.addView(actionButton(ctx, "", R.drawable.ic_action_copy_delete, primary, onPrimary, with(density) { 34.dp.roundToPx() }) {
                    (this@card.tag as? ScreenshotCardBinding)?.let { it.onCopyDelete(it.uri) }
                })
                actions.addView(Space(ctx).apply { layoutParams = LinearLayout.LayoutParams(with(density) { 6.dp.roundToPx() }, 1) })
                actions.addView(actionButton(ctx, "", R.drawable.ic_action_delete, variant, onVariant, with(density) { 34.dp.roundToPx() }) {
                    (this@card.tag as? ScreenshotCardBinding)?.let { it.onDelete(it.uri) }
                }); root.addView(actions)
            }
            addView(root)
        } }, update = { card ->
            card.tag = ScreenshotCardBinding(item.uri, latestClick, latestCopy, latestDelete)
            card.findViewWithTag<ImageView>(TAG_IMAGE).apply {
                if (thumbnailBelongsTo(item.uri.toString(), thumbnail.uri.toString()) && thumbnail.bitmap != null) setImageBitmap(thumbnail.bitmap) else setImageDrawable(null)
            }
            card.findViewWithTag<TextView>(TAG_NAME).text = item.displayName; card.findViewWithTag<TextView>(TAG_TIME).text = formatScreenshotTime(item.dateTaken)
        }, onReset = { card -> card.tag = null; card.findViewWithTag<ImageView>(TAG_IMAGE)?.setImageDrawable(null) }, modifier = Modifier.fillMaxWidth())
    }
}

private fun label(ctx: android.content.Context, tagValue: String, color: Int, size: Float) = TextView(ctx).apply { tag = tagValue; setTextColor(color); textSize = size; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
private fun actionButton(ctx: android.content.Context, textValue: String, icon: Int, bg: Int, fg: Int, width: Int, click: () -> Unit) = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding((5 * resources.displayMetrics.density).toInt(), 0, (5 * resources.displayMetrics.density).toInt(), 0)
    background = GradientDrawable().apply { cornerRadius = 10 * resources.displayMetrics.density; setColor(bg) }; layoutParams = LinearLayout.LayoutParams(width, (34 * resources.displayMetrics.density).toInt()); isClickable = true; setOnClickListener { click() }
    addView(ImageView(ctx).apply { setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(fg); layoutParams = LinearLayout.LayoutParams((14 * resources.displayMetrics.density).toInt(), (14 * resources.displayMetrics.density).toInt()) })
    if (textValue.isNotEmpty()) { addView(Space(ctx).apply { layoutParams = LinearLayout.LayoutParams((4 * resources.displayMetrics.density).toInt(), 1) }); addView(TextView(ctx).apply { text = textValue; setTextColor(fg); textSize = 9f; setTypeface(typeface, Typeface.BOLD); maxLines = 1 }) }
}

@Composable fun ScreenshotPagerThumbnail(item: ScreenshotItem, onClick: (Uri, View) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current; val thumbnail by rememberThumbnail(item.uri, 144, item.thumbnailCacheToken()); val latestClick by rememberUpdatedState(onClick)
    val outline = MaterialTheme.colorScheme.outlineVariant.toArgb()
    AndroidView(
        factory = { ctx ->
            MaterialCardView(ctx).apply card@{
                radius = with(density) { 12.dp.toPx() }
                strokeWidth = with(density) { 1.dp.roundToPx() }
                strokeColor = outline
                cardElevation = 0f
                addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = ViewGroup.LayoutParams(-1, -1) })
                setOnClickListener {
                    (this@card.tag as? ScreenshotClickBinding)?.let { binding ->
                        binding.onClick(binding.uri, this@card)
                    }
                }
            }
        },
        update = { card ->
            card.tag = ScreenshotClickBinding(item.uri, latestClick)
            card.strokeColor = outline
            (card.getChildAt(0) as ImageView).apply {
                if (thumbnailBelongsTo(item.uri.toString(), thumbnail.uri.toString()) && thumbnail.bitmap != null) setImageBitmap(thumbnail.bitmap) else setImageDrawable(null)
            }
        },
        onReset = { card -> card.tag = null; (card.getChildAt(0) as? ImageView)?.setImageDrawable(null) },
        modifier = modifier
    )
}

private data class ScreenshotCardBinding(
    val uri: Uri,
    val onClick: (Uri, View) -> Unit,
    val onCopyDelete: (Uri) -> Unit,
    val onDelete: (Uri) -> Unit
)

private data class ScreenshotClickBinding(
    val uri: Uri,
    val onClick: (Uri, View) -> Unit
)

private data class ThumbnailResult(val uri: Uri, val bitmap: Bitmap?)

internal fun thumbnailBelongsTo(itemUri: String, resultUri: String): Boolean =
    itemUri == resultUri

@Composable private fun rememberThumbnail(uri: Uri, size: Int, cacheToken: String): State<ThumbnailResult> {
    val context = LocalContext.current
    return produceState(ThumbnailResult(uri, ScreenshotImageLoader.peekThumbnail(uri, size, cacheToken)), uri, size, cacheToken) {
        value = ThumbnailResult(uri, ScreenshotImageLoader.peekThumbnail(uri, size, cacheToken))
        if (value.bitmap == null) {
            val bitmap = withContext(Dispatchers.IO) {
                ScreenshotImageLoader.loadThumbnail(context.contentResolver, uri, size, cacheToken)
            }
            value = ThumbnailResult(uri, bitmap)
        }
    }
}

private fun ScreenshotItem.thumbnailCacheToken(): String =
    "$id:$dateTaken:$size:$displayName"

internal val screenshotTimeFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
internal fun formatScreenshotTime(timestamp: Long): String = screenshotTimeFormat.format(Date(timestamp))
internal fun formatScreenshotRange(items: List<ScreenshotItem>): String = formatTimestampRange(items.map { it.dateTaken })
internal fun formatTimestampRange(timestamps: List<Long>): String = if (timestamps.isEmpty()) "本页暂无截图" else { val oldest = timestamps.min(); val newest = timestamps.max(); if (oldest == newest) formatScreenshotTime(newest) else "${formatScreenshotTime(oldest)} — ${formatScreenshotTime(newest)}" }
private const val TAG_IMAGE = "screenshot_image"; private const val TAG_NAME = "screenshot_name"; private const val TAG_TIME = "screenshot_time"
