package com.edwardresearchlabs.aimotion.display

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import com.edwardresearchlabs.aimotion.game.BodyNinjaGame
import com.edwardresearchlabs.aimotion.game.NinjaHit
import com.edwardresearchlabs.aimotion.game.NinjaTarget
import com.edwardresearchlabs.aimotion.game.NinjaTargetType
import com.edwardresearchlabs.aimotion.motion.BodyPose
import com.edwardresearchlabs.aimotion.motion.Joint
import com.edwardresearchlabs.aimotion.motion.MotionRuntime
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
        var life: Float
    )

    private val game = BodyNinjaGame()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val particles = mutableListOf<Particle>()
    private val labels = mutableListOf<FloatLabel>()
    private val random = Random(7)

    private var lastFrame = System.nanoTime()
    private var lastWristLeft: Pair<Float, Float>? = null
    private var lastWristRight: Pair<Float, Float>? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(0xFF05060A.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = System.nanoTime()
        val dt = ((now - lastFrame) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastFrame = now

        val pose = MotionRuntime.freshPose(500L)
        val hits = game.update(dt, pose)
        for (hit in hits) explode(hit)

        updateParticles(dt)
        updateLabels(dt)

        val w = width.toFloat()
        val h = height.toFloat()

        drawBackground(canvas, w, h)
        drawArena(canvas, w, h)
        drawTargets(canvas, w, h)

        if (pose != null) {
            drawBody(canvas, pose, w, h)
        }

        drawParticles(canvas, w, h)
        drawHud(canvas, w, h, pose != null)
        drawLabels(canvas, w, h)

        postInvalidateOnAnimation()
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                0xFF08111F.toInt(),
                0xFF101827.toInt(),
                0xFF06080E.toInt()
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.shader = RadialGradient(
            w * 0.5f,
            h * 0.42f,
            max(w, h) * 0.55f,
            0x334CC9F0,
            0x00000000,
            Shader.TileMode.CLAMP
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

        paint.style = Paint.Style.FILL
        paint.color = 0x18000000
        canvas.drawRoundRect(
            RectF(w * 0.03f, h * 0.03f, w * 0.97f, h * 0.96f),
            32f, 32f, paint
        )
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
            cx - r * 0.28f,
            cy - r * 0.28f,
            r * 1.25f,
            intArrayOf(
                0xFFFFFFFF.toInt(),
                0xFF60DFFF.toInt(),
                0xFF136789.toInt()
            ),
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

        val sides = 6
        for (i in 0 until sides) {
            val a = rotation + i * (Math.PI * 2 / sides).toFloat()
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

        val spikes = 12
        for (i in 0 until spikes * 2) {
            val angle = i * Math.PI.toFloat() / spikes
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

            canvas.drawLine(
                (1f - pa.x) * w,
                pa.y * h,
                (1f - pb.x) * w,
                pb.y * h,
                paint
            )
        }

        paint.strokeCap = Paint.Cap.BUTT

        for (joint in listOf(
            Joint.LEFT_WRIST,
            Joint.RIGHT_WRIST,
            Joint.LEFT_ANKLE,
            Joint.RIGHT_ANKLE
        )) {
            val p = pose[joint] ?: continue
            if (p.confidence < 0.45f) continue

            val cx = (1f - p.x) * w
            val cy = p.y * h

            paint.maskFilter = BlurMaskFilter(h * 0.018f, BlurMaskFilter.Blur.NORMAL)
            paint.style = Paint.Style.FILL
            paint.color = 0xAA7DD3FC.toInt()
            canvas.drawCircle(cx, cy, h * 0.028f, paint)
            paint.maskFilter = null

            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(cx, cy, h * 0.012f, paint)
        }
    }

    private fun drawTrail(
        canvas: Canvas,
        pose: BodyPose,
        w: Float,
        h: Float,
        joint: Joint,
        left: Boolean
    ) {
        val p = pose[joint] ?: return
        if (p.confidence < 0.45f) return

        val current = Pair((1f - p.x) * w, p.y * h)
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

    private fun explode(hit: NinjaHit) {
        val count = if (hit.type == NinjaTargetType.DODGE) 16 else 24
        repeat(count) {
            val angle = random.nextFloat() * Math.PI.toFloat() * 2f
            val speed = 0.12f + random.nextFloat() * 0.30f
            particles += Particle(
                x = hit.x,
                y = hit.y,
                vx = cos(angle) * speed,
                vy = sin(angle) * speed,
                life = 0.55f + random.nextFloat() * 0.35f,
                size = 0.004f + random.nextFloat() * 0.008f,
                type = hit.type
            )
        }

        labels += FloatLabel(
            text = if (hit.points > 0) "${hit.label} +${hit.points}" else hit.label,
            x = hit.x,
            y = hit.y,
            life = 0.75f
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
            canvas.drawCircle(p.x * w, p.y * h, p.size * minOf(w, h), paint)
        }
        paint.alpha = 255
    }

    private fun drawLabels(canvas: Canvas, w: Float, h: Float) {
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = h * 0.038f
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD

        for (label in labels) {
            paint.alpha = (255 * label.life.coerceIn(0f, 1f)).toInt()
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawText(label.text, label.x * w, label.y * h, paint)
        }

        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textAlign = Paint.Align.LEFT
        paint.alpha = 255
    }

    private fun drawHud(canvas: Canvas, w: Float, h: Float, tracking: Boolean) {
        val state = game.state

        paint.style = Paint.Style.FILL
        paint.color = 0xA60A0F18.toInt()
        canvas.drawRoundRect(
            RectF(w * 0.035f, h * 0.035f, w * 0.40f, h * 0.16f),
            h * 0.026f,
            h * 0.026f,
            paint
        )

        paint.color = 0xFFFFFFFF.toInt()
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = h * 0.040f
        canvas.drawText("BODY NINJA", w * 0.055f, h * 0.085f, paint)

        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = h * 0.024f
        paint.color = 0xFF94A3B8.toInt()
        canvas.drawText(
            if (tracking) "LIVE BODY • HAND / FOOT / DODGE" else "STEP INTO CAMERA",
            w * 0.055f,
            h * 0.125f,
            paint
        )

        paint.textAlign = Paint.Align.RIGHT
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = h * 0.054f
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawText(state.score.toString(), w * 0.94f, h * 0.085f, paint)

        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = h * 0.026f
        paint.color = 0xFF67E8F9.toInt()
        canvas.drawText("COMBO x${state.combo}", w * 0.94f, h * 0.125f, paint)

        paint.textAlign = Paint.Align.LEFT

        for (i in 0 until 3) {
            paint.style = Paint.Style.FILL
            paint.color = if (i < state.lives) 0xFFFF496C.toInt() else 0xFF253047.toInt()
            val x = w * 0.055f + i * h * 0.034f
            canvas.drawCircle(x, h * 0.185f, h * 0.010f, paint)
        }
    }
}
