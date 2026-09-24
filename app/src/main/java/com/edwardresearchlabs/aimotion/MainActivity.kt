package com.edwardresearchlabs.aimotion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
import com.edwardresearchlabs.aimotion.display.AvatarView
import com.edwardresearchlabs.aimotion.display.GameView
import com.edwardresearchlabs.aimotion.display.MotionPresentation
import com.edwardresearchlabs.aimotion.display.PoseOverlay
import com.edwardresearchlabs.aimotion.motion.MotionEngine
import com.edwardresearchlabs.aimotion.motion.MotionRuntime
import com.edwardresearchlabs.aimotion.vision.FrameAnalyzer
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), DisplayManager.DisplayListener {

    private enum class TestMode {
        MIRROR,
        AVATAR,
        PLAY
    }

    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var poseOverlay: PoseOverlay
    private lateinit var avatarView: AvatarView
    private lateinit var gameView: GameView
    private lateinit var statusView: TextView
    private lateinit var modeView: TextView
    private lateinit var cameraButton: Button
    private lateinit var displayManager: DisplayManager

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val motionEngine = MotionEngine()

    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: FrameAnalyzer? = null
    private var presentation: MotionPresentation? = null
    private var bindGeneration: Long = 0L

    private var currentMode = TestMode.MIRROR
    private var useFrontCamera = true

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
        applyMode()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }

        showExternalDisplay()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView, fullScreenParams())

        poseOverlay = PoseOverlay(this)
        root.addView(poseOverlay, fullScreenParams())

        avatarView = AvatarView(this)
        root.addView(avatarView, fullScreenParams())

        gameView = GameView(this)
        root.addView(gameView, fullScreenParams())

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
            setBackgroundColor(0xB8000000.toInt())
        }

        val title = TextView(this).apply {
            text = "AI MOTION  V0.3.1"
            textSize = 24f
            setTextColor(Color.WHITE)
        }

        modeView = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF8FD3FF.toInt())
        }

        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFD7D7D7.toInt())
        }

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        modeRow.addView(modeButton("MIRROR", TestMode.MIRROR))
        modeRow.addView(modeButton("AVATAR", TestMode.AVATAR))
        modeRow.addView(modeButton("PLAY", TestMode.PLAY))

        cameraButton = Button(this).apply {
            text = "CAMERA: FRONT"
            setOnClickListener {
                useFrontCamera = !useFrontCamera
                MotionRuntime.frontCamera = useFrontCamera
                motionEngine.resetCalibration()
                applyMode()
                rebindCamera()
            }
        }

        val calibrate = Button(this).apply {
            text = "RECALIBRATE"
            setOnClickListener {
                motionEngine.resetCalibration()
                MotionRuntime.clearTracking()
                trackedPoints = 0
                lastEvent = "calibration reset"
                updateStatus()
            }
        }

        panel.addView(title)
        panel.addView(modeView)
        panel.addView(statusView)
        panel.addView(modeRow)
        panel.addView(cameraButton)
        panel.addView(calibrate)

        root.addView(panel, FrameLayout.LayoutParams(
            (resources.displayMetrics.density * 390).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            leftMargin = 20
            topMargin = 20
        })

        setContentView(root)
    }

    private fun modeButton(label: String, mode: TestMode): Button {
        return Button(this).apply {
            text = label
            setOnClickListener {
                currentMode = mode

                if (mode == TestMode.PLAY) {
                    useFrontCamera = false
                    MotionRuntime.frontCamera = false
                }

                motionEngine.resetCalibration()
                applyMode()
                rebindCamera()
            }
        }
    }

    private fun fullScreenParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun applyMode() {
        val mirror = useFrontCamera
        poseOverlay.mirrorX = mirror
        avatarView.mirrorX = mirror

        when (currentMode) {
            TestMode.MIRROR -> {
                previewView.visibility = View.VISIBLE
                poseOverlay.visibility = View.VISIBLE
                avatarView.visibility = View.GONE
                gameView.visibility = View.GONE
            }
            TestMode.AVATAR -> {
                previewView.visibility = View.GONE
                poseOverlay.visibility = View.GONE
                avatarView.visibility = View.VISIBLE
                gameView.visibility = View.GONE
            }
            TestMode.PLAY -> {
                previewView.visibility = View.GONE
                poseOverlay.visibility = View.GONE
                avatarView.visibility = View.GONE
                gameView.visibility = View.VISIBLE
            }
        }

        cameraButton.text = "CAMERA: ${if (useFrontCamera) "FRONT" else "REAR"}"
        modeView.text = when (currentMode) {
            TestMode.MIRROR -> "MIRROR TEST  •  preview + skeleton"
            TestMode.AVATAR -> "AVATAR TEST  •  analysis-only tracking"
            TestMode.PLAY -> "PLAY MODE  •  analysis-only rear camera"
        }

        updateStatus()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            rebindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rebindCamera() {
        val provider = cameraProvider ?: return
        val generation = ++bindGeneration

        provider.unbindAll()
        analyzer?.requestClose()
        analyzer = null

        MotionRuntime.clearTracking()
        fps = 0f
        trackedPoints = 0
        latencyMs = 0L
        lastEvent = "waiting for live pose"

        val requested = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val selector = if (provider.hasCamera(requested)) {
            requested
        } else {
            useFrontCamera = false
            MotionRuntime.frontCamera = false
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val newAnalyzer = FrameAnalyzer(
            onFps = {
                if (generation != bindGeneration) return@FrameAnalyzer
                fps = it
                runOnUiThread { updateStatus() }
            },
            onPose = { pose, inferenceMs ->
                if (generation != bindGeneration) return@FrameAnalyzer

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
                    avatarView.submitPose(pose)
                    updateStatus()
                }
            },
            onError = {
                if (generation == bindGeneration) {
                    runOnUiThread {
                        updateStatus("Pose error: ${it.javaClass.simpleName}")
                    }
                }
            }
        )

        analyzer = newAnalyzer
        analysis.setAnalyzer(cameraExecutor, newAnalyzer)

        if (currentMode == TestMode.MIRROR) {
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            provider.bindToLifecycle(this, selector, preview, analysis)
        } else {
            provider.bindToLifecycle(this, selector, analysis)
        }

        updateStatus()
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
        val poseAge = MotionRuntime.poseAgeMs()
        val live = poseAge <= 450L

        val bodyStatus = when {
            !live -> "SEARCHING"
            trackedPoints >= 25 -> "TRACKED"
            trackedPoints > 0 -> "PARTIAL"
            else -> "SEARCHING"
        }

        statusView.text = buildString {
            appendLine("Frames: %.1f FPS".format(fps))
            appendLine("Body: $bodyStatus ($trackedPoints/33)")
            appendLine("Inference: ${latencyMs} ms")
            appendLine("Pose age: ${if (poseAge == Long.MAX_VALUE) "-" else "${poseAge} ms"}")
            appendLine("Calibration: ${if (motionEngine.calibration != null) "READY" else "stand naturally"}")
            appendLine("Pipeline: ${if (currentMode == TestMode.MIRROR) "PREVIEW + ANALYSIS" else "ANALYSIS ONLY"}")
            append("Last motion: $lastEvent")
            if (!extra.isNullOrBlank()) appendLine().append(extra)
        }
    }

    override fun onDisplayAdded(displayId: Int) = showExternalDisplay()
    override fun onDisplayRemoved(displayId: Int) = showExternalDisplay()
    override fun onDisplayChanged(displayId: Int) = Unit

    override fun onDestroy() {
        bindGeneration++
        displayManager.unregisterDisplayListener(this)
        presentation?.dismiss()
        cameraProvider?.unbindAll()
        analyzer?.requestClose()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
