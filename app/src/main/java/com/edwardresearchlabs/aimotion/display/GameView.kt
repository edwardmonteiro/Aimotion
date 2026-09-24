package com.edwardresearchlabs.aimotion.display

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import com.edwardresearchlabs.aimotion.game.BodyNinjaGame
import com.edwardresearchlabs.aimotion.game.NinjaHit
import com.edwardresearchlabs.aimotion.game.NinjaTargetType
import com.edwardresearchlabs.aimotion.motion.BodyPose
import com.edwardresearchlabs.aimotion.motion.Joint
import com.edwardresearchlabs.aimotion.motion.MotionRuntime
import com.edwardresearchlabs.aimotion.vision.SegmentedPersonFrame
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context) : View(context) {

    private data class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var life: Float,
        val size: Float,
        val type: NinjaTargetType
    )

    private data class FloatLabel(
        val text: String,
        var x: Float,
        var y: Float,
        var life: Float,
        val perfect: Boolean
    )

    private val game = BodyNinjaGame()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val particles = mutableListOf<Particle>()
    private val labels = mutableListOf<FloatLabel>()
    private val random = Random(7)

    private val tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 55) }.getOrNull()
    private val vibrator: Vibrator? = if (android.os.Build.VERSION.SDK_INT >= 31) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var lastFrame = System.nanoTime()
    private var lastWristLeft: Pair<Float, Float>? = null
    private var lastWristRight: Pair<Float, Float>? = null

    private var shakeUntilNs = 0L
    private var shakeStrengthPx = 0f
    private var slowUntilNs = 0L
    private var debugPhysics = false
    private var realMeEnabled = false
    private var personFrame: SegmentedPersonFrame? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(0xFF05060A.toInt())
    }

    fun togglePhysicsDebug(): Boolean {
        debugPhysics = !debugPhysics
        invalidate()
        return debugPhysics
    }

    fun setRealMeEnabled(enabled: Boolean) {
        realMeEnabled = enabled
        if (!enabled) clearPersonFrame()
        invalidate()
    }

    fun submitPersonFrame(frame: SegmentedPersonFrame) {
        val old = personFrame
        personFrame = frame
        if (old != null && old.bitmap !== frame.bitmap && !old.bitmap.isRecycled) {
            old.bitmap.recycle()
        }
        invalidate()
    }

    fun clearPersonFrame() {
        val old = personFrame
        personFrame = null
        if (old != null && !old.bitmap.isRecycled) {
            old.bitmap.recycle()
        }
        invalidate()
    }

    fun resetGame() {
        game.reset()
        particles.clear()
        labels.clear()
        lastWristLeft = null
        lastWristRight = null
        invalidate()
    }

    override fun onDetachedFromWindow() {
        clearPersonFrame()
        runCatching { tone?.release() }
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = System.nanoTime()
        val rawDt = ((now - lastFrame) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastFrame = now
        val dt = if (now < slowUntilNs) rawDt * 0.24f else rawDt

        val pose = MotionRuntime.freshPose(500L)
        val hits = game.update(dt, pose)
        for (hit in hits) onHit(hit)

        updateParticles(rawDt)
        updateLabels(rawDt)

        val w = width.toFloat()
        val h = height.toFloat()

        canvas.save()
        applyShake(canvas, now)

        drawBackground(canvas, w, h)
        drawArena(canvas, w, h)

        val personVisible = realMeEnabled && drawRealMe(canvas, w, h)
        drawTargets(canvas, w, h)

        if (pose != null) {
            if (personVisible) {
                drawRealMeActionGlow(canvas, pose, w, h)
            } else {
                drawBody(canvas, pose, w, h)
            }
            if (debugPhysics) drawPhysics(canvas, pose, w, h)
        }

        drawParticles(canvas, w, h)
        drawHud(canvas, w, h, pose != null)
        drawLabels(canvas, w, h)

        canvas.restore()
        postInvalidateOnAnimation()
    }

    private fun onHit(hit: NinjaHit) {
        explode(hit)

        if (hit.points > 0) {
            if (hit.perfect) {
                safeTone(ToneGenerator.TONE_PROP_ACK, 70)
                slowUntilNs = System.nanoTime() + 95_000_000L
                shakeUntilNs = System.nanoTime() + 120_000_000L
                shakeStrengthPx = 8f
                vibrate(32)
            } else {
                safeTone(ToneGenerator.TONE_PROP_BEEP, 45)
                shakeUntilNs = System.nanoTime() + 70_000_000L
                shakeStrengthPx = 3.5f
                vibrate(16)
            }
        } else {
            safeTone(ToneGenerator.TONE_PROP_NACK, 70)
            shakeUntilNs = System.nanoTime() + 150_000_000L
            shakeStrengthPx = 10f
            vibrate(45)
        }
    }

    private fun safeTone(toneType: Int, durationMs: Int) {
        runCatching {
            tone?.startTone(toneType, durationMs)
        }
    }

    private fun vibrate(ms: Long) {
        runCatching {
            val deviceVibrator = vibrator ?: return@runCatching
            if (!deviceVibrator.hasVibrator()) return@runCatching

            if (android.os.Build.VERSION.SDK_INT >= 26) {
                deviceVibrator.vibrate(
                    VibrationEffect.createOneShot(
                        ms,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                deviceVibrator.vibrate(ms)
            }
        }
    }

    private fun applyShake(canvas: Canvas, now: Long) {
        if (now >= shakeUntilNs) return
        val dx = (random.nextFloat() - 0.5f) * shakeStrengthPx * 2f
        val dy = (random.nextFloat() - 0.5f) * shakeStrengthPx * 2f
        canvas.translate(dx, dy)
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                0xFF07101E.toInt(),
                0xFF111B2C.toInt(),
                0xFF05070C.toInt()
            ),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.shader = RadialGradient(
            w * 0.5f, h * 0.42f, max(w, h) * 0.58f,
            0x334CC9F0, 0x00000000, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun drawArena(canvas: Canvas, w: Float, h: Float) {
        val horizon = h * 0.76f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = 0x244CC9F0

        for (i in -6..6) {
            val bottomX = w * 0.5f + i * w * 0.10f
            canvas.drawLine(w * 0.5f, horizon, bottomX, h, paint)
        }

        for (i in 0..5) {
            val t = i / 5f
            val y = horizon + (h - horizon) * t * t
            canvas.drawLine(0f, y, w, y, paint)
        }
    }

    private fun drawTargets(canvas: Canvas, w: Float, h: Float) {
        for (target in game.targets()) {
            val cx = target.x * w
            val cy = target.y * h
            val r = target.radius * minOf(w, h)

            when (target.type) {
                NinjaTargetType.HAND -> drawSliceOrb(canvas, cx, cy, r, target.rotation)
                NinjaTargetType.FOOT -> drawKickCore(canvas, cx, cy, r, target.rotation)
                NinjaTargetType.DODGE -> drawHazard(canvas, cx, cy, r, target.rotation)
            }
        }
    }

    private fun drawSliceOrb(canvas: Canvas, cx: Float, cy: Float, r: Float, rotation: Float) {
        paint.maskFilter = BlurMaskFilter(r * 0.42f, BlurMaskFilter.Blur.NORMAL)
        paint.style = Paint.Style.FILL
        paint.color = 0x884CC9F0.toInt()
        canvas.drawCircle(cx, cy, r * 1.22f, paint)
        paint.maskFilter = null

        paint.shader = RadialGradient(
            cx - r * 0.28f, cy - r * 0.28f, r * 1.25f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFF60DFFF.toInt(), 0xFF136789.toInt()),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(2f, r * 0.10f)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.save()
        canvas.rotate(rotation * 57.3f, cx, cy)
        canvas.drawArc(RectF(cx-r*0.62f, cy-r*0.62f, cx+r*0.62f, cy+r*0.62f), -55f, 110f, false, paint)
        canvas.restore()
    }

    private fun drawKickCore(canvas: Canvas, cx: Float, cy: Float, r: Float, rotation: Float) {
        paint.maskFilter = BlurMaskFilter(r * 0.34f, BlurMaskFilter.Blur.NORMAL)
        paint.style = Paint.Style.FILL
        paint.color = 0x88F7B955.toInt()
        canvas.drawCircle(cx, cy, r * 1.18f, paint)
        paint.maskFilter = null

        paint.style = Paint.Style.FILL
        paint.color = 0xFFF7B955.toInt()
        path.reset()
        for (i in 0 until 6) {
            val a = rotation + i * (Math.PI * 2 / 6).toFloat()
            val x = cx + cos(a) * r
            val y = cy + sin(a) * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(3f, r * 0.12f)
        paint.color = 0xFF3B2B0F.toInt()
        canvas.drawCircle(cx, cy, r * 0.42f, paint)
    }

    private fun drawHazard(canvas: Canvas, cx: Float, cy: Float, r: Float, rotation: Float) {
        paint.maskFilter = BlurMaskFilter(r * 0.38f, BlurMaskFilter.Blur.NORMAL)
        paint.style = Paint.Style.FILL
        paint.color = 0x88FF496C.toInt()
        canvas.drawCircle(cx, cy, r * 1.2f, paint)
        paint.maskFilter = null

        canvas.save()
        canvas.rotate(rotation * 57.3f, cx, cy)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFF496C.toInt()
        path.reset()

        for (i in 0 until 24) {
            val angle = i * Math.PI.toFloat() / 12
            val rr = if (i % 2 == 0) r else r * 0.58f
            val x = cx + cos(angle) * rr
            val y = cy + sin(angle) * rr
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, paint)

        paint.color = 0xFF380A14.toInt()
        canvas.drawCircle(cx, cy, r * 0.32f, paint)
        canvas.restore()
    }

    private fun drawRealMe(canvas: Canvas, w: Float, h: Float): Boolean {
        val frame = personFrame ?: return false
        val ageMs = System.currentTimeMillis() - frame.timestampMs
        if (ageMs > 900L || frame.bitmap.isRecycled) return false

        val bitmap = frame.bitmap
        val offsetX = MotionRuntime.anchorOffsetX() * w

        fun drawPass(alpha: Int, blur: Float) {
            canvas.save()
            canvas.translate(offsetX + if (MotionRuntime.frontCamera) w else 0f, 0f)
            if (MotionRuntime.frontCamera) canvas.scale(-1f, 1f)

            paint.style = Paint.Style.FILL
            paint.alpha = alpha
            paint.maskFilter = if (blur > 0f) BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) else null
            canvas.drawBitmap(bitmap, null, RectF(0f, 0f, w, h), paint)

            paint.maskFilter = null
            paint.alpha = 255
            canvas.restore()
        }

        drawPass(70, h * 0.016f)
        drawPass(245, 0f)
        return true
    }

    private fun drawRealMeActionGlow(canvas: Canvas, pose: BodyPose, w: Float, h: Float) {
        drawTrail(canvas, pose, w, h, Joint.LEFT_WRIST, true)
        drawTrail(canvas, pose, w, h, Joint.RIGHT_WRIST, false)

        for (joint in listOf(
            Joint.LEFT_WRIST,
            Joint.RIGHT_WRIST,
            Joint.LEFT_ANKLE,
            Joint.RIGHT_ANKLE
        )) {
            val p = pose[joint] ?: continue
            if (p.confidence < 0.45f) continue

            val cx = MotionRuntime.mapX(p.x) * w
            val cy = p.y * h

            paint.style = Paint.Style.FILL
            paint.maskFilter = BlurMaskFilter(h * 0.022f, BlurMaskFilter.Blur.NORMAL)
            paint.color = 0xAA67E8F9.toInt()
            canvas.drawCircle(cx, cy, h * 0.032f, paint)
            paint.maskFilter = null

            paint.color = 0xCCFFFFFF.toInt()
            canvas.drawCircle(cx, cy, h * 0.009f, paint)
        }
    }

    private fun drawBody(canvas: Canvas, pose: BodyPose, w: Float, h: Float) {
        drawTrail(canvas, pose, w, h, Joint.LEFT_WRIST, true)
        drawTrail(canvas, pose, w, h, Joint.RIGHT_WRIST, false)

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = h * 0.015f
        paint.color = 0xCCDBEAFE.toInt()

        for ((a, b) in PoseOverlay.bones) {
            val pa = pose[a] ?: continue
            val pb = pose[b] ?: continue
            if (pa.confidence < 0.42f || pb.confidence < 0.42f) continue
            canvas.drawLine(MotionRuntime.mapX(pa.x)*w, pa.y*h, MotionRuntime.mapX(pb.x)*w, pb.y*h, paint)
        }

        paint.strokeCap = Paint.Cap.BUTT

        for (joint in listOf(Joint.LEFT_WRIST, Joint.RIGHT_WRIST, Joint.LEFT_ANKLE, Joint.RIGHT_ANKLE)) {
            val p = pose[joint] ?: continue
            if (p.confidence < 0.45f) continue
            val cx = MotionRuntime.mapX(p.x)*w
            val cy = p.y*h

            paint.maskFilter = BlurMaskFilter(h*0.018f, BlurMaskFilter.Blur.NORMAL)
            paint.style = Paint.Style.FILL
            paint.color = 0xAA7DD3FC.toInt()
            canvas.drawCircle(cx, cy, h*0.028f, paint)
            paint.maskFilter = null

            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(cx, cy, h*0.012f, paint)
        }
    }

    private fun drawTrail(canvas: Canvas, pose: BodyPose, w: Float, h: Float, joint: Joint, left: Boolean) {
        val p = pose[joint] ?: return
        if (p.confidence < 0.45f) return

        val current = Pair(MotionRuntime.mapX(p.x)*w, p.y*h)
        val previous = if (left) lastWristLeft else lastWristRight

        if (previous != null) {
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = h * 0.022f
            paint.color = if (left) 0x804CC9F0.toInt() else 0x80A78BFA.toInt()
            canvas.drawLine(previous.first, previous.second, current.first, current.second, paint)
            paint.strokeCap = Paint.Cap.BUTT
        }

        if (left) lastWristLeft = current else lastWristRight = current
    }

    private fun drawPhysics(canvas: Canvas, pose: BodyPose, w: Float, h: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = 0xAA22C55E.toInt()

        val segments = listOf(
            Joint.LEFT_WRIST to Joint.LEFT_ELBOW,
            Joint.LEFT_ELBOW to Joint.LEFT_SHOULDER,
            Joint.RIGHT_WRIST to Joint.RIGHT_ELBOW,
            Joint.RIGHT_ELBOW to Joint.RIGHT_SHOULDER,
            Joint.LEFT_FOOT_INDEX to Joint.LEFT_ANKLE,
            Joint.LEFT_ANKLE to Joint.LEFT_KNEE,
            Joint.RIGHT_FOOT_INDEX to Joint.RIGHT_ANKLE,
            Joint.RIGHT_ANKLE to Joint.RIGHT_KNEE
        )

        for ((a, b) in segments) {
            val pa = pose[a] ?: continue
            val pb = pose[b] ?: continue
            canvas.drawLine(MotionRuntime.mapX(pa.x)*w, pa.y*h, MotionRuntime.mapX(pb.x)*w, pb.y*h, paint)
        }

        paint.color = 0xAA22C55E.toInt()
        paint.textSize = h * 0.022f
        canvas.drawText("PHYSICS CAPSULES ON", w*0.055f, h*0.225f, paint)
    }

    private fun explode(hit: NinjaHit) {
        val count = when {
            hit.perfect -> 42
            hit.type == NinjaTargetType.DODGE -> 18
            else -> 26
        }

        repeat(count) {
            val angle = random.nextFloat() * Math.PI.toFloat() * 2f
            val speed = 0.12f + random.nextFloat() * if (hit.perfect) 0.48f else 0.30f
            particles += Particle(
                x = hit.x,
                y = hit.y,
                vx = cos(angle) * speed,
                vy = sin(angle) * speed,
                life = 0.55f + random.nextFloat() * 0.45f,
                size = 0.004f + random.nextFloat() * if (hit.perfect) 0.012f else 0.008f,
                type = hit.type
            )
        }

        val speedText = if (hit.impactSpeed > 0f) "  %.1f".format(hit.impactSpeed) else ""
        labels += FloatLabel(
            text = if (hit.points > 0) "${hit.label} +${hit.points}$speedText" else hit.label,
            x = hit.x,
            y = hit.y,
            life = if (hit.perfect) 0.95f else 0.75f,
            perfect = hit.perfect
        )
    }

    private fun updateParticles(dt: Float) {
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.life -= dt
            if (p.life <= 0f) {
                iterator.remove()
                continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy += 0.24f * dt
        }
    }

    private fun updateLabels(dt: Float) {
        val iterator = labels.iterator()
        while (iterator.hasNext()) {
            val label = iterator.next()
            label.life -= dt
            label.y -= dt * 0.08f
            if (label.life <= 0f) iterator.remove()
        }
    }

    private fun drawParticles(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        for (p in particles) {
            paint.alpha = (255 * p.life.coerceIn(0f, 1f)).toInt()
            paint.color = when (p.type) {
                NinjaTargetType.HAND -> 0xFF67E8F9.toInt()
                NinjaTargetType.FOOT -> 0xFFFBBF24.toInt()
                NinjaTargetType.DODGE -> 0xFFFB7185.toInt()
            }
            canvas.drawCircle(p.x*w, p.y*h, p.size*minOf(w,h), paint)
        }
        paint.alpha = 255
    }

    private fun drawLabels(canvas: Canvas, w: Float, h: Float) {
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD

        for (label in labels) {
            paint.alpha = (255 * label.life.coerceIn(0f,1f)).toInt()
            paint.textSize = h * if (label.perfect) 0.052f else 0.038f
            paint.color = if (label.perfect) 0xFFFFF4B2.toInt() else 0xFFFFFFFF.toInt()
            canvas.drawText(label.text, label.x*w, label.y*h, paint)
        }

        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textAlign = Paint.Align.LEFT
        paint.alpha = 255
    }

    private fun drawHud(canvas: Canvas, w: Float, h: Float, tracking: Boolean) {
        val state = game.state

        paint.style = Paint.Style.FILL
        paint.color = 0xA60A0F18.toInt()
        canvas.drawRoundRect(RectF(w*0.035f,h*0.035f,w*0.46f,h*0.175f), h*0.026f,h*0.026f,paint)

        paint.color = 0xFFFFFFFF.toInt()
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = h * 0.040f
        canvas.drawText("BODY NINJA  •  LV ${state.level}", w*0.055f,h*0.082f,paint)

        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = h * 0.022f
        paint.color = 0xFF94A3B8.toInt()
        canvas.drawText(
            if (tracking) "ACC ${state.accuracyPercent}%  •  REACT ${state.averageReactionMs}ms  •  PERFECT ${state.perfectHits}"
            else "STEP INTO CAMERA",
            w*0.055f,h*0.122f,paint
        )

        paint.textAlign = Paint.Align.RIGHT
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = h * 0.052f
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawText(state.score.toString(), w*0.94f,h*0.082f,paint)

        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = h * 0.026f
        paint.color = 0xFF67E8F9.toInt()
        canvas.drawText("COMBO x${state.combo}", w*0.94f,h*0.123f,paint)

        paint.textAlign = Paint.Align.LEFT
        for (i in 0 until 3) {
            paint.style = Paint.Style.FILL
            paint.color = if (i < state.lives) 0xFFFF496C.toInt() else 0xFF253047.toInt()
            canvas.drawCircle(w*0.055f + i*h*0.034f, h*0.158f, h*0.010f, paint)
        }
    }
}
