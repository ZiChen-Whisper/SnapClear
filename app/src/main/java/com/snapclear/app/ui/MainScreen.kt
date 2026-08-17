package com.snapclear.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapclear.app.R
import com.snapclear.app.screenshot.ScreenshotItem
import com.snapclear.app.ui.theme.StatusDenied
import com.snapclear.app.ui.theme.StatusGranted

/**
 * 主界面（双 Tab + 悬浮毛玻璃胶囊底部导航）
 *
 * - 主页 Tab：监听卡片 + 最近截图网格
 * - 管理 Tab：权限管理 / 诊断面板入口卡片
 * - Tab 切换：AnimatedContent 淡入淡出 + 胶囊药丸 spring 滑动
 */
@Composable
fun MainScreen(
    isMonitoring: Boolean,
    allPermissionsGranted: Boolean,
    permissionGrantedCount: Int,
    permissionTotalCount: Int,
    homeScreenshots: List<ScreenshotItem>,
    recentPageScreenshots: List<ScreenshotItem>,
    currentScreenshotPage: Int,
    recentPageHasNext: Boolean,
    isLoadingScreenshotPage: Boolean,
    onScreenshotPageSelected: (Int) -> Unit,
    onToggleMonitoring: () -> Unit,
    onCopyDeleteScreenshot: (Uri) -> Unit,
    onDeleteScreenshot: (Uri) -> Unit,
    onScreenshotClick: (Uri, android.view.View) -> Unit,
    permissionsIntent: Intent,
    diagnosticsIntent: Intent,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(tween(220)).togetherWith(fadeOut(tween(180)))
                },
                label = "tabSwitch"
            ) { tab ->
                when (tab) {
                    0 -> HomeTabContent(
                        isMonitoring = isMonitoring,
                        allGranted = allPermissionsGranted,
                        screenshots = homeScreenshots,
                        onToggle = onToggleMonitoring,
                        onCopyDelete = onCopyDeleteScreenshot,
                        onDelete = onDeleteScreenshot,
                        onScreenshotClick = onScreenshotClick
                    )
                    1 -> RecentScreenshotsTabContent(
                        screenshots = recentPageScreenshots,
                        currentPage = currentScreenshotPage,
                        hasNext = recentPageHasNext,
                        isLoading = isLoadingScreenshotPage,
                        onPageSelected = onScreenshotPageSelected,
                        onCopyDelete = onCopyDeleteScreenshot,
                        onDelete = onDeleteScreenshot,
                        onScreenshotClick = onScreenshotClick
                    )
                    2 -> ManageTabContent(
                        allPermissionsGranted = allPermissionsGranted,
                        permissionGrantedCount = permissionGrantedCount,
                        permissionTotalCount = permissionTotalCount,
                        permissionsIntent = permissionsIntent,
                        diagnosticsIntent = diagnosticsIntent
                    )
                }
            }

            FloatingCapsuleBottomBar(
                tabs = remember { listOf(HomeTab, RecentTab, ManageTab) },
                selectedIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/**
 * 主页 Tab：监听卡片 + 最近截图网格
 *
 * 使用 LazyVerticalGrid 直接承载两列截图，减少 Row 包装与整行重组，
 * 顶部状态栏 inset + 底部胶囊导航留白。
 */
@Composable
private fun HomeTabContent(
    isMonitoring: Boolean,
    allGranted: Boolean,
    screenshots: List<ScreenshotItem>,
    onToggle: () -> Unit,
    onCopyDelete: (Uri) -> Unit,
    onDelete: (Uri) -> Unit,
    onScreenshotClick: (Uri, android.view.View) -> Unit
) {
    val homeScreenshotCount = minOf(10, screenshots.size)
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 8.dp,
            bottom = 120.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            PageHeader(
                title = "主页",
                subtitle = "SnapClear · 截图一键清理",
                trailing = {
                    StatusBadge(
                        text = if (isMonitoring) "监听中" else "已停止",
                        color = if (isMonitoring) StatusGranted else MaterialTheme.colorScheme.outline
                    )
                }
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            MonitoringCard(
                isMonitoring = isMonitoring,
                allGranted = allGranted,
                onToggle = onToggle
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "最近截图",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (screenshots.size > 10) "最近 10 张" else "$homeScreenshotCount 张",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (homeScreenshotCount == 0) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyScreenshotsState() }
        } else {
            items(
                items = screenshots,
                key = { it.id },
                contentType = { "screenshot_card" }
            ) { item ->
                ScreenshotCard(
                    item = item,
                    onCopyDelete = { onCopyDelete(item.uri) },
                    onDelete = { onDelete(item.uri) },
                    onClick = { uri, view -> onScreenshotClick(uri, view) }
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { TipCard() }
    }
}

/** 最近截图 Tab：显式分页，内存与界面中只保留当前页。 */
@Composable
private fun RecentScreenshotsTabContent(
    screenshots: List<ScreenshotItem>,
    currentPage: Int,
    hasNext: Boolean,
    isLoading: Boolean,
    onPageSelected: (Int) -> Unit,
    onCopyDelete: (Uri) -> Unit,
    onDelete: (Uri) -> Unit,
    onScreenshotClick: (Uri, android.view.View) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 8.dp,
            bottom = 120.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "recent_header", span = { GridItemSpan(maxLineSpan) }) {
            PageHeader(
                title = "最近截图",
                subtitle = "第 ${currentPage + 1} 页 · 本页 ${screenshots.size} 张"
            )
        }

        if (screenshots.isEmpty() && !isLoading) {
            item(key = "recent_empty", span = { GridItemSpan(maxLineSpan) }) {
                EmptyScreenshotsState()
            }
        } else {
            items(
                items = screenshots,
                key = { it.id },
                contentType = { "screenshot_card" }
            ) { item ->
                ScreenshotCard(
                    item = item,
                    onCopyDelete = { onCopyDelete(item.uri) },
                    onDelete = { onDelete(item.uri) },
                    onClick = { uri, view -> onScreenshotClick(uri, view) }
                )
            }
        }

        item(key = "recent_pager", span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onPageSelected(currentPage - 1) },
                    enabled = currentPage > 0 && !isLoading,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("上一页") }
                Text(
                    text = "${currentPage + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OutlinedButton(
                    onClick = { onPageSelected(currentPage + 1) },
                    enabled = hasNext && !isLoading,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(if (isLoading) "加载中" else "下一页") }
            }
        }
    }
}

