package com.crylo.ludo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the whole board and every token, and turns taps into token choices.
 *
 * All layout is done in board cells (0..15 on each axis) and scaled by [cell]
 * at draw time, so the same code works at any size without a single dp.
 */
class BoardView(context: Context) : View(context) {

    /** Called when the player taps one of the currently highlighted tokens. */
    var onTokenPicked: ((Int) -> Unit)? = null

    private var state: GameState? = null
    private var highlights = IntArray(0)

    private var cell = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33000000
    }

    private val rect = RectF()
    private val path = Path()
    private val star = Path()

    // Scratch space for Board.locate, reused to keep onDraw allocation-free.
    private val here = FloatArray(2)
    private val there = FloatArray(2)
    private val xs = FloatArray(Board.TOKENS)
    private val ys = FloatArray(Board.TOKENS)
    private val stackIndex = IntArray(Board.TOKENS)
    private val stackSize = IntArray(Board.TOKENS)

    /** Ring index -> player, for painting the four coloured entry squares. */
    private val startOwner = HashMap<Int, Int>(4).apply {
        Board.start.forEachIndexed { player, index -> put(index, player) }
    }

    // A token sliding along its path. -1 when nothing is moving.
    private var movingToken = -1
    private var movingAt = 0f
    private var movingTo = 0
    private var mover: ValueAnimator? = null

    // Tokens held at their pre-capture square until the capturing token lands.
    private var frozenTokens = IntArray(0)
    private var frozenSteps = IntArray(0)

    private var pulse = 0f
    private var pulser: ValueAnimator? = null

    init {
        buildStar()
    }

    fun showState(newState: GameState) {
        state = newState
        invalidate()
    }

    fun setHighlights(tokens: IntArray) {
        highlights = tokens
        if (tokens.isEmpty()) stopPulse() else startPulse()
        invalidate()
    }

    fun clearHighlights() = setHighlights(IntArray(0))

    /**
     * Slides [token] from one position to another, one board square at a time
     * so it visibly walks the corners of the path rather than cutting across.
     * Captured tokens stay put until the move lands, then snap to their yard.
     */
    fun animateMove(
        token: Int,
        from: Int,
        to: Int,
        captured: IntArray,
        capturedFrom: IntArray,
        onEnd: () -> Unit,
    ) {
        mover?.cancel()
        movingToken = token
        movingTo = to
        frozenTokens = captured
        frozenSteps = capturedFrom

        val squares = (to - from).coerceAtLeast(1)
        mover = ValueAnimator.ofFloat(from.toFloat(), to.toFloat()).apply {
            duration = (squares * MS_PER_SQUARE).coerceIn(MIN_MOVE_MS, MAX_MOVE_MS)
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                movingAt = it.animatedValue as Float
                invalidate()
            }
            doOnEnd {
                movingToken = -1
                frozenTokens = IntArray(0)
                frozenSteps = IntArray(0)
                invalidate()
                onEnd()
            }
            start()
        }
    }

    fun cancelAnimations() {
        mover?.cancel()
        mover = null
        movingToken = -1
        stopPulse()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAnimations()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // The board is always square; take the smaller of whatever we are given.
        val size = min(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec))
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cell = min(w, h) / Board.GRID.toFloat()
        stroke.strokeWidth = (cell * 0.05f).coerceAtLeast(1f)
    }

    override fun onDraw(canvas: Canvas) {
        val game = state ?: return

        drawPaper(canvas)
        drawYards(canvas)
        drawRing(canvas)
        drawHomeRuns(canvas)
        drawCentre(canvas)
        layOutTokens(game)
        drawTokens(canvas, game)
    }

    private fun drawPaper(canvas: Canvas) {
        fill.color = PAPER
        rect.set(0f, 0f, Board.GRID * cell, Board.GRID * cell)
        canvas.drawRoundRect(rect, cell * 0.3f, cell * 0.3f, fill)
    }

    private fun drawYards(canvas: Canvas) {
        for (player in 0 until Board.PLAYERS) {
            val o = Board.yardOrigin[player]
            fill.color = Board.colors[player]
            rect.set(o[0] * cell, o[1] * cell, (o[0] + 6) * cell, (o[1] + 6) * cell)
            canvas.drawRoundRect(rect, cell * 0.35f, cell * 0.35f, fill)

            fill.color = PAPER
            rect.inset(cell * 0.75f, cell * 0.75f)
            canvas.drawRoundRect(rect, cell * 0.25f, cell * 0.25f, fill)

            // Empty seats are greyed out so it is obvious who is playing.
            if (state?.seats?.get(player) == Seat.NONE) {
                fill.color = 0xB3F6F1E4.toInt()
                rect.set(o[0] * cell, o[1] * cell, (o[0] + 6) * cell, (o[1] + 6) * cell)
                canvas.drawRoundRect(rect, cell * 0.35f, cell * 0.35f, fill)
            }
        }
    }

    private fun drawRing(canvas: Canvas) {
        stroke.color = GRID_LINE
        Board.ring.forEachIndexed { index, packed ->
            val owner = startOwner[index]
            fill.color = when {
                owner != null -> Board.colors[owner]
                index in Board.safe -> SAFE_TINT
                else -> PAPER
            }
            square(Board.colOf(packed), Board.rowOf(packed))
            canvas.drawRect(rect, fill)
            canvas.drawRect(rect, stroke)

            if (owner == null && index in Board.safe) drawStar(canvas)
        }
    }

    private fun drawHomeRuns(canvas: Canvas) {
        stroke.color = GRID_LINE
        for (player in 0 until Board.PLAYERS) {
            fill.color = Board.colors[player]
            for (packed in Board.homeRun[player]) {
                square(Board.colOf(packed), Board.rowOf(packed))
                canvas.drawRect(rect, fill)
                canvas.drawRect(rect, stroke)
            }
        }
    }

    /** The centre square, as four triangles pointing at the middle. */
    private fun drawCentre(canvas: Canvas) {
        val lo = 6 * cell
        val hi = 9 * cell
        val mx = 7.5f * cell
        // Each player's triangle sits on the edge its own home run arrives from.
        val corners = arrayOf(
            floatArrayOf(lo, lo, lo, hi), // red, from the left
            floatArrayOf(lo, lo, hi, lo), // green, from the top
            floatArrayOf(hi, lo, hi, hi), // yellow, from the right
            floatArrayOf(lo, hi, hi, hi), // blue, from the bottom
        )
        for (player in 0 until Board.PLAYERS) {
            val c = corners[player]
            path.reset()
            path.moveTo(c[0], c[1])
            path.lineTo(c[2], c[3])
            path.lineTo(mx, mx)
            path.close()
            fill.color = Board.colors[player]
            canvas.drawPath(path, fill)
        }
        stroke.color = GRID_LINE
        rect.set(lo, lo, hi, hi)
        canvas.drawRect(rect, stroke)
    }

    /**
     * Resolves every token to a pixel centre, then works out which tokens share
     * a square so they can be fanned out instead of hidden behind each other.
     */
    private fun layOutTokens(game: GameState) {
        for (token in 0 until Board.TOKENS) {
            val player = Board.owner(token)
            val slot = token % Board.TOKENS_PER_PLAYER
            val steps = stepsShownFor(token, game)

            if (token == movingToken) {
                val low = floor(movingAt).toInt()
                val frac = movingAt - low
                Board.locate(player, low, slot, here)
                Board.locate(player, min(low + 1, movingTo), slot, there)
                xs[token] = (here[0] + (there[0] - here[0]) * frac) * cell
                ys[token] = (here[1] + (there[1] - here[1]) * frac) * cell
            } else {
                Board.locate(player, steps, slot, here)
                xs[token] = here[0] * cell
                ys[token] = here[1] * cell
            }
        }

        for (token in 0 until Board.TOKENS) {
            var size = 0
            var index = 0
            for (other in 0 until Board.TOKENS) {
                if (game.seats[Board.owner(other)] == Seat.NONE) continue
                if (abs(xs[other] - xs[token]) > cell * 0.2f) continue
                if (abs(ys[other] - ys[token]) > cell * 0.2f) continue
                if (other < token) index++
                size++
            }
            stackIndex[token] = index
            stackSize[token] = size
        }
    }

    private fun stepsShownFor(token: Int, game: GameState): Int {
        val frozen = frozenTokens.indexOf(token)
        return if (frozen >= 0) frozenSteps[frozen] else game.steps[token]
    }

    private fun drawTokens(canvas: Canvas, game: GameState) {
        // Draw the moving token last so it passes over the others.
        for (pass in 0..1) {
            for (token in 0 until Board.TOKENS) {
                if ((token == movingToken) != (pass == 1)) continue
                val player = Board.owner(token)
                if (game.seats[player] == Seat.NONE) continue
                drawToken(canvas, token, player)
            }
        }
    }

    private fun drawToken(canvas: Canvas, token: Int, player: Int) {
        val size = stackSize[token]
        var x = xs[token]
        var y = ys[token]
        var radius = cell * 0.36f

        if (size > 1) {
            // Fan a stack out around its square so every token stays visible.
            val angle = (stackIndex[token] / size.toFloat()) * 2.0 * Math.PI
            val offset = cell * 0.16f
            x += (cos(angle) * offset).toFloat()
            y += (sin(angle) * offset).toFloat()
            radius *= 0.8f
        }

        if (token in highlights) {
            fill.color = Board.colors[player]
            fill.alpha = 90
            canvas.drawCircle(x, y, radius * (1.45f + 0.25f * pulse), fill)
            fill.alpha = 255
        }

        canvas.drawCircle(x, y + radius * 0.14f, radius, shadow)

        fill.color = Board.colors[player]
        canvas.drawCircle(x, y, radius, fill)

        stroke.color = TOKEN_EDGE
        canvas.drawCircle(x, y, radius, stroke)

        fill.color = 0x88FFFFFF.toInt()
        canvas.drawCircle(x - radius * 0.28f, y - radius * 0.3f, radius * 0.24f, fill)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return false
        if (highlights.isEmpty() || movingToken >= 0) return false

        var picked = -1
        var closest = cell * 0.85f
        for (token in highlights) {
            val distance = hypot(event.x - xs[token], event.y - ys[token])
            if (distance < closest) {
                closest = distance
                picked = token
            }
        }
        if (picked < 0) return false

        performClick()
        onTokenPicked?.invoke(picked)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun square(col: Int, row: Int) {
        rect.set(col * cell, row * cell, (col + 1) * cell, (row + 1) * cell)
    }

    private fun drawStar(canvas: Canvas) {
        canvas.save()
        canvas.translate(rect.centerX(), rect.centerY())
        canvas.scale(cell * 0.34f, cell * 0.34f)
        fill.color = STAR
        canvas.drawPath(star, fill)
        canvas.restore()
    }

    private fun buildStar() {
        star.reset()
        for (point in 0 until 10) {
            val radius = if (point % 2 == 0) 1f else 0.42f
            val angle = -Math.PI / 2 + point * Math.PI / 5
            val x = (cos(angle) * radius).toFloat()
            val y = (sin(angle) * radius).toFloat()
            if (point == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
    }

    private fun startPulse() {
        if (pulser != null) return
        pulser = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulser?.cancel()
        pulser = null
        pulse = 0f
    }

    private companion object {
        const val MS_PER_SQUARE = 95L
        const val MIN_MOVE_MS = 180L
        const val MAX_MOVE_MS = 700L

        const val PAPER = 0xFFF6F1E4.toInt()
        const val GRID_LINE = 0xFF8C8674.toInt()
        const val SAFE_TINT = 0xFFE4DCC4.toInt()
        const val STAR = 0xFF9A9079.toInt()
        const val TOKEN_EDGE = 0xFF2B2B2B.toInt()
    }
}

/** ValueAnimator.doOnEnd without pulling in androidx.core. */
internal fun ValueAnimator.doOnEnd(action: () -> Unit) {
    addListener(object : android.animation.AnimatorListenerAdapter() {
        private var cancelled = false

        override fun onAnimationCancel(animation: android.animation.Animator) {
            cancelled = true
        }

        override fun onAnimationEnd(animation: android.animation.Animator) {
            if (!cancelled) action()
        }
    })
}
