package com.edwardresearchlabs.aimotion.display

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import com.edwardresearchlabs.aimotion.motion.BodyPose
import com.edwardresearchlabs.aimotion.motion.Joint
import com.edwardresearchlabs.aimotion.motion.MotionRuntime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class SplitGameView(context: Context) : View(context) {

    private enum class Stage {
        CALIBRATE, COUNTDOWN, ROPE, SPLIT, ESCAPE, RECOVER, GAME_OVER
    }

    private data class Calibration(
        val hipY: Float,
        val ankleY: Float,
        val torsoHeight: Float,
        val ankleSpread: Float,
        val hipWidth: Float
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val random = Random(24)

    private val tone = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, 42)
    }.getOrNull()

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    private var stage = Stage.CALIBRATE
    private var calibration: Calibration? = null

    private var calCount = 0
    private var calHipY = 0f
    private var calAnkleY = 0f
    private var calTorso = 0f
    private var calAnkleSpread = 0f
    private var calHipWidth = 0f

    private var stageStartedMs = SystemClock.elapsedRealtime()
    private var lastFrameNs = System.nanoTime()

    private var ropePhase = 0.08f
    private var ropeSpeed = 0.52f
    private var jumpSeenThisCycle = false
    private var goodJumps = 0

    private var splitAirSeen = false
    private var trapSide = -1
    private var escapeStartCenter = 0.5f

    private var score = 0
    private var combo = 0
    private var lives = 3
    private var bestSplit = 0
    private var round = 1

    private var feedback = ""
    private var feedbackUntilMs = 0L
    private var flashUntilMs = 0L
    private var cameraError: String? = null
    private var visionFps = 0f
    private var visionLatencyMs = 0L

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
    }

    fun setVisionFps(value: Float) { visionFps = value }
    fun setVisionLatency(value: Long) { visionLatencyMs = value }
    fun setCameraError(value: String?) { cameraError = value; invalidate() }

    override fun onDetachedFromWindow() {
        runCatching { tone?.release() }
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        if (stage == Stage.GAME_OVER) resetAll()
        else {
            resetCalibration()
            showFeedback("RECALIBRATING", 700L)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val nowNs = System.nanoTime()
        val dt = ((nowNs - lastFrameNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastFrameNs = nowNs

        val nowMs = SystemClock.elapsedRealtime()
        val pose = MotionRuntime.freshPose(450L)

        if (stage == Stage.CALIBRATE) collectCalibration(pose)
        else updateGame(dt, nowMs, pose)

        drawCameraShade(canvas)
        drawCourt(canvas)
        drawGameObjects(canvas, nowMs)
        drawHud(canvas)
        drawPrompt(canvas, nowMs)
        drawTracking(canvas, pose)

        if (nowMs < flashUntilMs) {
            paint.style = Paint.Style.FILL
            paint.color = 0x30FF294D
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }

        postInvalidateOnAnimation()
    }

    private fun collectCalibration(pose: BodyPose?) {
        if (pose == null) return
        val sample = sampleCalibration(pose) ?: return

        calHipY += sample.hipY
        calAnkleY += sample.ankleY
        calTorso += sample.torsoHeight
        calAnkleSpread += sample.ankleSpread
        calHipWidth += sample.hipWidth
        calCount++

        if (calCount >= CALIBRATION_FRAMES) {
            calibration = Calibration(
                hipY = calHipY / calCount,
                ankleY = calAnkleY / calCount,
                torsoHeight = calTorso / calCount,
                ankleSpread = calAnkleSpread / calCount,
                hipWidth = calHipWidth / calCount
            )
            stage = Stage.COUNTDOWN
            stageStartedMs = SystemClock.elapsedRealtime()
            successHaptic()
        }
    }

    private fun sampleCalibration(pose: BodyPose): Calibration? {
        val ls = pose[Joint.LEFT_SHOULDER] ?: return null
        val rs = pose[Joint.RIGHT_SHOULDER] ?: return null
        val lh = pose[Joint.LEFT_HIP] ?: return null
        val rh = pose[Joint.RIGHT_HIP] ?: return null
        val la = pose[Joint.LEFT_ANKLE] ?: return null
        val ra = pose[Joint.RIGHT_ANKLE] ?: return null

        if (listOf(ls, rs, lh, rh, la, ra).any { it.confidence < 0.58f }) return null

        val shoulderY = (ls.y + rs.y) * 0.5f
        val hipY = (lh.y + rh.y) * 0.5f
        val ankleY = (la.y + ra.y) * 0.5f
        val torso = abs(hipY - shoulderY)
        val ankleSpread = abs(la.x - ra.x)
        val hipWidth = abs(lh.x - rh.x)

        if (torso < 0.065f || hipWidth < 0.025f) return null

        return Calibration(
            hipY = hipY,
            ankleY = ankleY,
            torsoHeight = torso,
            ankleSpread = max(ankleSpread, 0.025f),
            hipWidth = hipWidth
        )
    }

    private fun updateGame(dt: Float, nowMs: Long, pose: BodyPose?) {
        val base = calibration ?: return

        when (stage) {
            Stage.COUNTDOWN -> {
                if (nowMs - stageStartedMs >= 2500L) {
                    stage = Stage.ROPE
                    stageStartedMs = nowMs
                    ropePhase = 0.08f
                    jumpSeenThisCycle = false
                }
            }

            Stage.ROPE -> {
                val old = ropePhase
                ropePhase += dt * ropeSpeed

                val jumpWindow = ropePhase >= 0.58f || ropePhase <= 0.06f
                if (jumpWindow && pose != null && isAirborne(pose, base)) {
                    jumpSeenThisCycle = true
                }

                if (ropePhase >= 1f) {
                    ropePhase -= 1f

                    if (jumpSeenThisCycle) {
                        goodJumps++
                        combo++
                        score += 12 + min(combo, 8)
                        showFeedback("GOOD", 300L)
                        successTick()
                    } else {
                        softMiss("JUMP")
                    }

                    jumpSeenThisCycle = false

                    if (goodJumps >= JUMPS_BEFORE_SPLIT && stage != Stage.GAME_OVER) {
                        goodJumps = 0
                        stage = Stage.SPLIT
                        stageStartedMs = nowMs
                        splitAirSeen = false
                        warningHaptic()
                    }
                }

                if (old < 0.58f && ropePhase >= 0.58f) {
                    runCatching { tone?.startTone(ToneGenerator.TONE_PROP_PROMPT, 35) }
                }
            }

            Stage.SPLIT -> {
                if (pose != null) {
                    val airborne = isAirborne(pose, base)
                    if (airborne) splitAirSeen = true

                    val elapsed = nowMs - stageStartedMs
                    if (!airborne && elapsed > 180L && isSplitLanding(pose, base)) {
                        val value = splitScore(pose, base, elapsed)
                        bestSplit = max(bestSplit, value)
                        score += 25 + value / 4
                        combo++
                        showFeedback("SPLIT  $value", 520L)
                        successHaptic()

                        trapSide = if (random.nextBoolean()) -1 else 1
                        escapeStartCenter = bodyCenterX(pose)
                        stage = Stage.ESCAPE
                        stageStartedMs = nowMs
                    }
                }

                if (nowMs - stageStartedMs > 1800L) {
                    hardMiss("SPLIT")
                    if (stage != Stage.GAME_OVER) {
                        trapSide = if (random.nextBoolean()) -1 else 1
                        escapeStartCenter = pose?.let { bodyCenterX(it) } ?: 0.5f
                        stage = Stage.ESCAPE
                        stageStartedMs = nowMs
                    }
                }
            }

            Stage.ESCAPE -> {
                if (pose != null) {
                    val center = bodyCenterX(pose)
                    val delta = center - escapeStartCenter
                    val movedEnough = if (trapSide < 0) delta > 0.115f else delta < -0.115f

                    if (movedEnough) {
                        score += 40 + min(combo * 2, 28)
                        combo++
                        showFeedback("SAFE", 420L)
                        successHaptic()
                        stage = Stage.RECOVER
                        stageStartedMs = nowMs
                    }
                }

                if (nowMs - stageStartedMs > 2100L) {
                    hardMiss(if (trapSide < 0) "GO RIGHT" else "GO LEFT")
                    if (stage != Stage.GAME_OVER) {
                        stage = Stage.RECOVER
                        stageStartedMs = nowMs
                    }
                }
            }

            Stage.RECOVER -> {
                if (pose != null) {
                    val center = bodyCenterX(pose)
                    if (abs(center - 0.5f) < 0.085f) {
                        score += 18
                        combo++
                        showFeedback("CENTER", 350L)
                        round++
                        ropeSpeed = (ropeSpeed + 0.025f).coerceAtMost(0.72f)

                        stage = Stage.ROPE
                        stageStartedMs = nowMs
                        ropePhase = 0.08f
                        jumpSeenThisCycle = false
                    }
                }

                if (nowMs - stageStartedMs > 2400L) {
                    combo = 0
                    stage = Stage.ROPE
                    stageStartedMs = nowMs
                    ropePhase = 0.08f
                    jumpSeenThisCycle = false
                }
            }

            Stage.GAME_OVER, Stage.CALIBRATE -> Unit
        }
    }

    private fun isAirborne(pose: BodyPose, base: Calibration): Boolean {
        val lh = pose[Joint.LEFT_HIP] ?: return false
        val rh = pose[Joint.RIGHT_HIP] ?: return false
        val la = pose[Joint.LEFT_ANKLE] ?: return false
        val ra = pose[Joint.RIGHT_ANKLE] ?: return false

        if (listOf(lh, rh, la, ra).any { it.confidence < 0.45f }) return false

        val hipY = (lh.y + rh.y) * 0.5f
        val ankleY = (la.y + ra.y) * 0.5f
        val hipLift = base.hipY - hipY
        val ankleLift = base.ankleY - ankleY

        return hipLift > base.torsoHeight * 0.040f ||
            ankleLift > base.torsoHeight * 0.065f
    }

    private fun isSplitLanding(pose: BodyPose, base: Calibration): Boolean {
        val la = pose[Joint.LEFT_ANKLE] ?: return false
        val ra = pose[Joint.RIGHT_ANKLE] ?: return false
        val lh = pose[Joint.LEFT_HIP] ?: return false
        val rh = pose[Joint.RIGHT_HIP] ?: return false

        if (listOf(la, ra, lh, rh).any { it.confidence < 0.50f }) return false

        val ankleSpread = abs(la.x - ra.x)
        val hipWidth = max(abs(lh.x - rh.x), 0.025f)
        val target = max(base.ankleSpread * 1.10f, hipWidth * 1.12f)

        return ankleSpread >= target &&
            (splitAirSeen || SystemClock.elapsedRealtime() - stageStartedMs > 420L)
    }

    private fun splitScore(pose: BodyPose, base: Calibration, elapsed: Long): Int {
        val la = pose[Joint.LEFT_ANKLE] ?: return 65
        val ra = pose[Joint.RIGHT_ANKLE] ?: return 65
        val lh = pose[Joint.LEFT_HIP] ?: return 65
        val rh = pose[Joint.RIGHT_HIP] ?: return 65
        val lk = pose[Joint.LEFT_KNEE] ?: return 65
        val rk = pose[Joint.RIGHT_KNEE] ?: return 65

        val ankleSpread = abs(la.x - ra.x)
        val hipWidth = max(abs(lh.x - rh.x), 0.025f)
        val widthRatio = ankleSpread / hipWidth

        val hipY = (lh.y + rh.y) * 0.5f
        val kneeY = (lk.y + rk.y) * 0.5f
        val compression = abs(kneeY - hipY) / max(base.torsoHeight, 0.05f)

        val width = ((widthRatio - 1.05f) / 0.70f * 35f).coerceIn(15f, 35f)
        val low = ((1.95f - compression) / 0.90f * 25f).coerceIn(10f, 25f)
        val timing = when {
            elapsed <= 650L -> 40f
            elapsed <= 950L -> 34f
            elapsed <= 1300L -> 27f
            else -> 20f
        }

        return (width + low + timing).toInt().coerceIn(60, 100)
    }

    private fun bodyCenterX(pose: BodyPose): Float {
        val pts = listOfNotNull(
            pose[Joint.LEFT_HIP],
            pose[Joint.RIGHT_HIP],
            pose[Joint.LEFT_SHOULDER],
            pose[Joint.RIGHT_SHOULDER]
        ).filter { it.confidence >= 0.45f }

        if (pts.isEmpty()) return 0.5f
        val raw = pts.map { it.x }.average().toFloat()
        return MotionRuntime.mapX(raw)
    }

    private fun softMiss(label: String) {
        combo = 0
        showFeedback(label, 420L)
        flashUntilMs = SystemClock.elapsedRealtime() + 120L
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_NACK, 45) }
        vibrate(20L)
    }

    private fun hardMiss(label: String) {
        combo = 0
        lives--
        showFeedback(label, 550L)
        flashUntilMs = SystemClock.elapsedRealtime() + 180L
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_NACK, 75) }
        vibrate(42L)

        if (lives <= 0) {
            stage = Stage.GAME_OVER
            stageStartedMs = SystemClock.elapsedRealtime()
        }
    }

    private fun successTick() {
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 24) }
        vibrate(8L)
    }

    private fun successHaptic() {
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 55) }
        vibrate(24L)
    }

    private fun warningHaptic() {
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_PROMPT, 70) }
        vibrate(18L)
    }

    private fun vibrate(ms: Long) {
        runCatching {
            val device = vibrator ?: return@runCatching
            if (!device.hasVibrator()) return@runCatching
            if (Build.VERSION.SDK_INT >= 26) {
                device.vibrate(
                    VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                device.vibrate(ms)
            }
        }
    }

    private fun showFeedback(text: String, durationMs: Long) {
        feedback = text
        feedbackUntilMs = SystemClock.elapsedRealtime() + durationMs
    }

    private fun drawCameraShade(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0x5A000000, 0x08000000, 0x50000000),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun drawCourt(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val horizon = h * 0.72f
        val bottom = h * 0.98f

        paint.style = Paint.Style.FILL
        paint.color = 0x24000000
        path.reset()
        path.moveTo(w * 0.18f, horizon)
        path.lineTo(w * 0.82f, horizon)
        path.lineTo(w * 0.98f, bottom)
        path.lineTo(w * 0.02f, bottom)
        path.close()
        canvas.drawPath(path, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.0024f
        paint.color = 0x50FFFFFF
        canvas.drawLine(w * 0.5f, horizon, w * 0.5f, bottom, paint)

        for (i in 1..3) {
            val t = i / 3f
            val y = horizon + (bottom - horizon) * t * t
            canvas.drawLine(
                w * (0.18f - 0.16f * t),
                y,
                w * (0.82f + 0.16f * t),
                y,
                paint
            )
        }
    }

    private fun drawGameObjects(canvas: Canvas, nowMs: Long) {
        when (stage) {
            Stage.ROPE -> drawRope(canvas)
            Stage.SPLIT -> drawSplitTarget(canvas)
            Stage.ESCAPE -> drawEscapeZones(canvas, nowMs)
            Stage.RECOVER -> drawCenterTarget(canvas)
            else -> Unit
        }
    }

    private fun drawRope(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        val angle = ropePhase * (2.0 * PI)
        val swing = ((1.0 - cos(angle)) * 0.5).toFloat()
        val topY = h * 0.15f
        val floorY = h * 0.89f
        val ropeY = topY + swing * (floorY - topY)
        val danger = ropePhase >= 0.58f || ropePhase <= 0.06f

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = h * if (danger) 0.011f else 0.007f
        paint.color = if (danger) Color.WHITE else 0xA8E6F9FF.toInt()
        paint.maskFilter = BlurMaskFilter(h * 0.005f, BlurMaskFilter.Blur.NORMAL)

        path.reset()
        path.moveTo(w * 0.08f, ropeY)
        path.cubicTo(
            w * 0.30f, ropeY + h * 0.04f,
            w * 0.70f, ropeY + h * 0.04f,
            w * 0.92f, ropeY
        )
        canvas.drawPath(path, paint)
        paint.maskFilter = null

        paint.style = Paint.Style.FILL
        paint.color = 0xE8FFFFFF.toInt()
        canvas.drawRoundRect(
            RectF(w * 0.045f, ropeY - h * 0.025f, w * 0.075f, ropeY + h * 0.025f),
            h * 0.01f, h * 0.01f, paint
        )
        canvas.drawRoundRect(
            RectF(w * 0.925f, ropeY - h * 0.025f, w * 0.955f, ropeY + h * 0.025f),
            h * 0.01f, h * 0.01f, paint
        )

        paint.color = if (danger) 0xD8FFFFFF.toInt() else 0x35FFFFFF
        canvas.drawRoundRect(
            RectF(w * 0.33f, h * 0.925f, w * 0.67f, h * 0.937f),
            h * 0.006f, h * 0.006f, paint
        )

        if (danger) {
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = h * 0.040f
            paint.color = Color.WHITE
            canvas.drawText("JUMP", w * 0.5f, h * 0.82f, paint)
        }
    }

    private fun drawSplitTarget(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val base = calibration ?: return
        val targetHalf = max(base.ankleSpread * 0.70f, base.hipWidth * 0.78f)
            .coerceIn(0.055f, 0.12f)

        val leftX = (0.5f - targetHalf) * w
        val rightX = (0.5f + targetHalf) * w
        val y = h * 0.90f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.006f
        paint.color = 0xF2FFFFFF.toInt()

        val l = RectF(leftX-w*0.030f, y-h*0.018f, leftX+w*0.030f, y+h*0.018f)
        val r = RectF(rightX-w*0.030f, y-h*0.018f, rightX+w*0.030f, y+h*0.018f)
        canvas.drawOval(l, paint)
        canvas.drawOval(r, paint)

        paint.style = Paint.Style.FILL
        paint.color = 0x26FFFFFF
        canvas.drawOval(l, paint)
        canvas.drawOval(r, paint)
    }

    private fun drawEscapeZones(canvas: Canvas, nowMs: Long) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pulse = 0.55f + 0.45f * sin((nowMs-stageStartedMs)/95.0).toFloat()
        val trapLeft = trapSide < 0

        val left = RectF(0f, h*0.72f, w*0.50f, h)
        val right = RectF(w*0.50f, h*0.72f, w, h)

        paint.style = Paint.Style.FILL
        paint.color = if (trapLeft) ((120 + pulse*70).toInt() shl 24) or 0x00FF3158 else 0x5032FF9A
        canvas.drawRect(left, paint)
        paint.color = if (!trapLeft) ((120 + pulse*70).toInt() shl 24) or 0x00FF3158 else 0x5032FF9A
        canvas.drawRect(right, paint)

        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = h * 0.030f
        paint.color = Color.WHITE
        canvas.drawText(if (trapLeft) "TRAP" else "SAFE", w*0.25f, h*0.87f, paint)
        canvas.drawText(if (!trapLeft) "TRAP" else "SAFE", w*0.75f, h*0.87f, paint)

        paint.textSize = h * 0.075f
        canvas.drawText(if (trapLeft) "→" else "←", w*0.5f, h*0.80f, paint)
    }

    private fun drawCenterTarget(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.5f
        val cy = h * 0.88f
        val rect = RectF(cx-w*0.075f, cy-h*0.030f, cx+w*0.075f, cy+h*0.030f)

        paint.style = Paint.Style.FILL
        paint.color = 0x24FFFFFF
        canvas.drawOval(rect, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.006f
        paint.color = 0xE8FFFFFF.toInt()
        canvas.drawOval(rect, paint)
    }

    private fun drawHud(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = h * 0.040f
        paint.color = Color.WHITE
        canvas.drawText("SPLIT", w*0.045f, h*0.075f, paint)

        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = h * 0.018f
        paint.color = 0xAFFFFFFF.toInt()
        canvas.drawText("ROUND $round", w*0.045f, h*0.108f, paint)

        paint.textAlign = Paint.Align.RIGHT
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = h * 0.038f
        paint.color = Color.WHITE
        canvas.drawText(score.toString(), w*0.95f, h*0.075f, paint)

        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = h * 0.018f
        paint.color = 0xAFFFFFFF.toInt()
        canvas.drawText("COMBO $combo", w*0.95f, h*0.108f, paint)

        paint.textAlign = Paint.Align.LEFT
        for (i in 0 until 3) {
            paint.color = if (i < lives) Color.WHITE else 0x38FFFFFF
            canvas.drawCircle(w*0.047f + i*h*0.030f, h*0.145f, h*0.007f, paint)
        }

        if (bestSplit > 0) {
            paint.textAlign = Paint.Align.RIGHT
            paint.textSize = h * 0.016f
            paint.color = 0xAFFFFFFF.toInt()
            canvas.drawText("BEST SPLIT $bestSplit", w*0.95f, h*0.143f, paint)
        }
    }

    private fun drawPrompt(canvas: Canvas, nowMs: Long) {
        val w = width.toFloat()
        val h = height.toFloat()
        val transient = nowMs < feedbackUntilMs && feedback.isNotBlank()

        val primary = when {
            transient -> feedback
            stage == Stage.CALIBRATE -> "STEP BACK"
            stage == Stage.COUNTDOWN -> countdownText(nowMs)
            stage == Stage.ROPE -> "FOLLOW THE ROPE"
            stage == Stage.SPLIT -> "SPLIT NOW"
            stage == Stage.ESCAPE -> if (trapSide < 0) "MOVE RIGHT" else "MOVE LEFT"
            stage == Stage.RECOVER -> "CENTER"
            stage == Stage.GAME_OVER -> "GAME OVER"
            else -> ""
        }

        val secondary = when (stage) {
            Stage.CALIBRATE -> "FULL BODY · FEET VISIBLE"
            Stage.COUNTDOWN -> "JUMP WHEN THE ROPE REACHES YOUR FEET"
            Stage.ROPE -> "${goodJumps + 1} / $JUMPS_BEFORE_SPLIT"
            Stage.SPLIT -> "SMALL HOP · LAND WIDE · STAY LOW"
            Stage.ESCAPE -> "PUSH OFF · DO NOT CROSS YOUR FEET"
            Stage.RECOVER -> "RETURN TO THE WHITE OVAL"
            Stage.GAME_OVER -> "TAP TO PLAY AGAIN"
        }

        if (primary.isNotBlank()) {
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = h * if (stage == Stage.GAME_OVER) 0.070f else 0.054f
            canvas.drawText(primary, w*0.5f, h*0.28f, paint)
        }

        if (!transient && secondary.isNotBlank()) {
            paint.typeface = android.graphics.Typeface.DEFAULT
            paint.textSize = h * 0.019f
            paint.color = 0xC8FFFFFF.toInt()
            canvas.drawText(secondary, w*0.5f, h*0.325f, paint)
        }

        if (stage == Stage.CALIBRATE) {
            val progress = (calCount / CALIBRATION_FRAMES.toFloat()).coerceIn(0f, 1f)
            val barW = w * 0.20f
            val y = h * 0.37f

            paint.style = Paint.Style.FILL
            paint.color = 0x30FFFFFF
            canvas.drawRoundRect(
                RectF(w*0.5f-barW*0.5f, y, w*0.5f+barW*0.5f, y+h*0.007f),
                h*0.004f, h*0.004f, paint
            )
            paint.color = Color.WHITE
            canvas.drawRoundRect(
                RectF(w*0.5f-barW*0.5f, y, w*0.5f-barW*0.5f+barW*progress, y+h*0.007f),
                h*0.004f, h*0.004f, paint
            )
        }
    }

    private fun countdownText(nowMs: Long): String {
        val elapsed = nowMs - stageStartedMs
        return when {
            elapsed < 800L -> "3"
            elapsed < 1600L -> "2"
            elapsed < 2400L -> "1"
            else -> "GO"
        }
    }

    private fun drawTracking(canvas: Canvas, pose: BodyPose?) {
        val w = width.toFloat()
        val h = height.toFloat()

        val error = cameraError
        if (error != null) {
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = h * 0.025f
            paint.color = 0xFFFF6B82.toInt()
            canvas.drawText(error, w*0.5f, h*0.62f, paint)
            return
        }

        if (pose == null) {
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = h * 0.024f
            paint.color = 0xD8FFFFFF.toInt()
            canvas.drawText("FINDING BODY…", w*0.5f, h*0.62f, paint)
            return
        }

        val center = bodyCenterX(pose)
        paint.style = Paint.Style.FILL
        paint.color = 0xD8FFFFFF.toInt()
        canvas.drawCircle(center*w, h*0.91f, h*0.008f, paint)

        paint.textAlign = Paint.Align.RIGHT
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = h * 0.013f
        paint.color = 0x60FFFFFF
        canvas.drawText(
            "${"%.0f".format(visionFps)} FPS · $visionLatencyMs ms",
            w*0.96f,
            h*0.965f,
            paint
        )
    }

    private fun resetAll() {
        score = 0
        combo = 0
        lives = 3
        bestSplit = 0
        round = 1
        ropeSpeed = 0.52f
        ropePhase = 0.08f
        goodJumps = 0
        jumpSeenThisCycle = false
        resetCalibration()
    }

    private fun resetCalibration() {
        calibration = null
        calCount = 0
        calHipY = 0f
        calAnkleY = 0f
        calTorso = 0f
        calAnkleSpread = 0f
        calHipWidth = 0f
        stage = Stage.CALIBRATE
        stageStartedMs = SystemClock.elapsedRealtime()
        splitAirSeen = false
    }

    companion object {
        private const val CALIBRATION_FRAMES = 28
        private const val JUMPS_BEFORE_SPLIT = 3
    }
}
