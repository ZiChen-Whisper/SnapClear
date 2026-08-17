package com.snapclear.app.screenshot

import android.os.Handler
import android.os.Looper

/**
 * 截图事件总线
 *
 * 用于跨组件通知截图列表的变化：
 * - ScreenshotMonitorService 检测到新截图时调用 [notifyScreenshotDetected]
 * - NotificationActionReceiver 处理拷贝删除后调用 [notifyScreenshotListChanged]
 * - MainActivity 注册监听器，收到事件后刷新「最近截图」列表
 *
 * 简单的 observer 模式，不引入额外依赖。
 */
object ScreenshotEvents {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    @Synchronized
    fun register(listener: () -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun unregister(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * 通知监听器：有新截图被检测到，或截图列表可能已变化（如删除后）。
     * 在主线程触发回调。
     */
    fun notifyScreenshotDetected() {
        mainHandler.post {
            synchronized(this) {
                listeners.forEach { it.invoke() }
            }
        }
    }

    /**
     * 通知监听器：截图列表已变化（如拷贝删除后），需要刷新。
     * 别名方法，语义更明确。
     */
    fun notifyScreenshotListChanged() = notifyScreenshotDetected()
}
