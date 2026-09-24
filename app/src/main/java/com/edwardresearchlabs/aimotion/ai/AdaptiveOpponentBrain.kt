package com.edwardresearchlabs.aimotion.ai

import com.edwardresearchlabs.aimotion.motion.MotionEvent
import kotlin.math.max
import kotlin.random.Random

enum class OpponentAction {
    ADVANCE,
    RETREAT,
    GUARD,
    DODGE,
    JAB,
    HEAVY,
    COUNTER,
    WAIT
}

enum class OpponentStyle {
    BRUTE,
    COUNTER,
    LEARNER
}

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
    fun dominantPattern(): String {
        return when {
            kickRate > 0.34f -> "READ: KICKS"
            rightPunchBias > leftPunchBias + 0.12f -> "READ: RIGHT"
            leftPunchBias > rightPunchBias + 0.12f -> "READ: LEFT"
            aggression > 0.45f -> "READ: PRESSURE"
            guardRate > 0.42f -> "READ: GUARD"
            else -> "READ: MIXED"
        }
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
    private var previousWasAttack = false

    fun reset() {
        rightPunchBias = 0.20f
        leftPunchBias = 0.20f
        kickRate = 0.10f
        guardRate = 0.10f
        aggression = 0.20f
        repeatBias = 0f
        previousAttack = null
        previousWasAttack = false
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
        kickRate = ewma(
            kickRate,
            if (attack == MotionEvent.RIGHT_KICK || attack == MotionEvent.LEFT_KICK) 1f else 0f,
            alpha
        )
        guardRate = ewma(guardRate, if (guarding) 1f else 0f, alpha * 0.65f)
        aggression = ewma(aggression, if (attack != null) 1f else 0f, alpha * 0.75f)

        val repeated = attack != null && previousAttack == attack
        repeatBias = ewma(repeatBias, if (repeated) 1f else 0f, 0.10f)

        if (attack != null) previousAttack = attack
        previousWasAttack = attack != null
    }

    fun profile(): AdaptiveProfile {
        return AdaptiveProfile(
            rightPunchBias = rightPunchBias.coerceIn(0f, 1f),
            leftPunchBias = leftPunchBias.coerceIn(0f, 1f),
            kickRate = kickRate.coerceIn(0f, 1f),
            guardRate = guardRate.coerceIn(0f, 1f),
            aggression = aggression.coerceIn(0f, 1f),
            repeatBias = repeatBias.coerceIn(0f, 1f)
        )
    }

    fun choose(
        style: OpponentStyle,
        features: OpponentFeatures
    ): OpponentAction {
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

        if (features.distance > 0.16f) {
            scores.bump(OpponentAction.ADVANCE, 0.62f)
            scores.bump(OpponentAction.WAIT, 0.10f)
        } else {
            scores.bump(OpponentAction.JAB, 0.34f)
        }

        if (features.playerAttacking) {
            scores.bump(OpponentAction.GUARD, 0.46f)
            scores.bump(OpponentAction.DODGE, 0.34f)
            scores.bump(OpponentAction.COUNTER, 0.24f)
            scores.bump(OpponentAction.RETREAT, 0.16f)
        }

        if (p.repeatBias > 0.25f && features.playerAttacking) {
            scores.bump(OpponentAction.DODGE, 0.30f)
            scores.bump(OpponentAction.COUNTER, 0.34f)
        }

        if ((p.rightPunchBias > 0.28f || p.leftPunchBias > 0.28f) && features.playerAttacking) {
            scores.bump(OpponentAction.GUARD, 0.20f)
        }

        if (p.kickRate > 0.22f && features.playerAttacking) {
            scores.bump(OpponentAction.RETREAT, 0.28f)
            scores.bump(OpponentAction.DODGE, 0.20f)
        }

        if (features.playerGuarding) {
            scores.bump(OpponentAction.RETREAT, 0.18f)
            scores.bump(OpponentAction.HEAVY, 0.10f)
            scores.bump(OpponentAction.WAIT, 0.10f)
        }

        if (!features.attackReady) {
            scores[OpponentAction.JAB] = 0.01f
            scores[OpponentAction.HEAVY] = 0.01f
            scores[OpponentAction.COUNTER] = 0.01f
        }

        when (style) {
            OpponentStyle.BRUTE -> {
                scores.bump(OpponentAction.ADVANCE, 0.28f)
                scores.bump(OpponentAction.HEAVY, 0.34f)
                scores.bump(OpponentAction.JAB, 0.16f)
                scores[OpponentAction.RETREAT] = (scores[OpponentAction.RETREAT] ?: 0f) * 0.55f
                scores[OpponentAction.DODGE] = (scores[OpponentAction.DODGE] ?: 0f) * 0.55f
            }

            OpponentStyle.COUNTER -> {
                scores.bump(OpponentAction.GUARD, 0.22f)
                scores.bump(OpponentAction.DODGE, 0.30f)
                scores.bump(OpponentAction.COUNTER, 0.38f)
                scores.bump(OpponentAction.RETREAT, 0.18f)
            }

            OpponentStyle.LEARNER -> {
                val learning = (p.aggression + p.repeatBias + p.kickRate).coerceIn(0f, 1.5f)
                scores.bump(OpponentAction.DODGE, learning * 0.16f)
                scores.bump(OpponentAction.GUARD, max(p.rightPunchBias, p.leftPunchBias) * 0.24f)
                scores.bump(OpponentAction.COUNTER, p.repeatBias * 0.40f)
                scores.bump(OpponentAction.RETREAT, p.kickRate * 0.26f)
            }
        }

        val wavePressure = ((features.wave - 1).coerceAtLeast(0) * 0.018f).coerceAtMost(0.12f)
        scores.bump(OpponentAction.ADVANCE, wavePressure)
        scores.bump(OpponentAction.JAB, wavePressure * 0.8f)

        if (features.enemyHealthRatio < 0.40f) {
            scores.bump(OpponentAction.GUARD, 0.18f)
            scores.bump(OpponentAction.RETREAT, 0.14f)
        }

        return weightedPick(scores)
    }

    private fun weightedPick(scores: Map<OpponentAction, Float>): OpponentAction {
        val positive = scores.mapValues { (_, value) -> value.coerceAtLeast(0.001f) }
        val total = positive.values.sum().coerceAtLeast(0.001f)
        var needle = random.nextFloat() * total

        for ((action, score) in positive) {
            needle -= score
            if (needle <= 0f) return action
        }
        return OpponentAction.WAIT
    }

    private fun MutableMap<OpponentAction, Float>.bump(action: OpponentAction, value: Float) {
        this[action] = (this[action] ?: 0f) + value
    }

    private fun ewma(current: Float, sample: Float, alpha: Float): Float {
        return current * (1f - alpha) + sample * alpha
    }
}
