package com.edwardresearchlabs.aimotion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.edwardresearchlabs.aimotion.display.MotionPresentation
import com.edwardresearchlabs.aimotion.motion.MotionEngine
import com.edwardresearchlabs.aimotion.vision.FrameAnalyzer
import com.edwardresearchlabs.aimotion.vision.NoOpPoseEstimator
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), DisplayManager.DisplayListener {

    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var displayManager: DisplayManager
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val motionEngine = MotionEngine()
    private var presentation: MotionPresentation? = null
    private var fps: Float = 0f

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else updateStatus("Camera permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(this, null)
        buildUi()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }

        showExternalDisplay()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        root.addView(previewView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
            setBackgroundColor(0xAA000000.toInt())
        }

        val title = TextView(this).apply {
            text = "AI MOTION"
            textSize = 28f
            setTextColor(Color.WHITE)
        }
        statusView = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFDDDDDD.toInt())
        }

        panel.addView(title)
        panel.addView(statusView)

        root.addView(panel, FrameLayout.LayoutParams(
            (resources.displayMetrics.density * 330).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            leftMargin = 24
            topMargin = 24
        })

        setContentView(root)
        updateStatus("Starting…")
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor, FrameAnalyzer(
                poseEstimator = NoOpPoseEstimator(),
                onFps = {
                    fps = it
                    runOnUiThread { updateStatus() }
                },
                onPose = { pose ->
                    val events = motionEngine.update(pose)
                    if (events.isNotEmpty()) runOnUiThread { updateStatus(events.joinToString()) }
                }
            ))

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
            updateStatus()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showExternalDisplay() {
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val target = displays.firstOrNull()
        if (target == null) {
            presentation?.dismiss()
            presentation = null
            updateStatus()
            return
        }

        if (presentation?.display?.displayId == target.displayId) return
        presentation?.dismiss()
        presentation = MotionPresentation(this, target).also { it.show() }
        updateStatus()
    }

    private fun updateStatus(extra: String? = null) {
        val hdmi = presentation != null
        val text = buildString {
            appendLine("Camera: ON")
            appendLine("Frames: %.1f FPS".format(fps))
            appendLine("Body AI: adapter ready")
            appendLine("External display: ${if (hdmi) "CONNECTED" else "not detected"}")
            append("Game: Goalkeeper prototype")
            if (!extra.isNullOrBlank()) appendLine().append(extra)
        }
        statusView.text = text
    }

    override fun onDisplayAdded(displayId: Int) = showExternalDisplay()
    override fun onDisplayRemoved(displayId: Int) = showExternalDisplay()
    override fun onDisplayChanged(displayId: Int) = Unit

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(this)
        presentation?.dismiss()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
