package com.edwardresearchlabs.aimotion.motion

import kotlin.math.hypot

/**
 * Converts noisy detector output into a game-friendly pose.
 *
 * - adaptive EMA smoothing
 * - short prediction for fast limbs
 * - brief hold of missing joints
 */
class PoseSmoother {
    private data class Track(
        val point: PosePoint,
        val vx: Float,
        val vy: Float,
        val vz: Float,
        val missingFrames: Int = 0
    )

    private val tracks = mutableMapOf<Joint, Track>()
    private var previousTimestampMs: Long = 0L

    fun reset() {
        tracks.clear()
        previousTimestampMs = 0L
    }

    fun filter(raw: BodyPose): BodyPose {
        val dt = if (previousTimestampMs == 0L) {
            1f / 30f
        } else {
            ((raw.timestampMs - previousTimestampMs).coerceIn(8L, 80L)) / 1000f
        }
        previousTimestampMs = raw.timestampMs

        val output = LinkedHashMap<Joint, PosePoint>()

        for (joint in Joint.entries) {
            val measurement = raw[joint]
            val previous = tracks[joint]

            if (measurement == null || measurement.confidence < 0.25f) {
                if (previous != null && previous.missingFrames < 4) {
                    val predicted = previous.point.copy(
                        x = (previous.point.x + previous.vx * dt).coerceIn(-0.2f, 1.2f),
                        y = (previous.point.y + previous.vy * dt).coerceIn(-0.2f, 1.2f),
                        z = previous.point.z + previous.vz * dt,
                        confidence = previous.point.confidence * 0.82f
                    )
                    tracks[joint] = previous.copy(
                        point = predicted,
                        missingFrames = previous.missingFrames + 1
                    )
                    output[joint] = predicted
                }
                continue
            }

            if (previous == null) {
                tracks[joint] = Track(measurement, 0f, 0f, 0f)
                output[joint] = measurement
                continue
            }

            val rawVx = (measurement.x - previous.point.x) / dt
            val rawVy = (measurement.y - previous.point.y) / dt
            val rawVz = (measurement.z - previous.point.z) / dt
            val speed = hypot(rawVx, rawVy)

            val alpha = when {
                speed > 1.20f -> 0.78f
                speed > 0.60f -> 0.64f
                speed > 0.25f -> 0.50f
                else -> 0.34f
            }

            val vx = previous.vx * 0.45f + rawVx * 0.55f
            val vy = previous.vy * 0.45f + rawVy * 0.55f
            val vz = previous.vz * 0.45f + rawVz * 0.55f

            val sx = previous.point.x + (measurement.x - previous.point.x) * alpha
            val sy = previous.point.y + (measurement.y - previous.point.y) * alpha
            val sz = previous.point.z + (measurement.z - previous.point.z) * alpha

            val predictionSeconds = when {
                speed > 1.0f -> 0.028f
                speed > 0.5f -> 0.018f
                else -> 0.008f
            }

            val filtered = PosePoint(
                x = (sx + vx * predictionSeconds).coerceIn(-0.2f, 1.2f),
                y = (sy + vy * predictionSeconds).coerceIn(-0.2f, 1.2f),
                z = sz + vz * predictionSeconds,
                confidence = measurement.confidence
            )

            tracks[joint] = Track(filtered, vx, vy, vz)
            output[joint] = filtered
        }

        return BodyPose(raw.timestampMs, output)
    }
}
