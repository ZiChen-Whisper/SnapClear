package com.snapclear.app.ui.image

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotImageLoaderTest {

    @Test
    fun calculateSampleSize_keepsImagesNearTargetResolution() {
        assertEquals(1, ScreenshotImageLoader.calculateSampleSize(1080, 2400, 1080, 2400))
        assertEquals(2, ScreenshotImageLoader.calculateSampleSize(2160, 4800, 1080, 2400))
        assertEquals(4, ScreenshotImageLoader.calculateSampleSize(4320, 9600, 1080, 2400))
    }

    @Test
    fun calculateSampleSize_doesNotUndershootEitherDimension() {
        assertEquals(1, ScreenshotImageLoader.calculateSampleSize(2000, 1000, 1080, 2400))
        assertEquals(1, ScreenshotImageLoader.calculateSampleSize(1000, 4000, 1080, 2400))
    }
}
