package com.edwardresearchlabs.aimotion.vision

import androidx.camera.core.ImageProxy
import com.edwardresearchlabs.aimotion.motion.BodyPose

interface PoseEstimator {
    fun estimate(image: ImageProxy, timestampMs: Long): BodyPose?
    fun close() = Unit
}

class NoOpPoseEstimator : PoseEstimator {
    override fun estimate(image: ImageProxy, timestampMs: Long): BodyPose? = null
}
