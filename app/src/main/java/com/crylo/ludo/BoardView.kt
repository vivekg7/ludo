package com.crylo.ludo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.abs
import kotlin.math.atan2
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
 *
 * Above and below the board is a strip [LABEL_CELLS] tall holding each seat's
 * name over its own yard, and the turn marker. They are drawn here rather than
 * as separate views so they stay lined up with the yards at any aspect ratio.
 */
class BoardView(context: Context) : View(context) {

    /** Called when the player taps one of the currently highlighted tokens. */
    var onTokenPicked: ((Int) -> Unit)? = null

    /** Called each time a sliding token reaches the next square on its way. */
    var onSquareReached: (() -> Unit)? = null

    /** Called as a capturing token lands, when its victims start back home. */
    var onCapture: (() -> Unit)? = null

    /** What each seat is called, in seat order. */
    var names: Array<String> = Board.names
        set(value) {
            field = value
            nameTexts.fill(null)
            invalidate()
        }

    /**
     * Turns the names above the board upside down, to face the players sitting
     * at that end of a phone lying flat on the table.
     */
    var namesFaceTable = false
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Seat the turn marker points at, or -1 for none. Set by the turn loop
     * rather than read from the state, which passes the dice on before the
     * previous player's token has finished sliding.
     */
    var turn = -1
        set(value) {
            field = value
            invalidate()
        }

    private var state: GameState? = null
    private var highlights = IntArray(0)

    private var cell = 0f

