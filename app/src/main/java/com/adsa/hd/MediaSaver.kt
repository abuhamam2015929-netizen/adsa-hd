package com.adsa.hd

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

object MediaSaver {

    const val FOLDER = "AdsaHD"

    fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** يحفظ الصورة (بعد التحسين إن كان مفعّلاً) في معرض الصور داخل مجلد AdsaHD. */
    fun savePhoto(ctx: Context, src: File, enhance: Boolean, filter: ColorMatrix? = null): Uri? {
        var data = src
        var enhanced: File? = null
        if (enhance || filter != null) {
            try {
                val out = File(ctx.cacheDir, "enh_${System.nanoTime()}.jpg")
                ImageEnhancer.process(src, out, enhance, filter)
                enhanced = out
                data = out
            } catch (_: Throwable) {
                // لو فشل التحسين (ذاكرة مثلاً) نحفظ الأصل كما هو
                data = src
            }
        }
        try {
            return insertImage(ctx, data)
        } finally {
            enhanced?.delete()
        }
    }

    private fun insertImage(ctx: Context, file: File): Uri? {
        val name = "ADSA_${timestamp()}.jpg"
        val resolver = ctx.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { it.copyTo(out, 1 shl 16) }
            }
            cv.clear()
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, cv, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                FOLDER
            ).apply { mkdirs() }
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, dest.absolutePath)
            }
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        }
    }

    /** آخر صورة/فيديو التُقط عبر التطبيق (من مجلد AdsaHD). */
    fun latest(ctx: Context): Pair<Uri, Boolean>? {
        return try {
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE
            )
            @Suppress("DEPRECATION")
            val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR " +
                    "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?) AND " +
                    "${MediaStore.MediaColumns.DATA} LIKE ?"
            val args = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                "%/$FOLDER/%"
            )
            ctx.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection, selection, args,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    val isVideo = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    ContentUris.withAppendedId(base, id) to isVideo
                } else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** صورة مصغّرة مربّعة للمعرض. */
    fun thumbnail(ctx: Context, uri: Uri, isVideo: Boolean, size: Int): Bitmap? {
        return try {
            val raw: Bitmap? = if (isVideo) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    r.getFrameAtTime(0)
                } finally {
                    r.release()
                }
            } else {
                decodeSampled(ctx, uri, size * 2)
            }
            raw?.let { centerCrop(it, size) }
        } catch (_: Throwable) {
            null
        }
    }

    /** فك صورة بحجم مناسب للشاشة مع تصحيح اتجاه EXIF. */
    fun decodeSampled(ctx: Context, uri: Uri, maxDim: Int): Bitmap? {
        val resolver = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
        val bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bmp
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun centerCrop(src: Bitmap, size: Int): Bitmap {
        val s = minOf(src.width, src.height)
        val x = (src.width - s) / 2
        val y = (src.height - s) / 2
        val square = Bitmap.createBitmap(src, x, y, s, s)
        return Bitmap.createScaledBitmap(square, size, size, true)
    }
}
