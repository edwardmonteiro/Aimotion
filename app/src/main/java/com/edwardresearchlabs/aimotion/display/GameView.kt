package com.edwardresearchlabs.aimotion.display

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.edwardresearchlabs.aimotion.game.GoalkeeperGame

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
        game.tick(dt)

        val w = width.toFloat()
        val h = height.toFloat()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.008f
        paint.color = 0xFF444444.toInt()
        canvas.drawRect(RectF(w * 0.12f, h * 0.12f, w * 0.88f, h * 0.92f), paint)

        val s = game.state
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(s.ballX * w, s.ballY * h, h * 0.035f, paint)

        paint.color = 0xFFBDBDBD.toInt()
        canvas.drawCircle(s.playerX * w, h * 0.70f, h * 0.055f, paint)
        paint.strokeWidth = h * 0.025f
        canvas.drawLine(s.playerX * w, h * 0.74f, s.playerX * w, h * 0.87f, paint)
        canvas.drawLine(s.playerX * w, h * 0.77f, (s.playerX - 0.08f) * w, h * 0.82f, paint)
        canvas.drawLine(s.playerX * w, h * 0.77f, (s.playerX + 0.08f) * w, h * 0.82f, paint)

        paint.textSize = h * 0.045f
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawText("AI MOTION  •  GOALKEEPER  •  ${s.score}/${s.shots}", w * 0.04f, h * 0.08f, paint)

        postInvalidateOnAnimation()
    }
}
