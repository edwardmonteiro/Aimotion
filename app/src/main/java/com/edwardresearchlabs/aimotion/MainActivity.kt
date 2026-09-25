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

    private enum class TestMode { MIRROR, AVATAR, NINJA, REAL_ME }

    private lateinit var previewView: PreviewView
    private lateinit var poseOverlay: PoseOverlay
    private lateinit var avatarView: AvatarView
    private lateinit var gameView: GameView
    private lateinit var developerPanel: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var cameraButton: Button
    private lateinit var debugButton: Button
    private lateinit var menuButton: TextView
    private lateinit var displayManager: DisplayManager

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val motionEngine = MotionEngine()
    private val poseSmoother = PoseSmoother()

    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: FrameAnalyzer? = null
    private var presentation: MotionPresentation? = null
    private var bindGeneration: Long = 0L

    private var currentMode = TestMode.REAL_ME
    private var useFrontCamera = false

    private var fps = 0f
    private var trackedPoints = 0
    private var latencyMs = 0L
    private var segmentationLatencyMs = 0L
    private var lastEvent = "none"

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else updateStatus("Camera permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MotionRuntime.frontCamera = false

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
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

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

        menuButton = TextView(this).apply {
            text = "⋯"
            gravity = Gravity.CENTER
            textSize = 28f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x55000000)
            setPadding(18, 2, 18, 8)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                developerPanel.visibility =
                    if (developerPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

        root.addView(
            menuButton,
            FrameLayout.LayoutParams(
                dp(60),
                dp(48),
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(12)
                rightMargin = dp(16)
            }
        )

        developerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            setBackgroundColor(0xED0B0F17.toInt())
            visibility = View.GONE
        }

        val title = TextView(this).apply {
            text = "AI MOTION  0.6.1"
            textSize = 18f
            setTextColor(Color.WHITE)
        }

        val subtitle = TextView(this).apply {
            text = "Developer controls"
            textSize = 12f
            setTextColor(0xFF8FD3FF.toInt())
        }

        statusView = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFFD7D7D7.toInt())
            setPadding(0, dp(8), 0, dp(8))
        }

        val modes1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modes1.addView(modeButton("REAL ME", TestMode.REAL_ME))
        modes1.addView(modeButton("NINJA", TestMode.NINJA))

        val modes2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modes2.addView(modeButton("MIRROR", TestMode.MIRROR))
        modes2.addView(modeButton("AVATAR", TestMode.AVATAR))

        cameraButton = Button(this).apply {
            text = "CAMERA: REAR"
            textSize = 11f
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
            textSize = 11f
            setOnClickListener {
                val enabled = gameView.togglePhysicsDebug()
                text = "PHYSICS: ${if (enabled) "ON" else "OFF"}"
            }
        }

        val recalibrate = Button(this).apply {
            text = "RECALIBRATE"
            textSize = 11f
            setOnClickListener {
                resetTracking()
                lastEvent = "calibration reset"
                updateStatus()
            }
        }

        val close = Button(this).apply {
            text = "CLOSE"
            textSize = 11f
            setOnClickListener { developerPanel.visibility = View.GONE }
        }

        developerPanel.addView(title)
        developerPanel.addView(subtitle)
        developerPanel.addView(statusView)
        developerPanel.addView(modes1)
        developerPanel.addView(modes2)
        developerPanel.addView(cameraButton)
        developerPanel.addView(debugButton)
        developerPanel.addView(recalibrate)
        developerPanel.addView(close)

        root.addView(
            developerPanel,
            FrameLayout.LayoutParams(
                dp(330),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(66)
                rightMargin = dp(16)
            }
        )

        setContentView(root)
    }

    private fun modeButton(label: String, mode: TestMode): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            setOnClickListener {
                currentMode = mode

                if (mode == TestMode.NINJA || mode == TestMode.REAL_ME) {
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun resetTracking() {
        motionEngine.resetCalibration()
        poseSmoother.reset()
        MotionRuntime.clearTracking()
        trackedPoints = 0
        segmentationLatencyMs = 0L
    }

    private fun fullScreenParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun applyMode() {
        val mirror = useFrontCamera
        poseOverlay.mirrorX = mirror
        avatarView.mirrorX = mirror

        val isGame = currentMode == TestMode.NINJA || currentMode == TestMode.REAL_ME

        previewView.visibility = if (currentMode == TestMode.MIRROR) View.VISIBLE else View.GONE
        poseOverlay.visibility = if (currentMode == TestMode.MIRROR) View.VISIBLE else View.GONE
        avatarView.visibility = if (currentMode == TestMode.AVATAR) View.VISIBLE else View.GONE
        gameView.visibility = if (isGame) View.VISIBLE else View.GONE

        gameView.setRealMeEnabled(currentMode == TestMode.REAL_ME)
        if (currentMode != TestMode.REAL_ME) gameView.clearPersonFrame()

        cameraButton.text = "CAMERA: ${if (useFrontCamera) "FRONT" else "REAR"}"
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
        gameView.clearPersonFrame()
        resetTracking()

        fps = 0f
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

        val segmentationEnabled = currentMode == TestMode.REAL_ME

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
                    runOnUiThread { updateStatus("Vision: ${it.javaClass.simpleName}") }
                }
            },
            enableSegmentation = segmentationEnabled,
            onPersonFrame = { frame, segmentationMs ->
                if (generation != bindGeneration) {
                    if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
                    return@FrameAnalyzer
                }

                segmentationLatencyMs = segmentationMs
                runOnUiThread {
                    if (generation == bindGeneration && currentMode == TestMode.REAL_ME) {
                        gameView.submitPersonFrame(frame)
                        updateStatus()
                    } else if (!frame.bitmap.isRecycled) {
                        frame.bitmap.recycle()
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
        val target = displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull()

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
        if (!::statusView.isInitialized) return

        val poseAge = MotionRuntime.poseAgeMs()
        val live = poseAge <= 500L
        val bodyStatus = when {
            !live -> "SEARCHING"
            trackedPoints >= 25 -> "TRACKED"
            trackedPoints > 0 -> "PARTIAL"
            else -> "SEARCHING"
        }

        statusView.text = buildString {
            appendLine("Mode: ${currentMode.name}")
            appendLine("FPS %.1f  •  Pose ${latencyMs}ms".format(fps))
            appendLine("Body $bodyStatus  •  $trackedPoints/33")
            if (currentMode == TestMode.REAL_ME) {
                appendLine(
                    "Segmentation: ${if (segmentationLatencyMs > 0) "${segmentationLatencyMs}ms" else "warming up"}"
                )
            }
            append("Calibration: ${if (motionEngine.calibration != null) "READY" else "stand naturally"}")
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
        gameView.clearPersonFrame()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
