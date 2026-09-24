package com.edwardresearchlabs.aimotion.display

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import com.edwardresearchlabs.aimotion.motion.BodyPose
import com.edwardresearchlabs.aimotion.motion.Joint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AvatarView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    @Volatile private var pose: BodyPose? = null
    @Volatile var mirrorX: Boolean = true

    init {
        setBackgroundColor(0xFF06070A.toInt())
    }

    fun submitPose(newPose: BodyPose) {
        pose = newPose
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = pose

        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            width * 0.5f,
            height * 0.48f,
            max(width, height) * 0.6f,
            0xFF1B2230.toInt(),
            0xFF06070A.toInt(),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        drawFloor(canvas)

        if (current == null || current.trackedPointCount < 8) {
            paint.color = 0xFFFFFFFF.toInt()
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = height * 0.045f
            canvas.drawText("STEP INTO VIEW", width * 0.5f, height * 0.5f, paint)
            paint.textAlign = Paint.Align.LEFT
            postInvalidateOnAnimation()
            return
        }

        val center = bodyCenter(current)
        val scale = avatarScale(current)

        for ((a, b) in PoseOverlay.bones) {
            drawLimb(canvas, current, a, b, center, scale)
        }

        drawJoint(canvas, current, Joint.LEFT_WRIST, center, scale, 1.05f)
        drawJoint(canvas, current, Joint.RIGHT_WRIST, center, scale, 1.05f)
        drawJoint(canvas, current, Joint.LEFT_ANKLE, center, scale, 1.08f)
        drawJoint(canvas, current, Joint.RIGHT_ANKLE, center, scale, 1.08f)

        val nose = current[Joint.NOSE]
        if (nose != null && nose.confidence >= 0.45f) {
            val p = project(nose.x, nose.y, nose.z, center, scale)
            paint.style = Paint.Style.FILL
            paint.color = 0xFFECEFF4.toInt()
            canvas.drawCircle(p.first, p.second, height * 0.055f * p.third, paint)

            paint.color = 0xFF0A0C10.toInt()
            val eyeOffset = height * 0.017f * p.third
            canvas.drawCircle(p.first - eyeOffset, p.second - eyeOffset * 0.15f, eyeOffset * 0.35f, paint)
            canvas.drawCircle(p.first + eyeOffset, p.second - eyeOffset * 0.15f, eyeOffset * 0.35f, paint)
        }

        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = height * 0.032f
        canvas.drawText("AVATAR TEST  •  2.5D BODY", width * 0.04f, height * 0.07f, paint)
        paint.color = 0xFF98A2B3.toInt()
        paint.textSize = height * 0.024f
        canvas.drawText("${current.trackedPointCount}/33 points", width * 0.04f, height * 0.105f, paint)

        postInvalidateOnAnimation()
    }

    private fun drawFloor(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = 0x2238BDF8
        val horizon = height * 0.78f

        for (i in -5..5) {
            val x = width * 0.5f + i * width * 0.09f
            canvas.drawLine(width * 0.5f, horizon, x, height.toFloat(), paint)
        }
        for (i in 0..4) {
            val y = horizon + (height - horizon) * (i / 4f)
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
        }
    }

    private fun drawLimb(
        canvas: Canvas,
        pose: BodyPose,
        a: Joint,
        b: Joint,
        center: Pair<Float, Float>,
        scale: Float
    ) {
        val pa = pose[a] ?: return
        val pb = pose[b] ?: return
        if (pa.confidence < 0.4f || pb.confidence < 0.4f) return

        val aa = project(pa.x, pa.y, pa.z, center, scale)
        val bb = project(pb.x, pb.y, pb.z, center, scale)

        val depth = ((aa.third + bb.third) * 0.5f).coerceIn(0.7f, 1.35f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = height * 0.030f * depth
        paint.color = 0xFF98A2B3.toInt()
        canvas.drawLine(aa.first, aa.second, bb.first, bb.second, paint)

        paint.strokeWidth = height * 0.012f * depth
        paint.color = 0xFFE6E8EC.toInt()
        canvas.drawLine(aa.first, aa.second, bb.first, bb.second, paint)

        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawJoint(
        canvas: Canvas,
        pose: BodyPose,
        joint: Joint,
        center: Pair<Float, Float>,
        scale: Float,
        radiusScale: Float
    ) {
        val p = pose[joint] ?: return
        if (p.confidence < 0.4f) return
        val pp = project(p.x, p.y, p.z, center, scale)

        paint.style = Paint.Style.FILL
        paint.color = 0xFFECEFF4.toInt()
        canvas.drawCircle(pp.first, pp.second, height * 0.026f * pp.third * radiusScale, paint)
    }

    private fun bodyCenter(pose: BodyPose): Pair<Float, Float> {
        val lh = pose[Joint.LEFT_HIP]
        val rh = pose[Joint.RIGHT_HIP]
        val x = if (lh != null && rh != null) (lh.x + rh.x) * 0.5f else 0.5f
        val y = if (lh != null && rh != null) (lh.y + rh.y) * 0.5f else 0.58f
        return x to y
    }

    private fun avatarScale(pose: BodyPose): Float {
        val ls = pose[Joint.LEFT_SHOULDER]
        val rs = pose[Joint.RIGHT_SHOULDER]
        val widthNorm = if (ls != null && rs != null) abs(ls.x - rs.x) else 0.22f
        return (0.22f / widthNorm.coerceAtLeast(0.08f)).coerceIn(0.65f, 1.45f)
    }

    private fun project(
        x: Float,
        y: Float,
        z: Float,
        center: Pair<Float, Float>,
        scale: Float
    ): Triple<Float, Float, Float> {
        val mirroredX = if (mirrorX) 1f - x else x
        val mirroredCenter = if (mirrorX) 1f - center.first else center.first

        val depthScale = (1f - z * 0.35f).coerceIn(0.72f, 1.30f)
        val screenX = width * 0.5f + (mirroredX - mirroredCenter) * width * scale * depthScale
        val screenY = height * 0.64f + (y - center.second) * height * scale * depthScale
        return Triple(screenX, screenY, depthScale)
    }
}
