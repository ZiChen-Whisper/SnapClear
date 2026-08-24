package com.snapclear.app.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.snapclear.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.material.card.MaterialCardView
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * 全应用统一的玻璃材质：局部高斯模糊、主题感知的半透明黑/白底与轻阴影。
 * HazeStyle 本身已经会绘制半透明 tint，不再叠加第二层纯色背景，
 * 否则会把已经计算好的高斯模糊几乎完全遮住。
 */
@Composable
fun GlassSurface(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    contentAlignment: Alignment = Alignment.TopStart,
    interactive: Boolean = true,
    clipContent: Boolean = true,
    shadowElevation: Dp? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val scaleX = remember { Animatable(1f) }
    val scaleY = remember { Animatable(1f) }
    val highlightAlpha = remember { Animatable(0f) }
    var highlightPosition by remember { mutableStateOf(Offset.Zero) }
    var transformOrigin by remember { mutableStateOf(TransformOrigin.Center) }
    val tint = if (isLight) {
        ComposeColor.White.copy(alpha = 0.76f)
    } else {
        ComposeColor.Black.copy(alpha = 0.64f)
    }
    val shadowColor = if (isLight) {
        ComposeColor.Black.copy(alpha = 0.07f)
    } else {
        ComposeColor.Black.copy(alpha = 0.30f)
    }
    val gestureModifier = if (interactive) {
        Modifier.pointerInput(Unit) {
            coroutineScope {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.coerceAtLeast(1).toFloat()
                    val height = size.height.coerceAtLeast(1).toFloat()
                    highlightPosition = down.position
                    transformOrigin = TransformOrigin.Center
                    launch { highlightAlpha.animateTo(1f, tween(90)) }
                    launch { scaleX.animateTo(0.985f, tween(80)) }
                    launch { scaleY.animateTo(0.985f, tween(80)) }

                    var pointerPressed = true
                    while (pointerPressed) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        pointerPressed = change.pressed
                        if (pointerPressed) {
                            highlightPosition = change.position
                            val delta = change.position - down.position
                            val resistedX = rubberBandOffset(delta.x, width * 0.16f)
                            val resistedY = rubberBandOffset(delta.y, height * 0.16f)
                            val horizontalPull = abs(resistedX) / width
                            val verticalPull = abs(resistedY) / height
                            transformOrigin = TransformOrigin(
                                pivotFractionX = when {
                                    abs(resistedX) < 0.5f -> 0.5f
                                    resistedX > 0f -> 0f
                                    else -> 1f
                                },
                                pivotFractionY = when {
                                    abs(resistedY) < 0.5f -> 0.5f
                                    resistedY > 0f -> 0f
                                    else -> 1f
                                }
                            )
                            launch {
                                scaleX.snapTo(0.985f + horizontalPull * 0.58f - verticalPull * 0.08f)
                                scaleY.snapTo(0.985f + verticalPull * 0.58f - horizontalPull * 0.08f)
                            }
                        }
                    }

                    val rebound = spring<Float>(
                        dampingRatio = 0.72f,
                        stiffness = 420f
                    )
                    launch { scaleX.animateTo(1f, rebound) }
                    launch { scaleY.animateTo(1f, rebound) }
                    launch { highlightAlpha.animateTo(0f, tween(190)) }
                }
            }
        }
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .then(gestureModifier)
            .graphicsLayer {
                this.scaleX = scaleX.value
                this.scaleY = scaleY.value
                this.transformOrigin = transformOrigin
            }
            .shadow(
                elevation = shadowElevation ?: if (isLight) 1.dp else 2.dp,
                shape = shape,
                clip = false,
                ambientColor = shadowColor.copy(alpha = shadowColor.alpha * 0.72f),
                spotColor = shadowColor
            ),
        contentAlignment = contentAlignment
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    val baseWidth = constraints.maxWidth
                    val baseHeight = constraints.maxHeight
                    val stretchedWidth = (baseWidth * scaleX.value).roundToInt().coerceAtLeast(1)
                    val stretchedHeight = (baseHeight * scaleY.value).roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(
                        androidx.compose.ui.unit.Constraints.fixed(stretchedWidth, stretchedHeight)
                    )
                    val x = ((baseWidth - stretchedWidth) * transformOrigin.pivotFractionX).roundToInt()
                    val y = ((baseHeight - stretchedHeight) * transformOrigin.pivotFractionY).roundToInt()
                    layout(baseWidth, baseHeight) {
                        placeable.place(x, y)
                    }
                }
                .hazeChild(
                    state = hazeState,
                    shape = shape,
                    style = HazeStyle(tint = tint, blurRadius = 18.dp)
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (clipContent) Modifier.clip(shape) else Modifier
                ),
            contentAlignment = contentAlignment,
            content = content
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .drawWithCache {
                    val radius = max(size.width, size.height) * 0.9f
                    val highlightStrength = if (isLight) 0.24f else 0.32f
                    val highlight = Brush.radialGradient(
                        colors = listOf(
                            ComposeColor.White.copy(alpha = highlightStrength * highlightAlpha.value),
                            ComposeColor.White.copy(alpha = highlightStrength * 0.32f * highlightAlpha.value),
                            ComposeColor.Transparent
                        ),
                        center = highlightPosition,
                        radius = radius
                    )
                    onDrawBehind {
                        if (highlightAlpha.value > 0.001f) {
                            drawCircle(highlight, radius = radius, center = highlightPosition)
                        }
                    }
                }
        )
    }
}

