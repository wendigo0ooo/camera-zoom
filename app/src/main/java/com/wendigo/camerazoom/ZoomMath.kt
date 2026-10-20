package com.wendigo.camerazoom

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

object ZoomMath {
    fun sliderToZoom(progress: Int, minZoom: Float, maxZoom: Float, sliderMax: Int): Float {
        val lo = minZoom.coerceAtLeast(0.01f)
        val hi = maxZoom.coerceAtLeast(lo)
        if (sliderMax <= 0) return lo
        val t = (progress.toFloat() / sliderMax).coerceIn(0f, 1f)
        return (lo * (hi / lo).pow(t)).coerceIn(lo, hi)
    }

    fun zoomToSlider(zoom: Float, minZoom: Float, maxZoom: Float, sliderMax: Int): Int {
        val lo = minZoom.coerceAtLeast(0.01f)
        val hi = maxZoom.coerceAtLeast(lo)
        if (hi <= lo || sliderMax <= 0) return 0
        val clamped = zoom.coerceIn(lo, hi)
        val t = (ln(clamped / lo) / ln(hi / lo)).coerceIn(0.0, 1.0)
        return (t * sliderMax).roundToInt().coerceIn(0, sliderMax)
    }
}
