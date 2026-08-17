package com.snapclear.app.screenshot

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.content.ContentUris
import android.util.Log
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.*

/**
 * ScreenshotObserver 核心路径单元测试
 *
 * 覆盖：
 * - detectAndAdvance 的 lastDetectedId 推进逻辑（截图/非截图均推进）
 * - initLastDetectedId 两种场景（首次安装 vs 进程恢复）
 * - 空 cursor / null cursor 边界条件
 * - 截图回调只对匹配项触发
 * - 路径匹配 vs 文件名匹配
 */
class ScreenshotObserverTest {

    private lateinit var mockResolver: ContentResolver
    private lateinit var mockCursor: Cursor
    private lateinit var logMock: MockedStatic<Log>
    private lateinit var contentUrisMock: MockedStatic<ContentUris>
    private lateinit var mockImageUri: Uri

    private val screenshotName = "Screenshot_20240801_120000.png"
    private val screenshotPath = "Pictures/Screenshots/"
    private val normalImageName = "IMG_20240801_120000.jpg"
    private val normalImagePath = "DCIM/Camera/"
    private val columnId = android.provider.MediaStore.Images.Media._ID
    private val columnName = android.provider.MediaStore.Images.Media.DISPLAY_NAME
    private val columnPath = android.provider.MediaStore.Images.Media.RELATIVE_PATH

    @Before
    fun setUp() {
        logMock = mockStatic(Log::class.java)
        contentUrisMock = mockStatic(ContentUris::class.java)
        mockImageUri = mock()
        contentUrisMock.`when`<Uri> { ContentUris.withAppendedId(any(), anyLong()) }
            .thenReturn(mockImageUri)
        mockResolver = mock()
        mockCursor = mock()

        whenever(mockCursor.getColumnIndexOrThrow(columnId)).thenReturn(0)
        whenever(mockCursor.getColumnIndexOrThrow(columnName)).thenReturn(1)
        whenever(mockCursor.getColumnIndexOrThrow(columnPath)).thenReturn(2)
    }

    @After
    fun tearDown() {
        resetCompanionState()
        try { logMock.close() } catch (_: Exception) { }
        try { contentUrisMock.close() } catch (_: Exception) { }
    }

    // ─── initLastDetectedId：首次安装（无持久化值）─────────────────────────

