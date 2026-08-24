package com.snapclear.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import com.snapclear.app.ui.AboutScreen
import com.snapclear.app.ui.theme.SnapClearTheme

/** 与截图详情同层级的项目介绍页面，由主页 SnapClear 文字经 OPPO 无缝动画启动。 */
class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        setContent {
            val forceLight = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_FORCE_LIGHT_MODE, false)
            val systemDarkTheme = isSystemInDarkTheme()
            SnapClearTheme(darkTheme = systemDarkTheme && !forceLight) {
                AboutScreen(onBack = { finish() })
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "snapclear_prefs"
        const val PREF_FORCE_LIGHT_MODE = "force_light_mode"
    }
}
