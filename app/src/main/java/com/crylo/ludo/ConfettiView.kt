package com.crylo.ludo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin
import kotlin.random.Random

/**
 * A few seconds of paper confetti in the four player colours, for a win.
 *
 * Every piece's path is a closed-form function of time from values picked at
 * [burst], so a frame is just arithmetic over one float array: no per-piece
 * objects and nothing allocated while it falls. It never takes touches, and
 * shows nothing when the system has animations turned off.
 */
class ConfettiView(context: Context) : View(context) {

    // Per piece: x, y at t=0, fall speed, sway amplitude, sway phase, angle, spin, colour.
    private val pieces = FloatArray(COUNT * FIELDS)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val palette = Board.colors + Color.WHITE

    private var seconds = 0f
    private var animator: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable = false
    }

    fun burst() {
        stop()
        if (!ValueAnimator.areAnimatorsEnabled() || width == 0 || height == 0) return
        val random = Random.Default
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 0 until COUNT) {
            val o = i * FIELDS
            pieces[o] = random.nextFloat() * w
            // Stacked above the top edge, so they arrive as a shower rather than a line.
            pieces[o + 1] = -random.nextFloat() * h * 0.9f - h * 0.05f
            pieces[o + 2] = h * (0.45f + random.nextFloat() * 0.35f)
            pieces[o + 3] = w * (0.01f + random.nextFloat() * 0.03f)
            pieces[o + 4] = random.nextFloat() * 6.28f
            pieces[o + 5] = random.nextFloat() * 360f
            pieces[o + 6] = (random.nextFloat() - 0.5f) * 720f
            pieces[o + 7] = random.nextInt(palette.size).toFloat()
        }
        animator = ValueAnimator.ofFloat(0f, DURATION_MS / 1000f).apply {
            duration = DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                seconds = it.animatedValue as Float
                invalidate()
            }
            doOnEnd { stop() }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        seconds = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onDraw(canvas: Canvas) {
        if (animator == null) return
        val t = seconds
        val total = DURATION_MS / 1000f
        // Fades over the last stretch instead of vanishing mid-fall.
        val fade = ((total - t) / FADE_S).coerceIn(0f, 1f)
        val size = PIECE_DP * resources.displayMetrics.density
        for (i in 0 until COUNT) {
            val o = i * FIELDS
            val y = pieces[o + 1] + pieces[o + 2] * t
            if (y !in -size..height + size) continue
            val x = pieces[o] + pieces[o + 3] * sin(pieces[o + 4] + t * SWAY_RATE)
            paint.color = palette[pieces[o + 7].toInt()]
            paint.alpha = (255 * fade).toInt()
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(pieces[o + 5] + pieces[o + 6] * t)
            canvas.drawRect(-size, -size * 0.45f, size, size * 0.45f, paint)
            canvas.restore()
        }
    }

    private companion object {
        const val COUNT = 90
        const val FIELDS = 8
        const val DURATION_MS = 3200L
        const val FADE_S = 0.8f
        const val SWAY_RATE = 3.2f
        const val PIECE_DP = 4f
    }
}
