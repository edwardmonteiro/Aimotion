package com.edwardresearchlabs.aimotion

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.edwardresearchlabs.aimotion.display.SplitGameView
import com.edwardresearchlabs.aimotion.motion.MotionEngine
import com.edwardresearchlabs.aimotion.motion.MotionRuntime
import com.edwardresearchlabs.aimotion.motion.PoseSmoother
import com.edwardresearchlabs.aimotion.vision.FrameAnalyzer
import java.util.concurrent.Executors

class SplitActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var gameView: SplitGameView

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val motionEngine = MotionEngine()
    private val poseSmoother = PoseSmoother()

    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: FrameAnalyzer? = null
    private var bindGeneration = 0L

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else gameView.setCameraError("CAMERA PERMISSION REQUIRED")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()

        MotionRuntime.frontCamera = true

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }

        gameView = SplitGameView(this)

        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            gameView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(root)

        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
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

        val selector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            MotionRuntime.frontCamera = false
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val nextAnalyzer = FrameAnalyzer(
            onFps = { fps ->
                if (generation == bindGeneration) {
                    runOnUiThread { gameView.setVisionFps(fps) }
                }
            },
            onPose = { rawPose, inferenceMs ->
                if (generation != bindGeneration) return@FrameAnalyzer

                val pose = poseSmoother.filter(rawPose)
                val events = motionEngine.update(pose)

                MotionRuntime.publish(
                    newPose = pose,
                    newEvents = events,
                    isCalibrated = motionEngine.calibration != null,
                    latencyMs = inferenceMs
                )

                runOnUiThread {
                    gameView.setVisionLatency(inferenceMs)
                }
            },
            onError = { error ->
                if (generation == bindGeneration) {
                    runOnUiThread {
                        gameView.setCameraError("VISION ${error.javaClass.simpleName}")
                    }
                }
            }
        )

        analyzer = nextAnalyzer
        analysis.setAnalyzer(cameraExecutor, nextAnalyzer)

        provider.bindToLifecycle(this, selector, preview, analysis)
    }

    override fun onDestroy() {
        bindGeneration++
        cameraProvider?.unbindAll()
        analyzer?.requestClose()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
