package com.adsa.hd

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.util.concurrent.TimeUnit
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** إعدادات البورتريه: level من 1 (خفيف) إلى 5 (قوي)، ونقطة التركيز (0..1، y للأسفل). */
data class PortraitSpec(
    val level: Int,
    val fx: Float,
    val fy: Float,
    val tapped: Boolean,
    val mirrorX: Boolean
)

/**
 * بورتريه الصور: عزل الشخص بنموذج ML Kit (مضمّن)، وإن لم يوجد شخص أو ضغط المستخدم على
 * عنصر آخر نستخدم تركيزاً دائرياً ناعماً حول نقطة اللمس. الخلفية تُطمس ثم تُدمج بدقة الصورة الكاملة.
 */
object PortraitProcessor {

    private val RADII = intArrayOf(2, 4, 7, 11, 16)   // نصف قطر الطمس عند دقة 1024

    fun apply(bmp: Bitmap, spec: PortraitSpec, exifOrientation: Int) {
        val w = bmp.width
        val h = bmp.height
        val scale = min(1f, 1024f / max(w, h))
        val sw = max(1, (w * scale).toInt())
        val sh = max(1, (h * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(bmp, sw, sh, true)

        val rot = when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val up = if (rot == 0f) scaled else
            Bitmap.createBitmap(scaled, 0, 0, sw, sh, Matrix().apply { postRotate(rot) }, true)
        val uw = up.width
        val uh = up.height
        val px = IntArray(uw * uh)
        up.getPixels(px, 0, uw, 0, 0, uw, uh)

        val seg = segment(up)
        if (up !== scaled) up.recycle()
        scaled.recycle()

        val fx = (if (spec.mirrorX) 1f - spec.fx else spec.fx).coerceIn(0f, 1f)
        val fy = spec.fy.coerceIn(0f, 1f)

        // خريطة الحدة: 1 = واضح، 0 = مطموس
        val alpha = FloatArray(uw * uh)
        var usePerson = false
        if (seg != null) {
            val (m, mw, mh) = seg
            var cnt = 0
            for (v in m) if (v > 0.5f) cnt++
            val frac = cnt.toFloat() / m.size
            val atFocus = m[(min(mh - 1, (fy * mh).toInt())) * mw + min(mw - 1, (fx * mw).toInt())] > 0.5f
            usePerson = frac > 0.01f && (!spec.tapped || atFocus)
            if (usePerson) {
                for (y in 0 until uh) {
                    val my = min(mh - 1, y * mh / uh)
                    for (x in 0 until uw) {
                        val mx = min(mw - 1, x * mw / uw)
                        alpha[y * uw + x] = smooth(0.3f, 0.7f, m[my * mw + mx])
                    }
                }
            }
        }
        if (!usePerson) {
            val md = min(uw, uh).toFloat()
            for (y in 0 until uh) {
                for (x in 0 until uw) {
                    val d = hypot((x / uw.toFloat() - fx) * uw, (y / uh.toFloat() - fy) * uh) / md
                    alpha[y * uw + x] = 1f - smooth(0.13f, 0.32f, d)
                }
            }
        }

        val r = max(1, (RADII[(spec.level - 1).coerceIn(0, 4)] * uw.coerceAtLeast(uh) / 1024f).toInt())
        val blurred = boxBlur3(px, uw, uh, r)

        // معاملات تحويل إحداثيات الصورة الخام إلى الصورة المعتدلة (حسب EXIF)
        val a0: Float; val a1: Float; val a2: Float
        val b0: Float; val b1: Float; val b2: Float
        when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> { a0 = 1f; a1 = 0f; a2 = -1f; b0 = 0f; b1 = 1f; b2 = 0f }
            ExifInterface.ORIENTATION_ROTATE_180 -> { a0 = 1f; a1 = -1f; a2 = 0f; b0 = 1f; b1 = 0f; b2 = -1f }
            ExifInterface.ORIENTATION_ROTATE_270 -> { a0 = 0f; a1 = 0f; a2 = 1f; b0 = 1f; b1 = -1f; b2 = 0f }
            else -> { a0 = 0f; a1 = 1f; a2 = 0f; b0 = 0f; b1 = 0f; b2 = 1f }
        }

        val rowsPerChunk = max(1, 1_000_000 / w)
        val buf = IntArray(w * rowsPerChunk)
        var y0 = 0
        while (y0 < h) {
            val n = min(rowsPerChunk, h - y0)
            bmp.getPixels(buf, 0, w, 0, y0, w, n)
            for (ry in 0 until n) {
                val v = (y0 + ry + 0.5f) / h
                for (x in 0 until w) {
                    val u = (x + 0.5f) / w
                    val sx = (a0 + a1 * u + a2 * v) * uw - 0.5f
                    val sy = (b0 + b1 * u + b2 * v) * uh - 0.5f
                    val ix = sx.toInt().coerceIn(0, uw - 1)
                    val iy = sy.toInt().coerceIn(0, uh - 1)
                    val ix1 = min(ix + 1, uw - 1)
                    val iy1 = min(iy + 1, uh - 1)
                    val tx = (sx - ix).coerceIn(0f, 1f)
                    val ty = (sy - iy).coerceIn(0f, 1f)

                    val al = lerp(
                        lerp(alpha[iy * uw + ix], alpha[iy * uw + ix1], tx),
                        lerp(alpha[iy1 * uw + ix], alpha[iy1 * uw + ix1], tx), ty
                    )
                    if (al >= 0.995f) continue

                    val c00 = blurred[iy * uw + ix]; val c10 = blurred[iy * uw + ix1]
                    val c01 = blurred[iy1 * uw + ix]; val c11 = blurred[iy1 * uw + ix1]
                    val br = bil(c00 shr 16 and 255, c10 shr 16 and 255, c01 shr 16 and 255, c11 shr 16 and 255, tx, ty)
                    val bg = bil(c00 shr 8 and 255, c10 shr 8 and 255, c01 shr 8 and 255, c11 shr 8 and 255, tx, ty)
                    val bb = bil(c00 and 255, c10 and 255, c01 and 255, c11 and 255, tx, ty)

                    val i = ry * w + x
                    val p = buf[i]
                    val r2 = lerp(br, (p shr 16 and 255).toFloat(), al).toInt().coerceIn(0, 255)
                    val g2 = lerp(bg, (p shr 8 and 255).toFloat(), al).toInt().coerceIn(0, 255)
                    val b2c = lerp(bb, (p and 255).toFloat(), al).toInt().coerceIn(0, 255)
                    buf[i] = (p and 0xFF000000.toInt()) or (r2 shl 16) or (g2 shl 8) or b2c
                }
            }
            bmp.setPixels(buf, 0, w, 0, y0, w, n)
            y0 += n
        }
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun bil(c00: Int, c10: Int, c01: Int, c11: Int, tx: Float, ty: Float): Float =
        lerp(lerp(c00.toFloat(), c10.toFloat(), tx), lerp(c01.toFloat(), c11.toFloat(), tx), ty)

    private fun segment(bmp: Bitmap): Triple<FloatArray, Int, Int>? {
        return try {
            val options = SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
            val seg = Segmentation.getClient(options)
            try {
                val mask = Tasks.await(seg.process(InputImage.fromBitmap(bmp, 0)), 20, TimeUnit.SECONDS)
                val buf = mask.buffer
                buf.rewind()
                val n = mask.width * mask.height
                val arr = FloatArray(n)
                for (i in 0 until n) arr[i] = buf.float
                Triple(arr, mask.width, mask.height)
            } finally {
                seg.close()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** طمس صندوقي مكرّر 3 مرات (≈ غاوسي) على مصفوفة ARGB. */
    private fun boxBlur3(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        var a = src.copyOf()
        var b = IntArray(w * h)
        repeat(3) {
            blurPass(a, b, w, h, r, true)
            blurPass(b, a, w, h, r, false)
        }
        return a
    }

    private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val win = 2 * r + 1
        for (l in 0 until lines) {
            var sr = 0; var sg = 0; var sb = 0
            fun idx(i: Int): Int {
                val c = i.coerceIn(0, len - 1)
                return if (horizontal) l * w + c else c * w + l
            }
            for (i in -r..r) {
                val p = src[idx(i)]
                sr += p shr 16 and 255; sg += p shr 8 and 255; sb += p and 255
            }
            for (i in 0 until len) {
                dst[idx(i)] = (0xFF shl 24) or ((sr / win) shl 16) or ((sg / win) shl 8) or (sb / win)
                val add = src[idx(i + r + 1)]
                val rem = src[idx(i - r)]
                sr += (add shr 16 and 255) - (rem shr 16 and 255)
                sg += (add shr 8 and 255) - (rem shr 8 and 255)
                sb += (add and 255) - (rem and 255)
            }
        }
    }
}