/**
 * 状态胶囊：圆点 + 文本，用于卡片右上角的状态标识
 */
@Composable
fun StatusBadge(
    text: String,
    color: ComposeColor,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 二级菜单入口卡片
 *
 * 全部内容（图标 / 标题 / 副标题 / 状态胶囊 / 箭头）以原生 View 形式嵌入
 * MaterialCardView 内部，使整个卡片成为单一 View 树。
 * 这样 OPPO 无缝过渡动画的 leash 能完整捕获卡片内容（含图标和文字），
 * 避免返回动画结束时出现「内容短暂消失再瞬间出现」的闪烁。
 *
 * 点击时优先走无缝动画启动目标 Activity，不支持时回退普通启动。
 */
@Composable
fun SeamlessEntryCard(
    @DrawableRes iconRes: Int,
    title: String,
    subtitle: String,
    intent: Intent,
    modifier: Modifier = Modifier,
    statusText: String? = null,
    statusColor: ComposeColor? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val cornerRadius = 22.dp
    val cardColor = MaterialTheme.colorScheme.surface
    val strokeColor = MaterialTheme.colorScheme.outlineVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryContainerColor = MaterialTheme.colorScheme.primaryContainer
    val primaryColor = MaterialTheme.colorScheme.primary

    val radiusPx = with(density) { cornerRadius.toPx() }
    val strokePx = with(density) { 1.dp.toPx() }
    val cardArgb = cardColor.toArgb()
    val titleArgb = onSurfaceColor.toArgb()
    val subtitleArgb = onSurfaceVariantColor.toArgb()
    val iconBgArgb = primaryContainerColor.toArgb()
    val iconTintArgb = primaryColor.toArgb()
    val chevronArgb = onSurfaceVariantColor.toArgb()
    val statusArgb = statusColor?.toArgb() ?: 0

    val paddingHDp = 18.dp
    val paddingHPx = with(density) { paddingHDp.toPx().toInt() }
    val iconSizePx = with(density) { 46.dp.toPx().toInt() }
    val iconCornerPx = with(density) { 14.dp.toPx().toInt() }
    val iconDrawableSizePx = with(density) { 24.dp.toPx().toInt() }
    val chevronSizePx = with(density) { 24.dp.toPx().toInt() }
    val spacerPx = with(density) { 16.dp.toPx().toInt() }
    val smallSpacerPx = with(density) { 2.dp.toPx().toInt() }
    val statusSpacerPx = with(density) { 8.dp.toPx().toInt() }
    val titleTextSize = with(density) { 16.dp.toPx() }
    val subtitleTextSize = with(density) { 12.dp.toPx() }
    val statusTextSize = with(density) { 11.dp.toPx() }

    var cardView by remember { mutableStateOf<MaterialCardView?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(cornerRadius))
            .clickable {
                val activity = context as? Activity ?: return@clickable
                val view = cardView ?: return@clickable
                OplusSeamlessHelper.startActivitySeamless(
                    view = view,
                    activity = activity,
                    intent = intent,
                    cornerRadiusPx = radiusPx,
                    colorInt = cardArgb
                )
            }
    ) {
        // 单一 AndroidView：MaterialCardView 内嵌全部原生内容
        AndroidView(
            factory = { ctx ->
                MaterialCardView(ctx).apply {
                    setRadius(radiusPx)
                    setCardBackgroundColor(ColorStateList.valueOf(cardArgb))
                    setStrokeColor(strokeColor.toArgb())
                    setStrokeWidth(strokePx.toInt())
                    cardElevation = 0f
                    isClickable = false
                    isFocusable = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    val contentLayout = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(paddingHPx, 0, paddingHPx, 0)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }

                    // 图标背景容器
                    val iconBg = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            setCornerRadius(iconCornerPx.toFloat())
                            setColor(iconBgArgb)
                        }
                        layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
                    }
                    val iconView = AppCompatImageView(ctx).apply {
                        setImageResource(iconRes)
                        imageTintList = ColorStateList.valueOf(iconTintArgb)
                        layoutParams = LinearLayout.LayoutParams(iconDrawableSizePx, iconDrawableSizePx)
                    }
                    iconBg.addView(iconView)
                    contentLayout.addView(iconBg)

                    // 间距
                    contentLayout.addView(Space(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(spacerPx, 1)
                    })

                    // 文本列
                    val textColumn = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                    }
                    val titleView = TextView(ctx).apply {
                        text = title
                        setTextColor(titleArgb)
                        textSize = titleTextSize / density.density
                        setTypeface(typeface, Typeface.BOLD)
                        setLineSpacing(0f, 1.1f)
                    }
                    val subtitleView = TextView(ctx).apply {
                        text = subtitle
                        setTextColor(subtitleArgb)
                        textSize = subtitleTextSize / density.density
                        setLineSpacing(0f, 1.2f)
                    }
                    textColumn.addView(titleView)
                    textColumn.addView(Space(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(1, smallSpacerPx)
                    })
                    textColumn.addView(subtitleView)
                    contentLayout.addView(textColumn)

                    // 状态胶囊（可选）
                    if (statusText != null && statusColor != null) {
                        val badgePaddingHPx = with(density) { 10.dp.toPx().toInt() }
                        val badgePaddingVPx = with(density) { 4.dp.toPx().toInt() }
                        val dotSizePx = with(density) { 6.dp.toPx().toInt() }
                        val dotSpacerPx = with(density) { 6.dp.toPx().toInt() }

                        val badge = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                setCornerRadius(999f)
                                setColor(Color.argb(31, Color.red(statusArgb), Color.green(statusArgb), Color.blue(statusArgb)))
                            }
                            setPadding(badgePaddingHPx, badgePaddingVPx, badgePaddingHPx, badgePaddingVPx)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                        }
                        val dot = android.view.View(ctx).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(statusArgb)
                            }
                            layoutParams = LinearLayout.LayoutParams(dotSizePx, dotSizePx)
                        }
                        badge.addView(dot)
                        badge.addView(Space(ctx).apply {
                            layoutParams = LinearLayout.LayoutParams(dotSpacerPx, 1)
                        })
                        val badgeText = TextView(ctx).apply {
                            text = statusText
                            setTextColor(statusArgb)
                            textSize = statusTextSize / density.density
                            setTypeface(typeface, Typeface.BOLD)
                        }
                        badge.addView(badgeText)
                        contentLayout.addView(badge)
                        contentLayout.addView(Space(ctx).apply {
                            layoutParams = LinearLayout.LayoutParams(statusSpacerPx, 1)
                        })
                    }

                    // 右箭头
                    val chevronView = AppCompatImageView(ctx).apply {
                        setImageResource(R.drawable.ic_entry_chevron_right)
                        imageTintList = ColorStateList.valueOf(chevronArgb)
                        layoutParams = LinearLayout.LayoutParams(chevronSizePx, chevronSizePx)
                    }
                    contentLayout.addView(chevronView)

                    addView(contentLayout)
                }.also { cardView = it }
            },
            update = { view ->
                view.setRadius(radiusPx)
                view.setCardBackgroundColor(ColorStateList.valueOf(cardArgb))
                view.setStrokeColor(strokeColor.toArgb())
                view.setStrokeWidth(strokePx.toInt())

                val contentLayout = view.getChildAt(0) as? LinearLayout
                contentLayout?.let { row ->
                    // 图标背景 + 图标
                    val iconBg = row.getChildAt(0) as? LinearLayout
                    (iconBg?.getChildAt(0) as? AppCompatImageView)?.setImageResource(iconRes)
                    iconBg?.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setCornerRadius(iconCornerPx.toFloat())
                        setColor(iconBgArgb)
                    }
                    (iconBg?.getChildAt(0) as? AppCompatImageView)?.imageTintList =
                        ColorStateList.valueOf(iconTintArgb)

                    // 文本列（index 2: 标题/副标题）
                    val textColumn = row.getChildAt(2) as? LinearLayout
                    (textColumn?.getChildAt(0) as? TextView)?.apply {
                        text = title
                        setTextColor(titleArgb)
                    }
                    (textColumn?.getChildAt(2) as? TextView)?.apply {
                        text = subtitle
                        setTextColor(subtitleArgb)
                    }

                    // 箭头（最后一个子 View）
                    val lastIdx = row.childCount - 1
                    (row.getChildAt(lastIdx) as? AppCompatImageView)?.imageTintList =
                        ColorStateList.valueOf(chevronArgb)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

internal val GlassIconControlSize = 44.dp

@Composable
fun GlassIconButton(
    hazeState: HazeState,
    @DrawableRes iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconSize: Dp = 18.dp,
    iconOffsetX: Dp = 0.dp
) {
    GlassSurface(
        hazeState = hazeState,
        modifier = modifier.size(GlassIconControlSize),
        shape = CircleShape,
        contentAlignment = Alignment.Center,
        interactive = enabled
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.34f)
                },
                modifier = Modifier.size(iconSize).offset(x = iconOffsetX)
            )
        }
    }
}

