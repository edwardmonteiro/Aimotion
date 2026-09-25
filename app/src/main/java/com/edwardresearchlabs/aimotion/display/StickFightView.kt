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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class StickFightView(context: Context) : View(context) {

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
        var guarding: Boolean = false
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

        if (pose != null && pose.timestampMs != lastProcessedPoseTimestamp) {
            updateJointVelocities(pose)
            val playerGuarding = transform != null && isBlocking(pose, transform)
            brain.observe(MotionRuntime.events, playerGuarding)
            processPlayerAttacks(pose, transform)
            previousPose = pose
            lastProcessedPoseTimestamp = pose.timestampMs
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
        if (pose != null && transform != null) {
            drawPlayer(canvas, pose, transform, w, h)
        } else {
            drawWaitingPlayer(canvas, w, h)
        }
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

        if (ls == null || rs == null || lh == null || rh == null) {
            return PlayerTransform(0.5f, 0.64f, 1f)
        }

        val centerX = (
            MotionRuntime.mapX(ls.x) + MotionRuntime.mapX(rs.x) +
                MotionRuntime.mapX(lh.x) + MotionRuntime.mapX(rh.x)
            ) / 4f
        val shoulderWidth = abs(MotionRuntime.mapX(ls.x) - MotionRuntime.mapX(rs.x))
            .coerceAtLeast(0.08f)
        val hipY = (lh.y + rh.y) * 0.5f
        val scale = (0.18f / shoulderWidth).coerceIn(0.68f, 1.85f)

        return PlayerTransform(centerX.coerceIn(0.18f, 0.82f), hipY, scale)
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

    private fun processPlayerAttacks(pose: BodyPose, transform: PlayerTransform?) {
        if (transform == null) return
        val events = MotionRuntime.events
        processLimb(pose, transform, Joint.LEFT_WRIST, events.contains(MotionEvent.LEFT_PUNCH), 0.72f, false)
        processLimb(pose, transform, Joint.RIGHT_WRIST, events.contains(MotionEvent.RIGHT_PUNCH), 0.72f, false)
        processLimb(pose, transform, Joint.LEFT_ANKLE, events.contains(MotionEvent.LEFT_KICK), 0.58f, true)
        processLimb(pose, transform, Joint.RIGHT_ANKLE, events.contains(MotionEvent.RIGHT_KICK), 0.58f, true)
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
        val playerX = transform?.centerX ?: 0.5f
        val events = MotionRuntime.events
        val blocking = pose != null && transform != null && isBlocking(pose, transform)
        val dodging = events.contains(MotionEvent.CROUCH) ||
            events.contains(MotionEvent.LEAN_LEFT) ||
            events.contains(MotionEvent.LEAN_RIGHT)
        val playerAttacking = events.any {
            it == MotionEvent.LEFT_PUNCH ||
                it == MotionEvent.RIGHT_PUNCH ||
                it == MotionEvent.LEFT_KICK ||
                it == MotionEvent.RIGHT_KICK
        }

        val iterator = enemies.iterator()
        while (iterator.hasNext()) {
            val enemy = iterator.next()
            enemy.stunned = (enemy.stunned - dt).coerceAtLeast(0f)
            enemy.attackCooldown -= dt
            enemy.decisionCooldown -= dt
            enemy.actionTime += dt

            if (enemy.alive) {
                val dx = playerX - enemy.x
                val distance = abs(dx)

                if (enemy.decisionCooldown <= 0f && enemy.stunned <= 0f) {
                    enemy.action = brain.choose(
                        enemy.style,
                        OpponentFeatures(
                            distance = distance,
                            playerAttacking = playerAttacking,
                            playerGuarding = blocking,
                            enemyHealthRatio = (enemy.hp.toFloat() / enemy.maxHp.coerceAtLeast(1)).coerceIn(0f, 1f),
                            attackReady = enemy.attackCooldown <= 0f,
                            wave = wave
                        )
                    )
                    enemy.actionTime = 0f
                    enemy.guarding = enemy.action == OpponentAction.GUARD
                    enemy.decisionCooldown = when (enemy.style) {
                        OpponentStyle.BRUTE -> 0.18f
                        OpponentStyle.COUNTER -> 0.13f
                        OpponentStyle.LEARNER -> 0.15f
                    } + random.nextFloat() * 0.05f
                }

                if (enemy.stunned <= 0f) {
                    when (enemy.action) {
                        OpponentAction.ADVANCE -> {
                            val moveSpeed = (0.060f + wave * 0.0045f).coerceAtMost(0.092f)
                            enemy.vx += if (dx > 0f) moveSpeed * dt * 7f else -moveSpeed * dt * 7f
                        }

                        OpponentAction.RETREAT -> {
                            val retreat = 0.078f
                            enemy.vx += if (dx > 0f) -retreat * dt * 7f else retreat * dt * 7f
                        }

                        OpponentAction.DODGE -> {
                            if (enemy.actionTime < 0.18f) {
                                val dodge = 0.17f
                                enemy.vx += if (dx > 0f) -dodge * dt * 10f else dodge * dt * 10f
                            }
                        }

                        OpponentAction.GUARD -> {
                            enemy.vx *= 0.78f
                        }

                        OpponentAction.JAB,
                        OpponentAction.HEAVY,
                        OpponentAction.COUNTER -> {
                            if (distance > 0.115f) {
                                val closeSpeed = if (enemy.action == OpponentAction.HEAVY) 0.070f else 0.055f
                                enemy.vx += if (dx > 0f) closeSpeed * dt * 7f else -closeSpeed * dt * 7f
                            } else if (enemy.attackCooldown <= 0f) {
                                val counterBonus = enemy.action == OpponentAction.COUNTER && playerAttacking
                                val heavy = enemy.action == OpponentAction.HEAVY
                                enemy.attackCooldown = when {
                                    heavy -> 1.18f
                                    counterBonus -> 0.72f
                                    else -> 0.90f
                                }

                                when {
                                    blocking -> {
                                        score += 12
                                        combo++
                                        bestCombo = max(bestCombo, combo)
                                        enemy.vx += if (enemy.x < playerX) -0.16f else 0.16f
                                        enemy.stunned = if (heavy) 0.12f else 0.22f
                                        labels += FloatLabel("BLOCK +12", playerX, 0.42f, 0.55f, false)
                                    }

                                    dodging -> {
                                        score += 20
                                        labels += FloatLabel("DODGE +20", playerX, 0.42f, 0.55f, false)
                                    }

                                    else -> {
                                        val damage = if (heavy && random.nextFloat() < 0.34f) 2 else 1
                                        health = (health - damage).coerceAtLeast(0)
                                        combo = 0
                                        labels += FloatLabel(
                                            if (counterBonus) "COUNTER!" else if (heavy) "HEAVY HIT!" else "HIT!",
                                            playerX,
                                            0.43f,
                                            0.65f,
                                            true
                                        )
                                        shakeUntilNs = System.nanoTime() + if (heavy) 190_000_000L else 145_000_000L
                                        shakeStrength = if (heavy) 13f else 9f
                                        vibrate(if (heavy) 72 else 48)
                                        enemy.vx += if (enemy.x < playerX) -0.10f else 0.10f
                                        enemy.stunned = 0.16f
                                    }
                                }
                            }
                        }

                        OpponentAction.WAIT -> {
                            if (distance > 0.22f) {
                                enemy.vx += if (dx > 0f) 0.025f * dt * 7f else -0.025f * dt * 7f
                            }
                        }
                    }
                }

                enemy.vx *= 0.88f
                enemy.x = (enemy.x + enemy.vx * dt).coerceIn(-0.08f, 1.08f)
                enemy.angle *= 0.88f
            } else {
                enemy.corpseAge += dt
                enemy.vy += 0.82f * dt
                enemy.x += enemy.vx * dt
                enemy.y += enemy.vy * dt
                enemy.angle += enemy.angularVelocity * dt
                enemy.vx *= 0.992f
                enemy.angularVelocity *= 0.986f

                val ground = 0.835f
                if (enemy.y > ground) {
                    enemy.y = ground
                    if (abs(enemy.vy) > 0.07f) {
                        enemy.vy *= -0.27f
                        enemy.vx *= 0.82f
                        enemy.angularVelocity *= 0.72f
                    } else {
                        enemy.vy = 0f
                    }
                }

                if (enemy.corpseAge > 1.75f || enemy.x < -0.18f || enemy.x > 1.18f) {
                    iterator.remove()
                }
            }
        }

        if (health <= 0) {
            labels += FloatLabel("ROUND RESET", 0.5f, 0.35f, 1.1f, true)
            health = 5
            combo = 0
            enemies.clear()
            spawnedThisWave = 0
            defeatedThisWave = 0
            spawnClock = -0.8f
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
            x = if (side < 0) 0.06f else 0.94f,
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

    private fun drawPlayer(canvas: Canvas, pose: BodyPose, t: PlayerTransform, w: Float, h: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = max(5f, h * 0.012f)
        paint.color = 0xFFF7FAFC.toInt()

        for ((a, b) in PoseOverlay.bones) {
            val pa = mapPoint(pose, a, t) ?: continue
            val pb = mapPoint(pose, b, t) ?: continue
            canvas.drawLine(pa.first * w, pa.second * h, pb.first * w, pb.second * h, paint)
        }

        val ls = mapPoint(pose, Joint.LEFT_SHOULDER, t)
        val rs = mapPoint(pose, Joint.RIGHT_SHOULDER, t)
        val nose = mapPoint(pose, Joint.NOSE, t)
        if (ls != null && rs != null) {
            val cx = (ls.first + rs.first) * 0.5f * w
            val shoulderY = (ls.second + rs.second) * 0.5f * h
            val headY = nose?.second?.times(h) ?: shoulderY - h * 0.09f
            val radius = (abs(ls.first - rs.first) * w * 0.24f).coerceIn(h * 0.026f, h * 0.050f)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, headY, radius, paint)
        }

        paint.style = Paint.Style.FILL
        paint.color = 0xFF5EE7F7.toInt()
        for (joint in listOf(Joint.LEFT_WRIST, Joint.RIGHT_WRIST)) {
            val p = mapPoint(pose, joint, t) ?: continue
            canvas.drawCircle(p.first * w, p.second * h, h * 0.014f, paint)
        }

        if (isBlocking(pose, t)) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = h * 0.006f
            paint.color = 0x885EE7F7.toInt()
            canvas.drawArc(
                RectF(
                    (t.centerX - 0.13f) * w,
                    0.43f * h,
                    (t.centerX + 0.13f) * w,
                    0.72f * h
                ),
                205f,
                130f,
                false,
                paint
            )
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
        val bodyH = h * 0.205f
        val headY = feetY - bodyH
        val neckY = headY + bodyH * 0.24f
        val hipY = feetY - bodyH * 0.40f
        val facing = if (enemy.side < 0) 1f else -1f
        val bodyColor = when (enemy.style) {
            OpponentStyle.BRUTE -> 0xFFF4B860.toInt()
            OpponentStyle.COUNTER -> 0xFFA78BFA.toInt()
            OpponentStyle.LEARNER -> 0xFFFF6B7A.toInt()
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(4f, h * 0.009f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = if (enemy.alive) bodyColor else 0xFF9AA4AE.toInt()

        canvas.drawCircle(x, headY, h * 0.027f, paint)
        canvas.drawLine(x, neckY, x, hipY, paint)

        val shoulderY = neckY + bodyH * 0.08f
        val handReach = w * 0.052f
        canvas.drawLine(x, shoulderY, x + facing * handReach, shoulderY + h * 0.018f, paint)
        canvas.drawLine(x, shoulderY, x - facing * handReach * 0.55f, shoulderY + h * 0.045f, paint)
        canvas.drawLine(x, hipY, x + w * 0.030f, feetY, paint)
        canvas.drawLine(x, hipY, x - w * 0.030f, feetY, paint)

        if (enemy.alive) {
            val barW = w * 0.07f
            val top = headY - h * 0.045f
            paint.style = Paint.Style.FILL
            paint.color = 0x55343B44
            canvas.drawRoundRect(RectF(x - barW / 2, top, x + barW / 2, top + h * 0.008f), 6f, 6f, paint)
            paint.color = bodyColor
            val hpFraction = (enemy.hp / 3f).coerceIn(0.25f, 1f)
            canvas.drawRoundRect(
                RectF(x - barW / 2, top, x - barW / 2 + barW * hpFraction, top + h * 0.008f),
                6f,
                6f,
                paint
            )
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
