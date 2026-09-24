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
import com.edwardresearchlabs.aimotion.motion.PoseSmoother
import com.edwardresearchlabs.aimotion.vision.FrameAnalyzer
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), DisplayManager.DisplayListener {

    private enum class TestMode { MIRROR, AVATAR, NINJA }

    private lateinit var previewView: PreviewView
    private lateinit var poseOverlay: PoseOverlay
    private lateinit var avatarView: AvatarView
    private lateinit var gameView: GameView
    private lateinit var statusView: TextView
    private lateinit var modeView: TextView
    private lateinit var cameraButton: Button
    private lateinit var debugButton: Button
    private lateinit var displayManager: DisplayManager

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val motionEngine = MotionEngine()
    private val poseSmoother = PoseSmoother()

    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: FrameAnalyzer? = null
    private var presentation: MotionPresentation? = null
    private var bindGeneration: Long = 0L

    private var currentMode = TestMode.MIRROR
    private var useFrontCamera = true

    private var fps = 0f
    private var trackedPoints = 0
    private var latencyMs = 0L
    private var lastEvent = "none"

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
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

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
            setPadding(22, 16, 22, 16)
            setBackgroundColor(0xB8000000.toInt())
        }

        val title = TextView(this).apply {
            text = "AI MOTION  V0.5"
            textSize = 23f
            setTextColor(Color.WHITE)
        }

        modeView = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF8FD3FF.toInt())
        }

        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFD7D7D7.toInt())
        }

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeRow.addView(modeButton("MIRROR", TestMode.MIRROR))
        modeRow.addView(modeButton("AVATAR", TestMode.AVATAR))
        modeRow.addView(modeButton("NINJA", TestMode.NINJA))

        cameraButton = Button(this).apply {
            text = "CAMERA: FRONT"
            setOnClickListener {
                useFrontCamera = !useFrontCamera
                MotionRuntime.frontCamera = useFrontCamera
                resetTracking()
                applyMode()
                rebindCamera()
            }
        }

        debugButton = Button(this).apply {
            text = "PHYSICS: OFF"
            setOnClickListener {
                val enabled = gameView.togglePhysicsDebug()
                text = "PHYSICS: ${if (enabled) "ON" else "OFF"}"
            }
        }

        val calibrate = Button(this).apply {
            text = "RECALIBRATE"
            setOnClickListener {
                resetTracking()
                lastEvent = "calibration reset"
                updateStatus()
            }
        }

        panel.addView(title)
        panel.addView(modeView)
        panel.addView(statusView)
        panel.addView(modeRow)
        panel.addView(cameraButton)
        panel.addView(debugButton)
        panel.addView(calibrate)

        root.addView(panel, FrameLayout.LayoutParams(
            (resources.displayMetrics.density * 390).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            leftMargin = 18
            topMargin = 18
        })

        setContentView(root)
    }

    private fun modeButton(label: String, mode: TestMode): Button {
        return Button(this).apply {
            text = label
            setOnClickListener {
                currentMode = mode
                if (mode == TestMode.NINJA) {
                    useFrontCamera = false
                    MotionRuntime.frontCamera = false
                    gameView.resetGame()
                }
                resetTracking()
                applyMode()
                rebindCamera()
            }
        }
    }

    private fun resetTracking() {
        motionEngine.resetCalibration()
        poseSmoother.reset()
        MotionRuntime.clearTracking()
        trackedPoints = 0
    }

    private fun fullScreenParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun applyMode() {
        val mirror = useFrontCamera
        poseOverlay.mirrorX = mirror
        avatarView.mirrorX = mirror

        previewView.visibility = if (currentMode == TestMode.MIRROR) View.VISIBLE else View.GONE
        poseOverlay.visibility = if (currentMode == TestMode.MIRROR) View.VISIBLE else View.GONE
        avatarView.visibility = if (currentMode == TestMode.AVATAR) View.VISIBLE else View.GONE
        gameView.visibility = if (currentMode == TestMode.NINJA) View.VISIBLE else View.GONE
        debugButton.visibility = if (currentMode == TestMode.NINJA) View.VISIBLE else View.GONE

        cameraButton.text = "CAMERA: ${if (useFrontCamera) "FRONT" else "REAR"}"
        modeView.text = when (currentMode) {
            TestMode.MIRROR -> "MIRROR  •  smoothed skeleton"
            TestMode.AVATAR -> "AVATAR  •  smoothed + predicted pose"
            TestMode.NINJA -> "FEEL UPDATE  •  capsules + velocity + perfect hits"
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
        resetTracking()

        fps = 0f
        latencyMs = 0L
        lastEvent = "waiting for live pose"

        val requested = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
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
            onPose = { rawPose, inferenceMs ->
                if (generation != bindGeneration) return@FrameAnalyzer

                val pose = poseSmoother.filter(rawPose)
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
                    runOnUiThread { updateStatus("Pose error: ${it.javaClass.simpleName}") }
                }
            }
        )

        analyzer = newAnalyzer
        analysis.setAnalyzer(cameraExecutor, newAnalyzer)

        if (currentMode == TestMode.MIRROR) {
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            provider.bindToLifecycle(this, selector, preview, analysis)
        } else {
            provider.bindToLifecycle(this, selector, analysis)
        }

        updateStatus()
    }

    private fun showExternalDisplay() {
        val target = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        if (target == null) {
            presentation?.dismiss()
            presentation = null
            return
        }
        if (presentation?.display?.displayId == target.displayId) return
        presentation?.dismiss()
        presentation = MotionPresentation(this, target).also { it.show() }
    }

    private fun updateStatus(extra: String? = null) {
        val poseAge = MotionRuntime.poseAgeMs()
        val live = poseAge <= 500L
        val bodyStatus = when {
            !live -> "SEARCHING"
            trackedPoints >= 25 -> "TRACKED"
            trackedPoints > 0 -> "PARTIAL"
            else -> "SEARCHING"
        }

        statusView.text = buildString {
            appendLine("Frames %.1f  •  Inference ${latencyMs}ms".format(fps))
            appendLine("Body $bodyStatus  •  $trackedPoints/33  •  Smooth ON")
            appendLine("Calibration ${if (motionEngine.calibration != null) "READY" else "stand naturally"}")
            append("Motion $lastEvent")
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
