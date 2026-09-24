package com.edwardresearchlabs.aimotion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
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
import com.edwardresearchlabs.aimotion.display.PoseOverlay
import com.edwardresearchlabs.aimotion.motion.MotionEngine
import com.edwardresearchlabs.aimotion.motion.MotionRuntime
import com.edwardresearchlabs.aimotion.vision.FrameAnalyzer
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), DisplayManager.DisplayListener {

    private lateinit var previewView: PreviewView
    private lateinit var poseOverlay: PoseOverlay
    private lateinit var statusView: TextView
    private lateinit var displayManager: DisplayManager

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val motionEngine = MotionEngine()

    private var analyzer: FrameAnalyzer? = null
    private var presentation: MotionPresentation? = null
    private var fps: Float = 0f
    private var trackedPoints: Int = 0
    private var latencyMs: Long = 0L
    private var lastEvent: String = "none"

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

        poseOverlay = PoseOverlay(this)
        root.addView(poseOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
            setBackgroundColor(0xB0000000.toInt())
        }

        val title = TextView(this).apply {
            text = "AI MOTION  V0.2"
            textSize = 26f
            setTextColor(Color.WHITE)
        }

        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFDDDDDD.toInt())
        }

        val calibrate = Button(this).apply {
            text = "RECALIBRATE BODY"
            setOnClickListener {
                motionEngine.resetCalibration()
                lastEvent = "calibration reset"
                updateStatus()
            }
        }

        panel.addView(title)
        panel.addView(statusView)
        panel.addView(calibrate)

        root.addView(panel, FrameLayout.LayoutParams(
            (resources.displayMetrics.density * 350).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            leftMargin = 24
            topMargin = 24
        })

        setContentView(root)
        updateStatus("Starting body tracker…")
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

            analyzer?.close()
            analyzer = FrameAnalyzer(
                onFps = {
                    fps = it
                    runOnUiThread { updateStatus() }
                },
                onPose = { pose, inferenceMs ->
                    val events = motionEngine.update(pose)
                    trackedPoints = pose.trackedPointCount
                    latencyMs = inferenceMs
                    if (events.isNotEmpty()) lastEvent = events.joinToString()

                    MotionRuntime.publish(
                        newPose = pose,
                        newEvents = events,
                        isCalibrated = motionEngine.calibration != null,
                        latencyMs = inferenceMs
                    )

                    runOnUiThread {
                        poseOverlay.submitPose(pose)
                        updateStatus()
                    }
                },
                onError = {
                    runOnUiThread { updateStatus("Pose error: ${it.javaClass.simpleName}") }
                }
            )

            analysis.setAnalyzer(cameraExecutor, analyzer!!)

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
        val bodyStatus = when {
            trackedPoints >= 25 -> "TRACKED"
            trackedPoints > 0 -> "PARTIAL"
            else -> "SEARCHING"
        }

        statusView.text = buildString {
            appendLine("Camera: ON")
            appendLine("Frames: %.1f FPS".format(fps))
            appendLine("Body: $bodyStatus ($trackedPoints/33)")
            appendLine("Inference: ${latencyMs} ms")
            appendLine("Calibration: ${if (motionEngine.calibration != null) "READY" else "stand naturally"}")
            appendLine("External display: ${if (presentation != null) "CONNECTED" else "not detected"}")
            append("Last motion: $lastEvent")
            if (!extra.isNullOrBlank()) appendLine().append(extra)
        }
    }

    override fun onDisplayAdded(displayId: Int) = showExternalDisplay()
    override fun onDisplayRemoved(displayId: Int) = showExternalDisplay()
    override fun onDisplayChanged(displayId: Int) = Unit

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(this)
        presentation?.dismiss()
        analyzer?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
