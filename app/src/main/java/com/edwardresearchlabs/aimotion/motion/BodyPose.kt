package com.edwardresearchlabs.aimotion.motion

data class PosePoint(
    val x: Float,
    val y: Float,
    val z: Float = 0f,
    val confidence: Float = 1f
)

data class BodyPose(
    val timestampMs: Long,
    val points: Map<Joint, PosePoint>
) {
    operator fun get(joint: Joint): PosePoint? = points[joint]
}

enum class Joint {
    NOSE,
    LEFT_SHOULDER, RIGHT_SHOULDER,
    LEFT_ELBOW, RIGHT_ELBOW,
    LEFT_WRIST, RIGHT_WRIST,
    LEFT_HIP, RIGHT_HIP,
    LEFT_KNEE, RIGHT_KNEE,
    LEFT_ANKLE, RIGHT_ANKLE
}
