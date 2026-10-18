package com.adsa.hd

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * المشاركة بالجودة الأصلية.
 *
 * واتساب يضغط أي صورة/فيديو يصله على أنه "وسائط". الطريقة الوحيدة لتفادي الضغط
 * هي إرسال الملف كـ "مستند"، لذلك نشارك الملف بنوع MIME عام (application/octet-stream)
 * فيستقبله واتساب كمستند ويرسله كما هو بدون أي تغيير.
 */
object Sharer {

    private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    fun installedWhatsApp(ctx: Context): String? =
        WHATSAPP_PACKAGES.firstOrNull { pkg ->
            try {
                ctx.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

    /** يرسل الملف الأصلي إلى واتساب. يرجع false لو واتساب غير مثبّت. */
    fun toWhatsApp(ctx: Context, uri: Uri): Boolean {
        val pkg = installedWhatsApp(ctx) ?: return false
        return try {
            ctx.startActivity(baseIntent(uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** مشاركة عامة عبر أي تطبيق (أيضاً كملف أصلي). */
    fun chooser(ctx: Context, uri: Uri) {
        val chooser = Intent.createChooser(baseIntent(uri), "مشاركة الملف الأصلي")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(chooser)
    }

    private fun baseIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
