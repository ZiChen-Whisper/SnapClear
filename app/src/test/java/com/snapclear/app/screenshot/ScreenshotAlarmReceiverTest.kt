package com.snapclear.app.screenshot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.*

/**
 * ScreenshotAlarmReceiver 核心路径单元测试
 *
 * 覆盖：
 * - schedule() 的 3 级 Alarm 调度回退链
 * - 权限缺失时 SecurityException → setAndAllowWhileIdle
 * - 所有方式失败时 → setAlarmClock 最终兜底
 * - cancel() 取消调度
 */
class ScreenshotAlarmReceiverTest {

    private lateinit var mockAlarmManager: AlarmManager
    private lateinit var mockContext: Context
    private lateinit var mockContentResolver: android.content.ContentResolver
    private lateinit var mockPendingIntent: PendingIntent
    private lateinit var pendingIntentMock: MockedStatic<PendingIntent>

    @Before
    fun setUp() {
        mockAlarmManager = mock()
        mockContentResolver = mock()
        mockPendingIntent = mock()

        val mockPrefs = mock<SharedPreferences>()
        val mockEdit = mock<SharedPreferences.Editor>()
        mockContext = mock {
            on { applicationContext } doReturn it
            on { getSystemService(Context.ALARM_SERVICE) } doReturn mockAlarmManager
            on { contentResolver } doReturn mockContentResolver
            on { getSharedPreferences("snapclear_prefs", Context.MODE_PRIVATE) } doReturn mockPrefs
        }
        whenever(mockPrefs.getLong("last_detected_id", -1L)).thenReturn(100L)
        whenever(mockPrefs.getBoolean("monitoring_enabled", false)).thenReturn(true)
        whenever(mockPrefs.edit()).thenReturn(mockEdit)
        whenever(mockEdit.putLong(any(), any())).thenReturn(mockEdit)
        whenever(mockEdit.putBoolean(any(), any())).thenReturn(mockEdit)
        doNothing().whenever(mockEdit).apply()
    }

    @After
    fun tearDown() {
        try {
            pendingIntentMock.close()
        } catch (_: Exception) { }
        try {
            pendingIntentMock.closeOnDemand()
        } catch (_: Exception) { }
        resetCompanionState()
    }

    // ─── schedule()：setExactAndAllowWhileIdle 首选 ────────────────────

    @Test
    fun schedule_usesSetExactAndAllowWhileIdle_whenNoException() {
        mockStaticPendingIntent()
        setupObserverState()

        ScreenshotAlarmReceiver.schedule(mockContext)

        verify(mockAlarmManager).setExactAndAllowWhileIdle(
            eq(AlarmManager.ELAPSED_REALTIME_WAKEUP),
            any(),
            eq(mockPendingIntent)
        )
        verify(mockAlarmManager, never()).setAndAllowWhileIdle(any(), any(), any())
        verify(mockAlarmManager, never()).setAlarmClock(any(), any())
    }

    // ─── schedule()：SecurityException → setAndAllowWhileIdle ──────────

    @Test
    fun schedule_fallsBackToSetAndAllowWhileIdle_onSecurityException() {
        mockStaticPendingIntent()
        setupObserverState()

        whenever(mockAlarmManager.setExactAndAllowWhileIdle(any(), any(), any()))
            .thenThrow(SecurityException("SCHEDULE_EXACT_ALARM not granted"))

        ScreenshotAlarmReceiver.schedule(mockContext)

        verify(mockAlarmManager).setAndAllowWhileIdle(
            eq(AlarmManager.ELAPSED_REALTIME_WAKEUP),
            any(),
            eq(mockPendingIntent)
        )
        verify(mockAlarmManager, never()).setAlarmClock(any(), any())
    }

    // ─── schedule()：两层失败 → setAlarmClock 最终兜底 ─────────────────

    @Test
    fun schedule_fallsBackToSetAlarmClock_whenBothFail() {
        mockStaticPendingIntent()
        setupObserverState()

        whenever(mockAlarmManager.setExactAndAllowWhileIdle(any(), any(), any()))
            .thenThrow(SecurityException("not granted"))
        whenever(mockAlarmManager.setAndAllowWhileIdle(any(), any(), any()))
            .thenThrow(RuntimeException("fallback also failed"))

        ScreenshotAlarmReceiver.schedule(mockContext)

        verify(mockAlarmManager).setAlarmClock(
            any<AlarmManager.AlarmClockInfo>(),
            eq(mockPendingIntent)
        )
    }

    // ─── cancel() ──────────────────────────────────────────────────────

    @Test
    fun cancel_callsAlarmManagerCancel() {
        mockStaticPendingIntent()

        ScreenshotAlarmReceiver.cancel(mockContext)

        verify(mockAlarmManager).cancel(eq(mockPendingIntent))
    }

    // ─── Helper ──────────────────────────────────────────────────────────

    private fun mockStaticPendingIntent() {
        pendingIntentMock = mockStatic(PendingIntent::class.java)
        pendingIntentMock
            .`when`<PendingIntent> {
                PendingIntent.getBroadcast(
                    org.mockito.ArgumentMatchers.any(Context::class.java),
                    anyInt(),
                    org.mockito.ArgumentMatchers.any(Intent::class.java),
                    anyInt()
                )
            }
            .thenReturn(mockPendingIntent)
    }

    private val soClass = ScreenshotObserver::class.java

    private fun setupObserverState() {
        setStaticField("appContext", mockContext)
        setStaticField("initialized", true)
        setStaticField("lastDetectedId", 100L)
    }

    private fun resetCompanionState() {
        setStaticField("lastDetectedId", 0L)
        setStaticField("initialized", false)
        setStaticField("appContext", null)
    }

    private fun setStaticField(fieldName: String, value: Any?) {
        val field = soClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(null, value)
    }
}
