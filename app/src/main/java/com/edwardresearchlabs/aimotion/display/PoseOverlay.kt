package com.edwardresearchlabs.aimotion.display

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.edwardresearchlabs.aimotion.motion.BodyPose
import com.edwardresearchlabs.aimotion.motion.Joint

class PoseOverlay(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    @Volatile private var pose: BodyPose? = null

    fun submitPose(newPose: BodyPose) {
        pose = newPose
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = pose ?: return

        paint.strokeWidth = 5f
        paint.style = Paint.Style.STROKE
        paint.color = 0xCCFFFFFF.toInt()

        for ((a, b) in bones) {
            val pa = current[a] ?: continue
            val pb = current[b] ?: continue
            if (pa.confidence < 0.45f || pb.confidence < 0.45f) continue
            canvas.drawLine(pa.x * width, pa.y * height, pb.x * width, pb.y * height, paint)
        }

        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFFFFF.toInt()
        for (point in current.points.values) {
            if (point.confidence >= 0.5f) {
                canvas.drawCircle(point.x * width, point.y * height, 7f, paint)
            }
        }
    }

    companion object {
        val bones = listOf(
            Joint.LEFT_SHOULDER to Joint.RIGHT_SHOULDER,
            Joint.LEFT_SHOULDER to Joint.LEFT_ELBOW,
            Joint.LEFT_ELBOW to Joint.LEFT_WRIST,
            Joint.RIGHT_SHOULDER to Joint.RIGHT_ELBOW,
            Joint.RIGHT_ELBOW to Joint.RIGHT_WRIST,
            Joint.LEFT_SHOULDER to Joint.LEFT_HIP,
            Joint.RIGHT_SHOULDER to Joint.RIGHT_HIP,
            Joint.LEFT_HIP to Joint.RIGHT_HIP,
            Joint.LEFT_HIP to Joint.LEFT_KNEE,
            Joint.LEFT_KNEE to Joint.LEFT_ANKLE,
            Joint.RIGHT_HIP to Joint.RIGHT_KNEE,
            Joint.RIGHT_KNEE to Joint.RIGHT_ANKLE
        )
    }
}
