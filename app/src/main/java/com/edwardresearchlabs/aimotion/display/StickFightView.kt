package com.edwardresearchlabs.aimotion.display

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import com.edwardresearchlabs.aimotion.ai.AdaptiveOpponentBrain
import com.edwardresearchlabs.aimotion.ai.OpponentAction
import com.edwardresearchlabs.aimotion.ai.OpponentFeatures
import com.edwardresearchlabs.aimotion.ai.OpponentStyle
import com.edwardresearchlabs.aimotion.motion.BodyPose
import com.edwardresearchlabs.aimotion.motion.Joint
import com.edwardresearchlabs.aimotion.motion.MotionEvent
import com.edwardresearchlabs.aimotion.motion.MotionRuntime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class StickFightView(context: Context) : View(context) {

    private enum class PlayerAction { IDLE, PUNCH, KICK, GUARD, CROUCH }

    private data class Enemy(
        val id: Long,
        var x: Float,
        var y: Float,
        val side: Int,
        var hp: Int = 2,
        var alive: Boolean = true,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var angle: Float = 0f,
        var angularVelocity: Float = 0f,
        var attackCooldown: Float = 0.7f,
        var stunned: Float = 0f,
        var corpseAge: Float = 0f,
        val style: OpponentStyle = OpponentStyle.LEARNER,
        val maxHp: Int = hp,
        var action: OpponentAction = OpponentAction.WAIT,
        var decisionCooldown: Float = 0f,
        var actionTime: Float = 0f,
        var guarding: Boolean = false,
        var walkPhase: Float = 0f
    )

    private data class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var life: Float,
        var size: Float
    )

    private data class FloatLabel(
        var text: String,
        var x: Float,
        var y: Float,
        var life: Float,
        var strong: Boolean
    )

    private data class JointVelocity(val vx: Float, val vy: Float) {
        val speed: Float get() = hypot(vx, vy)
    }

    private data class PlayerTransform(
        val centerX: Float,
        val hipYRaw: Float,
        val scale: Float
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val enemies = mutableListOf<Enemy>()
    private val particles = mutableListOf<Particle>()
    private val labels = mutableListOf<FloatLabel>()
    private val velocities = mutableMapOf<Joint, JointVelocity>()
    private val random = Random(73)
    private val brain = AdaptiveOpponentBrain(441)

    private val vibrator: Vibrator? = if (android.os.Build.VERSION.SDK_INT >= 31) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var previousPose: BodyPose? = null
    private var lastProcessedPoseTimestamp = -1L
    private var lastPlayerAttackNs = 0L
    private var playerX = 0.50f
    private var playerFacing = 1
    private var playerAction = PlayerAction.IDLE
    private var playerActionUntilNs = 0L
    private var playerGuarding = false
    private var playerDodging = false
    private var lastFrameNs = System.nanoTime()
    private var enemyId = 1L

    private var score = 0
    private var combo = 0
    private var bestCombo = 0
    private var health = 5
    private var wave = 1
    private var spawnedThisWave = 0
    private var defeatedThisWave = 0
    private var spawnClock = -0.4f
    private var nextWaveClock = 0f

    private var shakeUntilNs = 0L
    private var shakeStrength = 0f
    private var slowUntilNs = 0L

    init {
        setBackgroundColor(0xFF050608.toInt())
    }

    fun resetGame() {
        enemies.clear()
        particles.clear()
        labels.clear()
        velocities.clear()
        brain.reset()
        previousPose = null
        lastProcessedPoseTimestamp = -1L
        lastPlayerAttackNs = 0L
        playerX = 0.50f
        playerFacing = 1
        playerAction = PlayerAction.IDLE
        playerActionUntilNs = 0L
        playerGuarding = false
        playerDodging = false
        enemyId = 1L
        score = 0
        combo = 0
        bestCombo = 0
        health = 5
        wave = 1
        spawnedThisWave = 0
        defeatedThisWave = 0
        spawnClock = -0.4f
        nextWaveClock = 0f
        shakeUntilNs = 0L
        slowUntilNs = 0L
        spawnEnemy()
        spawnedThisWave = 1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = System.nanoTime()
        val rawDt = ((now - lastFrameNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastFrameNs = now
        val physicsDt = if (now < slowUntilNs) rawDt * 0.28f else rawDt

        val pose = MotionRuntime.freshPose(500L)
        val transform = pose?.let { buildTransform(it) }

        if (transform != null) {
            playerX = playerX * 0.84f + transform.centerX * 0.16f
        }
        updatePlayerFacing()

        if (pose != null && pose.timestampMs != lastProcessedPoseTimestamp) {
            updateJointVelocities(pose)
            processPlayerAttacks(pose, transform, now)
            previousPose = pose
            lastProcessedPoseTimestamp = pose.timestampMs
        }

        if (now >= playerActionUntilNs && !playerGuarding) {
            playerAction = PlayerAction.IDLE
        }

        updateEnemies(physicsDt, pose, transform)
        updateParticles(rawDt)
        updateLabels(rawDt)
        manageWaves(rawDt)

        val w = width.toFloat()
        val h = height.toFloat()

        canvas.save()
        applyShake(canvas, now)
        drawBackground(canvas, w, h)
        drawEnemies(canvas, w, h)
        drawPlayer(canvas, w, h, pose != null, now)
        drawParticles(canvas, w, h)
        drawHud(canvas, w, h, pose != null)
        drawLabels(canvas, w, h)
        canvas.restore()

        postInvalidateOnAnimation()
    }

    private fun buildTransform(pose: BodyPose): PlayerTransform {
        val ls = pose[Joint.LEFT_SHOULDER]
        val rs = pose[Joint.RIGHT_SHOULDER]
        val lh = pose[Joint.LEFT_HIP]
        val rh = pose[Joint.RIGHT_HIP]

        val points = listOfNotNull(ls, rs, lh, rh).filter { it.confidence >= 0.45f }
        if (points.size < 2) return PlayerTransform(0.5f, 0.64f, 0.55f)

        val rawCenter = points.map { MotionRuntime.mapX(it.x) }.average().toFloat()
        val centerX = (0.50f + (rawCenter - 0.50f) * 0.34f).coerceIn(0.36f, 0.64f)
        return PlayerTransform(centerX, 0.64f, 0.55f)
    }

    private fun updatePlayerFacing() {
        val target = enemies.filter { it.alive }.minByOrNull { abs(it.x - playerX) }
        if (target != null && abs(target.x - playerX) > 0.02f) {
            playerFacing = if (target.x >= playerX) 1 else -1
        }
    }

    private fun mapPoint(pose: BodyPose, joint: Joint, t: PlayerTransform): Pair<Float, Float>? {
        val point = pose[joint] ?: return null
        if (point.confidence < 0.38f) return null
        val rawX = MotionRuntime.mapX(point.x)
        return (t.centerX + (rawX - t.centerX) * t.scale) to
            (0.64f + (point.y - t.hipYRaw) * t.scale)
    }

    private fun updateJointVelocities(pose: BodyPose) {
        val old = previousPose ?: return
        val oldTransform = buildTransform(old)
        val newTransform = buildTransform(pose)
        val dt = ((pose.timestampMs - old.timestampMs).coerceIn(8L, 120L)) / 1000f

        for (joint in listOf(
            Joint.LEFT_WRIST, Joint.RIGHT_WRIST,
            Joint.LEFT_ANKLE, Joint.RIGHT_ANKLE
        )) {
            val a = mapPoint(old, joint, oldTransform) ?: continue
            val b = mapPoint(pose, joint, newTransform) ?: continue
            val vx = (b.first - a.first) / dt
            val vy = (b.second - a.second) / dt
            val prior = velocities[joint]
            velocities[joint] = if (prior == null) {
                JointVelocity(vx, vy)
            } else {
                JointVelocity(
                    prior.vx * 0.30f + vx * 0.70f,
                    prior.vy * 0.30f + vy * 0.70f
                )
            }
        }
    }

    private fun processPlayerAttacks(pose: BodyPose, transform: PlayerTransform?, nowNs: Long) {
        if (transform == null) return

        val events = MotionRuntime.events
        playerGuarding = isBlocking(pose, transform)
        playerDodging = events.contains(MotionEvent.CROUCH) ||
            events.contains(MotionEvent.LEAN_LEFT) ||
            events.contains(MotionEvent.LEAN_RIGHT)

        brain.observe(events, playerGuarding)

        if (playerGuarding) {
            playerAction = PlayerAction.GUARD
            playerActionUntilNs = nowNs + 100_000_000L
        } else if (events.contains(MotionEvent.CROUCH)) {
            playerAction = PlayerAction.CROUCH
            playerActionUntilNs = nowNs + 150_000_000L
        }

        if (nowNs - lastPlayerAttackNs < 160_000_000L) return

        when {
            events.contains(MotionEvent.RIGHT_PUNCH) -> {
                lastPlayerAttackNs = nowNs
                playerAction = PlayerAction.PUNCH
                playerActionUntilNs = nowNs + 190_000_000L
                hitCanonical(velocities[Joint.RIGHT_WRIST]?.speed ?: 0.8f, false)
            }
            events.contains(MotionEvent.LEFT_PUNCH) -> {
                lastPlayerAttackNs = nowNs
                playerAction = PlayerAction.PUNCH
                playerActionUntilNs = nowNs + 190_000_000L
                hitCanonical(velocities[Joint.LEFT_WRIST]?.speed ?: 0.8f, false)
            }
            events.contains(MotionEvent.RIGHT_KICK) -> {
                lastPlayerAttackNs = nowNs
                playerAction = PlayerAction.KICK
                playerActionUntilNs = nowNs + 230_000_000L
                hitCanonical(velocities[Joint.RIGHT_ANKLE]?.speed ?: 0.7f, true)
            }
            events.contains(MotionEvent.LEFT_KICK) -> {
                lastPlayerAttackNs = nowNs
                playerAction = PlayerAction.KICK
                playerActionUntilNs = nowNs + 230_000_000L
                hitCanonical(velocities[Joint.LEFT_ANKLE]?.speed ?: 0.7f, true)
            }
        }
    }

    private fun hitCanonical(rawSpeed: Float, kick: Boolean) {
        updatePlayerFacing()
        val power = rawSpeed.coerceIn(0.60f, 2.8f)
        val reach = if (kick) 0.205f else 0.165f

        val enemy = enemies
            .filter { it.alive }
            .filter {
                val correctSide = if (playerFacing > 0) it.x >= playerX else it.x <= playerX
                correctSide && abs(it.x - playerX) <= reach
            }
            .minByOrNull { abs(it.x - playerX) }
            ?: return

        if (enemy.guarding && power < if (kick) 1.10f else 1.30f) {
            enemy.vx += playerFacing * 0.06f
            enemy.stunned = 0.08f
            labels += FloatLabel("BLOCK", enemy.x, 0.59f, 0.45f, false)
            spawnImpact(enemy.x, 0.64f, playerFacing.toFloat(), power * 0.45f)
            return
        }

        val strong = power >= if (kick) 1.05f else 1.25f
        enemy.hp -= if (strong) 2 else 1
        enemy.vx += playerFacing * (0.34f + power * if (kick) 0.26f else 0.20f)
        enemy.vy = -0.13f - power * if (kick) 0.10f else 0.065f
        enemy.angularVelocity += playerFacing * (2.5f + power * 2.2f)
        enemy.stunned = 0.18f + power * 0.05f

        if (enemy.hp <= 0) {
            enemy.alive = false
            enemy.corpseAge = 0f
            defeatedThisWave++
            combo++
            bestCombo = max(bestCombo, combo)
            val points = 100 + combo * 12 + (power * 42f).toInt() + if (kick) 30 else 0
            score += points
            labels += FloatLabel(if (strong) "HEAVY KO +$points" else "KO +$points", enemy.x, 0.56f, 0.90f, strong)
        } else {
            val points = 20 + (power * 16f).toInt()
            score += points
            labels += FloatLabel(if (kick) "KICK +$points" else "HIT +$points", enemy.x, 0.58f, 0.58f, false)
        }

        spawnImpact(enemy.x, 0.64f, playerFacing.toFloat(), power)
    }

    private fun processLimb(
        pose: BodyPose,
        transform: PlayerTransform,
        joint: Joint,
        eventTriggered: Boolean,
        speedThreshold: Float,
        kick: Boolean
    ) {
        val point = mapPoint(pose, joint, transform) ?: return
        val v = velocities[joint] ?: JointVelocity(0f, 0f)
        val speed = v.speed
        if (!eventTriggered && speed < speedThreshold) return

        var target: Enemy? = null
        var bestDistance = Float.MAX_VALUE
        for (enemy in enemies) {
            if (!enemy.alive || enemy.stunned > 0.05f) continue
            val d = hypot(point.first - enemy.x, point.second - (enemy.y - 0.135f))
            val reach = if (kick) 0.135f else 0.115f
            if (d < reach && d < bestDistance) {
                target = enemy
                bestDistance = d
            }
        }

        val enemy = target ?: return
        val power = speed.coerceIn(0.65f, 2.8f)

        if (enemy.guarding && power < if (kick) 1.05f else 1.35f) {
            val direction = if (enemy.x >= transform.centerX) 1f else -1f
            enemy.vx += direction * 0.08f
            enemy.stunned = 0.08f
            labels += FloatLabel("AI BLOCK", enemy.x, enemy.y - 0.22f, 0.48f, false)
            spawnImpact(enemy.x, enemy.y - 0.14f, direction, power * 0.45f)
            return
        }
        val relativeDirection = if (enemy.x >= transform.centerX) 1f else -1f
        val motionDirection = if (abs(v.vx) > 0.18f) {
            if (v.vx >= 0f) 1f else -1f
        } else {
            relativeDirection
        }
        val direction = if (motionDirection == relativeDirection) motionDirection else relativeDirection
        val damage = when {
            kick && power > 1.05f -> 2
            !kick && power > 1.35f -> 2
            else -> 1
        }

        enemy.hp -= damage
        enemy.vx += direction * (0.34f + power * if (kick) 0.28f else 0.22f)
        enemy.vy = -0.15f - power * if (kick) 0.11f else 0.075f
        enemy.angularVelocity += direction * (2.8f + power * 2.4f)
        enemy.stunned = 0.20f + power * 0.06f

        val strong = power >= if (kick) 1.0f else 1.25f
        if (enemy.hp <= 0) {
            enemy.alive = false
            enemy.corpseAge = 0f
            defeatedThisWave++
            combo++
            bestCombo = max(bestCombo, combo)
            val points = 100 + combo * 12 + (power * 45f).toInt() + if (kick) 35 else 0
            score += points
            labels += FloatLabel(
                if (strong) "HEAVY ${if (kick) "KICK" else "HIT"}  +$points" else "KO  +$points",
                enemy.x,
                enemy.y - 0.23f,
                0.95f,
                strong
            )
        } else {
            val points = 25 + (power * 18f).toInt()
            score += points
            labels += FloatLabel(
                "${if (kick) "KICK" else "HIT"}  +$points",
                enemy.x,
                enemy.y - 0.21f,
                0.60f,
                false
            )
        }

        spawnImpact(enemy.x, enemy.y - 0.14f, direction, power)
        if (strong) {
            slowUntilNs = System.nanoTime() + 95_000_000L
            shakeUntilNs = System.nanoTime() + 125_000_000L
            shakeStrength = 8f
            vibrate(32)
        } else {
            shakeUntilNs = System.nanoTime() + 65_000_000L
            shakeStrength = 3f
            vibrate(14)
        }
    }

    private fun updateEnemies(dt: Float, pose: BodyPose?, transform: PlayerTransform?) {
        val playerAttacking = playerAction == PlayerAction.PUNCH || playerAction == PlayerAction.KICK
        val tracking = pose != null

        val iterator = enemies.iterator()
        while (iterator.hasNext()) {
            val enemy = iterator.next()
            enemy.stunned = (enemy.stunned - dt).coerceAtLeast(0f)
            enemy.attackCooldown -= dt
            enemy.decisionCooldown -= dt
            enemy.actionTime += dt

            if (enemy.alive) {
                if (enemy.stunned > 0f) {
                    enemy.x += enemy.vx * dt
                    enemy.vx *= 0.88f
                    continue
                }

                val dx = playerX - enemy.x
                val distance = abs(dx)
                val direction = if (dx >= 0f) 1f else -1f

                if (enemy.x < 0.10f || enemy.x > 0.90f) {
                    enemy.x += direction * (0.19f + min(wave, 5) * 0.006f) * dt
                    enemy.walkPhase += dt * 7.5f
                    enemy.guarding = false
                    continue
                }

                if (enemy.decisionCooldown <= 0f) {
                    enemy.action = brain.choose(
                        enemy.style,
                        OpponentFeatures(
                            distance = distance,
                            playerAttacking = playerAttacking,
                            playerGuarding = playerGuarding,
                            enemyHealthRatio = enemy.hp.toFloat() / enemy.maxHp.coerceAtLeast(1),
                            attackReady = enemy.attackCooldown <= 0f,
                            wave = wave
                        )
                    )
                    enemy.actionTime = 0f
                    enemy.guarding = enemy.action == OpponentAction.GUARD
                    enemy.decisionCooldown = when (enemy.style) {
                        OpponentStyle.BRUTE -> 0.20f
                        OpponentStyle.COUNTER -> 0.15f
                        OpponentStyle.LEARNER -> 0.17f
                    } + random.nextFloat() * 0.04f
                }

                when {
                    distance > 0.30f -> {
                        enemy.x += direction * (0.135f + min(wave, 6) * 0.005f) * dt
                        enemy.walkPhase += dt * 7.0f
                    }
                    enemy.action == OpponentAction.ADVANCE -> {
                        enemy.x += direction * 0.125f * dt
                        enemy.walkPhase += dt * 7.3f
                    }
                    enemy.action == OpponentAction.RETREAT -> {
                        enemy.x -= direction * 0.095f * dt
                        enemy.walkPhase += dt * 6.0f
                    }
                    enemy.action == OpponentAction.DODGE -> {
                        if (enemy.actionTime < 0.18f) {
                            enemy.x -= direction * 0.24f * dt
                            enemy.walkPhase += dt * 10f
                        }
                    }
                    enemy.action == OpponentAction.GUARD -> Unit
                    enemy.action == OpponentAction.JAB ||
                        enemy.action == OpponentAction.HEAVY ||
                        enemy.action == OpponentAction.COUNTER -> {
                        if (distance > 0.155f) {
                            enemy.x += direction * 0.14f * dt
                            enemy.walkPhase += dt * 8f
                        } else if (enemy.attackCooldown <= 0f && tracking) {
                            resolveEnemyAttack(enemy, playerAttacking)
                        }
                    }
                    else -> {
                        if (distance > 0.20f) {
                            enemy.x += direction * 0.070f * dt
                            enemy.walkPhase += dt * 4.5f
                        }
                    }
                }

                enemy.x = enemy.x.coerceIn(-0.12f, 1.12f)
                enemy.angle *= 0.88f
            } else {
                enemy.corpseAge += dt
                enemy.vy += 0.82f * dt
                enemy.x += enemy.vx * dt
                enemy.y += enemy.vy * dt
                enemy.angle += enemy.angularVelocity * dt
                enemy.vx *= 0.992f
                enemy.angularVelocity *= 0.986f

                if (enemy.y > 0.84f) {
                    enemy.y = 0.84f
                    if (abs(enemy.vy) > 0.07f) {
                        enemy.vy *= -0.25f
                        enemy.vx *= 0.82f
                        enemy.angularVelocity *= 0.72f
                    } else {
                        enemy.vy = 0f
                    }
                }

                if (enemy.corpseAge > 1.7f || enemy.x < -0.20f || enemy.x > 1.20f) {
                    iterator.remove()
                }
            }
        }

        if (health <= 0) {
            labels += FloatLabel("ROUND RESET", playerX, 0.42f, 1.0f, true)
            health = 5
            combo = 0
            enemies.clear()
            spawnedThisWave = 0
            defeatedThisWave = 0
            spawnClock = 0f
            spawnEnemy()
            spawnedThisWave = 1
        }
    }

    private fun resolveEnemyAttack(enemy: Enemy, playerAttacking: Boolean) {
        val counter = enemy.action == OpponentAction.COUNTER && playerAttacking
        val heavy = enemy.action == OpponentAction.HEAVY

        enemy.attackCooldown = when {
            heavy -> 1.10f
            counter -> 0.68f
            else -> 0.88f
        }

        when {
            playerGuarding -> {
                score += 10
                enemy.stunned = if (heavy) 0.10f else 0.20f
                labels += FloatLabel("GUARD", playerX, 0.53f, 0.45f, false)
            }
            playerDodging -> {
                score += 15
                labels += FloatLabel("DODGE", playerX, 0.53f, 0.45f, false)
            }
            else -> {
                val damage = if (heavy && random.nextFloat() < 0.30f) 2 else 1
                health = (health - damage).coerceAtLeast(0)
                combo = 0
                labels += FloatLabel(
                    if (counter) "COUNTER!" else if (heavy) "HEAVY!" else "HIT!",
                    playerX,
                    0.50f,
                    0.60f,
                    true
                )
                shakeUntilNs = System.nanoTime() + if (heavy) 170_000_000L else 125_000_000L
                shakeStrength = if (heavy) 11f else 7f
                vibrate(if (heavy) 65 else 42)
            }
        }
    }

    private fun manageWaves(dt: Float) {
        val waveSize = min(2 + wave, 7)
        spawnClock += dt

        if (spawnedThisWave < waveSize && spawnClock >= spawnInterval()) {
            spawnClock = 0f
            spawnEnemy()
            spawnedThisWave++
        }

        if (spawnedThisWave >= waveSize && defeatedThisWave >= waveSize && enemies.none { it.alive }) {
            nextWaveClock += dt
            if (nextWaveClock >= 1.0f) {
                wave++
                spawnedThisWave = 0
                defeatedThisWave = 0
                nextWaveClock = 0f
                spawnClock = -0.45f
                labels += FloatLabel("WAVE $wave", 0.5f, 0.31f, 1.0f, true)
            }
        } else {
            nextWaveClock = 0f
        }
    }

    private fun spawnInterval(): Float = (1.05f - wave * 0.055f).coerceAtLeast(0.48f)

    private fun spawnEnemy() {
        val side = if ((spawnedThisWave + wave) % 2 == 0) -1 else 1
        val style = when ((spawnedThisWave + wave) % 3) {
            0 -> OpponentStyle.BRUTE
            1 -> OpponentStyle.COUNTER
            else -> OpponentStyle.LEARNER
        }
        val hp = if (wave >= 4 && spawnedThisWave % 3 == 2) 3 else 2
        enemies += Enemy(
            id = enemyId++,
            x = if (side < 0) -0.08f else 1.08f,
            y = 0.835f,
            side = side,
            hp = hp,
            style = style,
            maxHp = hp,
            attackCooldown = 0.45f + random.nextFloat() * 0.55f
        )
    }

    private fun isBlocking(pose: BodyPose, t: PlayerTransform): Boolean {
        val lw = mapPoint(pose, Joint.LEFT_WRIST, t) ?: return false
        val rw = mapPoint(pose, Joint.RIGHT_WRIST, t) ?: return false
        val ls = mapPoint(pose, Joint.LEFT_SHOULDER, t) ?: return false
        val rs = mapPoint(pose, Joint.RIGHT_SHOULDER, t) ?: return false
        val shoulderY = (ls.second + rs.second) * 0.5f
        val handsHigh = lw.second < shoulderY + 0.075f && rw.second < shoulderY + 0.075f
        val handsNearCenter = abs(lw.first - t.centerX) < 0.20f && abs(rw.first - t.centerX) < 0.20f
        return handsHigh && handsNearCenter
    }

    private fun spawnImpact(x: Float, y: Float, direction: Float, power: Float) {
        repeat(if (power > 1.2f) 26 else 14) {
            particles += Particle(
                x,
                y,
                direction * (0.10f + random.nextFloat() * 0.38f) +
                    (random.nextFloat() - 0.5f) * 0.18f,
                -0.08f - random.nextFloat() * 0.32f,
                0.35f + random.nextFloat() * 0.45f,
                0.003f + random.nextFloat() * 0.008f
            )
        }
    }

    private fun updateParticles(dt: Float) {
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.life -= dt
            if (p.life <= 0f) {
                iterator.remove()
            } else {
                p.x += p.vx * dt
                p.y += p.vy * dt
                p.vy += 0.40f * dt
            }
        }
    }

    private fun updateLabels(dt: Float) {
        val iterator = labels.iterator()
        while (iterator.hasNext()) {
            val label = iterator.next()
            label.life -= dt
            label.y -= 0.035f * dt
            if (label.life <= 0f) iterator.remove()
        }
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFF07111B.toInt(), 0xFF111823.toInt(), 0xFF050608.toInt()),
            floatArrayOf(0f, 0.66f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        val horizon = h * 0.835f
        paint.color = 0xFF11151B.toInt()
        canvas.drawRect(0f, horizon, w, h, paint)

        paint.color = 0xFF1E2630.toInt()
        for (i in 0..8) {
            val x = i * w / 8f
            val top = horizon - h * (0.09f + ((i * 37) % 5) * 0.025f)
            canvas.drawRect(x, top, x + w * 0.09f, horizon, paint)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1.5f, h * 0.002f)
        paint.color = 0x334A6478
        for (i in 0..7) {
            val y = horizon + (h - horizon) * i / 7f
            canvas.drawLine(0f, y, w, y, paint)
        }
        for (i in -6..6) {
            val x = w * 0.5f + i * w * 0.12f
            canvas.drawLine(w * 0.5f, horizon, x, h, paint)
        }
    }

    private fun drawPlayer(canvas: Canvas, w: Float, h: Float, tracking: Boolean, nowNs: Long) {
        val feetY = h * if (playerAction == PlayerAction.CROUCH) 0.855f else 0.84f
        val scale = if (playerAction == PlayerAction.CROUCH) 0.86f else 1f
        val x = playerX * w
        val facing = playerFacing.toFloat()

        val headR = h * 0.031f
        val headY = feetY - h * 0.255f * scale
        val shoulderY = headY + headR * 1.8f
        val hipY = shoulderY + h * 0.145f * scale
        val stroke = max(5f, h * 0.010f)

        paint.color = if (tracking) 0xFFF4F7FA.toInt() else 0x66778490
        paint.style = Paint.Style.FILL
        canvas.drawCircle(x + facing * headR * 0.18f, headY, headR, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = stroke

        val neckX = x + facing * w * 0.008f
        val hipX = x - facing * w * 0.006f
        canvas.drawLine(neckX, shoulderY, hipX, hipY, paint)

        canvas.drawLine(hipX, hipY, x + facing * w * 0.025f, hipY + h * 0.075f, paint)
        canvas.drawLine(x + facing * w * 0.025f, hipY + h * 0.075f, x + facing * w * 0.045f, feetY, paint)
        canvas.drawLine(hipX, hipY, x - facing * w * 0.030f, hipY + h * 0.070f, paint)
        canvas.drawLine(x - facing * w * 0.030f, hipY + h * 0.070f, x - facing * w * 0.042f, feetY, paint)

        val active = nowNs < playerActionUntilNs
        when {
            (playerAction == PlayerAction.GUARD || playerGuarding) -> {
                canvas.drawLine(neckX, shoulderY, x + facing * w * 0.024f, shoulderY + h * 0.045f, paint)
                canvas.drawLine(x + facing * w * 0.024f, shoulderY + h * 0.045f, x + facing * w * 0.052f, headY + h * 0.015f, paint)
                canvas.drawLine(neckX, shoulderY, x - facing * w * 0.012f, shoulderY + h * 0.052f, paint)
                canvas.drawLine(x - facing * w * 0.012f, shoulderY + h * 0.052f, x + facing * w * 0.028f, headY - h * 0.010f, paint)
            }
            playerAction == PlayerAction.PUNCH && active -> {
                canvas.drawLine(neckX, shoulderY, x + facing * w * 0.055f, shoulderY + h * 0.008f, paint)
                canvas.drawLine(x + facing * w * 0.055f, shoulderY + h * 0.008f, x + facing * w * 0.125f, shoulderY + h * 0.010f, paint)
                canvas.drawLine(neckX, shoulderY, x - facing * w * 0.025f, shoulderY + h * 0.050f, paint)
                canvas.drawLine(x - facing * w * 0.025f, shoulderY + h * 0.050f, x + facing * w * 0.020f, headY + h * 0.010f, paint)
            }
            else -> {
                canvas.drawLine(neckX, shoulderY, x + facing * w * 0.032f, shoulderY + h * 0.047f, paint)
                canvas.drawLine(x + facing * w * 0.032f, shoulderY + h * 0.047f, x + facing * w * 0.060f, headY + h * 0.020f, paint)
                canvas.drawLine(neckX, shoulderY, x - facing * w * 0.026f, shoulderY + h * 0.052f, paint)
                canvas.drawLine(x - facing * w * 0.026f, shoulderY + h * 0.052f, x + facing * w * 0.028f, headY - h * 0.005f, paint)
            }
        }

        if (playerAction == PlayerAction.KICK && active) {
            canvas.drawLine(hipX, hipY, x + facing * w * 0.070f, hipY + h * 0.045f, paint)
            canvas.drawLine(x + facing * w * 0.070f, hipY + h * 0.045f, x + facing * w * 0.145f, hipY + h * 0.025f, paint)
        }

        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawWaitingPlayer(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val ground = h * 0.835f
        val headY = ground - h * 0.22f

        paint.color = 0x665EE7F7
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.010f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawCircle(cx, headY, h * 0.035f, paint)
        canvas.drawLine(cx, headY + h * 0.035f, cx, ground - h * 0.075f, paint)
        canvas.drawLine(cx, headY + h * 0.09f, cx - w * 0.08f, headY + h * 0.14f, paint)
        canvas.drawLine(cx, headY + h * 0.09f, cx + w * 0.08f, headY + h * 0.14f, paint)
        canvas.drawLine(cx, ground - h * 0.075f, cx - w * 0.045f, ground, paint)
        canvas.drawLine(cx, ground - h * 0.075f, cx + w * 0.045f, ground, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawEnemies(canvas: Canvas, w: Float, h: Float) {
        for (enemy in enemies) {
            canvas.save()
            val pivotX = enemy.x * w
            val pivotY = (enemy.y - 0.09f) * h
            canvas.rotate(enemy.angle * 57.2958f, pivotX, pivotY)
            drawEnemy(canvas, enemy, w, h)
            canvas.restore()
        }
    }

    private fun drawEnemy(canvas: Canvas, enemy: Enemy, w: Float, h: Float) {
        val x = enemy.x * w
        val feetY = enemy.y * h
        val facing = if (playerX >= enemy.x) 1f else -1f
        val scale = when (enemy.style) {
            OpponentStyle.BRUTE -> 1.08f
            OpponentStyle.COUNTER -> 0.98f
            OpponentStyle.LEARNER -> 1.00f
        }
        val color = when (enemy.style) {
            OpponentStyle.BRUTE -> 0xFFF0A44B.toInt()
            OpponentStyle.COUNTER -> 0xFF9D8CF2.toInt()
            OpponentStyle.LEARNER -> 0xFFE15C67.toInt()
        }
        val bodyColor = if (enemy.alive) color else 0xFF87919B.toInt()

        val headR = h * 0.027f * scale
        val headY = feetY - h * 0.235f * scale
        val shoulderY = headY + headR * 1.85f
        val hipY = feetY - h * 0.095f * scale
        val stroke = max(4f, h * 0.0085f * scale)

        paint.color = bodyColor
        paint.style = Paint.Style.FILL
        canvas.drawCircle(x + facing * headR * 0.18f, headY, headR, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = stroke

        val neckX = x + facing * w * 0.006f
        val hipX = x - facing * w * 0.006f
        canvas.drawLine(neckX, shoulderY, hipX, hipY, paint)

        val walking = enemy.alive && (enemy.x < 0.10f || enemy.x > 0.90f || enemy.action == OpponentAction.ADVANCE)
        val stride = if (walking) sin(enemy.walkPhase) * w * 0.022f else 0f

        canvas.drawLine(hipX, hipY, x + facing * w * 0.020f + stride, hipY + h * 0.050f, paint)
        canvas.drawLine(x + facing * w * 0.020f + stride, hipY + h * 0.050f, x + facing * w * 0.040f + stride, feetY, paint)
        canvas.drawLine(hipX, hipY, x - facing * w * 0.024f - stride, hipY + h * 0.052f, paint)
        canvas.drawLine(x - facing * w * 0.024f - stride, hipY + h * 0.052f, x - facing * w * 0.037f - stride, feetY, paint)

        val attacking = enemy.action == OpponentAction.JAB ||
            enemy.action == OpponentAction.HEAVY ||
            enemy.action == OpponentAction.COUNTER

        when {
            enemy.guarding -> {
                canvas.drawLine(neckX, shoulderY, x + facing * w * 0.025f, shoulderY + h * 0.040f, paint)
                canvas.drawLine(x + facing * w * 0.025f, shoulderY + h * 0.040f, x + facing * w * 0.052f, headY + h * 0.010f, paint)
                canvas.drawLine(neckX, shoulderY, x - facing * w * 0.016f, shoulderY + h * 0.045f, paint)
                canvas.drawLine(x - facing * w * 0.016f, shoulderY + h * 0.045f, x + facing * w * 0.030f, headY - h * 0.008f, paint)
            }
            attacking && enemy.actionTime < 0.24f -> {
                val extension = if (enemy.action == OpponentAction.HEAVY) 0.100f else 0.080f
                canvas.drawLine(neckX, shoulderY, x + facing * w * 0.042f, shoulderY + h * 0.010f, paint)
                canvas.drawLine(x + facing * w * 0.042f, shoulderY + h * 0.010f, x + facing * w * extension, shoulderY + h * 0.014f, paint)
                canvas.drawLine(neckX, shoulderY, x - facing * w * 0.020f, shoulderY + h * 0.046f, paint)
                canvas.drawLine(x - facing * w * 0.020f, shoulderY + h * 0.046f, x + facing * w * 0.026f, headY + h * 0.008f, paint)
            }
            else -> {
                val armSwing = if (walking) cos(enemy.walkPhase) * w * 0.012f else 0f
                canvas.drawLine(neckX, shoulderY, x + facing * w * 0.026f - armSwing, shoulderY + h * 0.043f, paint)
                canvas.drawLine(x + facing * w * 0.026f - armSwing, shoulderY + h * 0.043f, x + facing * w * 0.052f, headY + h * 0.020f, paint)
                canvas.drawLine(neckX, shoulderY, x - facing * w * 0.022f + armSwing, shoulderY + h * 0.048f, paint)
                canvas.drawLine(x - facing * w * 0.022f + armSwing, shoulderY + h * 0.048f, x + facing * w * 0.025f, headY - h * 0.004f, paint)
            }
        }

        if (enemy.alive) {
            val barW = w * 0.055f
            val barY = headY - h * 0.038f
            paint.style = Paint.Style.FILL
            paint.color = 0x55343A42
            canvas.drawRoundRect(RectF(x - barW / 2, barY, x + barW / 2, barY + h * 0.006f), 5f, 5f, paint)
            paint.color = color
            val ratio = enemy.hp.toFloat().coerceAtLeast(0f) / enemy.maxHp.coerceAtLeast(1)
            canvas.drawRoundRect(RectF(x - barW / 2, barY, x - barW / 2 + barW * ratio, barY + h * 0.006f), 5f, 5f, paint)
        }

        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawParticles(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        for (p in particles) {
            paint.alpha = (255 * p.life.coerceIn(0f, 1f)).toInt()
            paint.color = 0xFFFFD166.toInt()
            canvas.drawCircle(p.x * w, p.y * h, p.size * min(w, h), paint)
        }
        paint.alpha = 255
    }

    private fun drawHud(canvas: Canvas, w: Float, h: Float, tracking: Boolean) {
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.RIGHT
        paint.color = 0xFFF8FAFC.toInt()
        paint.textSize = h * 0.046f
        canvas.drawText(score.toString(), w * 0.95f, h * 0.072f, paint)

        paint.textSize = h * 0.023f
        paint.color = 0xFF8EE9F2.toInt()
        canvas.drawText("WAVE $wave   COMBO x$combo", w * 0.95f, h * 0.108f, paint)

        paint.textAlign = Paint.Align.LEFT
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = h * 0.026f
        paint.color = 0xFFF8FAFC.toInt()
        canvas.drawText("STICK FIGHT", w * 0.04f, h * 0.072f, paint)

        for (i in 0 until 5) {
            paint.style = Paint.Style.FILL
            paint.color = if (i < health) 0xFFFF5F72.toInt() else 0xFF28313A.toInt()
            canvas.drawCircle(w * 0.64f + i * h * 0.031f, h * 0.115f, h * 0.010f, paint)
        }

        paint.typeface = Typeface.DEFAULT
        paint.textSize = h * 0.018f
        paint.color = 0xFF748394.toInt()
        canvas.drawText("AI READ  ${brain.profile().dominantPattern()}  •  BEST $bestCombo", w * 0.04f, h * 0.145f, paint)

        paint.textSize = h * 0.019f
        paint.color = 0xFF94A3B8.toInt()
        canvas.drawText(
            if (tracking) "MOVE • PUNCH • KICK • GUARD" else "STEP BACK UNTIL YOUR BODY IS VISIBLE",
            w * 0.04f,
            h * 0.965f,
            paint
        )
    }

    private fun drawLabels(canvas: Canvas, w: Float, h: Float) {
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        for (label in labels) {
            paint.alpha = (255 * label.life.coerceIn(0f, 1f)).toInt()
            paint.textSize = h * if (label.strong) 0.043f else 0.027f
            paint.color = if (label.strong) 0xFFFFE39A.toInt() else 0xFFF8FAFC.toInt()
            canvas.drawText(label.text, label.x * w, label.y * h, paint)
        }
        paint.alpha = 255
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = Typeface.DEFAULT
    }

    private fun applyShake(canvas: Canvas, now: Long) {
        if (now >= shakeUntilNs) return
        canvas.translate(
            (random.nextFloat() - 0.5f) * shakeStrength * 2f,
            (random.nextFloat() - 0.5f) * shakeStrength * 2f
        )
    }

    private fun vibrate(ms: Long) {
        runCatching {
            val deviceVibrator = vibrator ?: return@runCatching
            if (!deviceVibrator.hasVibrator()) return@runCatching
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                deviceVibrator.vibrate(
                    VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                deviceVibrator.vibrate(ms)
            }
        }
    }
}
