package com.edwardresearchlabs.aimotion.game

import com.edwardresearchlabs.aimotion.motion.BodyPose
import com.edwardresearchlabs.aimotion.motion.Joint
import com.edwardresearchlabs.aimotion.motion.MotionRuntime
import com.edwardresearchlabs.aimotion.motion.PosePoint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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
    val rotation: Float,
    val ageSeconds: Float = 0f
)

data class NinjaHit(
    val x: Float,
    val y: Float,
    val type: NinjaTargetType,
    val points: Int,
    val label: String,
    val perfect: Boolean = false,
    val impactSpeed: Float = 0f,
    val reactionMs: Int = 0
)

data class NinjaState(
    val score: Int = 0,
    val combo: Int = 0,
    val bestCombo: Int = 0,
    val lives: Int = 3,
    val hits: Int = 0,
    val misses: Int = 0,
    val perfectHits: Int = 0,
    val totalReactionMs: Long = 0L,
    val level: Int = 1
) {
    val accuracyPercent: Int
        get() {
            val attempts = hits + misses
            return if (attempts == 0) 100 else ((hits * 100f) / attempts).toInt()
        }

    val averageReactionMs: Int
        get() = if (hits == 0) 0 else (totalReactionMs / hits).toInt()
}

class BodyNinjaGame {

    private data class Velocity(val x: Float, val y: Float) {
        val speed: Float get() = hypot(x, y)
    }

    var state = NinjaState()
        private set

    private val targets = mutableListOf<NinjaTarget>()
    private var accumulator = -0.7f
    private var idCounter = 1L
    private var elapsed = 0f
    private var previousPose: BodyPose? = null
    private val velocities = mutableMapOf<Joint, Velocity>()
    private val random = Random(42)

    fun targets(): List<NinjaTarget> = targets.toList()

    fun reset() {
        targets.clear()
        accumulator = -0.7f
        elapsed = 0f
        idCounter = 1L
        previousPose = null
        velocities.clear()
        state = NinjaState()
    }

    fun update(dt: Float, pose: BodyPose?): List<NinjaHit> {
        elapsed += dt
        accumulator += dt

        updateVelocities(pose)

        val level = 1 + (state.score / 900).coerceAtMost(5)
        if (level != state.level) state = state.copy(level = level)

        val spawnEvery = when (level) {
            1 -> 1.05f
            2 -> 0.88f
            3 -> 0.76f
            4 -> 0.66f
            5 -> 0.58f
            else -> 0.52f
        }

        if (accumulator >= spawnEvery) {
            accumulator = 0f
            spawnTarget(level)
        }

        val hits = mutableListOf<NinjaHit>()
        val next = mutableListOf<NinjaTarget>()

        for (target in targets) {
            val moved = target.copy(
                x = (target.x + target.driftX * dt).coerceIn(0.07f, 0.93f),
                y = target.y + target.speedY * dt,
                rotation = target.rotation + dt * (2.2f + level * 0.2f),
                ageSeconds = target.ageSeconds + dt
            )

            if (moved.type == NinjaTargetType.DODGE && pose != null && targetTouchesTorso(moved, pose)) {
                state = state.copy(
                    lives = (state.lives - 1).coerceAtLeast(0),
                    combo = 0,
                    misses = state.misses + 1
                )
                hits += NinjaHit(
                    x = moved.x,
                    y = moved.y,
                    type = NinjaTargetType.DODGE,
                    points = 0,
                    label = "HIT — DODGE",
                    reactionMs = (moved.ageSeconds * 1000).toInt()
                )
                continue
            }

            val hit = pose?.let { detectHit(moved, it) }
            if (hit != null) {
                val combo = state.combo + 1
                val comboBonus = min(combo, 20) * 6
                state = state.copy(
                    score = state.score + hit.points + comboBonus,
                    combo = combo,
                    bestCombo = maxOf(state.bestCombo, combo),
                    hits = state.hits + 1,
                    perfectHits = state.perfectHits + if (hit.perfect) 1 else 0,
                    totalReactionMs = state.totalReactionMs + hit.reactionMs
                )
                hits += hit
                continue
            }

            if (moved.y > 1.08f) {
                if (moved.type == NinjaTargetType.DODGE) {
                    val combo = state.combo + 1
                    state = state.copy(
                        score = state.score + 45 + combo * 3,
                        combo = combo,
                        bestCombo = maxOf(state.bestCombo, combo)
                    )
                    hits += NinjaHit(
                        x = moved.x,
                        y = 0.92f,
                        type = NinjaTargetType.DODGE,
                        points = 45,
                        label = "CLEAN DODGE",
                        reactionMs = (moved.ageSeconds * 1000).toInt()
                    )
                } else {
                    state = state.copy(combo = 0, misses = state.misses + 1)
                }
                continue
            }

            next += moved
        }

        targets.clear()
        targets += next

        if (state.lives <= 0) {
            state = state.copy(lives = 3, combo = 0)
            targets.clear()
            accumulator = -1.0f
        }

        previousPose = pose
        return hits
    }

