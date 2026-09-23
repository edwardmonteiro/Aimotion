package com.edwardresearchlabs.aimotion.vision

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.edwardresearchlabs.aimotion.motion.BodyPose
import java.util.concurrent.atomic.AtomicLong

class FrameAnalyzer(
    private val poseEstimator: PoseEstimator,
    private val onFps: (Float) -> Unit,
    private val onPose: (BodyPose) -> Unit
) : ImageAnalysis.Analyzer {

    private val frames = AtomicLong(0)
    private var windowStart = System.nanoTime()

    override fun analyze(image: ImageProxy) {
        try {
            val nowMs = System.currentTimeMillis()
            poseEstimator.estimate(image, nowMs)?.let(onPose)

            val count = frames.incrementAndGet()
            val now = System.nanoTime()
            val elapsed = now - windowStart
            if (elapsed >= 1_000_000_000L) {
                val fps = count * 1_000_000_000f / elapsed
                onFps(fps)
                frames.set(0)
                windowStart = now
            }
        } finally {
            image.close()
        }
    }
}
