package com.instaweb.shell

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

class ArcSpinner(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = ContextCompat.getColor(context, R.color.spinner_blue)
    }

    private val rect = RectF()
    private var animator: ValueAnimator? = null
    private var rotation = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val stroke = resources.displayMetrics.density * 3f
        paint.strokeWidth = stroke
        val inset = stroke / 2f + 1f
        rect.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        startIfNeeded()
        canvas.save()
        canvas.rotate(rotation, width / 2f, height / 2f)
        canvas.drawArc(rect, 0f, 270f, false, paint)
        canvas.restore()
    }

    private fun startIfNeeded() {
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 900L
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
