package com.edwardresearchlabs.aimotion.motion

object MotionRuntime {
    @Volatile var pose: BodyPose? = null
    @Volatile var events: Set<MotionEvent> = emptySet()
    @Volatile var calibrated: Boolean = false
    @Volatile var inferenceLatencyMs: Long = 0L
    @Volatile var frontCamera: Boolean = true

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
    }
}
