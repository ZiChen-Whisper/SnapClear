package com.snapclear.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 悬浮毛玻璃胶囊底部导航（iOS 26/27 风格）
 *
 * - 底部居中悬浮，紧凑胶囊宽度（不占满全屏），距底部 20dp + 导航栏 inset
 * - 胶囊形状，半透明渐变背景模拟毛玻璃 + 细描边 + 阴影
 * - 激活 Tab：图标 + 文字 + 品牌色药丸背景
 * - 未激活 Tab：只显示图标，半透明
 * - 切换时药丸用 spring 弹性动画在两 Tab 间滑动
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
    val capsuleWidthDp = 176.dp
    val tabWidthDp = capsuleWidthDp / tabs.size

    val pillOffset by animateDpAsState(
        targetValue = tabWidthDp * selectedIndex,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
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
                    elevation = 12.dp,
                    shape = RoundedCornerShape(50),
                    ambientColor = Color.Black.copy(alpha = 0.12f),
                    spotColor = Color.Black.copy(alpha = 0.18f)
                )
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                        )
                    )
                )
        ) {
            // 滑动药丸（激活背景）
            Box(
                modifier = Modifier
                    .offset(x = pillOffset)
                    .width(tabWidthDp)
                    .height(56.dp)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary
                            )
                        )
                    )
            )

            // Tab 内容行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = index == selectedIndex
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onTabSelected(index) },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = if (isSelected)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.size(22.dp)
                        )
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = tab.label,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

data class CapsuleTab(
    val icon: ImageVector,
    val label: String
)

/** 主页 Tab */
val HomeTab = CapsuleTab(icon = Icons.Default.Home, label = "主页")

/** 管理 Tab */
val ManageTab = CapsuleTab(icon = Icons.Default.Apps, label = "管理")
