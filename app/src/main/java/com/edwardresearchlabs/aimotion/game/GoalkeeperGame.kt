package com.edwardresearchlabs.aimotion.game

import com.edwardresearchlabs.aimotion.motion.MotionEvent
import kotlin.math.max
import kotlin.math.min

data class GoalkeeperState(
    val playerX: Float = 0.5f,
    val ballX: Float = 0.82f,
    val ballY: Float = 0.18f,
    val score: Int = 0,
    val shots: Int = 0
)

class GoalkeeperGame {
    var state = GoalkeeperState()
        private set

    fun onMotion(events: Set<MotionEvent>) {
        var x = state.playerX
        if (MotionEvent.LEAN_LEFT in events) x -= 0.06f
        if (MotionEvent.LEAN_RIGHT in events) x += 0.06f
        state = state.copy(playerX = min(0.9f, max(0.1f, x)))
    }

    fun tick(dtSeconds: Float) {
        var y = state.ballY + dtSeconds * 0.22f
        var x = state.ballX - dtSeconds * 0.07f
        var score = state.score
        var shots = state.shots

        if (y >= 0.88f) {
            shots += 1
            if (kotlin.math.abs(x - state.playerX) < 0.15f) score += 1
            y = 0.15f
            x = 0.15f + ((shots * 37) % 70) / 100f
        }
        state = state.copy(ballX = x, ballY = y, score = score, shots = shots)
    }
}