/**
 * 管理 Tab：权限管理 + 诊断面板入口卡片
 */
@Composable
private fun ManageTabContent(
    allPermissionsGranted: Boolean,
    permissionGrantedCount: Int,
    permissionTotalCount: Int,
    permissionsIntent: Intent,
    diagnosticsIntent: Intent
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        PageHeader(title = "管理", subtitle = "权限与诊断")

        SeamlessEntryCard(
            iconRes = R.drawable.ic_entry_shield,
            title = "权限管理",
            subtitle = if (allPermissionsGranted)
                "全部权限已就绪"
            else
                "$permissionGrantedCount / $permissionTotalCount 项已授权",
            intent = permissionsIntent,
            statusText = if (allPermissionsGranted) "已就绪" else "待授权",
            statusColor = if (allPermissionsGranted) StatusGranted else StatusDenied
        )

        SeamlessEntryCard(
            iconRes = R.drawable.ic_entry_bug,
            title = "诊断面板",
            subtitle = "查看检测管线状态与事件日志",
            intent = diagnosticsIntent
        )

        TipCard()

        Spacer(modifier = Modifier.height(8.dp))
        Spacer(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
private fun PageHeader(
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun MonitoringCard(
    isMonitoring: Boolean,
    allGranted: Boolean,
    onToggle: () -> Unit
) {
    val bgColor = if (isMonitoring)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant
    val onBgColor = if (isMonitoring)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(bgColor)
            .padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isMonitoring) Icons.Default.Verified else Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (isMonitoring) StatusGranted else onBgColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isMonitoring) "正在监听截图" else "监听已停止",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = onBgColor
                    )
                    Text(
                        text = if (isMonitoring)
                            "新截图将以流体云提醒，可一键拷贝并删除"
                        else
                            "开启后自动检测新截图并提醒",
                        style = MaterialTheme.typography.bodySmall,
                        color = onBgColor.copy(alpha = 0.8f)
                    )
                }
            }

            if (!allGranted) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "部分权限未授予，可能影响后台检测",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isMonitoring) {
                OutlinedButton(
                    onClick = onToggle,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("停止监听")
                }
            } else {
                Button(
                    onClick = onToggle,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("开始监听截图")
                }
            }
        }
    }
}

@Composable
private fun TipCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.ContentCut,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "检测到新截图后，流体云会展示 60 秒倒计时，超时自动关闭。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyScreenshotsState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "近 30 天暂无截图",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = "截图后此处将自动展示",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}
