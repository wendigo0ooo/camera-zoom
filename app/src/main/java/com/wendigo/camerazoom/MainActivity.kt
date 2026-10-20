package com.wendigo.camerazoom

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var analysisView: ImageView
    private lateinit var freezeView: ImageView
    private lateinit var frozenBadge: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var zoomSlider: SeekBar
    private lateinit var permOverlay: LinearLayout
    private lateinit var btnTorch: ImageButton
    private lateinit var btnEnhance: ImageButton
    private lateinit var btnTele: ImageButton
    private lateinit var focusRing: View

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var zoomObserver: Observer<ZoomState>? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var minZoom = 1f
    private var maxZoom = 1f
    private var torchOn = false
    private var frozen = false
    private var enhanceMode = false
    private var teleMode = false
    private var teleAvailable = false
    private var permissionAsked = false
    private var cameraStarting = false
    private var sliderTracking = false

    private var totalZoom = 1f
    private var freezeBitmap: Bitmap? = null

    @Volatile
    private var digitalFactor = 1f

    @Volatile
    private var lastFrame: Bitmap? = null

    @Volatile
    private var analysisTargetWidth = 720

    private var lastProcAt = 0L

    private val pendingFrame = AtomicReference<Bitmap?>(null)
    private val uiPostPending = AtomicBoolean(false)

    private lateinit var analyzerExecutor: ExecutorService
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var tapDetector: GestureDetector

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                permOverlay.visibility = View.GONE
                startCamera()
            } else {
                val permanentlyDenied =
                    !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                val message = if (permanentlyDenied) {
                    R.string.perm_settings_hint
                } else {
                    R.string.perm_message
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        analysisView = findViewById(R.id.analysisView)
        freezeView = findViewById(R.id.freezeView)
        frozenBadge = findViewById(R.id.frozenBadge)
        zoomLabel = findViewById(R.id.zoomLabel)
        zoomSlider = findViewById(R.id.zoomSlider)
        permOverlay = findViewById(R.id.permOverlay)
        btnTorch = findViewById(R.id.btnTorch)
        btnEnhance = findViewById(R.id.btnEnhance)
        btnTele = findViewById(R.id.btnTele)
        focusRing = findViewById(R.id.focusRing)
        val btnSwitch = findViewById<ImageButton>(R.id.btnSwitch)
        val btnFreeze = findViewById<ImageButton>(R.id.btnFreeze)
        val btnZoomIn = findViewById<ImageButton>(R.id.btnZoomIn)
        val btnZoomOut = findViewById<ImageButton>(R.id.btnZoomOut)
        val btnPerm = findViewById<Button>(R.id.btnPerm)

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0) analysisTargetWidth = width.coerceAtMost(MAX_PROC_WIDTH)
        }
        freezeView.isClickable = true

        analyzerExecutor = Executors.newSingleThreadExecutor()
        setupGestures()
        setupPresets()
        setupBackNavigation()

        permissionAsked = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_PERM_ASKED, false)

        totalZoom = savedInstanceState?.getFloat(KEY_ZOOM) ?: 1f
        lensFacing = savedInstanceState?.getInt(KEY_LENS, CameraSelector.LENS_FACING_BACK)
            ?: CameraSelector.LENS_FACING_BACK
        teleMode = savedInstanceState?.getBoolean(KEY_TELE) ?: false
        enhanceMode = savedInstanceState?.getBoolean(KEY_ENHANCE) ?: false
        updateEnhanceUi()
        updateTeleButton()
        updateZoomLabel(totalZoom)
        updateSlider(totalZoom)

        btnTorch.setOnClickListener { toggleTorch() }
        btnEnhance.setOnClickListener { toggleEnhance() }
        btnTele.setOnClickListener { toggleTele() }
        btnSwitch.setOnClickListener { switchCamera() }
        btnFreeze.setOnClickListener { toggleFreeze() }
        btnZoomIn.setOnClickListener {
            if (camera == null) return@setOnClickListener
            tapHaptic()
            applyZoom(totalZoom * ZOOM_STEP)
        }
        btnZoomOut.setOnClickListener {
            if (camera == null) return@setOnClickListener
            tapHaptic()
            applyZoom(totalZoom / ZOOM_STEP)
        }
        btnPerm.setOnClickListener { requestCameraPermission() }
        updateTorchUi()

        zoomSlider.isEnabled = false
        zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) applyZoom(sliderToZoom(progress), syncSlider = false)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                sliderTracking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                sliderTracking = false
                updateSlider(totalZoom)
            }
        })

        if (hasCameraPermission()) startCamera() else showPermission()
    }

    private fun setupGestures() {
        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    applyZoom(totalZoom * detector.scaleFactor)
                    return true
                }
            }
        )
        tapDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    focusAt(e.x, e.y)
                    return true
                }
            }
        )
        val touchListener = View.OnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)
            true
        }
        previewView.setOnTouchListener(touchListener)
        analysisView.setOnTouchListener(touchListener)
    }

    private fun setupPresets() {
        listOf(
            R.id.preset1 to 1f,
            R.id.preset5 to 5f,
            R.id.preset10 to 10f,
            R.id.preset30 to 30f
        ).forEach { (id, ratio) ->
            findViewById<TextView>(id).setOnClickListener {
                if (camera == null) {
                    Toast.makeText(this, R.string.camera_not_ready, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                tapHaptic()
                applyZoom(ratio)
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    frozen -> unfreeze()
                    enhanceMode -> toggleEnhance()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat(KEY_ZOOM, totalZoom)
        outState.putInt(KEY_LENS, lensFacing)
        outState.putBoolean(KEY_TELE, teleMode)
        outState.putBoolean(KEY_ENHANCE, enhanceMode)
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) {
            permOverlay.visibility = View.GONE
            if (cameraProvider == null && !cameraStarting) {
                startCamera()
            } else if (camera == null && !cameraStarting) {
                bindCamera()
            }
        } else {
            showPermission()
        }
    }

    override fun onDestroy() {
        analyzerExecutor.shutdown()
        freezeView.setImageBitmap(null)
        val frozenBitmap = freezeBitmap
        freezeBitmap = null
        if (frozenBitmap != null && frozenBitmap !== lastFrame) frozenBitmap.recycle()
        lastFrame = null
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        if (hasCameraPermission()) {
            permOverlay.visibility = View.GONE
            startCamera()
            return
        }
        if (permissionAsked && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            openAppSettings()
            return
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.perm_rationale_title)
                .setMessage(R.string.perm_rationale_message)
                .setPositiveButton(R.string.perm_allow) { _, _ -> launchPermissionRequest() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            launchPermissionRequest()
        }
    }

    private fun launchPermissionRequest() {
        permissionAsked = true
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PERM_ASKED, true)
            .apply()
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun openAppSettings() {
        Toast.makeText(this, R.string.perm_settings_hint, Toast.LENGTH_LONG).show()
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun showPermission() {
        permOverlay.visibility = View.VISIBLE
        zoomSlider.isEnabled = false
    }

    private fun startCamera() {
        if (cameraStarting) return
        cameraStarting = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraStarting = false
            if (isFinishing || isDestroyed) return@addListener
            try {
                cameraProvider = future.get()
                bindCamera()
            } catch (e: Exception) {
                Log.e(TAG, "camera provider failed", e)
                Toast.makeText(this, R.string.camera_open_failed, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        if (!hasCameraPermission()) {
            showPermission()
            return
        }

        var selector = resolveSelector(provider)
        if (!safeHasCamera(provider, selector)) {
            if (teleMode) teleMode = false
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                lensFacing = CameraSelector.LENS_FACING_BACK
            }
            selector = resolveSelector(provider)
            if (!safeHasCamera(provider, selector)) {
                Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_LONG).show()
                return
            }
        }
        teleAvailable = findTeleCameraId(provider) != null

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val useCases = mutableListOf<UseCase>(preview)
        if (enhanceMode) {
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(analyzerExecutor) { proxy -> analyzeFrame(proxy) }
                }
            useCases.add(analysis)
        }

        try {
            val oldCamera = camera
            zoomObserver?.let { oldCamera?.cameraInfo?.zoomState?.removeObserver(it) }
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, *useCases.toTypedArray())
            torchOn = false
            updateTorchUi()

            val zoomState = camera?.cameraInfo?.zoomState
            zoomState?.value?.let { zs ->
                minZoom = zs.minZoomRatio
                maxZoom = zs.maxZoomRatio
            }
            val observer = Observer<ZoomState> { zs ->
                minZoom = zs.minZoomRatio
                maxZoom = zs.maxZoomRatio
                val actual = zs.zoomRatio.coerceAtLeast(1f)
                digitalFactor = (totalZoom / actual).coerceIn(1f, MAX_DIGITAL_FACTOR)
                previewView.scaleX = digitalFactor
                previewView.scaleY = digitalFactor
                updateZoomLabel(totalZoom)
                if (!sliderTracking) updateSlider(totalZoom)
            }
            zoomObserver = observer
            zoomState?.observe(this, observer)

            updateTeleButton()
            applyZoom(totalZoom)
            zoomSlider.isEnabled = true
        } catch (e: Exception) {
            Log.e(TAG, "camera bind failed", e)
            camera = null
            Toast.makeText(this, R.string.camera_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun safeHasCamera(
        provider: ProcessCameraProvider,
        selector: CameraSelector
    ): Boolean = try {
        provider.hasCamera(selector)
    } catch (_: Exception) {
        false
    }

    /**
     * Arka kamerada, varsayılan geniş kameradan belirgin biçimde daha uzun odak
     * uzunluğuna sahip ayrı bir fiziksel kamera varsa onu seçer. Yoksa varsayılan
     * (lensFacing) seçici kullanılır ve tele modu kapatılır.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun resolveSelector(provider: ProcessCameraProvider): CameraSelector {
        if (lensFacing == CameraSelector.LENS_FACING_BACK && teleMode) {
            val teleId = findTeleCameraId(provider)
            if (teleId != null) {
                return CameraSelector.Builder()
                    .addCameraFilter { infos ->
                        infos.filter { Camera2CameraInfo.from(it).cameraId == teleId }
                    }
                    .build()
            }
            teleMode = false
            updateTeleButton()
        }
        return CameraSelector.Builder().requireLensFacing(lensFacing).build()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun findTeleCameraId(provider: ProcessCameraProvider): String? {
        val defaultInfo = CameraSelector.DEFAULT_BACK_CAMERA
            .filter(provider.availableCameraInfos)
            .firstOrNull() ?: return null
        val defaultId = Camera2CameraInfo.from(defaultInfo).cameraId
        val baseFocal = maxFocalLength(defaultInfo) ?: return null
        provider.availableCameraInfos.forEach { info ->
            Log.d(
                TAG,
                "camera=${Camera2CameraInfo.from(info).cameraId} facing=${info.lensFacing} " +
                    "focal=${maxFocalLength(info)}"
            )
        }
        return provider.availableCameraInfos
            .filter {
                it.lensFacing == null || it.lensFacing == CameraSelector.LENS_FACING_BACK
            }
            .mapNotNull { info -> maxFocalLength(info)?.let { info to it } }
            .filter { (info, focal) ->
                focal >= baseFocal * TELE_FOCAL_RATIO &&
                    Camera2CameraInfo.from(info).cameraId != defaultId
            }
            .maxByOrNull { it.second }
            ?.let { Camera2CameraInfo.from(it.first).cameraId }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun maxFocalLength(info: CameraInfo): Float? = try {
        Camera2CameraInfo.from(info)
            .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.maxOrNull()
    } catch (_: Exception) {
        null
    }

    private fun updateZoomLabel(ratio: Float) {
        val base = if (teleMode) {
            getString(R.string.zoom_tele_fmt, ratio)
        } else {
            getString(R.string.zoom_fmt, ratio)
        }
        zoomLabel.text = if (digitalFactor > 1.05f) {
            getString(R.string.zoom_digital_suffix, base)
        } else {
            base
        }
    }

    private fun updateTeleButton() {
        btnTele.setColorFilter(
            if (teleMode) ContextCompat.getColor(this, R.color.accent) else Color.WHITE
        )
        btnTele.alpha = when {
            lensFacing == CameraSelector.LENS_FACING_FRONT -> 0.4f
            !teleAvailable && !teleMode -> 0.5f
            else -> 1f
        }
        btnTele.contentDescription = getString(
            when {
                lensFacing == CameraSelector.LENS_FACING_FRONT -> R.string.cd_tele_none
                teleAvailable || teleMode -> R.string.cd_tele
                else -> R.string.cd_tele_none
            }
        )
    }

    private fun toggleTele() {
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            Toast.makeText(this, R.string.tele_front, Toast.LENGTH_SHORT).show()
            return
        }
        val provider = cameraProvider
        if (provider == null) {
            Toast.makeText(this, R.string.camera_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        if (!teleMode && findTeleCameraId(provider) == null) {
            Toast.makeText(this, R.string.tele_none, Toast.LENGTH_LONG).show()
            return
        }
        teleMode = !teleMode
        Toast.makeText(
            this,
            if (teleMode) R.string.tele_on else R.string.tele_off,
            Toast.LENGTH_SHORT
        ).show()
        totalZoom = 1f
        digitalFactor = 1f
        previewView.scaleX = 1f
        previewView.scaleY = 1f
        unfreeze()
        updateTeleButton()
        if (hasCameraPermission()) bindCamera()
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        if (!enhanceMode) {
            proxy.close()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastProcAt < FRAME_INTERVAL_MS) {
            proxy.close()
            return
        }
        lastProcAt = now
        try {
            val frame = FrameEnhancer.toBitmap(proxy)
            if (frame != null) {
                val frameWidth = frame.width
                val frameHeight = frame.height
                val started = SystemClock.elapsedRealtime()
                val out = try {
                    FrameEnhancer.process(frame, digitalFactor, analysisTargetWidth)
                } finally {
                    frame.recycle()
                }
                lastFrame = out
                val elapsed = SystemClock.elapsedRealtime() - started
                if (elapsed > 60L) {
                    Log.d(TAG, "enhance ${frameWidth}x${frameHeight} -> ${out.width} in ${elapsed}ms")
                }
                pendingFrame.set(out)
                if (uiPostPending.compareAndSet(false, true)) {
                    runOnUiThread {
                        val latest = pendingFrame.getAndSet(null)
                        uiPostPending.set(false)
                        if (latest != null && !isFinishing && !isDestroyed && enhanceMode) {
                            analysisView.setImageBitmap(latest)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame processing failed", e)
        } finally {
            proxy.close()
        }
    }

    /**
     * Kullanıcının istediği toplam zoom. Donanım (setZoomRatio) sınırına kadar
     * kamera ayarlanır; üzeri dijital kırpma + iyileştirme ile sağlanır.
     */
    private fun applyZoom(requested: Float, syncSlider: Boolean = true) {
        if (camera == null) return
        if (frozen) unfreeze()
        val hwMax = maxZoom.coerceAtLeast(1f)
        val maxTotal = (hwMax * MAX_DIGITAL_FACTOR)
            .coerceAtMost(totalZoomCap())
            .coerceAtLeast(minZoom)
        totalZoom = requested.coerceIn(minZoom, maxTotal)
        val camRatio = totalZoom.coerceAtMost(hwMax)
        camera?.cameraControl?.setZoomRatio(camRatio)
        digitalFactor = (totalZoom / camRatio).coerceIn(1f, MAX_DIGITAL_FACTOR)

        previewView.scaleX = digitalFactor
        previewView.scaleY = digitalFactor
        updateZoomLabel(totalZoom)
        if (syncSlider && !sliderTracking) updateSlider(totalZoom)
    }

    private fun sliderToZoom(progress: Int): Float =
        ZoomMath.sliderToZoom(progress, minZoom, totalZoomCap(), SLIDER_MAX)

    private fun updateSlider(ratio: Float) {
        zoomSlider.progress = ZoomMath.zoomToSlider(ratio, minZoom, totalZoomCap(), SLIDER_MAX)
    }

    private fun totalZoomCap(): Float =
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            MAX_TOTAL_ZOOM_FRONT
        } else {
            MAX_TOTAL_ZOOM
        }

    private fun updateEnhanceUi() {
        btnEnhance.setColorFilter(
            if (enhanceMode) ContextCompat.getColor(this, R.color.accent) else Color.WHITE
        )
        btnEnhance.contentDescription = getString(
            if (enhanceMode) R.string.cd_enhance_on else R.string.cd_enhance_off
        )
        analysisView.visibility = if (enhanceMode) View.VISIBLE else View.GONE
        previewView.visibility = if (enhanceMode) View.INVISIBLE else View.VISIBLE
    }

    private fun toggleEnhance() {
        enhanceMode = !enhanceMode
        updateEnhanceUi()
        unfreeze()
        if (!enhanceMode) {
            analysisView.setImageBitmap(null)
            lastFrame = null
        } else {
            Toast.makeText(this, R.string.enhance_on, Toast.LENGTH_SHORT).show()
        }
        if (hasCameraPermission()) bindCamera()
    }

    private fun updateTorchUi() {
        val available = camera?.cameraInfo?.hasFlashUnit() == true
        btnTorch.isEnabled = available
        btnTorch.setImageResource(
            if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        )
        btnTorch.setColorFilter(
            if (torchOn) ContextCompat.getColor(this, R.color.accent) else Color.WHITE
        )
        btnTorch.contentDescription = getString(
            if (torchOn) R.string.cd_torch_on else R.string.cd_torch_off
        )
    }

    private fun toggleTorch() {
        val c = camera ?: return
        val target = !torchOn
        c.cameraControl.enableTorch(target).addListener({
            try {
                it.get()
                torchOn = target
            } catch (e: Exception) {
                Log.w(TAG, "torch failed", e)
            }
            updateTorchUi()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun switchCamera() {
        val provider = cameraProvider
        if (provider == null) {
            Toast.makeText(this, R.string.camera_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val target = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val targetSelector = CameraSelector.Builder().requireLensFacing(target).build()
        if (!safeHasCamera(provider, targetSelector)) {
            Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        lensFacing = target
        teleMode = false
        totalZoom = 1f
        digitalFactor = 1f
        previewView.scaleX = 1f
        previewView.scaleY = 1f
        unfreeze()
        updateTeleButton()
        bindCamera()
    }

    private fun toggleFreeze() {
        if (frozen) {
            unfreeze()
            return
        }
        val bmp = if (enhanceMode) lastFrame else previewView.bitmap
        if (bmp == null) {
            Toast.makeText(this, R.string.image_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        freezeBitmap = bmp
        freezeView.setImageBitmap(bmp)
        val scale = if (enhanceMode) 1f else digitalFactor
        freezeView.scaleX = scale
        freezeView.scaleY = scale
        freezeView.visibility = View.VISIBLE
        frozenBadge.visibility = View.VISIBLE
        frozen = true
    }

    private fun unfreeze() {
        frozen = false
        freezeView.setImageBitmap(null)
        freezeView.scaleX = 1f
        freezeView.scaleY = 1f
        freezeView.visibility = View.GONE
        frozenBadge.visibility = View.GONE
        val bmp = freezeBitmap
        freezeBitmap = null
        if (bmp != null && bmp !== lastFrame) bmp.recycle()
    }

    private fun focusAt(x: Float, y: Float) {
        val c = camera ?: return
        var fx = x
        var fy = y
        if (enhanceMode && digitalFactor > 1.01f) {
            val cx = previewView.width / 2f
            val cy = previewView.height / 2f
            fx = cx + (x - cx) / digitalFactor
            fy = cy + (y - cy) / digitalFactor
        }
        val point = previewView.meteringPointFactory.createPoint(fx, fy)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(5, TimeUnit.SECONDS).build()
        if (!enhanceMode && digitalFactor > 1.01f) {
            val cx = previewView.width / 2f
            val cy = previewView.height / 2f
            showFocusRing(
                cx + (x - cx) * digitalFactor,
                cy + (y - cy) * digitalFactor
            )
        } else {
            showFocusRing(x, y)
        }
        c.cameraControl.startFocusAndMetering(action)
    }

    private fun showFocusRing(x: Float, y: Float) {
        val size = focusRing.layoutParams?.width?.takeIf { it > 0 }
            ?: (FOCUS_RING_DP * resources.displayMetrics.density).toInt()
        focusRing.animate().cancel()
        focusRing.x = x - size / 2f
        focusRing.y = y - size / 2f
        focusRing.alpha = 1f
        focusRing.visibility = View.VISIBLE
        focusRing.animate()
            .alpha(0f)
            .setStartDelay(700)
            .setDuration(250)
            .withEndAction { focusRing.visibility = View.GONE }
            .start()
    }

    private fun tapHaptic() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    companion object {
        private const val TAG = "CamZoom"
        private const val PREFS = "cam_zoom"
        private const val KEY_PERM_ASKED = "perm_asked"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_LENS = "lens"
        private const val KEY_TELE = "tele"
        private const val KEY_ENHANCE = "enhance"
        private const val SLIDER_MAX = 1000
        private const val MAX_TOTAL_ZOOM = 60f
        private const val MAX_TOTAL_ZOOM_FRONT = 20f
        private const val MAX_DIGITAL_FACTOR = 8f
        private const val MAX_PROC_WIDTH = 720
        private const val FRAME_INTERVAL_MS = 40L
        private const val TELE_FOCAL_RATIO = 1.5f
        private const val ZOOM_STEP = 1.25f
        private const val FOCUS_RING_DP = 72
    }
}