    private fun spawnTarget(level: Int) {
        val roll = random.nextFloat()
        val type = when (level) {
            1 -> NinjaTargetType.HAND
            2 -> if (roll < 0.82f) NinjaTargetType.HAND else NinjaTargetType.DODGE
            3 -> when {
                roll < 0.62f -> NinjaTargetType.HAND
                roll < 0.86f -> NinjaTargetType.FOOT
                else -> NinjaTargetType.DODGE
            }
            else -> when {
                roll < 0.50f -> NinjaTargetType.HAND
                roll < 0.78f -> NinjaTargetType.FOOT
                else -> NinjaTargetType.DODGE
            }
        }

        val x = when (type) {
            NinjaTargetType.HAND -> 0.16f + random.nextFloat() * 0.68f
            NinjaTargetType.FOOT -> 0.18f + random.nextFloat() * 0.64f
            NinjaTargetType.DODGE -> 0.24f + random.nextFloat() * 0.52f
        }

        val radius = when (type) {
            NinjaTargetType.HAND -> 0.042f
            NinjaTargetType.FOOT -> 0.050f
            NinjaTargetType.DODGE -> 0.070f
        }

        val levelBoost = (level - 1) * 0.018f
        targets += NinjaTarget(
            id = idCounter++,
            type = type,
            x = x,
            y = -0.08f,
            radius = radius,
            speedY = when (type) {
                NinjaTargetType.HAND -> 0.28f + random.nextFloat() * 0.09f + levelBoost
                NinjaTargetType.FOOT -> 0.23f + random.nextFloat() * 0.07f + levelBoost
                NinjaTargetType.DODGE -> 0.21f + random.nextFloat() * 0.06f + levelBoost
            },
            driftX = (random.nextFloat() - 0.5f) * (0.06f + level * 0.01f),
            rotation = random.nextFloat() * 6.28f
        )
    }

    private fun updateVelocities(pose: BodyPose?) {
        val previous = previousPose ?: return
        if (pose == null) return

        val dt = ((pose.timestampMs - previous.timestampMs).coerceIn(8L, 100L)) / 1000f
        val joints = listOf(
            Joint.LEFT_WRIST, Joint.RIGHT_WRIST,
            Joint.LEFT_ELBOW, Joint.RIGHT_ELBOW,
            Joint.LEFT_ANKLE, Joint.RIGHT_ANKLE,
            Joint.LEFT_FOOT_INDEX, Joint.RIGHT_FOOT_INDEX
        )

        for (joint in joints) {
            val now = pose[joint] ?: continue
            val old = previous[joint] ?: continue
            if (now.confidence < 0.4f || old.confidence < 0.4f) continue

            val vx = (now.x - old.x) / dt
            val vy = (now.y - old.y) / dt
            val previousV = velocities[joint]
            velocities[joint] = if (previousV == null) {
                Velocity(vx, vy)
            } else {
                Velocity(
                    previousV.x * 0.35f + vx * 0.65f,
                    previousV.y * 0.35f + vy * 0.65f
                )
            }
        }
    }

