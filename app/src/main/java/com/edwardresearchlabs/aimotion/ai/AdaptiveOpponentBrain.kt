package com.edwardresearchlabs.aimotion.ai

import com.edwardresearchlabs.aimotion.motion.MotionEvent
import kotlin.math.max
import kotlin.random.Random

enum class OpponentAction { ADVANCE, RETREAT, GUARD, DODGE, JAB, HEAVY, COUNTER, WAIT }
enum class OpponentStyle { BRUTE, COUNTER, LEARNER }

data class OpponentFeatures(
    val distance: Float,
    val playerAttacking: Boolean,
    val playerGuarding: Boolean,
    val enemyHealthRatio: Float,
    val attackReady: Boolean,
    val wave: Int
)

data class AdaptiveProfile(
    val rightPunchBias: Float,
    val leftPunchBias: Float,
    val kickRate: Float,
    val guardRate: Float,
    val aggression: Float,
    val repeatBias: Float
) {
    fun dominantPattern(): String = when {
        kickRate > 0.34f -> "KICKS"
        rightPunchBias > leftPunchBias + 0.12f -> "RIGHT"
        leftPunchBias > rightPunchBias + 0.12f -> "LEFT"
        aggression > 0.45f -> "PRESSURE"
        guardRate > 0.42f -> "GUARD"
        else -> "MIXED"
    }
}

class AdaptiveOpponentBrain(seed: Int = 441) {
    private val random = Random(seed)
    private var rightPunchBias = 0.20f
    private var leftPunchBias = 0.20f
    private var kickRate = 0.10f
    private var guardRate = 0.10f
    private var aggression = 0.20f
    private var repeatBias = 0f
    private var previousAttack: MotionEvent? = null

    fun reset() {
        rightPunchBias = 0.20f
        leftPunchBias = 0.20f
        kickRate = 0.10f
        guardRate = 0.10f
        aggression = 0.20f
        repeatBias = 0f
        previousAttack = null
    }

    fun observe(events: Set<MotionEvent>, guarding: Boolean) {
        val alpha = 0.055f
        val attack = when {
            events.contains(MotionEvent.RIGHT_PUNCH) -> MotionEvent.RIGHT_PUNCH
            events.contains(MotionEvent.LEFT_PUNCH) -> MotionEvent.LEFT_PUNCH
            events.contains(MotionEvent.RIGHT_KICK) -> MotionEvent.RIGHT_KICK
            events.contains(MotionEvent.LEFT_KICK) -> MotionEvent.LEFT_KICK
            else -> null
        }

        rightPunchBias = ewma(rightPunchBias, if (attack == MotionEvent.RIGHT_PUNCH) 1f else 0f, alpha)
        leftPunchBias = ewma(leftPunchBias, if (attack == MotionEvent.LEFT_PUNCH) 1f else 0f, alpha)
        kickRate = ewma(kickRate, if (attack == MotionEvent.RIGHT_KICK || attack == MotionEvent.LEFT_KICK) 1f else 0f, alpha)
        guardRate = ewma(guardRate, if (guarding) 1f else 0f, alpha * 0.65f)
        aggression = ewma(aggression, if (attack != null) 1f else 0f, alpha * 0.75f)
        repeatBias = ewma(repeatBias, if (attack != null && attack == previousAttack) 1f else 0f, 0.10f)
        if (attack != null) previousAttack = attack
    }

    fun profile(): AdaptiveProfile = AdaptiveProfile(
        rightPunchBias.coerceIn(0f, 1f),
        leftPunchBias.coerceIn(0f, 1f),
        kickRate.coerceIn(0f, 1f),
        guardRate.coerceIn(0f, 1f),
        aggression.coerceIn(0f, 1f),
        repeatBias.coerceIn(0f, 1f)
    )

    fun choose(style: OpponentStyle, f: OpponentFeatures): OpponentAction {
        val p = profile()
        val scores = linkedMapOf(
            OpponentAction.ADVANCE to 0.20f,
            OpponentAction.RETREAT to 0.08f,
            OpponentAction.GUARD to 0.10f,
            OpponentAction.DODGE to 0.08f,
            OpponentAction.JAB to 0.08f,
            OpponentAction.HEAVY to 0.05f,
            OpponentAction.COUNTER to 0.04f,
            OpponentAction.WAIT to 0.08f
        )
        fun bump(a: OpponentAction, v: Float) { scores[a] = (scores[a] ?: 0f) + v }

        if (f.distance > 0.16f) bump(OpponentAction.ADVANCE, 0.62f) else bump(OpponentAction.JAB, 0.34f)
        if (f.playerAttacking) {
            bump(OpponentAction.GUARD, 0.46f)
            bump(OpponentAction.DODGE, 0.34f)
            bump(OpponentAction.COUNTER, 0.24f)
            bump(OpponentAction.RETREAT, 0.16f)
        }
        if (p.repeatBias > 0.25f && f.playerAttacking) {
            bump(OpponentAction.DODGE, 0.30f)
            bump(OpponentAction.COUNTER, 0.34f)
        }
        if ((p.rightPunchBias > 0.28f || p.leftPunchBias > 0.28f) && f.playerAttacking) bump(OpponentAction.GUARD, 0.20f)
        if (p.kickRate > 0.22f && f.playerAttacking) {
            bump(OpponentAction.RETREAT, 0.28f)
            bump(OpponentAction.DODGE, 0.20f)
        }
        if (f.playerGuarding) {
            bump(OpponentAction.RETREAT, 0.18f)
            bump(OpponentAction.HEAVY, 0.10f)
        }
        if (!f.attackReady) {
            scores[OpponentAction.JAB] = 0.01f
            scores[OpponentAction.HEAVY] = 0.01f
            scores[OpponentAction.COUNTER] = 0.01f
        }

        when (style) {
            OpponentStyle.BRUTE -> {
                bump(OpponentAction.ADVANCE, 0.28f)
                bump(OpponentAction.HEAVY, 0.34f)
                bump(OpponentAction.JAB, 0.16f)
            }
            OpponentStyle.COUNTER -> {
                bump(OpponentAction.GUARD, 0.22f)
                bump(OpponentAction.DODGE, 0.30f)
                bump(OpponentAction.COUNTER, 0.38f)
            }
            OpponentStyle.LEARNER -> {
                val learning = (p.aggression + p.repeatBias + p.kickRate).coerceIn(0f, 1.5f)
                bump(OpponentAction.DODGE, learning * 0.16f)
                bump(OpponentAction.GUARD, max(p.rightPunchBias, p.leftPunchBias) * 0.24f)
                bump(OpponentAction.COUNTER, p.repeatBias * 0.40f)
                bump(OpponentAction.RETREAT, p.kickRate * 0.26f)
            }
        }

        val total = scores.values.sum().coerceAtLeast(0.001f)
        var needle = random.nextFloat() * total
        for ((action, score) in scores) {
            needle -= score.coerceAtLeast(0.001f)
            if (needle <= 0f) return action
        }
        return OpponentAction.WAIT
    }

    private fun ewma(current: Float, sample: Float, alpha: Float): Float =
        current * (1f - alpha) + sample * alpha
}
