package com.edwardresearchlabs.aimotion.motion

import kotlin.math.abs

class MotionEngine {
    private var previous: BodyPose? = null

    fun update(pose: BodyPose): Set<MotionEvent> {
        val events = linkedSetOf<MotionEvent>()
        val last = previous
        previous = pose

        val leftShoulder = pose[Joint.LEFT_SHOULDER]
        val rightShoulder = pose[Joint.RIGHT_SHOULDER]
        val leftHip = pose[Joint.LEFT_HIP]
        val rightHip = pose[Joint.RIGHT_HIP]

        if (leftShoulder != null && rightShoulder != null && leftHip != null && rightHip != null) {
            val shoulderMidX = (leftShoulder.x + rightShoulder.x) / 2f
            val hipMidX = (leftHip.x + rightHip.x) / 2f
            val lean = shoulderMidX - hipMidX
            if (lean < -0.08f) events += MotionEvent.LEAN_LEFT
            if (lean > 0.08f) events += MotionEvent.LEAN_RIGHT

            val torso = abs(((leftHip.y + rightHip.y) / 2f) - ((leftShoulder.y + rightShoulder.y) / 2f))
            if (torso < 0.16f) events += MotionEvent.CROUCH
        }

        if (last != null) {
            punch(pose, last, Joint.LEFT_WRIST, Joint.LEFT_SHOULDER)?.let { events += MotionEvent.LEFT_PUNCH }
            punch(pose, last, Joint.RIGHT_WRIST, Joint.RIGHT_SHOULDER)?.let { events += MotionEvent.RIGHT_PUNCH }

            val nowHipY = averageY(pose, Joint.LEFT_HIP, Joint.RIGHT_HIP)
            val oldHipY = averageY(last, Joint.LEFT_HIP, Joint.RIGHT_HIP)
            if (nowHipY != null && oldHipY != null && oldHipY - nowHipY > 0.045f) {
                events += MotionEvent.JUMP
            }
        }

        return events
    }

    private fun punch(now: BodyPose, old: BodyPose, wrist: Joint, shoulder: Joint): Boolean? {
        val nw = now[wrist] ?: return null
        val ow = old[wrist] ?: return null
        val s = now[shoulder] ?: return null
        return abs(nw.x - ow.x) > 0.06f && abs(nw.x - s.x) > 0.18f
    }

    private fun averageY(pose: BodyPose, a: Joint, b: Joint): Float? {
        val pa = pose[a] ?: return null
        val pb = pose[b] ?: return null
        return (pa.y + pb.y) / 2f
    }
}
