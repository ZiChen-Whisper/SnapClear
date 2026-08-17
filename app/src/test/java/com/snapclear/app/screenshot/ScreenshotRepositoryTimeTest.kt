package com.snapclear.app.screenshot

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotRepositoryTimeTest {

    @Test
    fun effectiveScreenshotTime_prefersMediaStoreAddedTime() {
        assertEquals(
            1_700_000_000_000L,
            ScreenshotRepository.effectiveScreenshotTime(
                dateTakenMs = 1_600_000_000_000L,
                dateAddedSeconds = 1_700_000_000L
            )
        )
    }

    @Test
    fun effectiveScreenshotTime_fallsBackWhenAddedTimeMissing() {
        assertEquals(
            1_600_000_000_000L,
            ScreenshotRepository.effectiveScreenshotTime(
                dateTakenMs = 1_600_000_000_000L,
                dateAddedSeconds = 0L
            )
        )
    }
}
