package com.snapclear.app.notification

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.snapclear.app.R
import com.snapclear.app.clipboard.ClipboardHelper
import com.snapclear.app.screenshot.ScreenshotEvents

/**
 * 通知“拷贝并删除”的透明中转页。
 *
 * Clipboard + MediaStore 删除确认需要前台 Activity 上下文；该 Activity 不绘制
 * 应用界面、不进入最近任务，只负责执行操作并立即退出。
 */
class NotificationActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val notificationId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIFICATION_ID, 0)
        val uriString = intent.getStringExtra(NotificationHelper.EXTRA_IMAGE_URI)

        if (uriString != null) {
            ClipboardHelper.copyAndDelete(this, Uri.parse(uriString))
        } else {
            Toast.makeText(this, R.string.toast_copy_failed, Toast.LENGTH_SHORT).show()
        }

        if (notificationId > 0) {
            NotificationHelper.cancelNotification(this, notificationId)
        }
        ScreenshotEvents.notifyScreenshotListChanged()

        finish()
        overridePendingTransition(0, 0)
    }
}