    @Test
    fun initLastDetectedId_firstRun_advancesToMediaStoreMax() {
        val mockPrefs = mock<SharedPreferences>()
        val mockContext = mock<Context> {
            on { applicationContext } doReturn it
            on { getSharedPreferences("snapclear_prefs", Context.MODE_PRIVATE) } doReturn mockPrefs
        }
        val mockEdit = mock<SharedPreferences.Editor>()
        whenever(mockPrefs.getLong("last_detected_id", -1L)).thenReturn(-1L)
        whenever(mockPrefs.edit()).thenReturn(mockEdit)
        whenever(mockEdit.putLong(any(), any())).thenReturn(mockEdit)

        whenever(mockResolver.query(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(mockCursor)
        whenever(mockCursor.moveToFirst()).thenReturn(true)
        whenever(mockCursor.getLong(0)).thenReturn(200L)

        setAppContext(mockContext)

        ScreenshotObserver.initLastDetectedId(mockResolver)

        // 验证 cursor 的 getLong(0) 被调用并返回正确的值
        verify(mockCursor).getLong(0)
        // 验证持久化被调用
        verify(mockEdit).putLong(eq("last_detected_id"), eq(200L))

        assertEquals("首次安装应推进到 MediaStore 最大 ID", 200L, ScreenshotObserver.lastDetectedId)
    }

    @Test
    fun initLastDetectedId_firstRun_emptyMediaStore_keepsZero() {
        val mockPrefs = mock<SharedPreferences>()
        val mockContext = mock<Context> {
            on { applicationContext } doReturn it
            on { getSharedPreferences("snapclear_prefs", Context.MODE_PRIVATE) } doReturn mockPrefs
        }
        val mockEdit = mock<SharedPreferences.Editor>()
        whenever(mockPrefs.getLong("last_detected_id", -1L)).thenReturn(-1L)
        whenever(mockPrefs.edit()).thenReturn(mockEdit)
        whenever(mockEdit.putLong(any(), any())).thenReturn(mockEdit)

        whenever(mockResolver.query(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(mockCursor)
        whenever(mockCursor.moveToFirst()).thenReturn(false)

        setAppContext(mockContext)

        ScreenshotObserver.initLastDetectedId(mockResolver)

        assertEquals("空的 MediaStore 应保持 lastDetectedId=0", 0L, ScreenshotObserver.lastDetectedId)
    }

    // ─── initLastDetectedId：进程恢复（有持久化值）─────────────────────────

    @Test
    fun initLastDetectedId_persisted_restoresWithoutAdvancing() {
        val persistedId = 150L
        val mockPrefs = mock<SharedPreferences>()
        val mockContext = mock<Context> {
            on { applicationContext } doReturn it
            on { getSharedPreferences("snapclear_prefs", Context.MODE_PRIVATE) } doReturn mockPrefs
        }
        val mockEdit = mock<SharedPreferences.Editor>()
        whenever(mockPrefs.getLong("last_detected_id", -1L)).thenReturn(persistedId)
        whenever(mockPrefs.edit()).thenReturn(mockEdit)
        whenever(mockEdit.putLong(any(), any())).thenReturn(mockEdit)

        setAppContext(mockContext)

        ScreenshotObserver.initLastDetectedId(mockResolver)

        assertEquals(
            "进程恢复应恢复到持久化值，而非推进到 MediaStore 最大 ID",
            persistedId,
            ScreenshotObserver.lastDetectedId
        )
    }

    @Test
    fun initLastDetectedId_alreadyInitialized_skipsReinitialization() {
        val mockPrefs = mock<SharedPreferences>()
        val mockContext = mock<Context> {
            on { applicationContext } doReturn it
            on { getSharedPreferences("snapclear_prefs", Context.MODE_PRIVATE) } doReturn mockPrefs
        }
        val mockEdit = mock<SharedPreferences.Editor>()
        whenever(mockPrefs.getLong("last_detected_id", -1L)).thenReturn(100L)
        whenever(mockPrefs.edit()).thenReturn(mockEdit)
        whenever(mockEdit.putLong(any(), any())).thenReturn(mockEdit)

        setAppContext(mockContext)

        ScreenshotObserver.initLastDetectedId(mockResolver)
        val firstValue = ScreenshotObserver.lastDetectedId
        assertEquals(100L, firstValue)

        // 第二次调用应短路跳过
        ScreenshotObserver.initLastDetectedId(mockResolver)
        assertEquals("第二次调用不应改变 lastDetectedId", firstValue, ScreenshotObserver.lastDetectedId)
    }

    // ─── detectAndAdvance：lastDetectedId 推进逻辑 ───────────────────────

    @Test
    fun detectAndAdvance_advancesLastDetectedIdThroughMultipleImages() {
        setLastDetected(50L)
        setupMockContextForSetLastDetected()

        whenever(mockResolver.query(
            anyOrNull(), anyOrNull(),
            eq("$columnId > ?"),
            eq(arrayOf("50")),
            anyOrNull()
        )).thenReturn(mockCursor)

        whenever(mockCursor.moveToNext())
            .thenReturn(true, true, true, false)
        whenever(mockCursor.getLong(0))
            .thenReturn(55L, 60L, 70L)
        whenever(mockCursor.getString(1))
            .thenReturn(screenshotName, screenshotName, screenshotName)
        whenever(mockCursor.getString(2))
            .thenReturn(screenshotPath, screenshotPath, screenshotPath)

        val detectedUris = mutableListOf<Uri>()
        ScreenshotObserver.detectAndAdvance(mockResolver) { detectedUris.add(it) }

        assertEquals("应推进到处理过的最大 ID", 70L, ScreenshotObserver.lastDetectedId)
        assertEquals("应检测到 3 张截图", 3, detectedUris.size)
    }

    @Test
    fun detectAndAdvance_nonScreenshotStillAdvancesCursor() {
        setLastDetected(50L)
        setupMockContextForSetLastDetected()

        whenever(mockResolver.query(
            anyOrNull(), anyOrNull(),
            eq("$columnId > ?"),
            eq(arrayOf("50")),
            anyOrNull()
        )).thenReturn(mockCursor)

        whenever(mockCursor.moveToNext())
            .thenReturn(true, true, false)
        whenever(mockCursor.getLong(0))
            .thenReturn(55L, 60L)
        whenever(mockCursor.getString(1))
            .thenReturn(normalImageName, normalImageName)
        whenever(mockCursor.getString(2))
            .thenReturn(normalImagePath, normalImagePath)

        val detectedUris = mutableListOf<Uri>()
        ScreenshotObserver.detectAndAdvance(mockResolver) { detectedUris.add(it) }

        assertEquals("非截图也应推进 cursor", 60L, ScreenshotObserver.lastDetectedId)
        assertTrue("非截图不应触发回调", detectedUris.isEmpty())
    }

    @Test
    fun detectAndAdvance_mixedContent_filtersCorrectly() {
        setLastDetected(0L)
        setupMockContextForSetLastDetected()

        whenever(mockResolver.query(
            anyOrNull(), anyOrNull(),
            eq("$columnId > ?"),
            eq(arrayOf("0")),
            anyOrNull()
        )).thenReturn(mockCursor)

        whenever(mockCursor.moveToNext())
            .thenReturn(true, true, true, false)
        whenever(mockCursor.getLong(0))
            .thenReturn(10L, 20L, 30L)
        whenever(mockCursor.getString(1))
            .thenReturn(screenshotName, normalImageName, "截屏_20240801.png")
        whenever(mockCursor.getString(2))
            .thenReturn(screenshotPath, normalImagePath, "DCIM/Screenshots/")

        val detectedUris = mutableListOf<Uri>()
        ScreenshotObserver.detectAndAdvance(mockResolver) { detectedUris.add(it) }

        assertEquals("推进到最后处理的行", 30L, ScreenshotObserver.lastDetectedId)
        assertEquals("只应对截图触发回调", 2, detectedUris.size)
    }

    // ─── detectAndAdvance：边界条件 ─────────────────────────────────────

    @Test
    fun detectAndAdvance_nullCursor_returnsEarly() {
        setLastDetected(50L)

        whenever(mockResolver.query(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(null)

        ScreenshotObserver.detectAndAdvance(mockResolver) {
            fail("不应触发回调")
        }

        assertEquals("null cursor 不应改变 lastDetectedId", 50L, ScreenshotObserver.lastDetectedId)
    }

    @Test
    fun detectAndAdvance_emptyCursor_noChange() {
        setLastDetected(100L)

        whenever(mockResolver.query(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(mockCursor)
        whenever(mockCursor.moveToNext()).thenReturn(false)

        ScreenshotObserver.detectAndAdvance(mockResolver) {
            fail("空 cursor 不应触发回调")
        }

        assertEquals("空 cursor 不应改变 lastDetectedId", 100L, ScreenshotObserver.lastDetectedId)
    }

    // ─── 截图识别：路径匹配 vs 文件名匹配 ──────────────────────────────

    @Test
    fun detectAndAdvance_detectsByDirectoryPath() {
        setLastDetected(0L)
        setupMockContextForSetLastDetected()

        whenever(mockResolver.query(
            anyOrNull(), anyOrNull(),
            eq("$columnId > ?"),
            eq(arrayOf("0")),
            anyOrNull()
        )).thenReturn(mockCursor)

        whenever(mockCursor.moveToNext()).thenReturn(true, false)
        whenever(mockCursor.getLong(0)).thenReturn(1L)
        whenever(mockCursor.getString(1)).thenReturn("IMG_001.png")
        whenever(mockCursor.getString(2)).thenReturn("Pictures/Screenshots/")

        val detectedUris = mutableListOf<Uri>()
        ScreenshotObserver.detectAndAdvance(mockResolver) { detectedUris.add(it) }

        assertEquals("通过路径匹配应识别为截图", 1, detectedUris.size)
    }

    @Test
    fun detectAndAdvance_detectsByNamePattern() {
        setLastDetected(0L)
        setupMockContextForSetLastDetected()

        whenever(mockResolver.query(
            anyOrNull(), anyOrNull(),
            eq("$columnId > ?"),
            eq(arrayOf("0")),
            anyOrNull()
        )).thenReturn(mockCursor)

        whenever(mockCursor.moveToNext()).thenReturn(true, false)
        whenever(mockCursor.getLong(0)).thenReturn(1L)
        whenever(mockCursor.getString(1)).thenReturn("my_screenshot_2024.jpg")
        whenever(mockCursor.getString(2)).thenReturn("Download/")

        val detectedUris = mutableListOf<Uri>()
        ScreenshotObserver.detectAndAdvance(mockResolver) { detectedUris.add(it) }

        assertEquals("通过文件名模式应识别为截图", 1, detectedUris.size)
    }

    // ─── Helper ──────────────────────────────────────────────────────────

    /** Companion 属性编译为外层类的静态字段，so 使用 outerClass 而非 Companion */
    private val outerClass = ScreenshotObserver::class.java

    private fun resetCompanionState() {
        setStaticField("lastDetectedId", 0L)
        setStaticField("initialized", false)
        setStaticField("appContext", null)
    }

    private fun setAppContext(mockContext: Context) {
        setStaticField("appContext", mockContext)
    }

    private fun setLastDetected(id: Long) {
        setStaticField("lastDetectedId", id)
    }

    private fun setupMockContextForSetLastDetected() {
        val mockPrefs = mock<SharedPreferences>()
        val mockEdit = mock<SharedPreferences.Editor>()
        val mockContext = mock<Context> {
            on { applicationContext } doReturn it
            on { getSharedPreferences("snapclear_prefs", Context.MODE_PRIVATE) } doReturn mockPrefs
        }
        whenever(mockPrefs.edit()).thenReturn(mockEdit)
        whenever(mockEdit.putLong(any(), any())).thenReturn(mockEdit)
        doNothing().whenever(mockEdit).apply()

        setAppContext(mockContext)
    }

    private fun setStaticField(fieldName: String, value: Any?) {
        val field = outerClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(null, value)
    }
}
