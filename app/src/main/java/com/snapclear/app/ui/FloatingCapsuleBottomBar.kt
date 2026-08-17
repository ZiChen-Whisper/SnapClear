package com.snapclear.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.annotation.DrawableRes
import com.snapclear.app.R

/**
 * 悬浮胶囊底部导航。
 *
 * - 底部居中悬浮，紧凑胶囊宽度（不占满全屏），距底部 20dp + 导航栏 inset
 * - 不透明白色背景与轻微阴影
 * - 纯图标表达，选中/未选中使用对应 SVG 矢量资源
 * - 滑块使用短促补间动画，不回弹
 */
@Composable
fun FloatingCapsuleBottomBar(
    tabs: List<CapsuleTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    require(tabs.size >= 2) { "CapsuleBar needs at least 2 tabs" }

    // iOS 风格紧凑底栏：胶囊宽度固定，不占满全屏
    val capsuleWidthDp = 192.dp
    val tabWidthDp = capsuleWidthDp / tabs.size

    val pillOffset by animateDpAsState(
        targetValue = tabWidthDp * selectedIndex,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "pillOffset"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        // 胶囊容器
        Box(
            modifier = Modifier
                .width(capsuleWidthDp)
                .height(56.dp)
                .clip(RoundedCornerShape(50))
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(50),
                    ambientColor = Color.Black.copy(alpha = 0.06f),
                    spotColor = Color.Black.copy(alpha = 0.10f)
                )
                .background(Color.White)
        ) {
            // 滑动药丸（激活背景）
            Box(
                modifier = Modifier
                    .offset(x = pillOffset)
                    .width(tabWidthDp)
                    .height(56.dp)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF0D9488))
            )

            // Tab 内容行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = index == selectedIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onTabSelected(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isSelected) tab.selectedIconRes else tab.unselectedIconRes
                            ),
                            contentDescription = tab.label,
                            tint = if (isSelected) Color.White else Color(0xFF667085),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

data class CapsuleTab(
    @param:DrawableRes val selectedIconRes: Int,
    @param:DrawableRes val unselectedIconRes: Int,
    val label: String
)

/** 主页 Tab */
val HomeTab = CapsuleTab(R.drawable.ic_tab_home_solid, R.drawable.ic_tab_home_regular, "主页")

/** 最近截图 Tab */
val RecentTab = CapsuleTab(R.drawable.ic_tab_picture_solid, R.drawable.ic_tab_picture_regular, "最近截图")

/** 管理 Tab */
val ManageTab = CapsuleTab(R.drawable.ic_tab_settings_solid, R.drawable.ic_tab_settings_regular, "管理")
