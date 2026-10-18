package com.adsa.hd

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * معالجة ما بعد الالتقاط:
 *  - تحسين تلقائي: ضبط مستويات + إضاءة + تباين وتشبّع خفيفين + حدّة (Sharpen).
 *  - فلتر ألوان اختياري (نفس مصفوفة المعاينة الحية).
 *
 * تعمل على البكسلات مباشرة وعلى شرائح (بدون نسخة ثانية من الصورة) لتوفير الذاكرة،
 * وتحافظ على الدقة الكاملة وبيانات EXIF (بما فيها الاتجاه).
 */
object ImageEnhancer {

    private const val MAX_PIXELS = 64_000_000L   // فوق هذا الحد نخفض الدقة للنصف تفادياً لنفاد الذاكرة
    private const val TARGET_MEAN = 0.46f
    private const val SATURATION = 1.06f
    private const val CONTRAST = 1.03f
    private const val SHARPEN_AMOUNT = 0.35f
    private const val JPEG_QUALITY = 100

    fun process(src: File, dst: File, autoEnhance: Boolean, filter: ColorMatrix?) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        val fullW = bounds.outWidth
        val fullH = bounds.outHeight
        require(fullW > 0 && fullH > 0) { "bad image" }

        val lut = if (autoEnhance) buildLut(src, max(fullW, fullH)) else IntArray(256) { it }

        var sample = 1
        while (fullW.toLong() * fullH / (sample.toLong() * sample) > MAX_PIXELS) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(src.absolutePath, opts)
            ?: throw IllegalStateException("decode failed")

        applyTone(bmp, lut, if (autoEnhance) SATURATION else 1f, filter)
        if (autoEnhance) sharpen(bmp, SHARPEN_AMOUNT)

        FileOutputStream(dst).use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bmp.recycle()

