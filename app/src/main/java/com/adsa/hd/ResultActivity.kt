package com.adsa.hd

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.adsa.hd.databinding.ActivityResultBinding

/** عرض الصورة/الفيديو الملتقط مع أزرار المشاركة بالجودة الأصلية. */
class ResultActivity : AppCompatActivity() {

    private lateinit var b: ActivityResultBinding
    private lateinit var uri: Uri
    private var isVideo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityResultBinding.inflate(layoutInflater)
        setContentView(b.root)

        val raw = intent.getStringExtra("uri")
        if (raw == null) {
            finish()
            return
        }
        uri = Uri.parse(raw)
        isVideo = intent.getBooleanExtra("video", false)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val d = resources.displayMetrics.density
            b.resultTop.setPadding((12 * d).toInt(), bars.top + (8 * d).toInt(), (12 * d).toInt(), 0)
            b.resultBottom.setPadding(
                (20 * d).toInt(), (28 * d).toInt(), (20 * d).toInt(), bars.bottom + (16 * d).toInt()
            )
            insets
        }

        showMedia()

        b.btnClose.setOnClickListener { finish() }

        b.btnDelete.setOnClickListener {
            try {
                val n = contentResolver.delete(uri, null, null)
                if (n > 0) {
                    setResult(RESULT_OK)
                    finish()
                } else {
                    toast("تعذّر الحذف")
                }
            } catch (e: Exception) {
                toast("تعذّر الحذف: ${e.message}")
            }
        }

        b.btnWhatsapp.setOnClickListener {
            if (!Sharer.toWhatsApp(this, uri)) toast("تطبيق واتساب غير مثبّت على هذا الجهاز")
        }

        b.btnShareOther.setOnClickListener { Sharer.chooser(this, uri) }
    }

    private fun showMedia() {
        if (isVideo) {
            b.image.visibility = View.GONE
            b.videoView.visibility = View.VISIBLE
            b.videoView.setVideoURI(uri)
            b.videoView.setOnPreparedListener {
                it.isLooping = true
                b.videoView.start()
            }
            b.videoView.setOnClickListener {
                if (b.videoView.isPlaying) b.videoView.pause() else b.videoView.start()
            }
        } else {
            val dm = resources.displayMetrics
            val maxDim = maxOf(dm.widthPixels, dm.heightPixels) * 2
            Thread {
                val bmp = MediaSaver.decodeSampled(applicationContext, uri, maxDim)
                runOnUiThread { if (bmp != null) b.image.setImageBitmap(bmp) }
            }.start()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
