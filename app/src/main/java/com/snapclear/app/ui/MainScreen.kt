package com.snapclear.app.ui

import android.graphics.Typeface
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.snapclear.app.R
import com.snapclear.app.screenshot.ScreenshotItem
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    isMonitoring: Boolean, allPermissionsGranted: Boolean,
    permissionStates: Map<String, Boolean>, exactAlarmGranted: Boolean,
    batteryOptimizationExempt: Boolean, promotedNotificationsGranted: Boolean,
    screenshotAccessibilityEnabled: Boolean, forceLightMode: Boolean,
    screenshotViewMode: ScreenshotViewMode, homeScreenshots: List<ScreenshotItem>,
    recentPageScreenshots: List<ScreenshotItem>, currentScreenshotPage: Int,
    recentPageHasNext: Boolean, isLoadingScreenshotPage: Boolean,
    onScreenshotViewModeChange: (ScreenshotViewMode) -> Unit,
    onForceLightModeChange: (Boolean) -> Unit,
    onScreenshotPageSelected: (Int) -> Unit, onToggleMonitoring: () -> Unit,
    onCopyDeleteScreenshot: (Uri) -> Unit, onDeleteScreenshot: (Uri) -> Unit,
    onScreenshotClick: (Uri, View) -> Unit, onOpenAbout: (View) -> Unit,
    onRequestPermission: (String) -> Unit,
    onOpenAppSettings: () -> Unit, onOpenExactAlarmSettings: () -> Unit,
    onRequestBatteryOptimization: () -> Unit, onOpenOppoBackgroundSettings: () -> Unit,
    onOpenScreenshotAccessibilitySettings: () -> Unit,
    onOpenPromotedNotificationsSettings: () -> Unit, modifier: Modifier = Modifier
) {
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    // Haze 内部保留当前颜色空间的渲染资源，明暗主题切换时必须重建。
    val hazeState = remember(forceLightMode) { HazeState() }
    Box(modifier.fillMaxSize()) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().haze(hazeState)) { page ->
            when (page) {
                0 -> HomePage(isMonitoring, allPermissionsGranted, homeScreenshots, screenshotViewMode, onScreenshotViewModeChange, onToggleMonitoring, onCopyDeleteScreenshot, onDeleteScreenshot, onScreenshotClick, onOpenAbout)
                1 -> RecentPage(recentPageScreenshots, screenshotViewMode, currentScreenshotPage, recentPageHasNext, isLoadingScreenshotPage, onScreenshotViewModeChange, onScreenshotPageSelected, onCopyDeleteScreenshot, onDeleteScreenshot, onScreenshotClick)
                else -> PermissionsScreen(permissionStates, exactAlarmGranted, batteryOptimizationExempt, promotedNotificationsGranted, screenshotAccessibilityEnabled, forceLightMode, onForceLightModeChange, onRequestPermission, onOpenAppSettings, onOpenExactAlarmSettings, onRequestBatteryOptimization, onOpenOppoBackgroundSettings, onOpenScreenshotAccessibilitySettings, onOpenPromotedNotificationsSettings)
            }
        }
        FloatingCapsuleBottomBar(listOf(HomeTab, RecentTab, PermissionTab), pager.currentPage,
            onTabSelected = { scope.launch { pager.animateScrollToPage(it) } },
            permissionWarning = !allPermissionsGranted, hazeState = hazeState,
            modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable private fun HomePage(monitoring: Boolean, allGranted: Boolean, screenshots: List<ScreenshotItem>, mode: ScreenshotViewMode,
    onMode: (ScreenshotViewMode) -> Unit, onToggle: () -> Unit, onCopy: (Uri) -> Unit, onDelete: (Uri) -> Unit,
    onClick: (Uri, View) -> Unit, onOpenAbout: (View) -> Unit) {
    val shown = screenshots.take(10)
    val cardState = rememberLazyGridState()
    val listState = rememberLazyGridState()
    val background = MaterialTheme.colorScheme.background
    val pageHazeState = remember(background) { HazeState() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize().haze(pageHazeState)) {
        AnimatedScreenshotGrid(
            mode = mode,
            cardState = cardState,
            listState = listState,
            topPadding = 184.dp,
            items = shown,
            onCopy = onCopy,
            onDelete = onDelete,
            onClick = onClick,
            emptyText = "最近 30 天暂无截图"
        )
        }
        ImmersiveTopBar(height = 184.dp) { Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            PageHeader("主页", "SnapClear · 截图一键清理", { MonitoringCapsule(monitoring, allGranted, pageHazeState, onToggle) }, onSubtitleClick = onOpenAbout)
            SectionHeader("最近截图", "仅显示近10张截图", mode, pageHazeState, onMode)
        } }
    }
}