        copyExif(src, dst)
    }

    // ------------------------------------------------------------------ tone

    private fun buildLut(src: File, maxDim: Int): IntArray {
        var sample = 1
        while (maxDim / sample > 512) sample *= 2
        val small = BitmapFactory.decodeFile(
            src.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return IntArray(256) { it }

        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        small.recycle()

        val hist = IntArray(256)
        for (p in px) {
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            hist[(r * 77 + g * 151 + b * 28) shr 8]++
        }
        val total = px.size.toFloat()

        var acc = 0f
        var lo = 0
        for (i in 0..255) { acc += hist[i]; if (acc / total >= 0.005f) { lo = i; break } }
        acc = 0f
        var hi = 255
        for (i in 255 downTo 0) { acc += hist[i]; if (acc / total >= 0.005f) { hi = i; break } }

        val loPoint = min(lo, 30).toFloat()
        val hiPoint = max(hi, 215).toFloat()
        val range = max(hiPoint - loPoint, 1f)

        var mean = 0f
        for (i in 0..255) {
            val v = ((i - loPoint) / range).coerceIn(0f, 1f)
            mean += v * hist[i]
        }
        mean = (mean / total).coerceIn(0.05f, 0.95f)

        var gamma = (ln(TARGET_MEAN.toDouble()) / ln(mean.toDouble())).toFloat()
        gamma = 1f + (gamma - 1f) * 0.6f
        gamma = gamma.coerceIn(0.8f, 1.15f)

        return IntArray(256) { i ->
            var v = ((i - loPoint) / range).coerceIn(0f, 1f)
            v = v.toDouble().pow(gamma.toDouble()).toFloat()
            v = ((v - 0.5f) * CONTRAST + 0.5f).coerceIn(0f, 1f)
            (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
    }

    private fun applyTone(bmp: Bitmap, lut: IntArray, saturation: Float, filter: ColorMatrix?) {
        val w = bmp.width
        val h = bmp.height
        val rowsPerChunk = max(1, 1_000_000 / w)
        val buf = IntArray(w * rowsPerChunk)
        val sat = (saturation * 256f).toInt()

        // مصفوفة الفلتر بصيغة أعداد صحيحة (×256)
        val f = filter?.array
        val hasFilter = f != null
        val m00 = if (f != null) (f[0] * 256f).toInt() else 0
        val m01 = if (f != null) (f[1] * 256f).toInt() else 0
        val m02 = if (f != null) (f[2] * 256f).toInt() else 0
        val m10 = if (f != null) (f[5] * 256f).toInt() else 0
        val m11 = if (f != null) (f[6] * 256f).toInt() else 0
        val m12 = if (f != null) (f[7] * 256f).toInt() else 0
        val m20 = if (f != null) (f[10] * 256f).toInt() else 0
        val m21 = if (f != null) (f[11] * 256f).toInt() else 0
        val m22 = if (f != null) (f[12] * 256f).toInt() else 0
        val o0 = if (f != null) f[4].toInt() else 0
        val o1 = if (f != null) f[9].toInt() else 0
        val o2 = if (f != null) f[14].toInt() else 0

        var y = 0
        while (y < h) {
            val n = min(rowsPerChunk, h - y)
            bmp.getPixels(buf, 0, w, 0, y, w, n)
            for (i in 0 until w * n) {
                val p = buf[i]
                val r0 = lut[(p shr 16) and 255]
                val g0 = lut[(p shr 8) and 255]
                val b0 = lut[p and 255]
                val l = (r0 * 77 + g0 * 151 + b0 * 28) shr 8
                var r = (l + (((r0 - l) * sat) shr 8)).coerceIn(0, 255)
                var g = (l + (((g0 - l) * sat) shr 8)).coerceIn(0, 255)
                var b = (l + (((b0 - l) * sat) shr 8)).coerceIn(0, 255)
                if (hasFilter) {
                    val nr = (((m00 * r + m01 * g + m02 * b) shr 8) + o0).coerceIn(0, 255)
                    val ng = (((m10 * r + m11 * g + m12 * b) shr 8) + o1).coerceIn(0, 255)
                    val nb = (((m20 * r + m21 * g + m22 * b) shr 8) + o2).coerceIn(0, 255)
                    r = nr; g = ng; b = nb
                }
                buf[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(buf, 0, w, 0, y, w, n)
            y += n
        }
    }

    // ------------------------------------------------------------------ sharpen

    /** حدّة بسيطة (Laplacian) تُطبَّق على شرائح مع الحفاظ على الصف الأصلي المجاور. */
    private fun sharpen(bmp: Bitmap, amount: Float) {
        val w = bmp.width
        val h = bmp.height
        val amt = (amount * 256f).toInt()
        val rows = max(1, 500_000 / w)
        val src = IntArray(w * (rows + 1))
        val out = IntArray(w * rows)
        val prev = IntArray(w)
        var havePrev = false

        var y = 0
        while (y < h) {
            val n = min(rows, h - y)
            val readRows = min(n + 1, h - y)
            bmp.getPixels(src, 0, w, 0, y, w, readRows)

            for (r in 0 until n) {
                val cur = r * w
                val up: IntArray
                val upOff: Int
                if (r == 0) {
                    if (havePrev) { up = prev; upOff = 0 } else { up = src; upOff = cur }
                } else {
                    up = src; upOff = cur - w
                }
                val downOff = if (r + 1 < readRows) cur + w else cur

                for (x in 0 until w) {
                    val c = src[cur + x]
                    val l = src[cur + max(x - 1, 0)]
                    val rt = src[cur + min(x + 1, w - 1)]
                    val u = up[upOff + x]
                    val d = src[downOff + x]
                    out[cur + x] = sharpPixel(c, l, rt, u, d, amt)
                }
            }
            bmp.setPixels(out, 0, w, 0, y, w, n)
            System.arraycopy(src, (n - 1) * w, prev, 0, w)
            havePrev = true
            y += n
        }
    }

    private fun sharpPixel(c: Int, l: Int, r: Int, u: Int, d: Int, amt: Int): Int {
        val cr = (c shr 16) and 255
        val cg = (c shr 8) and 255
        val cb = c and 255
        val lr = 4 * cr - ((l shr 16) and 255) - ((r shr 16) and 255) - ((u shr 16) and 255) - ((d shr 16) and 255)
        val lg = 4 * cg - ((l shr 8) and 255) - ((r shr 8) and 255) - ((u shr 8) and 255) - ((d shr 8) and 255)
        val lb = 4 * cb - (l and 255) - (r and 255) - (u and 255) - (d and 255)
        val nr = (cr + ((lr * amt) shr 8)).coerceIn(0, 255)
        val ng = (cg + ((lg * amt) shr 8)).coerceIn(0, 255)
        val nb = (cb + ((lb * amt) shr 8)).coerceIn(0, 255)
        return (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
    }

    // ------------------------------------------------------------------ exif

    private val EXIF_TAGS = arrayOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_ORIENTATION
    )

    private fun copyExif(src: File, dst: File) {
        try {
            val from = ExifInterface(src.absolutePath)
            val to = ExifInterface(dst.absolutePath)
            for (tag in EXIF_TAGS) {
                from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
            }
            to.saveAttributes()
        } catch (_: Throwable) {
            // بيانات EXIF اختيارية؛ فشلها لا يمنع حفظ الصورة
        }
    }
}
