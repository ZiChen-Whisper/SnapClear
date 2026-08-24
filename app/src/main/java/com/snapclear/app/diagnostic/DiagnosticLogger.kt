package com.snapclear.app.diagnostic

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

/**
 * 诊断日志缓冲区
 *
 * 在内存中保存最近的事件，并同步输出到 logcat。
 * 线程安全，可在任意线程写入。
 *
 * 日志同时输出到 Logcat（tag = "SnapClear Diag"），
 * 确保即使应用被杀，logcat 也有记录。
 */
object DiagnosticLogger {

    private const val MAX_EVENTS = 100
    private val dateFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    private val events: ArrayDeque<DiagnosticEvent> = ArrayDeque(MAX_EVENTS)

    // 同步锁
    private val lock = Any()

    /** 监听器：UI 注册后，新事件到来时触发刷新 */
    @Volatile
    var listener: (() -> Unit)? = null

    fun log(type: DiagnosticEventType, message: String) {
        val event = DiagnosticEvent(
            timestamp = System.currentTimeMillis(),
            timeStr = dateFormat.format(Instant.now()),
            type = type,
            message = message
        )
        synchronized(lock) {
            if (events.size >= MAX_EVENTS) {
                events.pollFirst()
            }
            events.addLast(event)
        }

        // 同步输出到 logcat
        android.util.Log.d("SnapClear Diag", "[${event.type.tag}] ${event.message}")

        // 通知 UI 刷新
        listener?.invoke()
    }

    /** 获取所有日志（按时间正序，最新在最后） */
    fun getEvents(): List<DiagnosticEvent> {
        synchronized(lock) {
            return events.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            events.clear()
        }
        listener?.invoke()
    }
}

enum class DiagnosticEventType(val tag: String, val label: String) {
    SERVICE_START("SRV", "服务启动"),
    SERVICE_STOP("SRV", "服务停止"),
    POLL("POLL", "轮询"),
    DETECT("DET", "检测"),
    SCREENSHOT("SHOT", "截图发现"),
    NOTIFY("NOTIFY", "通知发送"),
    FILE_OBS("FILE", "FileObserver"),
    CONTENT_OBS("COBS", "ContentObserver"),
    ERROR("ERR", "错误"),
    WARNING("WARN", "警告"),
    INFO("INFO", "信息"),
    TEST("TEST", "测试")
}

data class DiagnosticEvent(
    val timestamp: Long,
    val timeStr: String,
    val type: DiagnosticEventType,
    val message: String
)
