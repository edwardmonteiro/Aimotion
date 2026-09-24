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

    val trackedPointCount: Int
        get() = points.values.count { it.confidence >= 0.5f }
}

enum class Joint {
    NOSE,
    LEFT_EYE_INNER, LEFT_EYE, LEFT_EYE_OUTER,
    RIGHT_EYE_INNER, RIGHT_EYE, RIGHT_EYE_OUTER,
    LEFT_EAR, RIGHT_EAR,
    LEFT_MOUTH, RIGHT_MOUTH,
    LEFT_SHOULDER, RIGHT_SHOULDER,
    LEFT_ELBOW, RIGHT_ELBOW,
    LEFT_WRIST, RIGHT_WRIST,
    LEFT_PINKY, RIGHT_PINKY,
    LEFT_INDEX, RIGHT_INDEX,
    LEFT_THUMB, RIGHT_THUMB,
    LEFT_HIP, RIGHT_HIP,
    LEFT_KNEE, RIGHT_KNEE,
    LEFT_ANKLE, RIGHT_ANKLE,
    LEFT_HEEL, RIGHT_HEEL,
    LEFT_FOOT_INDEX, RIGHT_FOOT_INDEX
}
