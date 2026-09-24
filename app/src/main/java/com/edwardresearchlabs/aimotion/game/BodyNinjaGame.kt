package com.edwardresearchlabs.aimotion.game

import com.edwardresearchlabs.aimotion.motion.BodyPose
import com.edwardresearchlabs.aimotion.motion.Joint
import kotlin.math.hypot
import kotlin.random.Random

enum class NinjaTargetType {
    HAND,
    FOOT,
    DODGE
}

data class NinjaTarget(
    val id: Long,
    val type: NinjaTargetType,
    val x: Float,
    val y: Float,
    val radius: Float,
    val speedY: Float,
    val driftX: Float,
    val rotation: Float
)

data class NinjaHit(
    val x: Float,
    val y: Float,
    val type: NinjaTargetType,
    val points: Int,
    val label: String
)

data class NinjaState(
    val score: Int = 0,
    val combo: Int = 0,
    val bestCombo: Int = 0,
    val lives: Int = 3,
    val hits: Int = 0,
    val misses: Int = 0
)

class BodyNinjaGame {

    var state = NinjaState()
        private set

    private val targets = mutableListOf<NinjaTarget>()
    private var accumulator = 0f
    private var idCounter = 1L
    private var elapsed = 0f
    private val random = Random(42)

    fun targets(): List<NinjaTarget> = targets.toList()

    fun reset() {
        targets.clear()
        accumulator = 0f
        elapsed = 0f
        idCounter = 1L
        state = NinjaState()
    }

    fun update(dt: Float, pose: BodyPose?): List<NinjaHit> {
        elapsed += dt
        accumulator += dt

        val spawnEvery = (1.05f - elapsed * 0.003f).coerceAtLeast(0.52f)
        if (accumulator >= spawnEvery) {
            accumulator = 0f
            spawnTarget()
        }

        val hits = mutableListOf<NinjaHit>()
        val next = mutableListOf<NinjaTarget>()

        for (target in targets) {
            val moved = target.copy(
                x = (target.x + target.driftX * dt).coerceIn(0.08f, 0.92f),
                y = target.y + target.speedY * dt,
                rotation = target.rotation + dt * 2.4f
            )

            val hit = pose?.let { detectHit(moved, it) }
            if (hit != null) {
                hits += hit
                val combo = state.combo + 1
                state = state.copy(
                    score = state.score + hit.points + combo * 5,
                    combo = combo,
                    bestCombo = maxOf(state.bestCombo, combo),
                    hits = state.hits + 1
                )
                continue
            }

            if (moved.y > 1.08f) {
                if (moved.type == NinjaTargetType.DODGE) {
                    val combo = state.combo + 1
                    state = state.copy(
                        score = state.score + 35 + combo * 3,
                        combo = combo,
                        bestCombo = maxOf(state.bestCombo, combo)
                    )
                } else {
                    state = state.copy(
                        combo = 0,
                        misses = state.misses + 1
                    )
                }
                continue
            }

            if (moved.type == NinjaTargetType.DODGE && pose != null && targetTouchesTorso(moved, pose)) {
                state = state.copy(
                    lives = (state.lives - 1).coerceAtLeast(0),
                    combo = 0
                )
                hits += NinjaHit(
                    x = moved.x,
                    y = moved.y,
                    type = NinjaTargetType.DODGE,
                    points = 0,
                    label = "DODGE!"
                )
                continue
            }

            next += moved
        }

        targets.clear()
        targets += next

        if (state.lives <= 0) {
            state = state.copy(lives = 3, combo = 0)
            targets.clear()
            accumulator = -0.8f
        }

        return hits
    }

    private fun spawnTarget() {
        val roll = random.nextFloat()
        val type = when {
            roll < 0.54f -> NinjaTargetType.HAND
            roll < 0.80f -> NinjaTargetType.FOOT
            else -> NinjaTargetType.DODGE
        }

        val x = when (type) {
            NinjaTargetType.HAND -> 0.18f + random.nextFloat() * 0.64f
            NinjaTargetType.FOOT -> 0.18f + random.nextFloat() * 0.64f
            NinjaTargetType.DODGE -> 0.25f + random.nextFloat() * 0.50f
        }

        val radius = when (type) {
            NinjaTargetType.HAND -> 0.045f
            NinjaTargetType.FOOT -> 0.052f
            NinjaTargetType.DODGE -> 0.072f
        }

        targets += NinjaTarget(
            id = idCounter++,
            type = type,
            x = x,
            y = -0.08f,
            radius = radius,
            speedY = when (type) {
                NinjaTargetType.HAND -> 0.30f + random.nextFloat() * 0.10f
                NinjaTargetType.FOOT -> 0.24f + random.nextFloat() * 0.08f
                NinjaTargetType.DODGE -> 0.22f + random.nextFloat() * 0.06f
            },
            driftX = (random.nextFloat() - 0.5f) * 0.08f,
            rotation = random.nextFloat() * 6.28f
        )
    }

    private fun detectHit(target: NinjaTarget, pose: BodyPose): NinjaHit? {
        if (target.type == NinjaTargetType.DODGE) return null

        val candidates = when (target.type) {
            NinjaTargetType.HAND -> listOf(Joint.LEFT_WRIST, Joint.RIGHT_WRIST)
            NinjaTargetType.FOOT -> listOf(Joint.LEFT_ANKLE, Joint.RIGHT_ANKLE, Joint.LEFT_FOOT_INDEX, Joint.RIGHT_FOOT_INDEX)
            NinjaTargetType.DODGE -> emptyList()
        }

        for (joint in candidates) {
            val p = pose[joint] ?: continue
            if (p.confidence < 0.45f) continue

            val x = 1f - p.x
            val y = p.y
            val distance = hypot(x - target.x, y - target.y)

            if (distance <= target.radius + 0.045f) {
                val points = if (target.type == NinjaTargetType.HAND) 100 else 140
                val label = if (target.type == NinjaTargetType.HAND) "SLICE" else "KICK"
                return NinjaHit(target.x, target.y, target.type, points, label)
            }
        }

        return null
    }

    private fun targetTouchesTorso(target: NinjaTarget, pose: BodyPose): Boolean {
        val ls = pose[Joint.LEFT_SHOULDER] ?: return false
        val rs = pose[Joint.RIGHT_SHOULDER] ?: return false
        val lh = pose[Joint.LEFT_HIP] ?: return false
        val rh = pose[Joint.RIGHT_HIP] ?: return false

        val left = 1f - maxOf(ls.x, lh.x) - 0.05f
        val right = 1f - minOf(rs.x, rh.x) + 0.05f
        val top = minOf(ls.y, rs.y) - 0.04f
        val bottom = maxOf(lh.y, rh.y) + 0.06f

        return target.x + target.radius > left &&
            target.x - target.radius < right &&
            target.y + target.radius > top &&
            target.y - target.radius < bottom
    }
}
