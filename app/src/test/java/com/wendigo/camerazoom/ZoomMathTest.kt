package com.wendigo.camerazoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomMathTest {
    @Test
    fun sliderEndpointsMatchZoomRange() {
        assertEquals(1f, ZoomMath.sliderToZoom(0, 1f, 60f, 1000), 0.0001f)
        assertEquals(60f, ZoomMath.sliderToZoom(1000, 1f, 60f, 1000), 0.0001f)
    }

    @Test
    fun sliderIsLogarithmic() {
        val middle = ZoomMath.sliderToZoom(500, 1f, 60f, 1000)
        assertEquals(7.746f, middle, 0.01f)
    }

    @Test
    fun sliderRoundTrip() {
        listOf(1f, 2f, 5f, 10f, 30f, 60f).forEach { zoom ->
            val progress = ZoomMath.zoomToSlider(zoom, 1f, 60f, 1000)
            assertEquals(zoom, ZoomMath.sliderToZoom(progress, 1f, 60f, 1000), 0.15f)
        }
    }

    @Test
    fun clampsOutOfRangeValues() {
        assertEquals(1f, ZoomMath.sliderToZoom(-50, 1f, 60f, 1000), 0.0001f)
        assertEquals(60f, ZoomMath.sliderToZoom(5000, 1f, 60f, 1000), 0.0001f)
        assertTrue(ZoomMath.zoomToSlider(0.5f, 1f, 60f, 1000) in 0..1000)
        assertTrue(ZoomMath.zoomToSlider(90f, 1f, 60f, 1000) in 0..1000)
    }
}
