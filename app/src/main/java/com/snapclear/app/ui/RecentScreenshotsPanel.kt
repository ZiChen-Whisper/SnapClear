package com.snapclear.app.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.material.card.MaterialCardView
import com.snapclear.app.R
import com.snapclear.app.screenshot.ScreenshotItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 最近截图卡片（原生 View 实现，支持 OPPO 无缝动画）
 *
 * 关键设计：整个卡片用 MaterialCardView + 原生 View 树实现，
 * 而非 Compose Composable。这样 OPPO 无缝动画的 setSeamlessView
 * 能获取到真实的卡片 View 作为动画起点。
 *
 * - 点击缩略图：通过 OPPO 无缝动画打开截图详情页
 * - 「拷贝并删除」：复制到剪贴板 + 移入系统回收站
 * - 「删除」：仅将截图移入系统回收站
 */
@Composable
fun ScreenshotCard(
    item: ScreenshotItem,
    onCopyDelete: () -> Unit,
    onDelete: () -> Unit,
    onClick: (uri: Uri, view: View, bounds: Rect) -> Unit
) {
    val density = LocalDensity.current
    val thumbState by rememberThumbnail(item.uri, 300)

    // LazyColumn 复用卡片时（列表增删后条目移位），factory 中捕获的旧 item 会
    // 导致点击指向错误的截图。rememberUpdatedState 保证回调始终使用当前条目。
    val latestItem by rememberUpdatedState(item)
    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnCopyDelete by rememberUpdatedState(onCopyDelete)
    val latestOnDelete by rememberUpdatedState(onDelete)

    // 截图卡片 View 和位置（用于 OPPO 无缝动画）
    var cardBounds by remember { mutableStateOf<Rect?>(null) }
    var cardView by remember { mutableStateOf<MaterialCardView?>(null) }

    // 主题颜色
    val surfaceColor = MaterialTheme.colorScheme.surface
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary

    val surfaceArgb = surfaceColor.toArgb()
    val surfaceVariantArgb = surfaceVariantColor.toArgb()
    val onSurfaceArgb = onSurfaceColor.toArgb()
    val onSurfaceVariantArgb = onSurfaceVariantColor.toArgb()
    val primaryArgb = primaryColor.toArgb()
    val onPrimaryArgb = onPrimaryColor.toArgb()

    val paddingPx = with(density) { 8.dp.toPx().toInt() }
    val cornerRadiusPx = with(density) { 16.dp.toPx() }
    val thumbCornerPx = with(density) { 12.dp.toPx() }
    val btnCornerPx = with(density) { 10.dp.toPx() }
    val btnHeightPx = with(density) { 32.dp.toPx().toInt() }
    val btnWidthIgnorePx = with(density) { 32.dp.toPx().toInt() }
    val iconSizePx = with(density) { 14.dp.toPx().toInt() }
    val spacer6Px = with(density) { 6.dp.toPx().toInt() }
    val spacer4Px = with(density) { 4.dp.toPx().toInt() }
    val titleTextSize = with(density) { 11.dp.toPx() / density.density }
    val timeTextSize = with(density) { 10.dp.toPx() / density.density }
    val btnTextSize = with(density) { 10.dp.toPx() / density.density }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                val w = coords.size.width
                val h = coords.size.height
                cardBounds = Rect(pos.x.toInt(), pos.y.toInt(), (pos.x + w).toInt(), (pos.y + h).toInt())
            }
    ) {
        AndroidView(
            factory = { ctx ->
                MaterialCardView(ctx).apply {
                    setRadius(cornerRadiusPx)
                    setCardBackgroundColor(android.content.res.ColorStateList.valueOf(surfaceArgb))
                    cardElevation = 0f
                    isClickable = false
                    isFocusable = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )

                    val contentLayout = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }

                    // 缩略图容器
                    val thumbLayout = FrameLayout(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            setCornerRadius(thumbCornerPx)
                            setColor(surfaceVariantArgb)
                        }
                        isClickable = true
                        setOnClickListener {
                            // 通过 OPPO 无缝动画打开详情页
                            cardView?.let { view ->
                                cardBounds?.let { bounds ->
                                    latestOnClick(latestItem.uri, view, bounds)
                                }
                            }
                        }
                    }

                    // 缩略图 ImageView
                    val thumbView = ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            with(density) { 150.dp.toPx().toInt() } // 固定高度
                        )
                    }
                    thumbLayout.addView(thumbView)
                    contentLayout.addView(thumbLayout)

                    // 间距
                    contentLayout.addView(Space(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(1, spacer6Px)
                    })

                    // 文件名
                    val nameView = TextView(ctx).apply {
                        text = item.displayName
                        setTextColor(onSurfaceArgb)
                        textSize = titleTextSize
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    contentLayout.addView(nameView)

                    // 时间
                    val timeView = TextView(ctx).apply {
                        text = formatTime(item.dateTaken)
                        setTextColor(onSurfaceVariantArgb)
                        textSize = timeTextSize
                    }
                    contentLayout.addView(timeView)

                    // 间距
                    contentLayout.addView(Space(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(1, spacer6Px)
                    })

                    // 按钮行
                    val btnRow = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }

                    // 「拷贝并删除」按钮
                    val copyDeleteBtn = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            setCornerRadius(btnCornerPx)
                            setColor(primaryArgb)
                        }
                        isClickable = true
                        setOnClickListener { latestOnCopyDelete() }
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            btnHeightPx,
                            1f
                        )
                    }
                    val copyDeleteIcon = ImageView(ctx).apply {
                        setImageResource(R.drawable.ic_action_copy_delete)
                        imageTintList = android.content.res.ColorStateList.valueOf(onPrimaryArgb)
                        layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
                    }
                    val copyDeleteText = TextView(ctx).apply {
                        text = "拷贝并删除"
                        setTextColor(onPrimaryArgb)
                        textSize = btnTextSize
                        setTypeface(typeface, Typeface.BOLD)
                    }
                    copyDeleteBtn.addView(copyDeleteIcon)
                    copyDeleteBtn.addView(Space(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(spacer4Px, 1)
                    })
                    copyDeleteBtn.addView(copyDeleteText)
                    btnRow.addView(copyDeleteBtn)

                    // 按钮间距
                    btnRow.addView(Space(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(spacer4Px, 1)
                    })

                    // 「删除」按钮
                    val deleteBtn = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            setCornerRadius(btnCornerPx)
                            setColor(surfaceVariantArgb)
                        }
                        isClickable = true
                        setOnClickListener { latestOnDelete() }
                        layoutParams = LinearLayout.LayoutParams(btnWidthIgnorePx, btnHeightPx)
                    }
                    val deleteIcon = ImageView(ctx).apply {
                        setImageResource(R.drawable.ic_action_delete)
                        imageTintList = android.content.res.ColorStateList.valueOf(onSurfaceVariantArgb)
                        layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
                    }
                    deleteBtn.addView(deleteIcon)
                    btnRow.addView(deleteBtn)

                    contentLayout.addView(btnRow)
                    addView(contentLayout)
                }.also { cardView = it }
            },
            update = { view ->
                // 更新缩略图
                val contentLayout = view.getChildAt(0) as? LinearLayout
                val thumbLayout = contentLayout?.getChildAt(0) as? FrameLayout
                val thumbView = thumbLayout?.getChildAt(0) as? ImageView
                thumbView?.let { iv ->
                    val bmp = thumbState
                    if (bmp != null) {
                        iv.setImageBitmap(bmp)
                        iv.visibility = View.VISIBLE
                    } else {
                        iv.setImageDrawable(null)
                    }
                }

                // 更新文件名
                val nameView = contentLayout?.getChildAt(2) as? TextView
                nameView?.text = item.displayName
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 异步加载缩略图
 */
@Composable
private fun rememberThumbnail(uri: Uri, sizePx: Int): State<Bitmap?> {
    val context = LocalContext.current
    return produceState<Bitmap?>(initialValue = null, uri, sizePx) {
        // 条目切换时先清空旧图，避免 LazyColumn 复用卡片时短暂显示上一张的缩略图
        value = null
        value = withContext(Dispatchers.IO) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
                } else {
                    @Suppress("DEPRECATION")
                    android.provider.MediaStore.Images.Thumbnails.getThumbnail(
                        context.contentResolver,
                        android.content.ContentUris.parseId(uri),
                        android.provider.MediaStore.Images.Thumbnails.MINI_KIND,
                        null
                    )
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
private fun formatTime(timestamp: Long): String = timeFormat.format(Date(timestamp))
