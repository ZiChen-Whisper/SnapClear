package com.snapclear.app.screenshot

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import com.snapclear.app.diagnostic.DiagnosticEventType
import com.snapclear.app.diagnostic.DiagnosticLogger
import com.snapclear.app.notification.NotificationHelper
import java.util.concurrent.Executors

/**
 * 由系统 JobScheduler 监听 MediaStore 变化的后台检测层。
 *
 * 与应用进程内的 ContentObserver 不同，TriggerContentUri 的观察者由系统持有；
 * 即使 ColorOS 冻结了应用线程，图片库变化仍会让系统启动本 JobService，并在
 * Job 生命周期内持有唤醒状态，适合负责后台截图的实时通知。
 */
class ScreenshotContentJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        if (!ScreenshotMonitorService.isMonitoringEnabled(this)) return false

        executor.execute {
            try {
                ScreenshotObserver.init(this)
                ScreenshotObserver.initLastDetectedId(contentResolver)
                DiagnosticLogger.log(DiagnosticEventType.POLL, "系统 MediaStore Job 已唤醒")
                ScreenshotObserver.detectAndAdvance(contentResolver) { uri ->
                    DiagnosticLogger.log(DiagnosticEventType.POLL, "系统 Job 发现截图: $uri")
                    NotificationHelper.showScreenshotNotification(applicationContext, uri)
                    ScreenshotEvents.notifyScreenshotDetected()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Content-triggered screenshot detection failed", e)
                DiagnosticLogger.log(DiagnosticEventType.ERROR, "系统 MediaStore Job 异常: ${e.message}")
            } finally {
                // TriggerContentUri Job 是一次性的；官方要求每次完成后重新注册。
                if (ScreenshotMonitorService.isMonitoringEnabled(this)) schedule(this)
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // 若系统在完成前停止任务，请求重新调度以免失去 MediaStore 监听。
        return ScreenshotMonitorService.isMonitoringEnabled(this)
    }

    companion object {
        private const val TAG = "ScreenshotContentJob"
        private const val JOB_ID = 0x5343

        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ScreenshotContentJob")
        }

        fun schedule(context: Context) {
            if (!ScreenshotMonitorService.isMonitoringEnabled(context)) return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val job = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, ScreenshotContentJobService::class.java)
            )
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                    )
                )
                // 截图通常会产生数次 MediaStore 更新，短暂合并后尽快执行。
                .setTriggerContentUpdateDelay(100L)
                .setTriggerContentMaxDelay(1_000L)
                .build()
            val result = scheduler.schedule(job)
            Log.d(TAG, "MediaStore content job scheduled: result=$result")
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        }
    }
}
