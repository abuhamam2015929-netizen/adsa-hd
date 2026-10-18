package com.adsa.hd

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * معالجة تلقائية بعد الالتقاط: ضبط مستويات (Auto-Levels) + إضاءة (Gamma)
 * + تباين خفيف + تشبّع ألوان خفيف.
 *
 * تعمل على البكسلات مباشرة (بدون نسخة ثانية من الصورة) لتوفير الذاكرة
 * حتى مع الصور الكبيرة، وتحافظ على الدقة الكاملة وبيانات EXIF (بما فيها الاتجاه).
 */
object ImageEnhancer {

    private const val MAX_PIXELS = 64_000_000L   // فوق هذا الحد نخفض الدقة للنصف تفادياً لنفاد الذاكرة
    private const val TARGET_MEAN = 0.46f
    private const val SATURATION = 1.12f
    private const val CONTRAST = 1.05f
    private const val JPEG_QUALITY = 100

    fun enhance(src: File, dst: File) {
        // 1) إحصاءات سريعة من نسخة مصغّرة
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        val fullW = bounds.outWidth
        val fullH = bounds.outHeight
        require(fullW > 0 && fullH > 0) { "bad image" }

        val lut = buildLut(src, max(fullW, fullH))

        // 2) فك الصورة بالدقة الكاملة (قابلة للتعديل)
        var sample = 1
        while (fullW.toLong() * fullH / (sample.toLong() * sample) > MAX_PIXELS) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(src.absolutePath, opts)
            ?: throw IllegalStateException("decode failed")

        // 3) تطبيق التحسين على شرائح
        applyInPlace(bmp, lut)

        // 4) الحفظ بأعلى جودة
        FileOutputStream(dst).use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bmp.recycle()

        // 5) نسخ بيانات EXIF (التاريخ، الموقع، الكاميرا، الاتجاه...)
        copyExif(src, dst)
    }

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

        // أسود وأبيض فعليان (0.5% من الأطراف)
        var acc = 0f
        var lo = 0
        for (i in 0..255) { acc += hist[i]; if (acc / total >= 0.005f) { lo = i; break } }
        acc = 0f
        var hi = 255
        for (i in 255 downTo 0) { acc += hist[i]; if (acc / total >= 0.005f) { hi = i; break } }

        // حدّ أقصى للتمديد حتى لا تظهر الصورة مبالغاً فيها
        val loPoint = min(lo, 30).toFloat()
        val hiPoint = max(hi, 215).toFloat()
        val range = max(hiPoint - loPoint, 1f)

        // متوسط الإضاءة بعد ضبط المستويات
        var mean = 0f
        for (i in 0..255) {
            val v = ((i - loPoint) / range).coerceIn(0f, 1f)
            mean += v * hist[i]
        }
        mean = (mean / total).coerceIn(0.05f, 0.95f)

        var gamma = (ln(TARGET_MEAN.toDouble()) / ln(mean.toDouble())).toFloat()
        gamma = 1f + (gamma - 1f) * 0.6f            // نصف القوة تقريباً
        gamma = gamma.coerceIn(0.8f, 1.15f)

        return IntArray(256) { i ->
            var v = ((i - loPoint) / range).coerceIn(0f, 1f)
            v = v.toDouble().pow(gamma.toDouble()).toFloat()
            v = ((v - 0.5f) * CONTRAST + 0.5f).coerceIn(0f, 1f)
            (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
    }

    private fun applyInPlace(bmp: Bitmap, lut: IntArray) {
        val w = bmp.width
        val h = bmp.height
        val rowsPerChunk = max(1, 1_000_000 / w)
        val buf = IntArray(w * rowsPerChunk)
        val sat = (SATURATION * 256f).toInt()

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
                val r = (l + (((r0 - l) * sat) shr 8)).coerceIn(0, 255)
                val g = (l + (((g0 - l) * sat) shr 8)).coerceIn(0, 255)
                val b = (l + (((b0 - l) * sat) shr 8)).coerceIn(0, 255)
                buf[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(buf, 0, w, 0, y, w, n)
            y += n
        }
    }

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
