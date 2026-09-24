package com.edwardresearchlabs.aimotion.motion

import android.os.SystemClock

object MotionRuntime {
    @Volatile var pose: BodyPose? = null
    @Volatile var events: Set<MotionEvent> = emptySet()
    @Volatile var calibrated: Boolean = false
    @Volatile var inferenceLatencyMs: Long = 0L
    @Volatile var frontCamera: Boolean = true
    @Volatile var lastPoseAtMs: Long = 0L

    fun publish(
        newPose: BodyPose,
        newEvents: Set<MotionEvent>,
        isCalibrated: Boolean,
        latencyMs: Long
    ) {
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
}
