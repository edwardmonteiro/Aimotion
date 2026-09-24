package com.edwardresearchlabs.aimotion.game

import com.edwardresearchlabs.aimotion.motion.BodyPose
import com.edwardresearchlabs.aimotion.motion.Joint
import kotlin.math.max
import kotlin.math.min

data class GoalkeeperState(
    val playerX: Float = 0.5f,
    val guardLeft: Float = 0.38f,
    val guardRight: Float = 0.62f,
    val ballX: Float = 0.82f,
    val ballY: Float = 0.18f,
    val score: Int = 0,
    val shots: Int = 0
)

class GoalkeeperGame {
    var state = GoalkeeperState()
        private set

    fun updatePose(pose: BodyPose?) {
        if (pose == null) return
        val useful = listOf(
            Joint.LEFT_WRIST, Joint.RIGHT_WRIST,
            Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER,
            Joint.LEFT_HIP, Joint.RIGHT_HIP
        ).mapNotNull { pose[it] }.filter { it.confidence >= 0.4f }

        if (useful.isEmpty()) return

        val mirrored = useful.map { 1f - it.x }
        val center = mirrored.average().toFloat().coerceIn(0.08f, 0.92f)
        val left = (mirrored.minOrNull() ?: center) - 0.05f
        val right = (mirrored.maxOrNull() ?: center) + 0.05f

        state = state.copy(
            playerX = center,
            guardLeft = left.coerceIn(0.02f, 0.95f),
            guardRight = right.coerceIn(0.05f, 0.98f)
        )
    }

    fun tick(dtSeconds: Float) {
        var y = state.ballY + dtSeconds * 0.28f
        var x = state.ballX - dtSeconds * 0.05f
        var score = state.score
        var shots = state.shots

        if (y >= 0.86f) {
            shots += 1
            if (x in state.guardLeft..state.guardRight) score += 1
            y = 0.14f
            x = 0.12f + ((shots * 37) % 76) / 100f
        }

        state = state.copy(
            ballX = min(0.94f, max(0.06f, x)),
            ballY = y,
            score = score,
            shots = shots
        )
    }
}
