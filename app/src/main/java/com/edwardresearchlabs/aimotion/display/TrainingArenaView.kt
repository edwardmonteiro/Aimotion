package com.edwardresearchlabs.aimotion.display

import android.content.Context
import android.graphics.Color
import android.view.Choreographer
import android.view.TextureView
import com.google.android.filament.View as FilamentView
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer

/**
 * Lightweight transparent 3D layer for SPLIT.
 *
 * The camera preview remains underneath this TextureView. The complete arena
 * model is bundled in the APK, so no network connection is used at runtime.
 */
class TrainingArenaView(
    context: Context,
    private val onReadyChanged: (Boolean) -> Unit = {}
) : TextureView(context), Choreographer.FrameCallback {

    companion object {
        init {
            Utils.init()
        }

        private const val MODEL_PATH = "models/split_training_arena.glb"
    }

    private var modelViewer: ModelViewer? = null
    private var running = false
    private var released = false
    private var readyReported = false

    init {
        isOpaque = false
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false

        runCatching {
            setupRenderer()
        }.onFailure {
            reportReady(false)
        }
    }

    private fun setupRenderer() {
        val viewer = ModelViewer(this, manipulator = null)
        modelViewer = viewer

        viewer.view.blendMode = FilamentView.BlendMode.TRANSLUCENT
        viewer.view.antiAliasing = FilamentView.AntiAliasing.FXAA
        viewer.view.isPostProcessingEnabled = true

        viewer.renderer.clearOptions = viewer.renderer.clearOptions.apply {
            clear = true
            clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        }

        val bytes = context.assets.open(MODEL_PATH).use { input ->
            input.readBytes()
        }

        viewer.loadModelGlb(ByteBuffer.wrap(bytes))
        viewer.transformToUnitCube()

        // Portrait composition: see the complete training floor while keeping the
        // player's torso and feet visible through the transparent surface.
        viewer.cameraFocalLength = 31f
        viewer.cameraNear = 0.05f
        viewer.cameraFar = 30f
        viewer.camera.lookAt(
            0.0, 2.15, 1.10,
            0.0, -0.05, -4.15,
            0.0, 1.0, 0.0
        )

        post {
            if (!released && viewer.asset != null) {
                reportReady(true)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!released && modelViewer != null && !running) {
            running = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onDetachedFromWindow() {
        stopFrames()
        release()
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || released) return

        runCatching {
            modelViewer?.render(frameTimeNanos)
        }.onFailure {
            running = false
            reportReady(false)
        }

        if (running && !released) {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun stopFrames() {
        running = false
        runCatching {
            Choreographer.getInstance().removeFrameCallback(this)
        }
    }

    fun release() {
        if (released) return
        released = true
        stopFrames()

        runCatching {
            modelViewer?.destroy()
        }

        modelViewer = null
        reportReady(false)
    }

    private fun reportReady(value: Boolean) {
        if (readyReported == value) return
        readyReported = value
        onReadyChanged(value)
    }
}