/**
 * 二级界面左上角圆形返回按钮（angle-left 图标）
 */
@Composable
fun CircularBackButton(
    hazeState: HazeState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassIconButton(
        hazeState = hazeState,
        iconRes = R.drawable.ic_angle_left,
        contentDescription = "返回",
        onClick = onBack,
        modifier = modifier,
        iconSize = 16.dp,
        iconOffsetX = (-1).dp
    )
}

/** 连续阻尼函数：拖得越远阻力越大，但不会在阈值处突然停止。 */
internal fun rubberBandOffset(distance: Float, limit: Float): Float {
    if (distance == 0f || limit <= 0f) return 0f
    val magnitude = abs(distance)
    return sign(distance) * limit * magnitude / (magnitude + limit)
}

/** 分段滑块在两端之外继续保留渐进阻尼，避免硬裁切。 */
internal fun resistedSelectorPosition(rawPosition: Float, maxIndex: Float): Float = when {
    rawPosition < 0f -> -rubberBandOffset(-rawPosition, 0.42f)
    rawPosition > maxIndex -> maxIndex + rubberBandOffset(rawPosition - maxIndex, 0.42f)
    else -> rawPosition
}

/** 仅供 dp → px 转换的便捷扩展 */
private fun androidx.compose.ui.unit.Dp.toIntPx(density: Float): Int =
    (this.value * density).toInt()
