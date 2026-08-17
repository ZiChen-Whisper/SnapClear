package com.snapclear.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View

/**
 * OPPO View 无缝过渡动画接入助手
 *
 * 参考：https://open.oppomobile.com/documentation/page/info?id=13772
 *
 * 仅 ColorOS 16.1+ 且动效等级 B+ 以上机型支持；其它设备自动回退到普通 startActivity。
 * 为避免在非 OPPO 设备上触发 NoClassDefFoundError，这里全程使用反射访问
 * com.oplus.animation.OplusViewSeamless，不引入 compileOnly 依赖。
 *
 * 用法：在点击卡片需要启动下一个 Activity 之前调用 [prepare]，
 * 若返回 true 则使用返回的 Bundle 调用 startActivity(intent, bundle)，
 * 否则调用普通 startActivity(intent)。
 */
object OplusSeamlessHelper {

    private const val TAG = "SnapClear Seamless"
    private const val CLASS_NAME = "com.oplus.animation.OplusViewSeamless"
    private const val CALLBACK_NAME = "com.oplus.animation.OplusViewSeamless\$AnimationCallback"

    /**
     * 准备一次无缝过渡动画。
     *
     * @param view 参与动画的卡片 View（非空，需已 attach 到窗口）
     * @param activity 当前 Activity（必须是 Activity 类型 context）
     * @param cornerRadiusPx 卡片圆角，像素值（带圆角的 view 必传）
     * @param colorInt 卡片背景色（具体色值，非资源 id），用于框架取色错误时兜底
     * @return 非空 Bundle 表示支持无缝动画，需传入 startActivity；null 表示不支持，调用方走普通启动
     */
    fun prepare(
        view: View,
        activity: Activity,
        cornerRadiusPx: Float,
        colorInt: Int
    ): Bundle? {
        return try {
            val clazz = Class.forName(CLASS_NAME)
            val callbackClass = Class.forName(CALLBACK_NAME)

            // 读取常量字段名
            val openKey = clazz.getField("VIEW_SEAMLESS_OPEN").get(null) as String
            val radiusKey = clazz.getField("BUNDLE_RADIUS").get(null) as String
            val colorKey = clazz.getField("BUNDLE_COLOR").get(null) as String

            val bundle = Bundle()
            bundle.putBoolean(openKey, true)
            bundle.putFloat(radiusKey, cornerRadiusPx)
            bundle.putInt(colorKey, colorInt)

            // 版本号校验：仅 ColorOS 16.0/16.1 以上支持
            var version = -1
            var base16 = 0
            try {
                version = clazz.getMethod("getVersion").invoke(null) as Int
                base16 = clazz.getField("OS_16_0_BASE").get(null) as Int
            } catch (_: Throwable) {
                // 字段缺失时忽略，仅以 setSeamlessView 返回值为准
            }

            val setSeamless = clazz.getMethod(
                "setSeamlessView",
                View::class.java,
                Context::class.java,
                Bundle::class.java,
                callbackClass
            )
            val result = setSeamless.invoke(null, view, activity, bundle, null) as Boolean

            val versionOk = version <= 0 || version > base16
            if (result && versionOk) {
                Log.d(TAG, "seamless prepared, version=$version")
                bundle
            } else {
                Log.d(TAG, "seamless not supported (result=$result, version=$version)")
                null
            }
        } catch (e: Throwable) {
            // NoSuchMethodError / RuntimeException / ClassNotFoundException / NoClassDefFoundError
            Log.d(TAG, "seamless unavailable: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * 启动目标 Activity，自动选择无缝动画或普通启动。
     */
    fun startActivitySeamless(
        view: View,
        activity: Activity,
        intent: Intent,
        cornerRadiusPx: Float,
        colorInt: Int
    ) {
        val bundle = prepare(view, activity, cornerRadiusPx, colorInt)
        if (bundle != null) {
            activity.startActivity(intent, bundle)
        } else {
            activity.startActivity(intent)
        }
    }
}