    private fun detectHit(target: NinjaTarget, pose: BodyPose): NinjaHit? {
        if (target.type == NinjaTargetType.DODGE) return null

        val segments = when (target.type) {
            NinjaTargetType.HAND -> listOf(
                Triple(Joint.LEFT_WRIST, Joint.LEFT_ELBOW, Joint.LEFT_WRIST),
                Triple(Joint.LEFT_ELBOW, Joint.LEFT_SHOULDER, Joint.LEFT_WRIST),
                Triple(Joint.RIGHT_WRIST, Joint.RIGHT_ELBOW, Joint.RIGHT_WRIST),
                Triple(Joint.RIGHT_ELBOW, Joint.RIGHT_SHOULDER, Joint.RIGHT_WRIST)
            )
            NinjaTargetType.FOOT -> listOf(
                Triple(Joint.LEFT_FOOT_INDEX, Joint.LEFT_ANKLE, Joint.LEFT_ANKLE),
                Triple(Joint.LEFT_ANKLE, Joint.LEFT_KNEE, Joint.LEFT_ANKLE),
                Triple(Joint.RIGHT_FOOT_INDEX, Joint.RIGHT_ANKLE, Joint.RIGHT_ANKLE),
                Triple(Joint.RIGHT_ANKLE, Joint.RIGHT_KNEE, Joint.RIGHT_ANKLE)
            )
            NinjaTargetType.DODGE -> emptyList()
        }

        for ((aJoint, bJoint, velocityJoint) in segments) {
            val a = pose[aJoint] ?: continue
            val b = pose[bJoint] ?: continue
            if (a.confidence < 0.42f || b.confidence < 0.42f) continue

            val ax = MotionRuntime.mapX(a.x)
            val ay = a.y
            val bx = MotionRuntime.mapX(b.x)
            val by = b.y

            val capsuleRadius = if (target.type == NinjaTargetType.HAND) 0.032f else 0.038f
            val distance = pointToSegmentDistance(target.x, target.y, ax, ay, bx, by)

            if (distance <= target.radius + capsuleRadius) {
                val speed = velocities[velocityJoint]?.speed ?: 0f
                val perfectThreshold = if (target.type == NinjaTargetType.HAND) 0.85f else 0.68f
                val perfect = speed >= perfectThreshold

                val base = if (target.type == NinjaTargetType.HAND) 100 else 140
                val speedBonus = (speed.coerceIn(0f, 1.6f) * 55f).toInt()
                val perfectBonus = if (perfect) 90 else 0
                val labelBase = if (target.type == NinjaTargetType.HAND) "SLICE" else "KICK"

                return NinjaHit(
                    x = target.x,
                    y = target.y,
                    type = target.type,
                    points = base + speedBonus + perfectBonus,
                    label = if (perfect) "PERFECT $labelBase" else labelBase,
                    perfect = perfect,
                    impactSpeed = speed,
                    reactionMs = (target.ageSeconds * 1000).toInt()
                )
            }
        }

        return null
    }

    private fun pointToSegmentDistance(
        px: Float, py: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float
    ): Float {
        val abx = bx - ax
        val aby = by - ay
        val apx = px - ax
        val apy = py - ay
        val denom = abx * abx + aby * aby

        if (denom < 0.000001f) return hypot(px - ax, py - ay)

        val t = ((apx * abx + apy * aby) / denom).coerceIn(0f, 1f)
        val cx = ax + abx * t
        val cy = ay + aby * t
        return hypot(px - cx, py - cy)
    }

    private fun targetTouchesTorso(target: NinjaTarget, pose: BodyPose): Boolean {
        val ls = pose[Joint.LEFT_SHOULDER] ?: return false
        val rs = pose[Joint.RIGHT_SHOULDER] ?: return false
        val lh = pose[Joint.LEFT_HIP] ?: return false
        val rh = pose[Joint.RIGHT_HIP] ?: return false

        val xs = listOf(MotionRuntime.mapX(ls.x), MotionRuntime.mapX(rs.x), MotionRuntime.mapX(lh.x), MotionRuntime.mapX(rh.x))
        val ys = listOf(ls.y, rs.y, lh.y, rh.y)
        val left = xs.minOrNull()!! - 0.035f
        val right = xs.maxOrNull()!! + 0.035f
        val top = ys.minOrNull()!! - 0.035f
        val bottom = ys.maxOrNull()!! + 0.050f

        return target.x + target.radius > left &&
            target.x - target.radius < right &&
            target.y + target.radius > top &&
            target.y - target.radius < bottom
    }
}
