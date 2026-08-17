package com.snapclear.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * 流体云通知闹钟接收器
 *
 * 两个职责（均由 AlarmManager 精确闹钟驱动，穿透 ColorOS 后台进程冻结）：
 *
 * 1. ACTION_LIVE_UPDATE_POST —— 后台发送通知：
 *    ColorOS 会冻结后台应用进程，冻结进程内直接 notify() 的通知会被延迟到
 *    应用回到前台才弹出。检测到截图时（后台场景）先调度 200ms 后的精确闹钟，
 *    本 Receiver 被系统唤醒后发送通知 —— 系统级唤醒路径不受冻结队列限制，
 *    流体云可立即弹出。
 *
 * 2. ACTION_LIVE_UPDATE_TICK —— 倒计时到期取消：
 *    倒计时显示由 SystemUI Chronometer 驱动，本 Receiver 仅在 60 秒终点
 *    唤醒一次并取消通知。旧版本遗留的提前 tick 也会被安全地收敛到终点。
 *
 * 注意：此 Receiver 是 exported=false 的内部接收器，仅接收自身发送的闹钟。
 */
class LiveUpdateTickReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val notificationId = intent.getIntExtra(NotificationHelper.EXTRA_LIVE_UPDATE_ID, -1)

        // 必须在系统给予 BroadcastReceiver 的唤醒窗口内同步完成 notify()。
        // ColorOS 可能在 onReceive 返回后立刻重新冻结普通线程；此前 goAsync +
        // daemon executor 会让任务一直排队，直到下一个闹钟再次唤醒进程。
        try {
            when (action) {
                NotificationHelper.ACTION_LIVE_UPDATE_POST -> {
                    val uriString = intent.getStringExtra(NotificationHelper.EXTRA_LIVE_UPDATE_URI)
                    if (notificationId > 0 && uriString != null) {
                        Log.d("SnapClear Notify", "System wake post triggered: id=$notificationId")
                        NotificationHelper.postScreenshotNotification(
                            context, Uri.parse(uriString), notificationId
                        )
                    }
                }

                NotificationHelper.ACTION_LIVE_UPDATE_TICK -> {
                    if (notificationId > 0) {
                        NotificationHelper.onLiveUpdateTick(context, notificationId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SnapClear Notify", "LiveUpdateTickReceiver failed", e)
        }
    }
}