@Composable private fun RecentPage(items: List<ScreenshotItem>, mode: ScreenshotViewMode, page: Int, hasNext: Boolean, loading: Boolean,
    onMode: (ScreenshotViewMode) -> Unit, onPage: (Int) -> Unit, onCopy: (Uri) -> Unit, onDelete: (Uri) -> Unit, onClick: (Uri, View) -> Unit) {
    val cardState = rememberLazyGridState()
    val listState = rememberLazyGridState()
    val background = MaterialTheme.colorScheme.background
    val pageHazeState = remember(background) { HazeState() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize().haze(pageHazeState)) {
        AnimatedScreenshotGrid(mode, cardState, listState, topPadding = 102.dp, items = items,
            onCopy = onCopy, onDelete = onDelete, onClick = onClick, emptyText = if (loading) "正在加载" else "本页暂无截图",
            footer = { PagerPanel(items, page, hasNext, loading, onPage, onClick) })
        }
        ImmersiveTopBar { PageHeader("最近截图", "浏览或操作近30天的所有截图", { ViewModeSwitch(mode, pageHazeState, onMode) }) }
    }
}

@Composable
private fun AnimatedScreenshotGrid(
    mode: ScreenshotViewMode,
    cardState: LazyGridState,
    listState: LazyGridState,
    topPadding: androidx.compose.ui.unit.Dp,
    items: List<ScreenshotItem>,
    onCopy: (Uri) -> Unit,
    onDelete: (Uri) -> Unit,
    onClick: (Uri, View) -> Unit,
    emptyText: String,
    footer: (@Composable () -> Unit)? = null
) {
    AnimatedContent(
        targetState = mode,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val movesLeft = targetState.ordinal > initialState.ordinal
            val enter = slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { width ->
                if (movesLeft) width else -width
            } + fadeIn(tween(160))
            val exit = slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { width ->
                if (movesLeft) -width else width
            } + fadeOut(tween(140))
            (enter togetherWith exit).using(SizeTransform(clip = false))
        },
        label = "screenshotViewMode"
    ) { animatedMode ->
        ScreenshotGrid(
            mode = animatedMode,
            state = if (animatedMode == ScreenshotViewMode.CARD) cardState else listState,
            topPadding = topPadding,
            header = {},
            items = items,
            onCopy = onCopy,
            onDelete = onDelete,
            onClick = onClick,
            emptyText = emptyText,
            footer = footer
        )
    }
}

@Composable private fun ScreenshotGrid(mode: ScreenshotViewMode, state: LazyGridState = rememberLazyGridState(), header: @Composable () -> Unit,
    items: List<ScreenshotItem>, onCopy: (Uri) -> Unit, onDelete: (Uri) -> Unit, onClick: (Uri, View) -> Unit, emptyText: String,
    topPadding: androidx.compose.ui.unit.Dp = 8.dp, footer: (@Composable () -> Unit)? = null) {
    LazyVerticalGrid(columns = GridCells.Fixed(if (mode == ScreenshotViewMode.CARD) 2 else 1), state = state,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topPadding, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { header() } }
        if (items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        else items(items, key = { it.id }, contentType = { mode }) { item -> ScreenshotCard(item, mode, onCopy, onDelete, onClick) }
        footer?.let { item(span = { GridItemSpan(maxLineSpan) }) { it() } }
    }
}

