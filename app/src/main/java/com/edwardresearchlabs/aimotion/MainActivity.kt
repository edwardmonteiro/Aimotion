package com.edwardresearchlabs.aimotion

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.edwardresearchlabs.aimotion.display.StickFightView
import com.edwardresearchlabs.aimotion.motion.MotionEngine
import com.edwardresearchlabs.aimotion.motion.MotionRuntime
import com.edwardresearchlabs.aimotion.motion.PoseSmoother
import com.edwardresearchlabs.aimotion.vision.FrameAnalyzer
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var fightView: StickFightView
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val motionEngine = MotionEngine()
    private val poseSmoother = PoseSmoother()

    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: FrameAnalyzer? = null
    private var bindGeneration = 0L
    private var fps = 0f
    private var inferenceMs = 0L
    private var tracked = 0

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else statusView.text = "CAMERA REQUIRED"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MotionRuntime.frontCamera = true
        buildUi()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        fightView = StickFightView(this)
        root.addView(
            fightView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(0xFF111111.toInt())
        }

        val d = resources.displayMetrics.density
        root.addView(
            previewView,
            FrameLayout.LayoutParams((150 * d).toInt(), (205 * d).toInt(), Gravity.END or Gravity.BOTTOM).apply {
                rightMargin = (18 * d).toInt()
                bottomMargin = (18 * d).toInt()
            }
        )

        statusView = TextView(this).apply {
            text = "FINDING BODY"
            textSize = 12f
            setTextColor(0xFFB8C4D0.toInt())
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            setBackgroundColor(0x66000000)
        }

        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.BOTTOM
            ).apply {
                leftMargin = (18 * d).toInt()
                bottomMargin = (18 * d).toInt()
            }
        )

        setContentView(root)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val generation = ++bindGeneration

        provider.unbindAll()
        analyzer?.requestClose()
        analyzer = null

        motionEngine.resetCalibration()
        poseSmoother.reset()
        MotionRuntime.clearTracking()
        MotionRuntime.frontCamera = true
        fightView.resetGame()

        val selector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA.also { MotionRuntime.frontCamera = false }
        }

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
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
            onPose = { rawPose, latency ->
                if (generation != bindGeneration) return@FrameAnalyzer

                val pose = poseSmoother.filter(rawPose)
                val events = motionEngine.update(pose)
                tracked = pose.trackedPointCount
                inferenceMs = latency

                MotionRuntime.publish(
                    newPose = pose,
                    newEvents = events,
                    isCalibrated = motionEngine.calibration != null,
                    latencyMs = latency
                )

                runOnUiThread { updateStatus() }
            },
            onError = {
                if (generation == bindGeneration) {
                    runOnUiThread { statusView.text = "VISION ERROR" }
                }
            }
        )

        analyzer = newAnalyzer
        analysis.setAnalyzer(cameraExecutor, newAnalyzer)

        provider.bindToLifecycle(this, selector, preview, analysis)
    }

    private fun updateStatus() {
        val live = MotionRuntime.poseAgeMs() <= 500L
        statusView.text = when {
            !live -> "FINDING BODY"
            tracked < 18 -> "STEP BACK • $tracked/33"
            else -> "BODY LOCKED • %.0f FPS • ${inferenceMs}ms".format(fps)
        }
    }

    override fun onDestroy() {
        bindGeneration++
        cameraProvider?.unbindAll()
        analyzer?.requestClose()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
