package com.edwardresearchlabs.aimotion.display

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.edwardresearchlabs.aimotion.game.GoalkeeperGame
import com.edwardresearchlabs.aimotion.motion.BodyPose
import com.edwardresearchlabs.aimotion.motion.Joint
import com.edwardresearchlabs.aimotion.motion.MotionRuntime

class GameView(context: Context) : View(context) {
    private val game = GoalkeeperGame()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastFrame = System.nanoTime()

    init {
        setBackgroundColor(0xFF050505.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = System.nanoTime()
        val dt = ((now - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
        lastFrame = now

        val pose = MotionRuntime.freshPose(450L)
        game.updatePose(pose)
        game.tick(dt)

        val w = width.toFloat()
        val h = height.toFloat()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.008f
        paint.color = 0xFF444444.toInt()
        canvas.drawRect(RectF(w * 0.08f, h * 0.12f, w * 0.92f, h * 0.93f), paint)

        val s = game.state
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(s.ballX * w, s.ballY * h, h * 0.032f, paint)

        if (pose != null) drawPose(canvas, pose, w, h)

        paint.textSize = h * 0.042f
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawText("AI MOTION  •  GOALKEEPER  •  ${s.score}/${s.shots}", w * 0.04f, h * 0.07f, paint)

        paint.textSize = h * 0.026f
        paint.color = if (pose != null) 0xFFB8FFCA.toInt() else 0xFFFFC7C7.toInt()
        val tracking = if (pose != null) "LIVE BODY TRACKING" else "TRACKING LOST — STEP INTO CAMERA"
        canvas.drawText(tracking, w * 0.04f, h * 0.11f, paint)

        postInvalidateOnAnimation()
    }

    private fun drawPose(canvas: Canvas, pose: BodyPose, w: Float, h: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.012f
        paint.color = 0xFFE0E0E0.toInt()

        for ((a, b) in PoseOverlay.bones) {
            val pa = pose[a] ?: continue
            val pb = pose[b] ?: continue
            if (pa.confidence < 0.45f || pb.confidence < 0.45f) continue
            canvas.drawLine((1f - pa.x) * w, pa.y * h, (1f - pb.x) * w, pb.y * h, paint)
        }

        paint.style = Paint.Style.FILL
        for (joint in listOf(
            Joint.LEFT_WRIST, Joint.RIGHT_WRIST,
            Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER,
            Joint.LEFT_HIP, Joint.RIGHT_HIP,
            Joint.LEFT_KNEE, Joint.RIGHT_KNEE,
            Joint.LEFT_ANKLE, Joint.RIGHT_ANKLE
        )) {
            val p = pose[joint] ?: continue
            if (p.confidence < 0.45f) continue
            canvas.drawCircle((1f - p.x) * w, p.y * h, h * 0.014f, paint)
        }
    }
}
