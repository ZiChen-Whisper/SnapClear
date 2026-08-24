package com.snapclear.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import com.snapclear.app.R
import dev.chrisbanes.haze.HazeState
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun FloatingCapsuleBottomBar(tabs: List<CapsuleTab>, selectedIndex: Int, onTabSelected: (Int) -> Unit,
    permissionWarning: Boolean, hazeState: HazeState, modifier: Modifier = Modifier) {
    val width = 192.dp
    val tabWidth = width / tabs.size
    val tabWidthPx = with(LocalDensity.current) { tabWidth.toPx() }
    var dragPosition by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    var dragScaleX by remember { mutableFloatStateOf(1f) }
    var dragScaleY by remember { mutableFloatStateOf(1f) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(selectedIndex) {
        if (!isDragging) dragPosition = selectedIndex.toFloat()
    }
    val indicatorAnimation = if (isDragging) snap<Float>() else spring<Float>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
    val indicatorPosition by animateFloatAsState(dragPosition, indicatorAnimation, label = "tabPosition")
    val indicatorScaleX by animateFloatAsState(
        if (isDragging) dragScaleX else 1f,
        indicatorAnimation,
        label = "tabScaleX"
    )
    val indicatorScaleY by animateFloatAsState(
        if (isDragging) dragScaleY else 1f,
        indicatorAnimation,
        label = "tabScaleY"
    )

    Box(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 20.dp), contentAlignment = Alignment.Center) {
        GlassSurface(
            hazeState = hazeState,
            modifier = Modifier.width(width).height(56.dp),
            shape = RoundedCornerShape(28.dp),
            contentAlignment = Alignment.TopStart,
            interactive = false,
            clipContent = false
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(tabs.size, selectedIndex) {
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
                                    val rawPosition = lastX / tabWidthPx - 0.5f
                                    val resisted = resistedSelectorPosition(rawPosition, tabs.lastIndex.toFloat())
                                    val bridge = (abs(resisted - resisted.roundToInt()) * 2f).coerceAtMost(1f)
                                    isDragging = true
                                    dragPosition = resisted
                                    dragScaleX = 1f + bridge * 0.18f
                                    dragScaleY = 1f - bridge * 0.055f
                                    change.consume()
                                }
                                if (!change.pressed) break
                            }
                            val target = (lastX / tabWidthPx).toInt().coerceIn(0, tabs.lastIndex)
                            dragPosition = target.toFloat()
                            isDragging = false
                            if (dragged) onTabSelected(target)
                        }
                    }
            ) {
                val visualIndex = indicatorPosition.roundToInt().coerceIn(0, tabs.lastIndex)
                Box(
                    Modifier
                        .width(tabWidth)
                        .fillMaxHeight()
                        .zIndex(1f)
                        .graphicsLayer {
                            translationX = tabWidthPx * indicatorPosition
                            scaleX = indicatorScaleX
                            scaleY = indicatorScaleY
                        }
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(tabs[visualIndex].selectedIconRes),
                        tabs[visualIndex].label,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Row(Modifier.fillMaxSize()) { tabs.forEachIndexed { index, tab ->
                    Box(Modifier.weight(1f).fillMaxHeight().clickable(remember { MutableInteractionSource() }, null) { onTabSelected(index) }, contentAlignment = Alignment.Center) {
                        Icon(painterResource(tab.unselectedIconRes), tab.label,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        if (permissionWarning && tab == PermissionTab && selectedIndex != index) Box(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 13.dp).size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                    }
                } }
            }
        }
    }
}

data class CapsuleTab(@param:DrawableRes val selectedIconRes: Int, @param:DrawableRes val unselectedIconRes: Int, val label: String)
val HomeTab = CapsuleTab(R.drawable.ic_tab_home_solid, R.drawable.ic_tab_home_regular, "主页")
val RecentTab = CapsuleTab(R.drawable.ic_tab_picture_solid, R.drawable.ic_tab_picture_regular, "最近截图")
val PermissionTab = CapsuleTab(R.drawable.ic_tab_permission_solid, R.drawable.ic_tab_permission_regular, "权限管理")
