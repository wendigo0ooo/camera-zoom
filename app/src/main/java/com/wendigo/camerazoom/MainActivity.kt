package com.wendigo.camerazoom

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.GestureDetector
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
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var minZoom = 1f
    private var maxZoom = 1f
    private var torchOn = false
    private var frozen = false
    private var enhanceMode = false

    private var totalZoom = 1f

    @Volatile
    private var digitalFactor = 1f

    @Volatile
    private var lastFrame: Bitmap? = null

    private var lastProcAt = 0L

    private lateinit var analyzerExecutor: ExecutorService
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var tapDetector: GestureDetector

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
        val btnSwitch = findViewById<ImageButton>(R.id.btnSwitch)
        val btnFreeze = findViewById<ImageButton>(R.id.btnFreeze)
        val btnZoomIn = findViewById<ImageButton>(R.id.btnZoomIn)
        val btnZoomOut = findViewById<ImageButton>(R.id.btnZoomOut)
        val btnPerm = findViewById<Button>(R.id.btnPerm)

        analyzerExecutor = Executors.newSingleThreadExecutor()
        setupGestures()

        btnTorch.setOnClickListener { toggleTorch() }
        btnEnhance.setOnClickListener { toggleEnhance() }
        btnSwitch.setOnClickListener { switchCamera() }
        btnFreeze.setOnClickListener { toggleFreeze() }
        btnZoomIn.setOnClickListener { applyZoom(totalZoom * 1.4f) }
        btnZoomOut.setOnClickListener { applyZoom(totalZoom / 1.4f) }
        btnPerm.setOnClickListener { requestCameraPermission() }

        btnEnhance.setColorFilter(Color.WHITE)

        zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) applyZoom(sliderToZoom(progress))
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {}
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
        previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)
            true
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                permOverlay.visibility = View.GONE
                startCamera()
            } else {
                Toast.makeText(this, R.string.perm_message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analyzerExecutor.shutdown()
    }

    private fun showPermission() {
        permOverlay.visibility = View.VISIBLE
        zoomSlider.isEnabled = false
    }

    private fun startCamera() {
        permOverlay.visibility = View.GONE
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(analyzerExecutor) { proxy -> analyzeFrame(proxy) }
            }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, preview, analysis)
            torchOn = false
            btnTorch.setImageResource(R.drawable.ic_flash_off)
            camera?.cameraInfo?.zoomState?.observe(this) { zs ->
                minZoom = zs.minZoomRatio
                maxZoom = zs.maxZoomRatio
                // Sadece sınırları ve dijital faktörü güncelle; setZoomRatio ÇAĞIRMA
                // (aksi halde zoomState gözlemcisi ile geri besleme döngüsü oluşur).
                val hwMax = if (maxZoom > 0f) maxZoom else 1f
                digitalFactor = (totalZoom / totalZoom.coerceAtMost(hwMax)).coerceAtLeast(1f)
                updateZoomLabel(totalZoom)
                updateSlider(totalZoom)
            }
            applyZoom(totalZoom)
            zoomSlider.isEnabled = true
        } catch (e: Exception) {
            Toast.makeText(this, "Kamera açılamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        if (!enhanceMode) {
            proxy.close()
            return
        }
        // Kare hızını sınırla: ağır işleme CPU'yu doyurup arayüzü kastırmasın.
        val now = SystemClock.elapsedRealtime()
        if (now - lastProcAt < FRAME_INTERVAL_MS) {
            proxy.close()
            return
        }
        lastProcAt = now
        try {
            val frame = FrameEnhancer.toBitmap(proxy)
            if (frame != null) {
                val targetW = (previewView.width.takeIf { it > 0 } ?: 1080).coerceAtMost(MAX_PROC_WIDTH)
                val out = FrameEnhancer.process(frame, digitalFactor, targetW)
                lastFrame = out
                runOnUiThread { analysisView.setImageBitmap(out) }
            }
        } catch (_: Exception) {
        } finally {
            proxy.close()
        }
    }

    /**
     * Kullanıcının istediği toplam zoom. Donanım (setZoomRatio) sınırına kadar
     * kamera ayarlanır; üzeri dijital kırpma + iyileştirme ile sağlanır.
     */
    private fun applyZoom(requested: Float) {
        val hwMax = if (maxZoom > 0f) maxZoom else 1f
        totalZoom = requested.coerceIn(minZoom, MAX_TOTAL_ZOOM)
        val camRatio = totalZoom.coerceAtMost(hwMax)
        camera?.cameraControl?.setZoomRatio(camRatio)
        digitalFactor = (totalZoom / camRatio).coerceAtLeast(1f)

        previewView.scaleX = digitalFactor
        previewView.scaleY = digitalFactor
        updateZoomLabel(totalZoom)
        updateSlider(totalZoom)
    }

    private fun sliderToZoom(progress: Int): Float {
        val t = progress / SLIDER_MAX.toFloat()
        return minZoom + (MAX_TOTAL_ZOOM - minZoom) * t
    }

    private fun updateSlider(ratio: Float) {
        if (MAX_TOTAL_ZOOM <= minZoom) return
        val t = (ratio - minZoom) / (MAX_TOTAL_ZOOM - minZoom)
        zoomSlider.progress = (t * SLIDER_MAX).toInt().coerceIn(0, SLIDER_MAX)
    }

    private fun updateZoomLabel(ratio: Float) {
        zoomLabel.text = getString(R.string.zoom_fmt, ratio)
    }

    private fun toggleEnhance() {
        enhanceMode = !enhanceMode
        btnEnhance.setColorFilter(
            if (enhanceMode) ContextCompat.getColor(this, R.color.accent) else Color.WHITE
        )
        analysisView.visibility = if (enhanceMode) View.VISIBLE else View.GONE
        previewView.visibility = if (enhanceMode) View.INVISIBLE else View.VISIBLE
        if (!enhanceMode) {
            analysisView.setImageBitmap(null)
            lastFrame = null
        } else {
            Toast.makeText(this, R.string.enhance_on, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleTorch() {
        val c = camera ?: return
        torchOn = !torchOn
        c.cameraControl.enableTorch(torchOn)
        btnTorch.setImageResource(
            if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        )
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        unfreeze()
        if (hasCameraPermission()) bindCamera()
    }

    private fun toggleFreeze() {
        if (frozen) {
            unfreeze()
        } else {
            val bmp: Bitmap? = if (enhanceMode) lastFrame else previewView.bitmap
            if (bmp == null) {
                Toast.makeText(this, "Görüntü alınamadı", Toast.LENGTH_SHORT).show()
                return
            }
            freezeView.setImageBitmap(bmp)
            freezeView.visibility = View.VISIBLE
            frozenBadge.visibility = View.VISIBLE
            frozen = true
        }
    }

    private fun unfreeze() {
        frozen = false
        freezeView.visibility = View.GONE
        frozenBadge.visibility = View.GONE
    }

    private fun focusAt(x: Float, y: Float) {
        val c = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        c.cameraControl.startFocusAndMetering(action)
    }

    companion object {
        private const val REQ_CAMERA = 10
        private const val SLIDER_MAX = 1000
        private const val MAX_TOTAL_ZOOM = 60f
        private const val MAX_PROC_WIDTH = 720
        private const val FRAME_INTERVAL_MS = 40L
    }
}