@Composable fun PageHeader(
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
    onSubtitleClick: ((View) -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            if (onSubtitleClick == null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val entry = subtitle.substringBefore(" · ")
                val suffix = subtitle.removePrefix(entry)
                val entryColor = MaterialTheme.colorScheme.primary.toArgb()
                val entryTextSize = MaterialTheme.typography.bodySmall.fontSize.value
                val openAbout = onSubtitleClick
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AndroidView(
                        factory = { context ->
                            TextView(context).apply {
                                includeFontPadding = false
                                isClickable = true
                                contentDescription = "关于 SnapClear"
                            }
                        },
                        update = { view ->
                            view.text = entry
                            view.setTextColor(entryColor)
                            view.textSize = entryTextSize
                            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                            view.setOnClickListener { openAbout(view) }
                        },
                        modifier = Modifier.wrapContentSize()
                    )
                    Text(suffix, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        trailing?.invoke()
    }
}

@Composable fun ImmersiveTopBar(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 132.dp, content: @Composable () -> Unit) {
    val surface = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxWidth().height(height)
        .background(Brush.verticalGradient(*arrayOf(
            0f to surface,
            .20f to surface,
            .32f to surface.copy(alpha = .97f),
            .44f to surface.copy(alpha = .84f),
            .55f to surface.copy(alpha = .60f),
            .65f to surface.copy(alpha = .35f),
            .72f to surface.copy(alpha = .16f),
            .76f to surface.copy(alpha = .05f),
            .78f to surface.copy(alpha = 0f),
            1f to surface.copy(alpha = 0f)
        )))
        .statusBarsPadding().padding(horizontal = 20.dp, vertical = 7.dp)) { content() }
}

@Composable private fun SectionHeader(title: String, info: String, mode: ScreenshotViewMode, hazeState: HazeState, onMode: (ScreenshotViewMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface); Text(info, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; ViewModeSwitch(mode, hazeState, onMode) }
}

@Composable private fun ViewModeSwitch(mode: ScreenshotViewMode, hazeState: HazeState, onMode: (ScreenshotViewMode) -> Unit) {
    val options = listOf(
        ScreenshotViewMode.CARD to R.drawable.ic_view_cards,
        ScreenshotViewMode.LIST to R.drawable.ic_view_list
    )
    val selectedIndex = mode.ordinal
    val width = 92.dp
    val itemWidth = width / options.size
    val itemWidthPx = with(LocalDensity.current) { itemWidth.toPx() }
    var dragPosition by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    var dragScaleX by remember { mutableFloatStateOf(1f) }
    var dragScaleY by remember { mutableFloatStateOf(1f) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(selectedIndex) {
        if (!isDragging) dragPosition = selectedIndex.toFloat()
    }
    val indicatorAnimation = if (isDragging) snap<Float>() else spring<Float>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
    val indicatorPosition by animateFloatAsState(dragPosition, indicatorAnimation, label = "viewModePosition")
    val indicatorScaleX by animateFloatAsState(
        if (isDragging) dragScaleX else 1f,
        indicatorAnimation,
        label = "viewModeScaleX"
    )
    val indicatorScaleY by animateFloatAsState(
        if (isDragging) dragScaleY else 1f,
        indicatorAnimation,
        label = "viewModeScaleY"
    )

    GlassSurface(
        hazeState = hazeState,
        modifier = Modifier.width(width).height(40.dp),
        shape = RoundedCornerShape(20.dp),
        interactive = false,
        clipContent = false
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(options.size, selectedIndex) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var lastX = down.position.x
                        var dragged = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            lastX = change.position.x
                            if (abs(lastX - down.position.x) > viewConfiguration.touchSlop) dragged = true
                            if (dragged && change.pressed) {
                                val rawPosition = lastX / itemWidthPx - 0.5f
                                val resisted = resistedSelectorPosition(rawPosition, options.lastIndex.toFloat())
                                val bridge = (abs(resisted - resisted.roundToInt()) * 2f).coerceAtMost(1f)
                                isDragging = true
                                dragPosition = resisted
                                dragScaleX = 1f + bridge * 0.16f
                                dragScaleY = 1f - bridge * 0.05f
                                change.consume()
                            }
                            if (!change.pressed) break
                        }
                        val target = (lastX / itemWidthPx).toInt().coerceIn(0, options.lastIndex)
                        dragPosition = target.toFloat()
                        isDragging = false
                        if (dragged) onMode(options[target].first)
                    }
                }
        ) {
            val visualIndex = indicatorPosition.roundToInt().coerceIn(0, options.lastIndex)
            Box(
                Modifier
                    .width(itemWidth)
                    .fillMaxHeight()
                    .zIndex(1f)
                    .graphicsLayer {
                        translationX = itemWidthPx * indicatorPosition
                        scaleX = indicatorScaleX
                        scaleY = indicatorScaleY
                    }
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(options[visualIndex].second),
                    if (options[visualIndex].first == ScreenshotViewMode.CARD) "卡片视图" else "列表视图",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Row(Modifier.fillMaxSize()) { options.forEach { (value, icon) ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onMode(value) },
                    contentAlignment = Alignment.Center
                ) { Icon(painterResource(icon), if (value == ScreenshotViewMode.CARD) "卡片视图" else "列表视图", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
            } }
        }
    }
}

@Composable private fun MonitoringCapsule(monitoring: Boolean, allGranted: Boolean, hazeState: HazeState, onToggle: () -> Unit) {
    GlassSurface(hazeState, Modifier.width(116.dp).height(42.dp), RoundedCornerShape(21.dp)) {
        Row(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle
            ).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                painterResource(if (monitoring) R.drawable.ic_pause else R.drawable.ic_play_solid),
                if (monitoring) "暂停监听" else "开始监听",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text(if (monitoring) "监听中" else "已停止", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            if (!allGranted) {
                Spacer(Modifier.width(5.dp))
                Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

@Composable private fun PagerPanel(items: List<ScreenshotItem>, page: Int, hasNext: Boolean, loading: Boolean, onPage: (Int) -> Unit, onClick: (Uri, View) -> Unit) {
    val controlsHazeState = remember { HazeState() }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(formatScreenshotRange(items), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (items.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { lazyItems(items, key = { it.id }) { item -> ScreenshotPagerThumbnail(item, onClick, Modifier.size(56.dp, 72.dp)) } }
        Box(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.matchParentSize().haze(controlsHazeState)) {
                Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surface))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                GlassIconButton(
                    hazeState = controlsHazeState,
                    iconRes = R.drawable.ic_angle_left,
                    contentDescription = "上一页",
                    enabled = page > 0 && !loading,
                    onClick = { onPage(page - 1) },
                    iconSize = 17.dp
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = "第 ${page + 1} 页",
                    modifier = Modifier.width(84.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                Spacer(Modifier.width(14.dp))
                GlassIconButton(
                    hazeState = controlsHazeState,
                    iconRes = R.drawable.ic_angle_right,
                    contentDescription = "下一页",
                    enabled = hasNext && !loading,
                    onClick = { onPage(page + 1) },
                    iconSize = 17.dp
                )
            }
        }
    }
}
