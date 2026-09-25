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
import kotlin.random.Random

class SplitGameView(context: Context) : View(context) {

    private enum class Stage { CALIBRATE, ROPE, SPLIT, MOVE, RECOVER, GAME_OVER }

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
        ToneGenerator(AudioManager.STREAM_MUSIC, 45)
    }.getOrNull()

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var stage = Stage.CALIBRATE
    private var calibration: Calibration? = null

    private var calCount = 0
    private var calHipY = 0f
    private var calAnkleY = 0f
    private var calTorso = 0f
    private var calAnkleSpread = 0f
    private var calHipWidth = 0f

    private var lastFrameNs = System.nanoTime()
    private var stageStartedMs = SystemClock.elapsedRealtime()

    private var ropePhase = 0.18f
    private var ropeCrossings = 0
    private var ropeSpeed = 0.92f

    private var trapSide = -1
    private var splitAirSeen = false

    private var score = 0
    private var combo = 0
    private var lives = 3
    private var bestSplit = 0

    private var feedbackText = ""
    private var feedbackUntilMs = 0L
    private var flashMissUntilMs = 0L

    private var visionFps = 0f
    private var visionLatencyMs = 0L
    private var cameraError: String? = null

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
        if (stage == Stage.GAME_OVER) {
            resetAll()
        } else {
            resetCalibration()
            showFeedback("RECALIBRATING", 800L)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val nowNs = System.nanoTime()
        val dt = ((nowNs - lastFrameNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastFrameNs = nowNs

        val nowMs = SystemClock.elapsedRealtime()
        val pose = MotionRuntime.freshPose(420L)

        if (stage == Stage.CALIBRATE) collectCalibration(pose)
        else updateGame(dt, nowMs, pose)

        drawCameraPolish(canvas)
        drawFloor(canvas, nowMs)
        if (stage == Stage.ROPE) drawRope(canvas)
        drawPlayerGuides(canvas, pose)
        drawHud(canvas)
        drawStagePrompt(canvas, nowMs)
        drawVisionStatus(canvas, pose)

        if (nowMs < flashMissUntilMs) {
            paint.style = Paint.Style.FILL
            paint.color = 0x22FF3B5C
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }

        postInvalidateOnAnimation()
    }

    private fun collectCalibration(pose: BodyPose?) {
        if (pose == null) return
        val sample = calibrationSample(pose) ?: return

        calHipY += sample.hipY
        calAnkleY += sample.ankleY
        calTorso += sample.torsoHeight
        calAnkleSpread += sample.ankleSpread
        calHipWidth += sample.hipWidth
        calCount++

        if (calCount >= CALIBRATION_FRAMES) {
            calibration = Calibration(
                calHipY / calCount,
                calAnkleY / calCount,
                calTorso / calCount,
                calAnkleSpread / calCount,
                calHipWidth / calCount
            )
            stage = Stage.ROPE
            stageStartedMs = SystemClock.elapsedRealtime()
            showFeedback("READY", 700L)
            successHaptic()
        }
    }

    private fun calibrationSample(pose: BodyPose): Calibration? {
        val ls = pose[Joint.LEFT_SHOULDER] ?: return null
        val rs = pose[Joint.RIGHT_SHOULDER] ?: return null
        val lh = pose[Joint.LEFT_HIP] ?: return null
        val rh = pose[Joint.RIGHT_HIP] ?: return null
        val la = pose[Joint.LEFT_ANKLE] ?: return null
        val ra = pose[Joint.RIGHT_ANKLE] ?: return null

        val required = listOf(ls, rs, lh, rh, la, ra)
        if (required.any { it.confidence < 0.62f }) return null

        val shoulderY = (ls.y + rs.y) * 0.5f
        val hipY = (lh.y + rh.y) * 0.5f
        val ankleY = (la.y + ra.y) * 0.5f
        val torso = abs(hipY - shoulderY)
        val ankleSpread = abs(la.x - ra.x)
        val hipWidth = abs(lh.x - rh.x)

        if (torso < 0.07f || ankleSpread < 0.015f || hipWidth < 0.025f) return null

        return Calibration(hipY, ankleY, torso, ankleSpread, hipWidth)
    }

    private fun updateGame(dt: Float, nowMs: Long, pose: BodyPose?) {
        val base = calibration ?: return

        when (stage) {
            Stage.ROPE -> {
                val previous = ropePhase
                ropePhase += dt * ropeSpeed
                if (ropePhase >= 1f) ropePhase -= 1f

                val crossedFloor = previous > 0.82f && ropePhase < 0.18f
                if (crossedFloor) {
                    if (pose != null && isAirborne(pose, base)) {
                        score += 10 + min(combo, 10)
                        combo++
                        ropeCrossings++
                        successTick()
                    } else {
                        miss("JUMP")
                    }

                    if (ropeCrossings >= 4 && stage != Stage.GAME_OVER) {
                        ropeCrossings = 0
                        beginSplit(nowMs)
                    }
                }
            }

            Stage.SPLIT -> {
                if (pose != null) {
                    val airborne = isAirborne(pose, base)
                    if (airborne) splitAirSeen = true

                    if (!airborne && isWideReady(pose, base)) {
                        val splitScore = calculateSplitScore(pose, base, nowMs)
                        bestSplit = max(bestSplit, splitScore)
                        score += 20 + splitScore / 5
                        combo++
                        showFeedback("SPLIT  $splitScore", 620L)
                        successHaptic()
                        stage = Stage.MOVE
                        stageStartedMs = nowMs
                    }
                }

                if (nowMs - stageStartedMs > 1250L) {
                    miss("SPLIT")
                    if (stage != Stage.GAME_OVER) {
                        stage = Stage.MOVE
                        stageStartedMs = nowMs
                    }
                }
            }

            Stage.MOVE -> {
                if (pose != null) {
                    val center = bodyCenterX(pose)
                    val safeReached = if (trapSide < 0) center > 0.61f else center < 0.39f

                    if (safeReached) {
                        score += 35 + min(combo * 2, 30)
                        combo++
                        showFeedback(if (trapSide < 0) "RIGHT  ✓" else "LEFT  ✓", 500L)
                        successHaptic()
                        stage = Stage.RECOVER
                        stageStartedMs = nowMs
                    }
                }

                if (nowMs - stageStartedMs > 1450L) {
                    miss(if (trapSide < 0) "MOVE RIGHT" else "MOVE LEFT")
                    if (stage != Stage.GAME_OVER) {
                        stage = Stage.RECOVER
                        stageStartedMs = nowMs
                    }
                }
            }

            Stage.RECOVER -> {
                if (pose != null) {
                    val center = bodyCenterX(pose)
                    if (abs(center - 0.5f) < 0.095f) {
                        score += 15
                        combo++
                        showFeedback("RECOVER  ✓", 450L)
                        stage = Stage.ROPE
                        stageStartedMs = nowMs
                        ropeSpeed = (ropeSpeed + 0.018f).coerceAtMost(1.18f)
                    }
                }

                if (nowMs - stageStartedMs > 1600L) {
                    combo = 0
                    showFeedback("CENTER", 450L)
                    stage = Stage.ROPE
                    stageStartedMs = nowMs
                }
            }

            Stage.GAME_OVER, Stage.CALIBRATE -> Unit
        }
    }

    private fun beginSplit(nowMs: Long) {
        trapSide = if (random.nextBoolean()) -1 else 1
        splitAirSeen = false
        stage = Stage.SPLIT
        stageStartedMs = nowMs
        showFeedback("SPLIT", 520L)
        warningHaptic()
    }

    private fun isAirborne(pose: BodyPose, base: Calibration): Boolean {
        val lh = pose[Joint.LEFT_HIP] ?: return false
        val rh = pose[Joint.RIGHT_HIP] ?: return false
        val la = pose[Joint.LEFT_ANKLE] ?: return false
        val ra = pose[Joint.RIGHT_ANKLE] ?: return false

        if (listOf(lh, rh, la, ra).any { it.confidence < 0.48f }) return false

        val hipY = (lh.y + rh.y) * 0.5f
        val ankleY = (la.y + ra.y) * 0.5f
        val hipLift = base.hipY - hipY
        val ankleLift = base.ankleY - ankleY

        return hipLift > base.torsoHeight * 0.065f ||
            ankleLift > base.torsoHeight * 0.10f
    }

    private fun isWideReady(pose: BodyPose, base: Calibration): Boolean {
        val la = pose[Joint.LEFT_ANKLE] ?: return false
        val ra = pose[Joint.RIGHT_ANKLE] ?: return false
        val lh = pose[Joint.LEFT_HIP] ?: return false
        val rh = pose[Joint.RIGHT_HIP] ?: return false

        if (listOf(la, ra, lh, rh).any { it.confidence < 0.52f }) return false

        val ankleSpread = abs(la.x - ra.x)
        val hipWidth = abs(lh.x - rh.x)
        val wideEnough = ankleSpread > max(base.ankleSpread * 1.16f, hipWidth * 1.22f)

        return wideEnough &&
            (splitAirSeen || SystemClock.elapsedRealtime() - stageStartedMs > 360L)
    }

    private fun calculateSplitScore(pose: BodyPose, base: Calibration, nowMs: Long): Int {
        val la = pose[Joint.LEFT_ANKLE] ?: return 60
        val ra = pose[Joint.RIGHT_ANKLE] ?: return 60
        val lh = pose[Joint.LEFT_HIP] ?: return 60
        val rh = pose[Joint.RIGHT_HIP] ?: return 60
        val lk = pose[Joint.LEFT_KNEE] ?: return 60
        val rk = pose[Joint.RIGHT_KNEE] ?: return 60

        val spread = abs(la.x - ra.x)
        val hipWidth = max(0.02f, abs(lh.x - rh.x))
        val widthRatio = spread / hipWidth

        val hipY = (lh.y + rh.y) * 0.5f
        val kneeY = (lk.y + rk.y) * 0.5f
        val legCompression = abs(kneeY - hipY) / max(base.torsoHeight, 0.05f)

        val widthScore = (((widthRatio - 1.0f) / 0.8f) * 35f).coerceIn(12f, 35f)
        val compressionScore = ((1.9f - legCompression) / 0.8f * 25f).coerceIn(8f, 25f)

        val elapsed = nowMs - stageStartedMs
        val timingScore = when {
            elapsed <= 620L -> 40f
            elapsed <= 850L -> 33f
            elapsed <= 1050L -> 25f
            else -> 18f
        }

        return (widthScore + compressionScore + timingScore).toInt().coerceIn(55, 100)
    }

    private fun bodyCenterX(pose: BodyPose): Float {
        val points = listOfNotNull(
            pose[Joint.LEFT_HIP],
            pose[Joint.RIGHT_HIP],
            pose[Joint.LEFT_SHOULDER],
            pose[Joint.RIGHT_SHOULDER]
        ).filter { it.confidence >= 0.48f }

        if (points.isEmpty()) return 0.5f
        val raw = points.map { it.x }.average().toFloat()
        return MotionRuntime.mapX(raw)
    }

    private fun miss(label: String) {
        combo = 0
        lives--
        flashMissUntilMs = SystemClock.elapsedRealtime() + 180L
        showFeedback(label, 650L)
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_NACK, 90) }
        vibrate(48L)

        if (lives <= 0) {
            stage = Stage.GAME_OVER
            stageStartedMs = SystemClock.elapsedRealtime()
        }
    }

    private fun successTick() {
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 28) }
        vibrate(10L)
    }

    private fun successHaptic() {
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 60) }
        vibrate(28L)
    }

    private fun warningHaptic() {
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_PROMPT, 50) }
        vibrate(22L)
    }

    private fun vibrate(ms: Long) {
        runCatching {
            val device = vibrator ?: return@runCatching
            if (!device.hasVibrator()) return@runCatching
            if (Build.VERSION.SDK_INT >= 26) {
                device.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                device.vibrate(ms)
            }
        }
    }

    private fun showFeedback(text: String, durationMs: Long) {
        feedbackText = text
        feedbackUntilMs = SystemClock.elapsedRealtime() + durationMs
    }

    private fun drawCameraPolish(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0x8A000000.toInt(), 0x18000000, 0x42000000),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.color = 0x24000000
        canvas.drawRect(0f, 0f, w * 0.06f, h, paint)
        canvas.drawRect(w * 0.94f, 0f, w, h, paint)
    }

    private fun drawFloor(canvas: Canvas, nowMs: Long) {
        val w = width.toFloat()
        val h = height.toFloat()
        val horizon = h * 0.71f
        val bottom = h * 0.97f

        paint.style = Paint.Style.FILL
        paint.color = 0x28000000
        path.reset()
        path.moveTo(w * 0.17f, horizon)
        path.lineTo(w * 0.83f, horizon)
        path.lineTo(w * 0.98f, bottom)
        path.lineTo(w * 0.02f, bottom)
        path.close()
        canvas.drawPath(path, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, h * 0.0022f)
        paint.color = 0x45FFFFFF
        canvas.drawLine(w * 0.50f, horizon, w * 0.50f, bottom, paint)

        for (i in 1..3) {
            val t = i / 3f
            val y = horizon + (bottom - horizon) * t * t
            canvas.drawLine(w * (0.17f - 0.15f * t), y, w * (0.83f + 0.15f * t), y, paint)
        }

        if (stage == Stage.SPLIT || stage == Stage.MOVE) {
            val pulse = 0.55f + 0.45f * cos((nowMs - stageStartedMs) / 90.0).toFloat()
            drawTrap(canvas, trapSide, pulse)
        }

        if (stage == Stage.RECOVER) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = h * 0.006f
            paint.color = 0xCCFFFFFF.toInt()
            val cx = w * 0.5f
            val cy = h * 0.86f
            canvas.drawOval(
                RectF(cx - w * 0.065f, cy - h * 0.025f, cx + w * 0.065f, cy + h * 0.025f),
                paint
            )
        }
    }

    private fun drawTrap(canvas: Canvas, side: Int, pulse: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val horizon = h * 0.72f
        val bottom = h * 0.98f

        val leftHalf = side < 0
        val x0Top = if (leftHalf) w * 0.18f else w * 0.50f
        val x1Top = if (leftHalf) w * 0.50f else w * 0.82f
        val x0Bottom = if (leftHalf) w * 0.02f else w * 0.50f
        val x1Bottom = if (leftHalf) w * 0.50f else w * 0.98f

        paint.style = Paint.Style.FILL
        val alpha = (110 + pulse * 75).toInt().coerceIn(0, 255)
        paint.color = (alpha shl 24) or 0x00FF3158

        path.reset()
        path.moveTo(x0Top, horizon)
        path.lineTo(x1Top, horizon)
        path.lineTo(x1Bottom, bottom)
        path.lineTo(x0Bottom, bottom)
        path.close()
        canvas.drawPath(path, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.005f
        paint.color = 0xE6FF6784.toInt()
        canvas.drawPath(path, paint)

        paint.style = Paint.Style.FILL
        paint.color = 0xD6FFFFFF.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = h * 0.028f
        val tx = if (leftHalf) w * 0.28f else w * 0.72f
        canvas.drawText("TRAP", tx, h * 0.865f, paint)
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawRope(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        val theta = ropePhase * (2.0 * PI)
        val vertical = ((1.0 - cos(theta)) * 0.5).toFloat()
        val floorY = h * 0.89f
        val ropeY = floorY - vertical * h * 0.71f
        val floorProximity = 1f - vertical

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = h * 0.008f
        paint.color = if (floorProximity > 0.72f) 0xF2F8FAFC.toInt()
        else 0x90D7F7FF.toInt()

        paint.maskFilter = BlurMaskFilter(h * 0.006f, BlurMaskFilter.Blur.NORMAL)

        path.reset()
        path.moveTo(w * 0.08f, ropeY)
        path.cubicTo(
            w * 0.30f, ropeY + h * 0.035f,
            w * 0.70f, ropeY + h * 0.035f,
            w * 0.92f, ropeY
        )
        canvas.drawPath(path, paint)

        paint.maskFilter = null
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawPlayerGuides(canvas: Canvas, pose: BodyPose?) {
        if (pose == null || calibration == null) return

        val w = width.toFloat()
        val h = height.toFloat()
        val center = bodyCenterX(pose)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.003f
        paint.color = 0x70FFFFFF
        canvas.drawCircle(center * w, h * 0.91f, h * 0.018f, paint)

        if (stage == Stage.SPLIT) {
            val base = calibration ?: return
            val requiredHalf = max(base.ankleSpread * 0.62f, base.hipWidth * 0.72f)
            val left = (center - requiredHalf).coerceIn(0.04f, 0.96f) * w
            val right = (center + requiredHalf).coerceIn(0.04f, 0.96f) * w

            paint.color = 0xAFFFFFFF.toInt()
            paint.strokeWidth = h * 0.004f
            canvas.drawLine(left, h * 0.925f, left, h * 0.955f, paint)
            canvas.drawLine(right, h * 0.925f, right, h * 0.955f, paint)
        }
    }

    private fun drawHud(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        paint.textAlign = Paint.Align.LEFT
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = h * 0.046f
        canvas.drawText("SPLIT", w * 0.045f, h * 0.085f, paint)

        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = h * 0.020f
        paint.color = 0xB8FFFFFF.toInt()
        canvas.drawText("JUMP  ·  REACT  ·  RECOVER", w * 0.045f, h * 0.122f, paint)

        paint.textAlign = Paint.Align.RIGHT
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = h * 0.045f
        paint.color = Color.WHITE
        canvas.drawText(score.toString(), w * 0.95f, h * 0.085f, paint)

        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = h * 0.021f
        paint.color = 0xB8FFFFFF.toInt()
        canvas.drawText("COMBO  $combo", w * 0.95f, h * 0.122f, paint)

        paint.textAlign = Paint.Align.LEFT
        for (i in 0 until 3) {
            paint.color = if (i < lives) 0xFFFFFFFF.toInt() else 0x44FFFFFF
            canvas.drawCircle(
                w * 0.047f + i * h * 0.030f,
                h * 0.155f,
                h * 0.0075f,
                paint
            )
        }

        if (bestSplit > 0) {
            paint.textAlign = Paint.Align.RIGHT
            paint.textSize = h * 0.018f
            paint.color = 0xAFFFFFFF.toInt()
            canvas.drawText("BEST SPLIT  $bestSplit", w * 0.95f, h * 0.154f, paint)
        }

        paint.textAlign = Paint.Align.LEFT
        paint.typeface = android.graphics.Typeface.DEFAULT
    }

    private fun drawStagePrompt(canvas: Canvas, nowMs: Long) {
        val w = width.toFloat()
        val h = height.toFloat()

        val primary: String
        val secondary: String

        when (stage) {
            Stage.CALIBRATE -> { primary = "STEP BACK"; secondary = "FULL BODY IN FRAME" }
            Stage.ROPE -> { primary = ""; secondary = "" }
            Stage.SPLIT -> { primary = "SPLIT"; secondary = "LOW  ·  WIDE  ·  READY" }
            Stage.MOVE -> {
                primary = if (trapSide < 0) "MOVE RIGHT" else "MOVE LEFT"
                secondary = "PUSH  ·  DON'T CROSS FEET"
            }
            Stage.RECOVER -> { primary = "RECOVER"; secondary = "BACK TO CENTER" }
            Stage.GAME_OVER -> { primary = "GAME OVER"; secondary = "TAP TO RUN AGAIN" }
        }

        val transient = nowMs < feedbackUntilMs && feedbackText.isNotBlank()
        val headline = if (transient) feedbackText else primary

        if (headline.isNotBlank()) {
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = h * if (stage == Stage.GAME_OVER) 0.076f else 0.065f
            canvas.drawText(headline, w * 0.5f, h * 0.31f, paint)
        }

        if (!transient && secondary.isNotBlank()) {
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.DEFAULT
            paint.textSize = h * 0.020f
            paint.color = 0xC8FFFFFF.toInt()
            canvas.drawText(secondary, w * 0.5f, h * 0.355f, paint)
        }

        if (stage == Stage.CALIBRATE) {
            val progress = (calCount / CALIBRATION_FRAMES.toFloat()).coerceIn(0f, 1f)
            val barW = w * 0.18f
            val x0 = w * 0.5f - barW * 0.5f
            val y = h * 0.395f

            paint.style = Paint.Style.FILL
            paint.color = 0x35FFFFFF
            canvas.drawRoundRect(
                RectF(x0, y, x0 + barW, y + h * 0.006f),
                h * 0.003f, h * 0.003f, paint
            )

            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawRoundRect(
                RectF(x0, y, x0 + barW * progress, y + h * 0.006f),
                h * 0.003f, h * 0.003f, paint
            )
        }

        paint.textAlign = Paint.Align.LEFT
        paint.typeface = android.graphics.Typeface.DEFAULT
    }

    private fun drawVisionStatus(canvas: Canvas, pose: BodyPose?) {
        val w = width.toFloat()
        val h = height.toFloat()

        val error = cameraError
        if (error != null) {
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = h * 0.028f
            paint.color = 0xFFFF6B82.toInt()
            canvas.drawText(error, w * 0.5f, h * 0.60f, paint)
            paint.textAlign = Paint.Align.LEFT
            return
        }

        if (pose == null) {
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = h * 0.026f
            paint.color = 0xD8FFFFFF.toInt()
            canvas.drawText("FINDING BODY…", w * 0.5f, h * 0.62f, paint)
            paint.textAlign = Paint.Align.LEFT
            return
        }

        paint.textAlign = Paint.Align.RIGHT
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = h * 0.0145f
        paint.color = 0x72FFFFFF
        canvas.drawText(
            "${"%.0f".format(visionFps)} FPS  ·  ${visionLatencyMs} ms",
            w * 0.95f,
            h * 0.965f,
            paint
        )
        paint.textAlign = Paint.Align.LEFT
    }

    private fun resetAll() {
        score = 0
        combo = 0
        lives = 3
        bestSplit = 0
        ropeSpeed = 0.92f
        ropePhase = 0.18f
        ropeCrossings = 0
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
        private const val CALIBRATION_FRAMES = 32
    }
}
