package com.instaweb.shell

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class ArcSpinner(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val rect = RectF()
    private var animator: ValueAnimator? = null
    private var rotation = 0f
    private var blue = 0xFF0095F6.toInt()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val stroke = resources.displayMetrics.density * 3f
        paint.strokeWidth = stroke
        val inset = stroke / 2f + 1f
        rect.set(inset, inset, w - inset, h - inset)
        val cx = w / 2f
        val cy = h / 2f
        val tail = blue and 0x00FFFFFF
        paint.shader = SweepGradient(
            cx,
            cy,
            intArrayOf(tail, blue, blue),
            floatArrayOf(0f, 0.8333f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        startIfNeeded()
        canvas.save()
        canvas.rotate(rotation, width / 2f, height / 2f)
        canvas.drawArc(rect, 0f, 300f, false, paint)
        canvas.restore()
    }

    private fun startIfNeeded() {
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 850L
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                addUpdateListener { animator ->
                    rotation = animator.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
