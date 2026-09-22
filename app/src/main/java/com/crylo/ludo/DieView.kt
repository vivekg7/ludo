package com.crylo.ludo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.random.Random

/** The die: taps ask for a roll, and it tumbles through faces before settling. */
class DieView(context: Context) : View(context) {

    /** Face currently shown, or 0 before the first roll of a turn. */
    var face = 0
        set(value) {
            field = value
            invalidate()
        }

    /** Tinted with the colour of whoever holds the dice. */
    var tint = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    /** Whether a tap should ask for a roll. Bots roll on their own. */
    var rollable = false
        set(value) {
            field = value
            isClickable = value
            alpha = if (value || rolling) 1f else 0.75f
            if (value) breathe() else stopBreathing()
        }

    var onRollRequested: (() -> Unit)? = null

    private var rolling = false
    private var lastStep = -1
    private var tumble = 0f
    private var pop = 0f
    private var roller: ValueAnimator? = null

    // A slow swell while the die waits for a person to roll it.
    private var breath = 0f
    private var breather: ValueAnimator? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val bolt = Path()
    private val random = Random.Default

    init {
        buildBolt()
        setOnClickListener {
            if (rollable && !rolling) onRollRequested?.invoke()
        }
    }

    /**
     * Tumbles through random faces, lands on [result], then calls [onEnd].
     *
     * The faces are picked up front with [result] last, so the die settles on
     * the face it will keep: it shows [result] while it is still turning, and
     * never stops on one face only to jump to another.
     */
    fun roll(result: Int, onEnd: () -> Unit) {
        roller?.cancel()
        rolling = true
        rollable = false

        val faces = tumbleFaces(result)
        var landed = -1f
        lastStep = -1
        roller = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ROLL_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                // Ease out: the die spins and flicks fast at first, then
                // slows into its landing.
                val eased = 1f - (1f - t) * (1f - t) * (1f - t)
                tumble = eased
                val step = min((eased * faces.size).toInt(), faces.size - 1)
                if (step != lastStep) {
                    lastStep = step
                    face = faces[step]
                    if (step == faces.size - 1) landed = t
                }
                // A small pop when the final face lands, shrinking back as the
                // spin settles.
                pop = if (landed >= 0f) POP * (1f - t) / (1f - landed) else 0f
                invalidate()
            }
            doOnEnd {
                rolling = false
                tumble = 0f
                pop = 0f
                face = result
                invalidate()
                onEnd()
            }
            start()
        }
    }

    /** Random faces for the tumble, none repeating the one before, ending on [result]. */
    private fun tumbleFaces(result: Int): IntArray {
        val faces = IntArray(FLICKS)
        faces[FLICKS - 1] = result
        for (i in FLICKS - 2 downTo 0) {
            var next: Int
            do next = random.nextInt(6) + 1 while (next == faces[i + 1])
            faces[i] = next
        }
        return faces
    }

    private fun breathe() {
        if (breather != null) return
        breather = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BREATH_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                breath = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopBreathing() {
        breather?.cancel()
        breather = null
        breath = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        roller?.cancel()
        stopBreathing()
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val inset = size * 0.08f

        canvas.save()
        if (rolling) {
            // A spin reads as "rolling" without a sprite sheet.
            canvas.rotate(tumble * SPIN_DEGREES, width / 2f, height / 2f)
            canvas.scale(1f + pop, 1f + pop, width / 2f, height / 2f)
        }
        if (breath > 0f) {
            val swell = 1f + BREATH_SWELL * breath
            canvas.scale(swell, swell, width / 2f, height / 2f)
        }

        rect.set(inset, inset, size - inset, size - inset)
        fill.color = tint
        canvas.drawRoundRect(rect, size * 0.18f, size * 0.18f, fill)

        // A thick border in the player's colour, so whose die it is reads
        // from across the table.
        rect.inset(size * BORDER, size * BORDER)
        fill.color = 0xFFFAF7EE.toInt()
        canvas.drawRoundRect(rect, size * 0.11f, size * 0.11f, fill)

        if (face in 1..6) drawPips(canvas, rect) else drawBolt(canvas, rect)
        canvas.restore()
    }

    /** Shown on a die that has not been rolled yet this turn: tap to roll. */
    private fun drawBolt(canvas: Canvas, box: RectF) {
        canvas.save()
        canvas.translate(box.centerX(), box.centerY())
        val scale = box.width() * 0.4f
        canvas.scale(scale, scale)
        fill.color = tint
        canvas.drawPath(bolt, fill)
        outline.color = PIP
        outline.strokeWidth = 0.09f
        canvas.drawPath(bolt, outline)
        canvas.restore()
    }

    /** A lightning bolt in unit coordinates, centred on the origin. */
    private fun buildBolt() {
        bolt.reset()
        bolt.moveTo(0.22f, -0.95f)
        bolt.lineTo(-0.62f, 0.12f)
        bolt.lineTo(-0.04f, 0.12f)
        bolt.lineTo(-0.24f, 0.95f)
        bolt.lineTo(0.62f, -0.16f)
        bolt.lineTo(0.04f, -0.16f)
        bolt.close()
    }

    private fun drawPips(canvas: Canvas, box: RectF) {
        fill.color = PIP
        val radius = box.width() * 0.088f
        val left = box.left + box.width() * 0.26f
        val mid = box.centerX()
        val right = box.right - box.width() * 0.26f
        val top = box.top + box.height() * 0.26f
        val middle = box.centerY()
        val bottom = box.bottom - box.height() * 0.26f

        fun pip(x: Float, y: Float) = canvas.drawCircle(x, y, radius, fill)

        if (face % 2 == 1) pip(mid, middle)
        if (face >= 2) {
            pip(left, top)
            pip(right, bottom)
        }
        if (face >= 4) {
            pip(right, top)
            pip(left, bottom)
        }
        if (face == 6) {
            pip(left, middle)
            pip(right, middle)
        }
    }

    private companion object {
        const val ROLL_MS = 620L
        /** Faces shown during a roll, the last being the result. */
        const val FLICKS = 10
        /** A whole number of turns, so the die ends upright. */
        const val SPIN_DEGREES = 720f
        /** Extra scale when the result lands. */
        const val POP = 0.12f

        const val BREATH_MS = 650L
        const val BREATH_SWELL = 0.08f

        /** Width of the coloured border, as a fraction of the die's size. */
        const val BORDER = 0.1f
        const val PIP = 0xFF23262B.toInt()
    }
}
