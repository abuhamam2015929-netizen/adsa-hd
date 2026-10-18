package com.adsa.hd

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaActionSound
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.adsa.hd.databinding.ActivityMainBinding
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {

    private data class VideoOption(
        val quality: Quality,
        val label: String,
        val fps: Int,
        val range: Range<Int>?
    )

    private lateinit var b: ActivityMainBinding
    private lateinit var executor: ExecutorService
    private val handler = Handler(Looper.getMainLooper())
    private val sound = MediaActionSound()

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var isVideoMode = false
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var timerSeconds = 0
    private var enhance = true
    private var gridOn = false

    // أنماط الشركة المصنعة (HDR، ليل...) عبر CameraX Extensions
    private var photoStyle = ExtensionMode.NONE
    private var styleOptions: List<Pair<Int, String>> = emptyList()

    // الفلاتر والتعريض
    private var selectedFilter: Filters.Filter = Filters.all[0]
    private var filterPanelOpen = false
    private var evPanelOpen = false
    private var evLower = 0
    private var evStep = 0f

    private var videoOptions: List<VideoOption> = emptyList()
    private var videoOptionIndex = 0
    private var useFpsRange = true
    private var activeRange: Range<Int>? = null
    private var maxMegapixels = 0

    private var lastUri: android.net.Uri? = null
    private var lastIsVideo = false

    private var countdownRunning = false
    private var countdownRunnable: Runnable? = null
    private var topInset = 0

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector
    private var isScaling = false
    private var orientationListener: OrientationEventListener? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true || hasCameraPermission()) {
                startCamera()
            } else {
                toast("يحتاج التطبيق إلى إذن الكاميرا ليعمل")
            }
        }

    private val resultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshLast()
        }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        executor = Executors.newSingleThreadExecutor()
        sound.load(MediaActionSound.SHUTTER_CLICK)
        sound.load(MediaActionSound.START_VIDEO_RECORDING)
        sound.load(MediaActionSound.STOP_VIDEO_RECORDING)

        setupInsets()
        setupGestures()
        setupButtons()
        buildFilterRow()
        applyModeLayout()
        updateTabs()
        updateShutter()
        updateFlashIcon()
        updatePanels()
        tint(b.btnTimer, false)
        tint(b.btnGrid, false)
        tint(b.btnEnhance, enhance)

        // أعد تطبيق الفلتر الحي كلما بدأ البث (يُعاد إنشاء TextureView عند كل ربط)
        b.previewView.previewStreamState.observe(this) { state ->
            if (state == PreviewView.StreamState.STREAMING) applyLiveFilter()
        }

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = rotation
                videoCapture?.targetRotation = rotation
            }
        }

        if (hasCameraPermission()) startCamera() else requestPermissions()
        refreshLast()
    }

    override fun onResume() {
        super.onResume()
        orientationListener?.enable()
        if (gridOn) b.levelView.start()
        if (cameraProvider != null && camera == null && hasCameraPermission()) bindCamera()
    }

    override fun onPause() {
        orientationListener?.disable()
        b.levelView.stop()
        cancelCountdown()
        super.onPause()
    }

    override fun onDestroy() {
        recording?.stop()
        executor.shutdown()
        sound.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ permissions

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    // ------------------------------------------------------------------ UI setup

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topInset = bars.top
            b.topBar.setPadding(dp(12), bars.top + dp(8), dp(12), dp(8))
            b.bottomPanel.setPadding(0, dp(12), 0, bars.bottom + dp(14))
            (b.recTimer.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top + dp(64)
            applyModeLayout()
            insets
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                val z = cam.cameraInfo.zoomState.value ?: return true
                val ratio = (z.zoomRatio * detector.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio)
                cam.cameraControl.setZoomRatio(ratio)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                handler.postDelayed({ isScaling = false }, 150)
            }
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (isScaling || e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > dp(70) && abs(vx) > 700 && abs(dx) > abs(dy)) {
                    setMode(dx < 0)   // سحب لليسار => فيديو، لليمين => صورة
                    return true
                }
                return false
            }
        })

        b.previewView.setOnTouchListener { v, ev ->
            scaleDetector.onTouchEvent(ev)
            if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun setupButtons() {
        b.shutter.setOnClickListener {
            b.shutter.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            when {
                countdownRunning -> cancelCountdown()
                isVideoMode && recording != null -> stopRecording()
                isVideoMode -> startWithTimer { startRecording() }
                else -> startWithTimer { takePhoto() }
            }
        }

        b.btnFlip.setOnClickListener {
            if (recording != null) return@setOnClickListener
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            videoOptionIndex = 0
            useFpsRange = true
            photoStyle = ExtensionMode.NONE
            b.btnFlip.animate().rotationBy(180f).setDuration(250).start()
            bindCamera()
        }

        b.btnFlash.setOnClickListener {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
            updateFlashIcon()
            applyFlash()
        }

        b.btnTimer.setOnClickListener {
            timerSeconds = when (timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }
            tint(b.btnTimer, timerSeconds > 0)
            toast(if (timerSeconds == 0) "المؤقت: متوقف" else "المؤقت: $timerSeconds ثوانٍ")
        }

        b.btnGrid.setOnClickListener {
            gridOn = !gridOn
            tint(b.btnGrid, gridOn)
            b.gridOverlay.visibility = if (gridOn) View.VISIBLE else View.GONE
            b.levelView.visibility = if (gridOn) View.VISIBLE else View.GONE
            if (gridOn) b.levelView.start() else b.levelView.stop()
        }

        b.btnEnhance.setOnClickListener {
            enhance = !enhance
            tint(b.btnEnhance, enhance)
            toast(if (enhance) "التحسين التلقائي: مفعّل" else "التحسين التلقائي: متوقف (الصورة كما التقطتها الكاميرا)")
        }

        b.btnEv.setOnClickListener {
            evPanelOpen = !evPanelOpen
            if (evPanelOpen) filterPanelOpen = false
            updatePanels()
        }

        b.btnFilters.setOnClickListener {
            if (isVideoMode) return@setOnClickListener
            filterPanelOpen = !filterPanelOpen
            if (filterPanelOpen) evPanelOpen = false
            updatePanels()
        }

        b.evSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val index = progress + evLower
                camera?.cameraControl?.setExposureCompensationIndex(index)
                updateEvLabel(index)
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })

        b.tabPhoto.setOnClickListener { setMode(false) }
        b.tabVideo.setOnClickListener { setMode(true) }

        b.qualityChip.setOnClickListener {
            if (isVideoMode && recording == null && videoOptions.size > 1) {
                videoOptionIndex = (videoOptionIndex + 1) % videoOptions.size
                useFpsRange = true
                bindCamera()
            }
        }

        b.zoomChip.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            val z = cam.cameraInfo.zoomState.value ?: return@setOnClickListener
            val target = when {
                z.zoomRatio < 1.5f -> 2f
                z.zoomRatio < 2.5f -> 3f
                else -> 1f
            }.coerceIn(z.minZoomRatio, z.maxZoomRatio)
            cam.cameraControl.setZoomRatio(target)
        }

        b.thumb.clipToOutline = true
        b.thumb.setOnClickListener {
            val u = lastUri ?: return@setOnClickListener
            if (recording != null) return@setOnClickListener
            resultLauncher.launch(
                Intent(this, ResultActivity::class.java)
                    .putExtra("uri", u.toString())
                    .putExtra("video", lastIsVideo)
            )
        }
    }

    private fun applyModeLayout() {
        val w = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val lp = b.previewContainer.layoutParams as FrameLayout.LayoutParams
        lp.width = w
        lp.gravity = Gravity.TOP
        if (isVideoMode) {
            lp.height = min(screenH, w * 16 / 9)
            lp.topMargin = 0
        } else {
            lp.height = w * 4 / 3
            lp.topMargin = topInset + dp(60)
        }
        b.previewContainer.layoutParams = lp
    }

    private fun setMode(video: Boolean) {
        if (video == isVideoMode || recording != null || countdownRunning) return
        isVideoMode = video
        videoOptionIndex = 0
        useFpsRange = true
        photoStyle = ExtensionMode.NONE
        applyModeLayout()
        updateTabs()
        updateShutter()
        updatePanels()
        bindCamera()
    }

    private fun updateTabs() {
        b.tabPhoto.setTextColor(if (!isVideoMode) ContextCompat.getColor(this, R.color.accent) else Color.WHITE)
        b.tabVideo.setTextColor(if (isVideoMode) ContextCompat.getColor(this, R.color.accent) else Color.WHITE)
    }

    private fun updateShutter() {
        val res = when {
            recording != null -> R.drawable.bg_shutter_stop
            isVideoMode -> R.drawable.bg_shutter_core_video
            else -> R.drawable.bg_shutter_core
        }
        b.shutterCore.setBackgroundResource(res)
        val scale = if (recording != null) 0.55f else 1f
        b.shutterCore.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
    }

    private fun updateFlashIcon() {
        val icon = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
            ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
            else -> R.drawable.ic_flash_off
        }
        b.btnFlash.setImageResource(icon)
        tint(b.btnFlash, flashMode != ImageCapture.FLASH_MODE_OFF)
    }

    private fun updateChip() {
        if (isVideoMode) {
            val o = videoOptions.getOrNull(videoOptionIndex)
            b.qualityChip.text = if (o != null) "${o.label} · ${o.fps}fps" else ""
        } else {
            b.qualityChip.text = if (maxMegapixels > 0) "حتى ${maxMegapixels}MP" else "HD"
        }
    }

    private fun tint(button: ImageButton, active: Boolean) {
        val c = if (active) ContextCompat.getColor(this, R.color.accent) else Color.WHITE
        button.imageTintList = ColorStateList.valueOf(c)
    }

    // ------------------------------------------------------------------ filters / exposure / styles UI

    private fun addChip(row: LinearLayout, text: String, selected: Boolean, onClick: () -> Unit) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(if (selected) ContextCompat.getColor(this@MainActivity, R.color.accent) else Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setBackgroundResource(R.drawable.bg_chip)
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }
        row.addView(tv, lp)
    }

    private fun buildFilterRow() {
        b.filterRow.removeAllViews()
        for (f in Filters.all) {
            addChip(b.filterRow, f.label, f === selectedFilter) {
                selectedFilter = f
                buildFilterRow()
                applyLiveFilter()
                updatePanels()
            }
        }
    }

    private fun updatePanels() {
        b.evPanel.visibility = if (evPanelOpen) View.VISIBLE else View.GONE
        b.filterScroll.visibility = if (filterPanelOpen && !isVideoMode) View.VISIBLE else View.GONE
        tint(b.btnEv, evPanelOpen)
        tint(b.btnFilters, filterPanelOpen || selectedFilter.matrix != null)
        b.btnFilters.alpha = if (isVideoMode) 0.3f else 1f
    }

    private fun updateStyleRow() {
        b.styleRow.removeAllViews()
        if (isVideoMode || styleOptions.isEmpty()) {
            b.styleScroll.visibility = View.GONE
            return
        }
        b.styleScroll.visibility = View.VISIBLE
        addChip(b.styleRow, "عادي", photoStyle == ExtensionMode.NONE) {
            photoStyle = ExtensionMode.NONE
            bindCamera()
        }
        for ((mode, label) in styleOptions) {
            addChip(b.styleRow, label, photoStyle == mode) {
                photoStyle = mode
                bindCamera()
            }
        }
    }

    private fun updateEvLabel(index: Int) {
        b.evLabel.text = String.format(Locale.US, "%+.1f EV", index * evStep)
    }

    private fun setupExposure(cam: Camera) {
        val st = cam.cameraInfo.exposureState
        if (!st.isExposureCompensationSupported) {
            b.evSeek.isEnabled = false
            b.btnEv.alpha = 0.3f
            return
        }
        b.btnEv.alpha = 1f
        b.evSeek.isEnabled = true
        val range = st.exposureCompensationRange
        evLower = range.lower
        evStep = st.exposureCompensationStep.toFloat()
        b.evSeek.max = range.upper - range.lower
        b.evSeek.progress = -range.lower
        updateEvLabel(0)
    }

    /** الفلتر الحي على المعاينة (TextureView داخل PreviewView). */
    private fun applyLiveFilter() {
        val tv = findTextureView(b.previewView) ?: return
        val m = if (isVideoMode) null else selectedFilter.matrix
        val paint = if (m != null) Paint().apply { colorFilter = ColorMatrixColorFilter(m) } else null
        tv.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }

    private fun findTextureView(v: View): TextureView? {
        if (v is TextureView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findTextureView(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ camera binding

    private fun startCamera() {
        if (cameraProvider != null) {
            bindCamera()
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            try {
                val extFuture = ExtensionsManager.getInstanceAsync(this, provider)
                extFuture.addListener({
                    extensionsManager = try { extFuture.get() } catch (_: Throwable) { null }
                    bindCamera()
                }, ContextCompat.getMainExecutor(this))
            } catch (_: Throwable) {
                bindCamera()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun selectorFor(facing: Int) =
        CameraSelector.Builder().requireLensFacing(facing).build()

    private fun safeHasCamera(provider: ProcessCameraProvider, selector: CameraSelector) =
        try { provider.hasCamera(selector) } catch (_: Exception) { false }

    private fun computeStyles(selector: CameraSelector): List<Pair<Int, String>> {
        val em = extensionsManager ?: return emptyList()
        val all = listOf(
            ExtensionMode.HDR to "HDR",
            ExtensionMode.NIGHT to "ليل",
            ExtensionMode.AUTO to "تلقائي",
            ExtensionMode.FACE_RETOUCH to "تجميل",
            ExtensionMode.BOKEH to "بورتريه"
        )
        return all.filter {
            try { em.isExtensionAvailable(selector, it.first) } catch (_: Throwable) { false }
        }
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return

        camera?.cameraInfo?.let {
            it.zoomState.removeObservers(this)
            it.cameraState.removeObservers(this)
        }
        provider.unbindAll()
        camera = null
        imageCapture = null
        videoCapture = null

        var selector = selectorFor(lensFacing)
        if (!safeHasCamera(provider, selector)) {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            selector = selectorFor(lensFacing)
            if (!safeHasCamera(provider, selector)) {
                toast("لا توجد كاميرا متاحة على هذا الجهاز")
                return
            }
        }

        val info: CameraInfo? = try {
            selector.filter(provider.availableCameraInfos).firstOrNull()
        } catch (_: Exception) { null }
        val chars = characteristicsFor(lensFacing)
        maxMegapixels = computeMegapixels(chars)
        videoOptions = buildVideoOptions(info, chars)
        if (videoOptionIndex !in videoOptions.indices) videoOptionIndex = 0
        val option = videoOptions[videoOptionIndex]
        val range = if (isVideoMode && useFpsRange) option.range else null

        // أنماط الشركة المصنعة (HDR/ليل/...) إن كان الجهاز يدعمها
        styleOptions = if (!isVideoMode) computeStyles(selector) else emptyList()
        if (photoStyle != ExtensionMode.NONE && styleOptions.none { it.first == photoStyle }) {
            photoStyle = ExtensionMode.NONE
        }
        val useExt = !isVideoMode && photoStyle != ExtensionMode.NONE
        val bindSelector = if (useExt) {
            try {
                extensionsManager!!.getExtensionEnabledCameraSelector(selector, photoStyle)
            } catch (_: Throwable) {
                photoStyle = ExtensionMode.NONE
                selector
            }
        } else selector
        val extActive = useExt && photoStyle != ExtensionMode.NONE

        try {
            camera = bindUseCases(provider, bindSelector, option, range, extActive, chars)
        } catch (e: Exception) {
            if (extActive) {
                photoStyle = ExtensionMode.NONE
                toast("هذا النمط غير متاح حالياً على جهازك")
                try {
                    camera = bindUseCases(provider, selector, option, null, false, chars)
                } catch (e2: Exception) {
                    toast("تعذّر تشغيل الكاميرا: ${e2.message}")
                }
            } else if (range != null) {
                useFpsRange = false
                try {
                    camera = bindUseCases(provider, selector, option, null, false, chars)
                } catch (e2: Exception) {
                    toast("تعذّر تشغيل الكاميرا: ${e2.message}")
                }
            } else {
                toast("تعذّر تشغيل الكاميرا: ${e.message}")
            }
        }

        updateStyleRow()

        camera?.let { cam ->
            cam.cameraInfo.zoomState.observe(this) { z ->
                b.zoomChip.text = String.format(Locale.US, "%.1f×", z.zoomRatio)
            }
            cam.cameraInfo.cameraState.observe(this) { st ->
                // لو الجهاز رفض معدل الإطارات المرتفع نرجع تلقائياً للوضع الآمن
                if (st.error != null && isVideoMode && activeRange != null && useFpsRange) {
                    useFpsRange = false
                    handler.post {
                        bindCamera()
                        toast("الجهاز لا يدعم هذا المعدل بهذه الدقة، تم التحويل للوضع الآمن")
                    }
                }
            }
            setupExposure(cam)
        }
        applyFlash()
        updateChip()
        applyLiveFilter()
    }

    private fun bindUseCases(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
        option: VideoOption,
        range: Range<Int>?,
        extActive: Boolean,
        chars: CameraCharacteristics?
    ): Camera {
        val previewBuilder = Preview.Builder()
        if (!extActive) {
            // المعاينة بحجم معقول (لا حاجة لأعلى دقة)
            val previewSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    if (isVideoMode) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                    else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                )
                .setResolutionStrategy(
                    ResolutionStrategy(
                        if (isVideoMode) Size(1920, 1080) else Size(1440, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .build()
            previewBuilder.setResolutionSelector(previewSelector)
        }
        if (range != null) {
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
        }
        val preview = previewBuilder.build()
        preview.setSurfaceProvider(b.previewView.surfaceProvider)

        val cam: Camera
        if (isVideoMode) {
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        option.quality,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
            val vb = VideoCapture.Builder(recorder)
            if (range != null) {
                Camera2Interop.Extender(vb)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            }
            val vc = vb.build()
            videoCapture = vc
            cam = provider.bindToLifecycle(this, selector, preview, vc)
        } else {
            val icBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(100)
                .setFlashMode(flashMode)
            if (!extActive) {
                // أعلى دقة ممكنة
                icBuilder.setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build()
                )
                applyQualityModes(icBuilder, chars)
            }
            val ic = icBuilder.build()
            imageCapture = ic
            cam = provider.bindToLifecycle(this, selector, preview, ic)
        }
        activeRange = range
        return cam
    }

    /** يطلب من الكاميرا أعلى جودة لخفض التشويش وتحسين الحدّة (فقط ما يدعمه الجهاز). */
    private fun applyQualityModes(builder: ImageCapture.Builder, chars: CameraCharacteristics?) {
        if (chars == null) return
        val ext = Camera2Interop.Extender(builder)

        fun supports(key: CameraCharacteristics.Key<IntArray>, value: Int): Boolean =
            chars.get(key)?.contains(value) == true

        if (supports(
                CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            )
        ) ext.setCaptureRequestOption(
            CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
        )

        if (supports(
                CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES,
                CaptureRequest.EDGE_MODE_HIGH_QUALITY
            )
        ) ext.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)

        if (supports(
                CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
            )
        ) ext.setCaptureRequestOption(
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        )

        if (supports(
                CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES,
                CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
            )
        ) ext.setCaptureRequestOption(
            CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
        )
    }

    private fun applyFlash() {
        val cam = camera ?: return
        imageCapture?.flashMode = flashMode
        try {
            cam.cameraControl.enableTorch(isVideoMode && flashMode == ImageCapture.FLASH_MODE_ON)
        } catch (_: Exception) { }
    }

    // ------------------------------------------------------------------ hardware capabilities

    private fun characteristicsFor(facing: Int): CameraCharacteristics? = try {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (facing == CameraSelector.LENS_FACING_BACK)
            CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        cm.cameraIdList
            .map { cm.getCameraCharacteristics(it) }
            .firstOrNull { it.get(CameraCharacteristics.LENS_FACING) == want }
    } catch (_: Throwable) {
        null
    }

    private fun computeMegapixels(chars: CameraCharacteristics?): Int = try {
        val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        var best = 0L
        map?.getOutputSizes(ImageFormat.JPEG)?.forEach { best = maxOf(best, it.width.toLong() * it.height) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            map?.getHighResolutionOutputSizes(ImageFormat.JPEG)
                ?.forEach { best = maxOf(best, it.width.toLong() * it.height) }
        }
        Math.round(best / 1_000_000.0).toInt()
    } catch (_: Throwable) {
        0
    }

    private fun maxFpsFor(chars: CameraCharacteristics?, size: Size): Int = try {
        val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val d = map?.getOutputMinFrameDuration(SurfaceTexture::class.java, size) ?: 0L
        if (d > 0) (1_000_000_000L / d).toInt() else 30
    } catch (_: Throwable) {
        30
    }

    private fun pickRange(ranges: List<Range<Int>>, cap: Int): Range<Int>? =
        ranges.filter { it.upper <= cap }
            .maxWithOrNull(compareBy<Range<Int>>({ it.upper }, { it.lower }))

    private fun buildVideoOptions(info: CameraInfo?, chars: CameraCharacteristics?): List<VideoOption> {
        val supported: List<Quality> = try {
            if (info != null) QualitySelector.getSupportedQualities(info) else emptyList()
        } catch (_: Throwable) { emptyList() }
        val ranges: List<Range<Int>> =
            chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList()
                ?: emptyList()

        val specs = listOf(
            Triple(Quality.UHD, "4K", Size(3840, 2160)),
            Triple(Quality.FHD, "FHD", Size(1920, 1080)),
            Triple(Quality.HD, "HD", Size(1280, 720))
        )
        val out = mutableListOf<VideoOption>()
        for ((quality, label, size) in specs) {
            if (quality !in supported) continue
            val cap = min(maxFpsFor(chars, size), 60)
            val best = pickRange(ranges, cap)
            val fps = best?.upper ?: 30
            out += VideoOption(quality, label, fps, best)
            if (fps > 30) {
                val r30 = pickRange(ranges, 30)
                out += VideoOption(quality, label, r30?.upper ?: 30, r30)
            }
        }
        if (out.isEmpty()) out += VideoOption(Quality.FHD, "FHD", 30, null)
        return out
    }

    // ------------------------------------------------------------------ focus

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = b.previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)
        showFocusRing(x, y)
    }

    private fun showFocusRing(x: Float, y: Float) {
        val ring = b.focusRing
        val half = dp(36).toFloat()
        ring.animate().cancel()
        ring.translationX = x - half
        ring.translationY = y - half
        ring.visibility = View.VISIBLE
        ring.alpha = 1f
        ring.scaleX = 1.4f
        ring.scaleY = 1.4f
        ring.animate().scaleX(1f).scaleY(1f).setDuration(200).setStartDelay(0).withEndAction {
            ring.animate().alpha(0f).setStartDelay(900).setDuration(300).withEndAction {
                ring.visibility = View.GONE
                ring.animate().setStartDelay(0)
            }.start()
        }.start()
    }

    // ------------------------------------------------------------------ timer

    private fun startWithTimer(action: () -> Unit) {
        if (timerSeconds == 0) {
            action()
            return
        }
        countdownRunning = true
        var left = timerSeconds
        b.countdown.visibility = View.VISIBLE
        val r = object : Runnable {
            override fun run() {
                if (left > 0) {
                    b.countdown.text = left.toString()
                    left--
                    handler.postDelayed(this, 1000)
                } else {
                    b.countdown.visibility = View.GONE
                    countdownRunning = false
                    action()
                }
            }
        }
        countdownRunnable = r
        handler.post(r)
    }

    private fun cancelCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        countdownRunning = false
        b.countdown.visibility = View.GONE
    }

    // ------------------------------------------------------------------ photo

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val tmp = File.createTempFile("cap_", ".jpg", cacheDir)
        val options = ImageCapture.OutputFileOptions.Builder(tmp).build()

        // نثبّت الإعدادات لحظة الضغط (حتى لو غيّرها المستخدم أثناء المعالجة)
        val doEnhance = enhance
        val filterMatrix = selectedFilter.matrix

        sound.play(MediaActionSound.SHUTTER_CLICK)
        b.flashOverlay.alpha = 0.7f
        b.flashOverlay.animate().alpha(0f).setDuration(200).start()

        ic.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val uri = try {
                    MediaSaver.savePhoto(applicationContext, tmp, doEnhance, filterMatrix)
                } catch (_: Throwable) {
                    null
                } finally {
                    tmp.delete()
                }
                runOnUiThread {
                    if (uri == null) {
                        toast("تعذّر حفظ الصورة")
                    } else {
                        lastUri = uri
                        lastIsVideo = false
                        updateThumb()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                tmp.delete()
                runOnUiThread { toast("فشل الالتقاط: ${exception.message}") }
            }
        })
    }

    // ------------------------------------------------------------------ video

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ADSA_${MediaSaver.timestamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/${MediaSaver.FOLDER}")
            }
        }
        val output = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending = vc.output.prepareRecording(this, output)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            pending = pending.withAudioEnabled()
        }

        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> setRecordingUi(true)
                is VideoRecordEvent.Status -> {
                    val secs = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                    b.recTimer.text = String.format(Locale.US, "%02d:%02d", secs / 60, secs % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    setRecordingUi(false)
                    if (event.hasError()) {
                        toast("توقف التسجيل (رمز ${event.error})")
                    } else {
                        lastUri = event.outputResults.outputUri
                        lastIsVideo = true
                        updateThumb()
                    }
                }
                else -> Unit
            }
        }
    }

    private fun stopRecording() {
        sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        recording?.stop()
    }

    private fun setRecordingUi(active: Boolean) {
        b.recTimer.visibility = if (active) View.VISIBLE else View.GONE
        if (active) b.recTimer.text = "00:00"
        b.tabPhoto.alpha = if (active) 0.3f else 1f
        b.tabVideo.alpha = if (active) 0.3f else 1f
        b.btnFlip.alpha = if (active) 0.3f else 1f
        b.qualityChip.alpha = if (active) 0.5f else 1f
        updateShutter()
    }

    // ------------------------------------------------------------------ gallery thumb

    private fun refreshLast() {
        Thread {
            val latest = MediaSaver.latest(applicationContext)
            if (latest != null) {
                lastUri = latest.first
                lastIsVideo = latest.second
                runOnUiThread { updateThumb() }
            }
        }.start()
    }

    private fun updateThumb() {
        val u = lastUri ?: return
        val video = lastIsVideo
        Thread {
            val bmp = MediaSaver.thumbnail(applicationContext, u, video, dp(108))
            runOnUiThread { if (bmp != null) b.thumb.setImageBitmap(bmp) }
        }.start()
    }

    // ------------------------------------------------------------------ helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
