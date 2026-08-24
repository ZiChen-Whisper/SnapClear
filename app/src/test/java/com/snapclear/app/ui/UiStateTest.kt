package com.snapclear.app.ui

import org.junit.Assert.*
import org.junit.Test

class UiStateTest {
    @Test fun viewMode_defaultsToCard_andRestoresList() {
        assertEquals(ScreenshotViewMode.CARD, parseScreenshotViewMode(null))
        assertEquals(ScreenshotViewMode.CARD, parseScreenshotViewMode("bad-value"))
        assertEquals(ScreenshotViewMode.LIST, parseScreenshotViewMode("LIST"))
    }

    @Test fun accessibility_isRequiredOnlyOnOppo() {
        assertFalse(arePermissionsComplete(true, true, true, true, true, false))
        assertTrue(arePermissionsComplete(true, true, true, true, true, true))
        assertTrue(arePermissionsComplete(true, true, true, true, false, false))
    }

    @Test fun emptyScreenshotRange_hasExplicitLabel() {
        assertEquals("本页暂无截图", formatTimestampRange(emptyList()))
    }

    @Test fun recycledThumbnail_isShownOnlyForItsBoundUri() {
        assertTrue(thumbnailBelongsTo("content://images/41", "content://images/41"))
        assertFalse(thumbnailBelongsTo("content://images/42", "content://images/41"))
    }

    @Test fun rubberBand_keepsMovingWithoutCrossingItsSoftLimit() {
        val near = rubberBandOffset(distance = 20f, limit = 16f)
        val far = rubberBandOffset(distance = 200f, limit = 16f)

        assertTrue(near > 0f)
        assertTrue(far > near)
        assertTrue(far < 16f)
        assertEquals(-far, rubberBandOffset(-200f, 16f), 0.0001f)
    }

    @Test fun selectorPosition_followsInsideAndUsesResistanceOutside() {
        assertEquals(1.25f, resistedSelectorPosition(1.25f, 2f), 0.0001f)

        val beforeFirst = resistedSelectorPosition(-1f, 2f)
        val afterLast = resistedSelectorPosition(3f, 2f)
        assertTrue(beforeFirst < 0f && beforeFirst > -0.42f)
        assertTrue(afterLast > 2f && afterLast < 2.42f)
    }
}
