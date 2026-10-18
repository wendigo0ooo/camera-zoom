package com.wendigo.camerazoom

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import androidx.camera.core.ImageProxy

/**
 * Donanım zoom sınırının ötesine geçmek (dijital kırpma + kaliteli büyütme) ve
 * zoom sonrası bozulmayı azaltmak (gürültü azaltma + kenar keskinleştirme) için
 * saf Kotlin görüntü işleme modülü. RenderScript/OpenCV gerektirmez.
 */
object FrameEnhancer {

    /** ImageAnalysis (RGBA_8888) karesini yönü düzeltilmiş Bitmap'e çevirir. */
    fun toBitmap(proxy: ImageProxy): Bitmap? {
        if (proxy.format != PixelFormat.RGBA_8888) return null
        val plane = proxy.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * proxy.width
        val padded = Bitmap.createBitmap(
            proxy.width + rowPadding / pixelStride,
            proxy.height,
            Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(buffer)
        val frame = if (rowPadding == 0) {
            padded
        } else {
            Bitmap.createBitmap(padded, 0, 0, proxy.width, proxy.height)
        }
        val rot = proxy.imageInfo.rotationDegrees
        return if (rot == 0) frame else rotate(frame, rot.toFloat())
    }

    private fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val m = Matrix()
        m.postRotate(degrees)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private var bufPx = IntArray(0)
    private var bufBlur = IntArray(0)
    private var bufOut = IntArray(0)

    /**
     * Dijital zoom (kırpma) + kaliteli büyütme + gürültü azaltma + keskinleştirme.
     * Performans için TEK blur geçişi kullanır (hem gürültü azaltma hem keskinleştirme
     * aynı bulanık veriyi paylaşır) ve tamponları yeniden kullanır.
     *
     * @param crop     donanım zoom'unun üzerine uygulanacak dijital kırpma oranı (>=1)
     * @param targetW  hedef genişlik (ekran), yükseklik oranı korunur
     * @param denoise  gürültü azaltma gücü (0..1)
     * @param sharpen  keskinleştirme miktarı (0 = kapalı)
     */
    fun process(
        src: Bitmap,
        crop: Float,
        targetW: Int,
        denoise: Float = 0.35f,
        sharpen: Float = 1.1f
    ): Bitmap {
        val cropped = cropCenter(src, crop)
        val scaled = scaleToWidth(cropped, targetW)
        val w = scaled.width
        val h = scaled.height
        val n = w * h

        if (bufPx.size < n) bufPx = IntArray(n)
        if (bufBlur.size < n) bufBlur = IntArray(n)
        if (bufOut.size < n) bufOut = IntArray(n)

        scaled.getPixels(bufPx, 0, w, 0, 0, w, h)

        boxBlurInto(bufPx, bufBlur, w, h, 1)

        val base = if (denoise > 0f) blendInto(bufPx, bufBlur, bufOut, n, denoise) else bufPx
        val out = if (sharpen > 0f) unsharpInto(base, bufBlur, bufOut, n, sharpen) else base

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun cropCenter(src: Bitmap, crop: Float): Bitmap {
        if (crop <= 1.01f) return src
        val w = (src.width / crop).toInt().coerceAtLeast(1)
        val h = (src.height / crop).toInt().coerceAtLeast(1)
        val x = ((src.width - w) / 2).coerceAtLeast(0)
        val y = ((src.height - h) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    private fun scaleToWidth(src: Bitmap, targetW: Int): Bitmap {
        if (targetW <= 0 || src.width == 0) return src
        if (targetW >= src.width) {
            val th = (src.height * targetW.toFloat() / src.width).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(src, targetW, th, true)
        }
        return src
    }

    private var bufTmp = IntArray(0)

    /** O(n) ayrılabilir kutu bulanıklığı (alpha korunur). Sonucu [dst]'ye yazar. */
    private fun boxBlurInto(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        if (bufTmp.size < w * h) bufTmp = IntArray(w * h)
        val tmp = bufTmp
        val win = 2 * r + 1

        for (y in 0 until h) {
            val row = y * w
            var rs = 0; var gs = 0; var bs = 0
            for (k in -r..r) {
                val c = src[row + k.coerceIn(0, w - 1)]
                rs += (c shr 16) and 0xff
                gs += (c shr 8) and 0xff
                bs += c and 0xff
            }
            for (x in 0 until w) {
                tmp[row + x] = (0xff shl 24) or
                    ((rs / win) shl 16) or ((gs / win) shl 8) or (bs / win)
                val co = src[row + (x - r).coerceIn(0, w - 1)]
                val ci = src[row + (x + r + 1).coerceIn(0, w - 1)]
                rs += ((ci shr 16) and 0xff) - ((co shr 16) and 0xff)
                gs += ((ci shr 8) and 0xff) - ((co shr 8) and 0xff)
                bs += (ci and 0xff) - (co and 0xff)
            }
        }

        for (x in 0 until w) {
            var rs = 0; var gs = 0; var bs = 0
            for (k in -r..r) {
                val c = tmp[k.coerceIn(0, h - 1) * w + x]
                rs += (c shr 16) and 0xff
                gs += (c shr 8) and 0xff
                bs += c and 0xff
            }
            for (y in 0 until h) {
                dst[y * w + x] = (0xff shl 24) or
                    ((rs / win) shl 16) or ((gs / win) shl 8) or (bs / win)
                val co = tmp[(y - r).coerceIn(0, h - 1) * w + x]
                val ci = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                rs += ((ci shr 16) and 0xff) - ((co shr 16) and 0xff)
                gs += ((ci shr 8) and 0xff) - ((co shr 8) and 0xff)
                bs += (ci and 0xff) - (co and 0xff)
            }
        }
    }

    private fun blendInto(orig: IntArray, blur: IntArray, dst: IntArray, n: Int, amount: Float): IntArray {
        for (i in 0 until n) {
            val a = orig[i]
            val b = blur[i]
            dst[i] = (0xff shl 24) or
                (mix((a shr 16) and 0xff, (b shr 16) and 0xff, amount) shl 16) or
                (mix((a shr 8) and 0xff, (b shr 8) and 0xff, amount) shl 8) or
                mix(a and 0xff, b and 0xff, amount)
        }
        return dst
    }

    /** Unsharp mask: orijinal + miktar*(orijinal - bulanık). Sonucu [dst]'ye yazar. */
    private fun unsharpInto(orig: IntArray, blur: IntArray, dst: IntArray, n: Int, amount: Float): IntArray {
        for (i in 0 until n) {
            val a = orig[i]
            val b = blur[i]
            val r0 = (a shr 16) and 0xff
            val g0 = (a shr 8) and 0xff
            val b0 = a and 0xff
            val r = clamp(r0, r0 - ((b shr 16) and 0xff), amount)
            val g = clamp(g0, g0 - ((b shr 8) and 0xff), amount)
            val bl = clamp(b0, b0 - (b and 0xff), amount)
            dst[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or bl
        }
        return dst
    }

    private fun mix(a: Int, b: Int, amount: Float): Int =
        (a + (b - a) * amount).toInt().coerceIn(0, 255)

    private fun clamp(base: Int, detail: Int, amount: Float): Int =
        (base + detail * amount).toInt().coerceIn(0, 255)
}
