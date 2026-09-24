package com.edwardresearchlabs.aimotion.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.edwardresearchlabs.aimotion.motion.BodyPose
import com.edwardresearchlabs.aimotion.motion.Joint
import com.edwardresearchlabs.aimotion.motion.PosePoint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

class FrameAnalyzer(
    private val onFps: (Float) -> Unit,
    private val onPose: (BodyPose, Long) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val enableSegmentation: Boolean = false,
    private val onPersonFrame: (SegmentedPersonFrame, Long) -> Unit = { _, _ -> }
) : ImageAnalysis.Analyzer {

    private val poseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    private val segmenter = if (enableSegmentation) {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                .build()
        )
    } else {
        null
    }

    private val processing = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)
    private val detectorsClosed = AtomicBoolean(false)
    private val frames = AtomicLong(0)
    private val segmentationCounter = AtomicLong(0)
    private var windowStart = System.nanoTime()

    override fun analyze(imageProxy: ImageProxy) {
        if (closeRequested.get()) {
            imageProxy.close()
            closeDetectorsOnce()
            return
        }

        if (!processing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            processing.set(false)
            imageProxy.close()
            if (closeRequested.get()) closeDetectorsOnce()
            return
        }

        val startedAt = System.currentTimeMillis()
        val rotation = imageProxy.imageInfo.rotationDegrees
        val poseInput = InputImage.fromMediaImage(mediaImage, rotation)

        val rotatedWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val rotatedHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height

        val segmentBitmap = if (
            segmenter != null &&
            segmentationCounter.incrementAndGet() % SEGMENT_EVERY_N_FRAMES == 0L
        ) {
            runCatching { prepareSegmentationBitmap(imageProxy, rotation) }
                .onFailure { if (!closeRequested.get()) onError(it) }
                .getOrNull()
        } else {
            null
        }

        val pending = AtomicInteger(if (segmentBitmap != null && segmenter != null) 2 else 1)

        fun completeOne() {
            if (pending.decrementAndGet() != 0) return

            val count = frames.incrementAndGet()
            val now = System.nanoTime()
            val elapsed = now - windowStart

            if (!closeRequested.get() && elapsed >= 1_000_000_000L) {
                onFps(count * 1_000_000_000f / elapsed)
                frames.set(0)
                windowStart = now
            }

            imageProxy.close()
            processing.set(false)
            if (closeRequested.get()) closeDetectorsOnce()
        }

        poseDetector.process(poseInput)
            .addOnSuccessListener { pose ->
                if (closeRequested.get()) return@addOnSuccessListener

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
            .addOnFailureListener {
                if (!closeRequested.get()) onError(it)
            }
            .addOnCompleteListener {
                completeOne()
            }

        if (segmentBitmap != null && segmenter != null) {
            val segmentationInput = InputImage.fromBitmap(segmentBitmap, 0)
            segmenter.process(segmentationInput)
                .addOnSuccessListener { mask ->
                    if (closeRequested.get()) return@addOnSuccessListener

                    runCatching {
                        val cutout = buildCutout(segmentBitmap, mask.buffer, mask.width, mask.height)
                        onPersonFrame(
                            SegmentedPersonFrame(cutout, System.currentTimeMillis()),
                            System.currentTimeMillis() - startedAt
                        )
                    }.onFailure {
                        if (!closeRequested.get()) onError(it)
                    }
                }
                .addOnFailureListener {
                    if (!closeRequested.get()) onError(it)
                }
                .addOnCompleteListener {
                    if (!segmentBitmap.isRecycled) segmentBitmap.recycle()
                    completeOne()
                }
        }
    }

    private fun prepareSegmentationBitmap(imageProxy: ImageProxy, rotation: Int): Bitmap {
        val raw = imageProxy.toBitmap()
        val upright = if (rotation == 0) {
            raw
        } else {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also {
                if (it !== raw && !raw.isRecycled) raw.recycle()
            }
        }

        val largest = max(upright.width, upright.height)
        if (largest <= SEGMENT_MAX_EDGE) return upright

        val scale = SEGMENT_MAX_EDGE.toFloat() / largest.toFloat()
        val width = (upright.width * scale).toInt().coerceAtLeast(1)
        val height = (upright.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(upright, width, height, true).also {
            if (it !== upright && !upright.isRecycled) upright.recycle()
        }
    }

    private fun buildCutout(
        source: Bitmap,
        maskBuffer: java.nio.ByteBuffer,
        maskWidth: Int,
        maskHeight: Int
    ): Bitmap {
        maskBuffer.rewind()

        val working = if (source.width == maskWidth && source.height == maskHeight) {
            source
        } else {
            Bitmap.createScaledBitmap(source, maskWidth, maskHeight, true)
        }

        val pixels = IntArray(maskWidth * maskHeight)
        working.getPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

        for (i in pixels.indices) {
            val confidence = if (maskBuffer.remaining() >= 4) maskBuffer.float else 0f
            val normalized = ((confidence - 0.16f) / 0.50f).coerceIn(0f, 1f)
            val smooth = normalized * normalized * (3f - 2f * normalized)
            val alpha = (smooth * 255f).toInt().coerceIn(0, 255)
            pixels[i] = (pixels[i] and 0x00FFFFFF) or (alpha shl 24)
        }

        if (working !== source && !working.isRecycled) working.recycle()

        return Bitmap.createBitmap(
            pixels,
            maskWidth,
            maskHeight,
            Bitmap.Config.ARGB_8888
        )
    }

    fun requestClose() {
        closeRequested.set(true)
        if (!processing.get()) closeDetectorsOnce()
    }

    private fun closeDetectorsOnce() {
        if (detectorsClosed.compareAndSet(false, true)) {
            poseDetector.close()
            segmenter?.close()
        }
    }

    companion object {
        private const val SEGMENT_EVERY_N_FRAMES = 2L
        private const val SEGMENT_MAX_EDGE = 480

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
