package com.adsa.hd

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * "ليل ذكي": دمج عدة لقطات متتالية بعد محاذاتها (إزاحة أفقية/رأسية) ومتوسطها لخفض الضوضاء،
 * مع استبعاد البكسلات المتحركة لتجنب الأشباح. يعمل على شرائح لتوفير الذاكرة.
 */
object NightStacker {

    private const val GHOST_THRESHOLD = 130   // مجموع فروق القنوات الثلاث
    private const val STRIP_ROWS = 128
    private const val MAX_PIXELS = 50_000_000L

    @Suppress("DEPRECATION")
    fun stack(frames: List<File>, dst: File) {
        val ref = frames.first()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(ref.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (frames.size < 2 || w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) {
            ref.copyTo(dst, overwrite = true)
            return
        }

        val decoders = frames.map { BitmapRegionDecoder.newInstance(it.absolutePath, false) }
        try {
            // 1) المحاذاة: خشنة على نسخة مصغّرة ثم دقيقة على رقعة بالدقة الكاملة
            var s = 1
            while (max(w, h) / s > 480) s *= 2
            val refSmall = grayOf(frames[0], s)
            val shifts = ArrayList<IntArray>()
            shifts += intArrayOf(0, 0)
            for (i in 1 until frames.size) {
                val sm = grayOf(frames[i], s)
                val c = coarse(refSmall, sm)
                val bx = c[0] * s
                val by = c[1] * s
                shifts += refine(decoders[0], decoders[i], w, h, bx, by, s)
            }

            // 2) الدمج على شرائح
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            var y0 = 0
            while (y0 < h) {
                val y1 = min(h, y0 + STRIP_ROWS)
                val n = y1 - y0
                val base = decodePixels(decoders[0], Rect(0, y0, w, y1), opts)
                    ?: throw IllegalStateException("strip decode failed")
                val sr = IntArray(w * n)
                val sg = IntArray(w * n)
                val sb = IntArray(w * n)
                val cnt = IntArray(w * n)
                for (i in base.indices) {
                    val p = base[i]
                    sr[i] = p shr 16 and 255; sg[i] = p shr 8 and 255; sb[i] = p and 255; cnt[i] = 1
                }
                for (f in 1 until frames.size) {
                    val sx = shifts[f][0]
                    val sy = shifts[f][1]
                    val iL = max(sx, 0)
                    val iT = max(y0 + sy, 0)
                    val iR = min(w + sx, w)
                    val iB = min(y1 + sy, h)
                    if (iR <= iL || iB <= iT) continue
                    val rw = iR - iL
                    val rh = iB - iT
                    val px = decodePixels(decoders[f], Rect(iL, iT, iR, iB), opts) ?: continue
                    for (ry in 0 until n) {
                        val fy = y0 + ry + sy - iT
                        if (fy < 0 || fy >= rh) continue
                        for (x in 0 until w) {
                            val fx = x + sx - iL
                            if (fx < 0 || fx >= rw) continue
                            val q = px[fy * rw + fx]
                            val i = ry * w + x
                            val p = base[i]
                            val qr = q shr 16 and 255; val qg = q shr 8 and 255; val qb = q and 255
                            val d = abs(qr - (p shr 16 and 255)) + abs(qg - (p shr 8 and 255)) + abs(qb - (p and 255))
                            if (d <= GHOST_THRESHOLD) {
                                sr[i] += qr; sg[i] += qg; sb[i] += qb; cnt[i]++
                            }
                        }
                    }
                }
                val res = IntArray(w * n)
                for (i in res.indices) {
                    val c = cnt[i]
                    res[i] = (0xFF shl 24) or ((sr[i] / c) shl 16) or ((sg[i] / c) shl 8) or (sb[i] / c)
                }
                out.setPixels(res, 0, w, 0, y0, w, n)
                y0 = y1
            }

            FileOutputStream(dst).use { out.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            out.recycle()
            ImageEnhancer.copyExif(ref, dst)
        } finally {
            decoders.forEach { try { it.recycle() } catch (_: Throwable) { } }
        }
    }

    private fun decodePixels(d: BitmapRegionDecoder, r: Rect, opts: BitmapFactory.Options): IntArray? {
        val bmp = d.decodeRegion(r, opts) ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        bmp.recycle()
        return px
    }

    private class Gray(val px: IntArray, val w: Int, val h: Int)

    private fun grayOf(f: File, sample: Int): Gray {
        val bmp = BitmapFactory.decodeFile(
            f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: throw IllegalStateException("decode failed")
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val g = Gray(IntArray(px.size) { lum(px[it]) }, bmp.width, bmp.height)
        bmp.recycle()
        return g
    }

    private fun lum(p: Int) = ((p shr 16 and 255) * 77 + (p shr 8 and 255) * 151 + (p and 255) * 28) shr 8

    /** يرجع (dx, dy) بحيث frame(x+dx, y+dy) ≈ ref(x, y) */
    private fun coarse(ref: Gray, fr: Gray): IntArray {
        val range = 10
        val w = min(ref.w, fr.w)
        val h = min(ref.h, fr.h)
        var best = Long.MAX_VALUE
        var bx = 0
        var by = 0
        for (dy in -range..range) {
            for (dx in -range..range) {
                var sad = 0L
                var cnt = 0
                var y = range
                while (y < h - range) {
                    var x = range
                    while (x < w - range) {
                        sad += abs(ref.px[y * ref.w + x] - fr.px[(y + dy) * fr.w + (x + dx)])
                        cnt++
                        x += 2
                    }
                    y += 2
                }
                val score = if (cnt > 0) sad * 1000L / cnt else Long.MAX_VALUE
                if (score < best) { best = score; bx = dx; by = dy }
            }
        }
        return intArrayOf(bx, by)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun refine(
        dRef: BitmapRegionDecoder, dFr: BitmapRegionDecoder,
        w: Int, h: Int, bx: Int, by: Int, s: Int
    ): IntArray {
        val patch = min(384, min(w, h) / 2)
        val pl = (w - patch) / 2
        val pt = (h - patch) / 2
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val refPx = decodePixels(dRef, Rect(pl, pt, pl + patch, pt + patch), opts) ?: return intArrayOf(bx, by)

        val m = s + 2
        val eL = max(0, pl + bx - m)
        val eT = max(0, pt + by - m)
        val eR = min(w, pl + bx + patch + m)
        val eB = min(h, pt + by + patch + m)
        if (eR <= eL || eB <= eT) return intArrayOf(bx, by)
        val ew = eR - eL
        val frPx = decodePixels(dFr, Rect(eL, eT, eR, eB), opts) ?: return intArrayOf(bx, by)

        var best = Long.MAX_VALUE
        var rx = bx
        var ry = by
        for (dy in -s..s) {
            for (dx in -s..s) {
                val sx = bx + dx
                val sy = by + dy
                var sad = 0L
                var cnt = 0
                var y = 0
                while (y < patch) {
                    val fy = pt + y + sy - eT
                    if (fy in 0 until (eB - eT)) {
                        var x = 0
                        while (x < patch) {
                            val fx = pl + x + sx - eL
                            if (fx in 0 until ew) {
                                sad += abs(lum(refPx[y * patch + x]) - lum(frPx[fy * ew + fx]))
                                cnt++
                            }
                            x += 4
                        }
                    }
                    y += 4
                }
                if (cnt > 200) {
                    val score = sad * 1000L / cnt
                    if (score < best) { best = score; rx = sx; ry = sy }
                }
            }
        }
        return intArrayOf(rx, ry)
    }
}
