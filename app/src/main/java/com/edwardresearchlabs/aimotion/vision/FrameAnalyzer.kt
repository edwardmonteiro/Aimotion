package com.edwardresearchlabs.aimotion.vision

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.edwardresearchlabs.aimotion.motion.BodyPose
import com.edwardresearchlabs.aimotion.motion.Joint
import com.edwardresearchlabs.aimotion.motion.PosePoint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class FrameAnalyzer(
    private val onFps: (Float) -> Unit,
    private val onPose: (BodyPose, Long) -> Unit,
    private val onError: (Throwable) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    private val processing = AtomicBoolean(false)
    private val frames = AtomicLong(0)
    private var windowStart = System.nanoTime()

    override fun analyze(imageProxy: ImageProxy) {
        if (!processing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            processing.set(false)
            imageProxy.close()
            return
        }

        val startedAt = System.currentTimeMillis()
        val rotation = imageProxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)

        val rotatedWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val rotatedHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height

        detector.process(input)
            .addOnSuccessListener { pose ->
                val points = LinkedHashMap<Joint, PosePoint>()
                for ((joint, type) in landmarkMap) {
                    val landmark = pose.getPoseLandmark(type) ?: continue
                    points[joint] = PosePoint(
                        x = (landmark.position.x / rotatedWidth.toFloat()).coerceIn(-0.25f, 1.25f),
                        y = (landmark.position.y / rotatedHeight.toFloat()).coerceIn(-0.25f, 1.25f),
                        z = landmark.position3D.z / rotatedWidth.toFloat(),
                        confidence = landmark.inFrameLikelihood
                    )
                }
                if (points.isNotEmpty()) {
                    onPose(
                        BodyPose(
                            timestampMs = startedAt,
                            points = points
                        ),
                        System.currentTimeMillis() - startedAt
                    )
                }
            }
            .addOnFailureListener(onError)
            .addOnCompleteListener {
                val count = frames.incrementAndGet()
                val now = System.nanoTime()
                val elapsed = now - windowStart
                if (elapsed >= 1_000_000_000L) {
                    onFps(count * 1_000_000_000f / elapsed)
                    frames.set(0)
                    windowStart = now
                }
                imageProxy.close()
                processing.set(false)
            }
    }

    fun close() {
        detector.close()
    }

    companion object {
        private val landmarkMap = linkedMapOf(
            Joint.NOSE to PoseLandmark.NOSE,
            Joint.LEFT_EYE_INNER to PoseLandmark.LEFT_EYE_INNER,
            Joint.LEFT_EYE to PoseLandmark.LEFT_EYE,
            Joint.LEFT_EYE_OUTER to PoseLandmark.LEFT_EYE_OUTER,
            Joint.RIGHT_EYE_INNER to PoseLandmark.RIGHT_EYE_INNER,
            Joint.RIGHT_EYE to PoseLandmark.RIGHT_EYE,
            Joint.RIGHT_EYE_OUTER to PoseLandmark.RIGHT_EYE_OUTER,
            Joint.LEFT_EAR to PoseLandmark.LEFT_EAR,
            Joint.RIGHT_EAR to PoseLandmark.RIGHT_EAR,
            Joint.LEFT_MOUTH to PoseLandmark.LEFT_MOUTH,
            Joint.RIGHT_MOUTH to PoseLandmark.RIGHT_MOUTH,
            Joint.LEFT_SHOULDER to PoseLandmark.LEFT_SHOULDER,
            Joint.RIGHT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
            Joint.LEFT_ELBOW to PoseLandmark.LEFT_ELBOW,
            Joint.RIGHT_ELBOW to PoseLandmark.RIGHT_ELBOW,
            Joint.LEFT_WRIST to PoseLandmark.LEFT_WRIST,
            Joint.RIGHT_WRIST to PoseLandmark.RIGHT_WRIST,
            Joint.LEFT_PINKY to PoseLandmark.LEFT_PINKY,
            Joint.RIGHT_PINKY to PoseLandmark.RIGHT_PINKY,
            Joint.LEFT_INDEX to PoseLandmark.LEFT_INDEX,
            Joint.RIGHT_INDEX to PoseLandmark.RIGHT_INDEX,
            Joint.LEFT_THUMB to PoseLandmark.LEFT_THUMB,
            Joint.RIGHT_THUMB to PoseLandmark.RIGHT_THUMB,
            Joint.LEFT_HIP to PoseLandmark.LEFT_HIP,
            Joint.RIGHT_HIP to PoseLandmark.RIGHT_HIP,
            Joint.LEFT_KNEE to PoseLandmark.LEFT_KNEE,
            Joint.RIGHT_KNEE to PoseLandmark.RIGHT_KNEE,
            Joint.LEFT_ANKLE to PoseLandmark.LEFT_ANKLE,
            Joint.RIGHT_ANKLE to PoseLandmark.RIGHT_ANKLE,
            Joint.LEFT_HEEL to PoseLandmark.LEFT_HEEL,
            Joint.RIGHT_HEEL to PoseLandmark.RIGHT_HEEL,
            Joint.LEFT_FOOT_INDEX to PoseLandmark.LEFT_FOOT_INDEX,
            Joint.RIGHT_FOOT_INDEX to PoseLandmark.RIGHT_FOOT_INDEX
        )
    }
}
