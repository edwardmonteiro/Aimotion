package com.edwardresearchlabs.aimotion.motion

import android.os.SystemClock

object MotionRuntime {
    @Volatile var pose: BodyPose? = null
    @Volatile var events: Set<MotionEvent> = emptySet()
    @Volatile var calibrated: Boolean = false
    @Volatile var inferenceLatencyMs: Long = 0L
    @Volatile var frontCamera: Boolean = true
    @Volatile var lastPoseAtMs: Long = 0L

    @Volatile private var bodyAnchorX: Float? = null

    fun publish(
        newPose: BodyPose,
        newEvents: Set<MotionEvent>,
        isCalibrated: Boolean,
        latencyMs: Long
    ) {
        if (bodyAnchorX == null) {
            bodyCenterX(newPose)?.let { rawCenter ->
                bodyAnchorX = cameraX(rawCenter)
            }
        }

        pose = newPose
        events = newEvents
        calibrated = isCalibrated
        inferenceLatencyMs = latencyMs
        lastPoseAtMs = SystemClock.elapsedRealtime()
    }

    fun clearTracking() {
        pose = null
        events = emptySet()
        calibrated = false
        inferenceLatencyMs = 0L
        lastPoseAtMs = 0L
        bodyAnchorX = null
    }

    fun freshPose(maxAgeMs: Long = 450L): BodyPose? {
        val current = pose ?: return null
        val age = SystemClock.elapsedRealtime() - lastPoseAtMs
        return if (age in 0..maxAgeMs) current else null
    }

    fun poseAgeMs(): Long {
        if (lastPoseAtMs == 0L) return Long.MAX_VALUE
        return SystemClock.elapsedRealtime() - lastPoseAtMs
    }

    /**
     * Maps detector X into the game viewport.
     *
     * Front camera behaves like a mirror.
     * Rear camera preserves sensor direction.
     * The first reliable torso center becomes the viewport anchor, keeping
     * the player visible while preserving subsequent lateral movement.
     */
    fun mapX(rawX: Float): Float {
        val cameraSpace = cameraX(rawX)
        val anchor = bodyAnchorX ?: 0.5f
        return (cameraSpace + (0.5f - anchor)).coerceIn(-0.15f, 1.15f)
    }

    fun anchorOffsetX(): Float {
        val anchor = bodyAnchorX ?: return 0f
        return 0.5f - anchor
    }

    private fun cameraX(rawX: Float): Float {
        return if (frontCamera) 1f - rawX else rawX
    }

    private fun bodyCenterX(pose: BodyPose): Float? {
        val candidates = listOf(
            pose[Joint.LEFT_SHOULDER],
            pose[Joint.RIGHT_SHOULDER],
            pose[Joint.LEFT_HIP],
            pose[Joint.RIGHT_HIP]
        ).filterNotNull().filter { it.confidence >= 0.50f }

        if (candidates.size < 2) return null
        return candidates.map { it.x }.average().toFloat()
    }
}
