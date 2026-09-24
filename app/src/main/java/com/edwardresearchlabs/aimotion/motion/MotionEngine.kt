package com.edwardresearchlabs.aimotion.motion

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class MotionEngine {
    data class Calibration(
        val hipY: Float,
        val shoulderWidth: Float,
        val torsoHeight: Float,
        val hipKneeDistance: Float
    )

    var calibration: Calibration? = null
        private set

    private var previous: BodyPose? = null

    fun resetCalibration() {
        calibration = null
        previous = null
    }

    fun update(pose: BodyPose): Set<MotionEvent> {
        if (calibration == null) calibration = tryCalibrate(pose)

        val events = linkedSetOf<MotionEvent>()
        val last = previous
        previous = pose
        val base = calibration ?: return events

        val leftShoulder = pose[Joint.LEFT_SHOULDER]
        val rightShoulder = pose[Joint.RIGHT_SHOULDER]
        val leftHip = pose[Joint.LEFT_HIP]
        val rightHip = pose[Joint.RIGHT_HIP]

        if (leftShoulder != null && rightShoulder != null && leftHip != null && rightHip != null) {
            val shoulderMidX = (leftShoulder.x + rightShoulder.x) / 2f
            val hipMidX = (leftHip.x + rightHip.x) / 2f
            val lean = shoulderMidX - hipMidX
            val leanThreshold = max(0.035f, base.shoulderWidth * 0.22f)
            if (lean < -leanThreshold) events += MotionEvent.LEAN_LEFT
            if (lean > leanThreshold) events += MotionEvent.LEAN_RIGHT

            val hipKnee = hipKneeDistance(pose)
            if (hipKnee != null && hipKnee < base.hipKneeDistance * 0.72f) {
                events += MotionEvent.CROUCH
            }

            val nowHipY = (leftHip.y + rightHip.y) / 2f
            if (base.hipY - nowHipY > max(0.04f, base.torsoHeight * 0.18f)) {
                events += MotionEvent.JUMP
            }
        }

        if (last != null) {
            if (isFastExtension(pose, last, Joint.LEFT_WRIST, Joint.LEFT_SHOULDER)) {
                events += MotionEvent.LEFT_PUNCH
            }
            if (isFastExtension(pose, last, Joint.RIGHT_WRIST, Joint.RIGHT_SHOULDER)) {
                events += MotionEvent.RIGHT_PUNCH
            }
            if (isFastExtension(pose, last, Joint.LEFT_ANKLE, Joint.LEFT_HIP)) {
                events += MotionEvent.LEFT_KICK
            }
            if (isFastExtension(pose, last, Joint.RIGHT_ANKLE, Joint.RIGHT_HIP)) {
                events += MotionEvent.RIGHT_KICK
            }
        }

        return events
    }

    private fun tryCalibrate(pose: BodyPose): Calibration? {
        val ls = pose[Joint.LEFT_SHOULDER] ?: return null
        val rs = pose[Joint.RIGHT_SHOULDER] ?: return null
        val lh = pose[Joint.LEFT_HIP] ?: return null
        val rh = pose[Joint.RIGHT_HIP] ?: return null
        val hipKnee = hipKneeDistance(pose) ?: return null

        val required = listOf(ls, rs, lh, rh)
        if (required.any { it.confidence < 0.65f }) return null

        val shoulderWidth = abs(ls.x - rs.x)
        val shoulderY = (ls.y + rs.y) / 2f
        val hipY = (lh.y + rh.y) / 2f
        val torsoHeight = abs(hipY - shoulderY)
        if (shoulderWidth < 0.08f || torsoHeight < 0.08f) return null

        return Calibration(
            hipY = hipY,
            shoulderWidth = shoulderWidth,
            torsoHeight = torsoHeight,
            hipKneeDistance = hipKnee
        )
    }

    private fun hipKneeDistance(pose: BodyPose): Float? {
        val lh = pose[Joint.LEFT_HIP] ?: return null
        val rh = pose[Joint.RIGHT_HIP] ?: return null
        val lk = pose[Joint.LEFT_KNEE] ?: return null
        val rk = pose[Joint.RIGHT_KNEE] ?: return null
        val hipY = (lh.y + rh.y) / 2f
        val kneeY = (lk.y + rk.y) / 2f
        return abs(kneeY - hipY)
    }

    private fun isFastExtension(now: BodyPose, old: BodyPose, endpoint: Joint, anchor: Joint): Boolean {
        val n = now[endpoint] ?: return false
        val o = old[endpoint] ?: return false
        val a = now[anchor] ?: return false
        if (n.confidence < 0.5f || o.confidence < 0.5f || a.confidence < 0.5f) return false

        val dt = ((now.timestampMs - old.timestampMs).coerceAtLeast(1L)) / 1000f
        val speed = hypot(n.x - o.x, n.y - o.y) / dt
        val extension = hypot(n.x - a.x, n.y - a.y)
        return speed > 0.75f && extension > 0.16f
    }
}