    /** Top of the board itself, below the upper name strip. */
    private var boardTop = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33000000
    }

    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    // Label text is rebuilt only when it changes, not on every frame.
    private val nameTexts = arrayOfNulls<CharSequence>(Board.PLAYERS)
    private val progressTexts = arrayOfNulls<String>(Board.PLAYERS)
    private val progressShown = IntArray(Board.PLAYERS) { -1 }

    private val rect = RectF()
    private val path = Path()
    private val star = Path()
    private val arrow = Path()

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
    private var mover: ValueAnimator? = null

    // Captured tokens, held at the square they were taken on until the
    // capturing token lands, then walked back along their own path home.
    private var frozenTokens = IntArray(0)
    private var frozenSteps = IntArray(0)
    private var returned = 0f
    private var returner: ValueAnimator? = null

    // Where each highlighted token would land, as steps, and whether it captures there.
    private val landing = IntArray(Board.TOKENS)
    private val landingCaptures = BooleanArray(Board.TOKENS)
    private val landingX = FloatArray(Board.TOKENS)
    private val landingY = FloatArray(Board.TOKENS)

    private var pulse = 0f
    private var pulser: ValueAnimator? = null

    init {
        buildStar()
        buildArrow()
    }

    fun showState(newState: GameState) {
        state = newState
        invalidate()
    }

    fun setHighlights(tokens: IntArray) {
        highlights = tokens
        val game = state
        if (game != null) {
            for (token in tokens) {
                landing[token] = Rules.targetOf(game.steps[token], game.die)
                landingCaptures[token] =
                    landing[token] > 0 && Rules.victims(game, Board.owner(token), landing[token]).isNotEmpty()
            }
        }
        if (tokens.isEmpty()) stopPulse() else startPulse()
        invalidate()
    }

    fun clearHighlights() = setHighlights(IntArray(0))

    /**
     * Slides [token] from one position to another, one board square at a time
     * so it visibly walks the corners of the path rather than cutting across.
     * Captured tokens stay put until the move lands, then walk backwards along
     * the way they came to their yard; [onEnd] runs once they are home.
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
        returner?.cancel()
        movingToken = token
        frozenTokens = captured
        frozenSteps = capturedFrom
        returned = 0f

        val squares = (to - from).coerceAtLeast(1)
        var reached = from
        mover = ValueAnimator.ofFloat(from.toFloat(), to.toFloat()).apply {
            duration = (squares * MS_PER_SQUARE).coerceIn(MIN_MOVE_MS, MAX_MOVE_MS)
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                movingAt = it.animatedValue as Float
                if (floor(movingAt).toInt() > reached) {
                    reached = floor(movingAt).toInt()
                    onSquareReached?.invoke()
                }
                invalidate()
            }
            doOnEnd {
                movingToken = -1
                if (frozenTokens.isEmpty()) {
                    invalidate()
                    onEnd()
                } else {
                    onCapture?.invoke()
                    sendHome(onEnd)
                }
            }
            start()
        }
    }

    /** Walks the captured tokens back down their own path, all arriving together. */
    private fun sendHome(onEnd: () -> Unit) {
        val farthest = frozenSteps.max()
        returner = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (farthest * MS_PER_RETURN_SQUARE).coerceIn(MIN_RETURN_MS, MAX_RETURN_MS)
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                returned = it.animatedValue as Float
                invalidate()
            }
            doOnEnd {
                frozenTokens = IntArray(0)
                frozenSteps = IntArray(0)
                returned = 0f
                invalidate()
                onEnd()
            }
            start()
        }
    }

    fun cancelAnimations() {
        mover?.cancel()
        mover = null
        returner?.cancel()
        returner = null
        movingToken = -1
        frozenTokens = IntArray(0)
        frozenSteps = IntArray(0)
        stopPulse()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAnimations()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // The board is always square, with a name strip above and below it;
        // take the largest cell that fits whatever we are given.
        val size = cellFor(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec))
        setMeasuredDimension((size * Board.GRID).toInt(), (size * TALL_CELLS).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cell = cellFor(w, h)
        boardTop = LABEL_CELLS * cell
        stroke.strokeWidth = (cell * 0.05f).coerceAtLeast(1f)
        // Ellipsized for the new width on the next draw.
        nameTexts.fill(null)
    }

    private fun cellFor(w: Int, h: Int) = min(w / Board.GRID.toFloat(), h / TALL_CELLS)

    override fun onDraw(canvas: Canvas) {
        val game = state ?: return

        // Laid out first: the progress under each name follows the tokens as drawn.
        layOutTokens(game)
        drawLabels(canvas, game)

        canvas.save()
        canvas.translate(0f, boardTop)
        drawPaper(canvas)
        drawYards(canvas)
        drawRing(canvas)
        drawArrows(canvas)
        drawHomeRuns(canvas)
        drawCentre(canvas)
        drawTokens(canvas, game)
        drawLandings(canvas)
        canvas.restore()
    }

    /**
     * Each seat's name centred over its own yard, the top two above the board
     * and the bottom two below it. Working outwards from the board edge: the
     * turn marker for the current player, how far their tokens have travelled,
     * then the name.
     */
    private fun drawLabels(canvas: Canvas, game: GameState) {
        val boardBottom = boardTop + Board.GRID * cell
        for (player in 0 until Board.PLAYERS) {
            if (game.seats[player] == Seat.NONE) continue
            val above = Board.yardOrigin[player][1] == 0
            val x = (Board.yardOrigin[player][0] + 3f) * cell
            val current = player == turn

            if (current) {
                val half = cell * 0.36f
                val base = if (above) boardTop - cell * MARKER_BASE else boardBottom + cell * MARKER_BASE
                val tip = if (above) boardTop - cell * MARKER_TIP else boardBottom + cell * MARKER_TIP
                path.reset()
                path.moveTo(x - half, base)
                path.lineTo(x + half, base)
                path.lineTo(x, tip)
                path.close()
                fill.color = Board.colors[player]
                canvas.drawPath(path, fill)
            }

            // Upside down, a point drawn at `d` from the top of the strip lands
            // `d` from the board edge, so the same distances serve both ways.
            val flipped = above && namesFaceTable
            if (flipped) {
                canvas.save()
                canvas.rotate(180f, x, boardTop / 2)
            }
            fun rowY(distance: Float) = when {
                flipped -> cell * distance
                above -> boardTop - cell * distance
                else -> boardBottom + cell * distance
            }

            label.textSize = cell * 0.42f
            label.typeface = Typeface.DEFAULT
            label.color = if (current) PROGRESS_CURRENT else PROGRESS_IDLE
            drawCentred(canvas, progressText(game, player), x, rowY(PROGRESS_DISTANCE))

            label.textSize = cell * 0.62f
            label.typeface = if (current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            label.color = if (current) Color.WHITE else NAME_IDLE
            drawCentred(canvas, nameText(player), x, rowY(NAME_DISTANCE))

            if (flipped) canvas.restore()
        }
    }

    private fun drawCentred(canvas: Canvas, text: CharSequence, x: Float, y: Float) {
        canvas.drawText(text, 0, text.length, x, y - (label.ascent() + label.descent()) / 2, label)
    }

    private fun nameText(player: Int): CharSequence = nameTexts[player] ?: run {
        // Measured bold, the wider of the two faces, so a name never changes
        // length when its turn comes round.
        label.textSize = cell * 0.62f
        label.typeface = Typeface.DEFAULT_BOLD
        TextUtils.ellipsize(names[player], label, cell * 5.6f, TextUtils.TruncateAt.END)
            .also { nameTexts[player] = it }
    }

    /**
     * "34% · 1/4 home", from the tokens where they are drawn rather than where
     * the state has them, so it counts up as a token walks and down as a
     * captured one is walked home.
     */
    private fun progressText(game: GameState, player: Int): String {
        val first = Board.firstToken(player)
        var total = 0
        var home = 0
        for (token in first until first + Board.TOKENS_PER_PLAYER) {
            val at = shownAt(token, game).toInt()
            total += at
            if (at == Board.FINISH) home++
        }
        val percent = Board.travelPercent(total)
        val key = percent * (Board.TOKENS_PER_PLAYER + 1) + home
        if (progressShown[player] != key || progressTexts[player] == null) {
            progressShown[player] = key
            progressTexts[player] = context.getString(R.string.progress, percent, home, Board.TOKENS_PER_PLAYER)
        }
        return progressTexts[player]!!
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

            // A pocket for each token, so a yard reads as waiting for its
            // tokens to come back rather than as empty once they have left.
            stroke.color = Board.colors[player]
            stroke.strokeWidth = cell * 0.09f
            fill.color = blend(Board.colors[player], PAPER, POCKET_TINT)
            for (slot in 0 until Board.TOKENS_PER_PLAYER) {
                Board.locate(player, 0, slot, here)
                canvas.drawCircle(here[0] * cell, here[1] * cell, cell * POCKET_RADIUS, fill)
                canvas.drawCircle(here[0] * cell, here[1] * cell, cell * POCKET_RADIUS, stroke)
            }
            stroke.strokeWidth = (cell * 0.05f).coerceAtLeast(1f)

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

    /**
     * An arrow in each player's colour on the last ring square before its home
     * run, pointing the way in, so it is clear where a token turns off; and a
     * dark one on each start square, pointing the way tokens travel.
     */
    private fun drawArrows(canvas: Canvas) {
        for (player in 0 until Board.PLAYERS) {
            val turnOff = Board.ring[Board.ringIndex(player, Board.LAST_RING_STEP)]
            drawArrow(canvas, turnOff, Board.homeRun[player][0], Board.colors[player], 1f)

            val start = Board.ring[Board.start[player]]
            drawArrow(canvas, start, Board.ring[(Board.start[player] + 1) % Board.RING_CELLS], START_ARROW, 0.8f)
        }
    }

    /** An arrow on the square [from], pointing at the neighbouring square [toward]. */
    private fun drawArrow(canvas: Canvas, from: Int, toward: Int, color: Int, size: Float) {
        val dx = (Board.colOf(toward) - Board.colOf(from)).toFloat()
        val dy = (Board.rowOf(toward) - Board.rowOf(from)).toFloat()
        canvas.save()
        canvas.translate((Board.colOf(from) + 0.5f) * cell, (Board.rowOf(from) + 0.5f) * cell)
        canvas.rotate(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat())
        canvas.scale(cell * size, cell * size)
        fill.color = color
        canvas.drawPath(arrow, fill)
        canvas.restore()
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
            val at = shownAt(token, game)

            // Part way between two squares, as a moving or returning token is.
            val low = floor(at).toInt()
            val frac = at - low
            Board.locate(player, low, slot, here)
            if (frac > 0f) Board.locate(player, low + 1, slot, there)
            xs[token] = (here[0] + (there[0] - here[0]) * frac) * cell
            ys[token] = (here[1] + (there[1] - here[1]) * frac) * cell
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

    /** Where a token is drawn, in steps: fractional while it moves or returns. */
    private fun shownAt(token: Int, game: GameState): Float {
        if (token == movingToken) return movingAt
        val frozen = frozenTokens.indexOf(token)
        return if (frozen >= 0) frozenSteps[frozen] * (1f - returned) else game.steps[token].toFloat()
    }

    private fun drawTokens(canvas: Canvas, game: GameState) {
        // Draw moving and returning tokens last so they pass over the others.
        for (pass in 0..1) {
            for (token in 0 until Board.TOKENS) {
                val travelling = token == movingToken || (returned > 0f && token in frozenTokens)
                if (travelling != (pass == 1)) continue
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

    /**
     * Marks the square each highlighted token would land on: a dot, or a
     * crosshair where the move captures. Drawn over the tokens so a crosshair
     * shows around the token it would take.
     */
    private fun drawLandings(canvas: Canvas) {
        if (highlights.isEmpty() || movingToken >= 0) return
        for (token in highlights) {
            val to = landing[token]
            if (to <= 0) continue
            val player = Board.owner(token)
            Board.locate(player, to, token % Board.TOKENS_PER_PLAYER, here)
            val x = here[0] * cell
            val y = here[1] * cell
            landingX[token] = x
            landingY[token] = y

            stroke.color = TOKEN_EDGE
            if (landingCaptures[token]) {
                val r = cell * (0.44f + 0.05f * pulse)
                stroke.strokeWidth = cell * 0.09f
                canvas.drawCircle(x, y, r, stroke)
                for (i in 0 until 4) {
                    val dx = if (i < 2) (if (i == 0) 1f else -1f) else 0f
                    val dy = if (i >= 2) (if (i == 2) 1f else -1f) else 0f
                    canvas.drawLine(x + dx * r * 0.7f, y + dy * r * 0.7f, x + dx * r * 1.3f, y + dy * r * 1.3f, stroke)
                }
            } else {
                stroke.alpha = 150 + (105 * pulse).toInt()
                stroke.strokeWidth = cell * 0.07f
                canvas.drawCircle(x, y, cell * 0.3f, stroke)
                stroke.alpha = 255
                fill.color = Board.colors[player]
                canvas.drawCircle(x, y, cell * 0.13f, fill)
                stroke.strokeWidth = cell * 0.04f
                canvas.drawCircle(x, y, cell * 0.13f, stroke)
            }
        }
        stroke.strokeWidth = (cell * 0.05f).coerceAtLeast(1f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return false
        if (highlights.isEmpty() || movingToken >= 0) return false

        // Token centres are in board space, which starts below the name strip.
        val x = event.x
        val y = event.y - boardTop
        var picked = -1
        var closest = cell * 0.85f
        for (token in highlights) {
            val distance = hypot(x - xs[token], y - ys[token])
            if (distance < closest) {
                closest = distance
                picked = token
            }
        }
        // Failing a token, a tap on where one would land picks that token.
        if (picked < 0) {
            closest = cell * 0.6f
            for (token in highlights) {
                if (landing[token] <= 0) continue
                val distance = hypot(x - landingX[token], y - landingY[token])
                if (distance < closest) {
                    closest = distance
                    picked = token
                }
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

    /** A right-pointing arrow in cell units, centred on the origin. */
    private fun buildArrow() {
        arrow.reset()
        arrow.moveTo(-0.36f, -0.09f)
        arrow.lineTo(0.02f, -0.09f)
        arrow.lineTo(0.02f, -0.26f)
        arrow.lineTo(0.36f, 0f)
        arrow.lineTo(0.02f, 0.26f)
        arrow.lineTo(0.02f, 0.09f)
        arrow.lineTo(-0.36f, 0.09f)
        arrow.close()
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

        const val MS_PER_RETURN_SQUARE = 22L
        const val MIN_RETURN_MS = 320L
        const val MAX_RETURN_MS = 1000L

        /** Height of each name strip, in cells. */
        const val LABEL_CELLS = 2.7f
        const val TALL_CELLS = Board.GRID + 2 * LABEL_CELLS

        // Distances out from the board edge, in cells, of what the strip holds.
        const val MARKER_TIP = 0.18f
        const val MARKER_BASE = 0.72f
        const val PROGRESS_DISTANCE = 1.22f
        const val NAME_DISTANCE = 1.98f

        const val POCKET_RADIUS = 0.6f
        const val POCKET_TINT = 0.28f

        const val PAPER = 0xFFF6F1E4.toInt()
        const val GRID_LINE = 0xFF8C8674.toInt()
        const val SAFE_TINT = 0xFFE4DCC4.toInt()
        const val STAR = 0xFF9A9079.toInt()
        const val TOKEN_EDGE = 0xFF2B2B2B.toInt()
        const val NAME_IDLE = 0xFF9AA3AF.toInt()
        const val PROGRESS_IDLE = 0xFF6B7380.toInt()
        const val PROGRESS_CURRENT = 0xFFC9CFD6.toInt()
        const val START_ARROW = 0x70000000

        /** [top] laid over [bottom] at the given opacity, both fully opaque. */
        fun blend(top: Int, bottom: Int, amount: Float): Int {
            fun mix(shift: Int): Int {
                val a = (top shr shift) and 0xFF
                val b = (bottom shr shift) and 0xFF
                return ((a * amount + b * (1 - amount)) + 0.5f).toInt() shl shift
            }
            return (0xFF shl 24) or mix(16) or mix(8) or mix(0)
        }
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
