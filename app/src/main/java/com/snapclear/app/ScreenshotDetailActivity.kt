package com.snapclear.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snapclear.app.clipboard.ClipboardHelper
import com.snapclear.app.notification.NotificationHelper
import com.snapclear.app.screenshot.ScreenshotEvents
import com.snapclear.app.screenshot.ScreenshotItem
import com.snapclear.app.screenshot.ScreenshotRepository
import com.snapclear.app.ui.ScreenshotDetailScreen
import com.snapclear.app.ui.theme.SnapClearTheme

/**
 * 截图详情二级 Activity
 *
 * 由主页「最近截图」卡片经 OPPO 无缝动画启动，展示：
 * - 截图大图
 * - 文件信息（文件名、路径、时间、尺寸、大小）
 * - 「拷贝并删除」「不再提示」两个操作
 *
 * 「拷贝并删除」：复制到剪贴板 + 移入系统回收站 + 刷新主页列表 + finish
 * 「不再提示」：直接 finish（截图仍保留在最近截图面板）
 */
class ScreenshotDetailActivity : ComponentActivity() {

    private var screenshotItem by mutableStateOf<ScreenshotItem?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        val uriString = intent.getStringExtra(EXTRA_SCREENSHOT_URI)
        val uri = if (uriString != null) Uri.parse(uriString) else null

        // 后台查询截图详细信息
        if (uri != null) {
            Thread {
                val item = ScreenshotRepository.queryByUri(this, uri)
                screenshotItem = item
            }.start()
        }

        setContent {
            SnapClearTheme {
                ScreenshotDetailScreen(
                    item = screenshotItem,
                    onBack = { finish() },
                    onCopyDelete = { item ->
                        ClipboardHelper.copyAndDelete(this, item.uri)
                        ScreenshotEvents.notifyScreenshotListChanged()
                        finish()
                    },
                    onIgnore = {
                        // 不再提示：直接返回，截图仍保留在最近截图面板
                        finish()
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_SCREENSHOT_URI = "extra_screenshot_uri"

        fun createIntent(context: android.content.Context, uri: Uri): Intent {
            return Intent(context, ScreenshotDetailActivity::class.java).apply {
                putExtra(EXTRA_SCREENSHOT_URI, uri.toString())
            }
        }
    }
}
