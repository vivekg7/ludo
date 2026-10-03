package com.crylo.ludo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws a Snakes & Ladders board, its tokens and the seats' names.
 *
 * Laid out like [BoardView]: a square board with a name strip above and below
 * it, the top two seats' names over the left and right of the board and the
 * bottom two under it, where their yards would be on a Ludo board. The
 * strips, the names and the turn marker are measured in [unit], a fifteenth
 * of the board, which is a Ludo cell, so both games' screens read the same.
 *
 * There is nothing to tap: every roll has one move, which the turn loop plays.
 */
class SnakesBoardView(context: Context) : View(context), TableBoard<SnakesState> {

    /** Called each time a walking token reaches the next square on its way. */
    var onSquareReached: (() -> Unit)? = null

    override var names: Array<String> = Board.names
        set(value) {
            field = value
            nameTexts.fill(null)
            invalidate()
        }

    override var namesFaceTable = false
        set(value) {
            field = value
            invalidate()
        }

    override var turn = -1
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Just the board and its tokens, with no name strips or square numbers:
     * the setup screen's picture of the lineup. Set before layout.
     */
    var bare = false
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    private var state: SnakesState? = null

    /** One square of the board, in pixels. */
    private var cell = 0f

    /** A fifteenth of the board, a Ludo cell, which the name strips are measured in. */
    private var unit = 0f

    /** Top of the board itself, below the upper name strip. */
    private var boardTop = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33000000
    }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val number = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        color = NUMBER
    }
    private val emojiPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val nameTexts = arrayOfNulls<CharSequence>(Board.PLAYERS)
    private val numbers = Array(Snakes.FINISH + 1) { it.toString() }

    private val rect = RectF()
    private val path = Path()
    private val clip = Path()

    /**
     * Each snake's body from head to tail, as x, y pairs in squares, worked
     * out once per board: a wave along the line from its head to its tail. A
     * token sliding down a snake follows the same points.
     */
    private var bodies: Map<Int, FloatArray> = emptyMap()

    /** The board [bodies] were worked out for. */
    private var bodiesOf: SnakesLayout? = null

    // Scratch space, reused to keep onDraw allocation-free.
    private val here = FloatArray(2)
    private val xs = FloatArray(Board.PLAYERS)
    private val ys = FloatArray(Board.PLAYERS)
    private val stackIndex = IntArray(Board.PLAYERS)
    private val stackSize = IntArray(Board.PLAYERS)

    // The token on the move: walking square by square to where the roll took
    // it, then climbing or sliding. -1 when nothing is moving.
    private var movingPlayer = -1
    private var moving: Climb? = null
    private var walkedTo = 0f
    private var jumped = -1f
    private var mover: ValueAnimator? = null

    // Per seat: an emoji over its token, and when it appeared, or null.
    private val emojis = arrayOfNulls<String>(Board.PLAYERS)
    private val emojiSince = LongArray(Board.PLAYERS)
    private var reactionTicker: ValueAnimator? = null
    private val popIn = OvershootInterpolator(POP_OVERSHOOT)

    override fun showState(newState: SnakesState) {
        state = newState
        if (newState.layout != bodiesOf) {
            bodiesOf = newState.layout
            bodies = newState.layout.snakes.mapValues { (head, tail) -> body(head, tail) }
        }
        invalidate()
    }

    /**
     * Walks the climb's token to the square its roll reached, one square at a
     * time, then up the ladder or down the snake there. [onJump] runs as it
     * starts to climb or slide, and [onEnd] once it has arrived.
     */
    fun animateMove(climb: Climb, onJump: () -> Unit, onEnd: () -> Unit) {
        mover?.cancel()
        movingPlayer = climb.player
        moving = climb
        walkedTo = climb.from.toFloat()
        jumped = -1f

        val squares = climb.landed - climb.from
        var reached = climb.from
        mover = ValueAnimator.ofFloat(climb.from.toFloat(), climb.landed.toFloat()).apply {
            duration = (squares * MS_PER_SQUARE).coerceIn(MIN_MOVE_MS, MAX_MOVE_MS)
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                walkedTo = it.animatedValue as Float
                if (floor(walkedTo).toInt() > reached) {
                    reached = floor(walkedTo).toInt()
                    onSquareReached?.invoke()
                }
                invalidate()
            }
            doOnEnd {
                if (climb.to == climb.landed) {
                    stopMoving()
                    onEnd()
                } else {
                    onJump()
                    jump(climb, onEnd)
                }
            }
            start()
        }
    }

    /** Up the ladder in a straight line, or down the snake along its body. */
    private fun jump(climb: Climb, onEnd: () -> Unit) {
        jumped = 0f
        val squares = hypot(
            (Snakes.colOf(climb.to) - Snakes.colOf(climb.landed)).toFloat(),
            (Snakes.rowOf(climb.to) - Snakes.rowOf(climb.landed)).toFloat(),
        )
        mover = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (squares * MS_PER_JUMP_SQUARE).toLong().coerceIn(MIN_JUMP_MS, MAX_JUMP_MS)
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                jumped = it.animatedValue as Float
                invalidate()
            }
            doOnEnd {
                stopMoving()
                onEnd()
            }
            start()
        }
    }

    private fun stopMoving() {
        movingPlayer = -1
        moving = null
        jumped = -1f
        invalidate()
    }

    /** Pops [emoji] up over [player]'s token for a moment. It asks nothing of the turn loop. */
    fun react(player: Int, emoji: String) {
        emojis[player] = emoji
        emojiSince[player] = SystemClock.uptimeMillis()
        invalidate()
        if (ValueAnimator.areAnimatorsEnabled()) {
            if (reactionTicker == null) {
                // Only redraws; how far along each reaction is comes from the clock.
                reactionTicker = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1000
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { invalidate() }
                    start()
                }
            }
        } else {
            // With animations off nothing ticks, so the emoji is shown still
            // and a redraw is booked for when it is due to go.
            postDelayed({ invalidate() }, EMOJI_MS + 1)
        }
    }

    override fun clearReactions() {
        emojis.fill(null)
        reactionTicker?.cancel()
        reactionTicker = null
        invalidate()
    }

    override fun cancelAnimations() {
        clearReactions()
        mover?.cancel()
        mover = null
        movingPlayer = -1
        moving = null
        jumped = -1f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAnimations()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // Square, with a name strip above and below; the largest that fits.
        val side = sideFor(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec))
        setMeasuredDimension(side.toInt(), (side * tallness()).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val side = sideFor(w, h)
        cell = side / Snakes.GRID
        unit = side / Board.GRID
        boardTop = if (bare) 0f else LABEL_UNITS * unit
        nameTexts.fill(null)
    }

    private fun sideFor(w: Int, h: Int) = min(w.toFloat(), h / tallness())

    /** Height over width: the board, plus the two strips when they are shown. */
    private fun tallness() = if (bare) 1f else 1f + 2 * LABEL_UNITS / Board.GRID

    override fun onDraw(canvas: Canvas) {
        val game = state ?: return
        val now = SystemClock.uptimeMillis()
        expireReactions(now)
        layOutTokens(game)
        if (!bare) drawLabels(canvas, game)

        canvas.save()
        canvas.translate(0f, boardTop)
        drawSquares(canvas)
        drawLadders(canvas, game.layout)
        drawSnakes(canvas)
        drawTokens(canvas, game)
        if (!bare) drawEmojis(canvas, game, now)
        canvas.restore()
    }

    // --- squares, ladders, snakes -------------------------------------------

    private fun drawSquares(canvas: Canvas) {
        val side = cell * Snakes.GRID
        canvas.save()
        clip.reset()
        clip.addRoundRect(0f, 0f, side, side, cell * CORNER, cell * CORNER, Path.Direction.CW)
        canvas.clipPath(clip)

        number.textSize = cell * NUMBER_SIZE
        for (square in 1..Snakes.FINISH) {
            val col = Snakes.colOf(square)
            val row = Snakes.rowOf(square)
            rect.set(col * cell, row * cell, (col + 1) * cell, (row + 1) * cell)
            fill.color = when {
                square == Snakes.FINISH -> FINISH_SQUARE
                (col + row) % 2 == 0 -> PAPER
                else -> PAPER_DARK
            }
            canvas.drawRect(rect, fill)
            if (!bare) {
                canvas.drawText(
                    numbers[square], rect.left + cell * NUMBER_INSET,
                    rect.top + cell * NUMBER_INSET - number.ascent(), number,
                )
            }
        }
        canvas.restore()

        stroke.color = EDGE
        stroke.strokeWidth = (cell * 0.03f).coerceAtLeast(1f)
        rect.set(0f, 0f, side, side)
        canvas.drawRoundRect(rect, cell * CORNER, cell * CORNER, stroke)
    }

    /** Two rails from foot to top, with a rung every so often between them. */
    private fun drawLadders(canvas: Canvas, layout: SnakesLayout) {
        for ((foot, top) in layout.ladders) {
            val x1 = centreX(foot)
            val y1 = centreY(foot)
            val x2 = centreX(top)
            val y2 = centreY(top)
            val length = hypot(x2 - x1, y2 - y1)
            // Across the ladder, half its width.
            val nx = -(y2 - y1) / length * cell * LADDER_HALF_WIDTH
            val ny = (x2 - x1) / length * cell * LADDER_HALF_WIDTH

            stroke.color = RUNG
            stroke.strokeWidth = cell * 0.07f
            val rungs = max(2, (length / (cell * RUNG_GAP)).toInt())
            for (i in 1 until rungs) {
                val t = i / rungs.toFloat()
                val x = x1 + (x2 - x1) * t
                val y = y1 + (y2 - y1) * t
                canvas.drawLine(x - nx, y - ny, x + nx, y + ny, stroke)
            }
            stroke.color = RAIL
            stroke.strokeWidth = cell * 0.09f
            canvas.drawLine(x1 - nx, y1 - ny, x2 - nx, y2 - ny, stroke)
            canvas.drawLine(x1 + nx, y1 + ny, x2 + nx, y2 + ny, stroke)
        }
    }

    /**
     * Each snake as a body tapering from head to tail, outlined, with a few
     * spots along it and a head with eyes looking back up the board.
     */
    private fun drawSnakes(canvas: Canvas) {
        var index = 0
        for (body in bodies.values) {
            val colour = SNAKE_COLOURS[index++ % SNAKE_COLOURS.size]
            val points = body.size / 2

            // The outline all the way down first, so a fill never sits under
            // the next segment's outline.
            for (pass in 0..1) {
                stroke.color = if (pass == 0) Style.blend(colour, Color.BLACK, OUTLINE) else colour
                for (i in 0 until points - 1) {
                    val width = cell * (HEAD_WIDTH + (TAIL_WIDTH - HEAD_WIDTH) * i / (points - 1f))
                    stroke.strokeWidth = if (pass == 0) width + cell * 0.05f else width
                    canvas.drawLine(
                        body[2 * i] * cell, body[2 * i + 1] * cell,
                        body[2 * i + 2] * cell, body[2 * i + 3] * cell, stroke,
                    )
                }
            }
            fill.color = Style.blend(colour, Color.WHITE, 0.4f)
            for (i in SPOT_EVERY until points - SPOT_EVERY step SPOT_EVERY) {
                val width = HEAD_WIDTH + (TAIL_WIDTH - HEAD_WIDTH) * i / (points - 1f)
                canvas.drawCircle(body[2 * i] * cell, body[2 * i + 1] * cell, cell * width * 0.22f, fill)
            }

            drawHead(canvas, body, colour)
        }
    }

    private fun drawHead(canvas: Canvas, body: FloatArray, colour: Int) {
        val x = body[0] * cell
        val y = body[1] * cell
        // Facing away from the neck.
        var fx = body[0] - body[2]
        var fy = body[1] - body[3]
        val length = hypot(fx, fy).takeIf { it > 0f } ?: 1f
        fx /= length
        fy /= length

        val radius = cell * HEAD_RADIUS
        fill.color = Style.blend(colour, Color.BLACK, OUTLINE)
        canvas.drawCircle(x, y, radius + cell * 0.025f, fill)
        fill.color = colour
        canvas.drawCircle(x, y, radius, fill)

        stroke.color = TONGUE
        stroke.strokeWidth = cell * 0.03f
        val tx = x + fx * radius * 1.7f
        val ty = y + fy * radius * 1.7f
        canvas.drawLine(x + fx * radius, y + fy * radius, tx, ty, stroke)
        canvas.drawLine(tx, ty, tx + (fx - fy) * radius * 0.35f, ty + (fy + fx) * radius * 0.35f, stroke)
        canvas.drawLine(tx, ty, tx + (fx + fy) * radius * 0.35f, ty + (fy - fx) * radius * 0.35f, stroke)

        for (side in intArrayOf(-1, 1)) {
            val ex = x + fx * radius * 0.3f - fy * radius * 0.45f * side
            val ey = y + fy * radius * 0.3f + fx * radius * 0.45f * side
            fill.color = Color.WHITE
            canvas.drawCircle(ex, ey, radius * 0.3f, fill)
            fill.color = Color.BLACK
            canvas.drawCircle(ex + fx * radius * 0.08f, ey + fy * radius * 0.08f, radius * 0.15f, fill)
        }
    }

    // --- tokens ---------------------------------------------------------------

    /**
     * Resolves every seat's token to a pixel centre, then works out which share
     * a square so they can be fanned out instead of hidden behind each other.
     */
    private fun layOutTokens(game: SnakesState) {
        for (player in 0 until Board.PLAYERS) {
            locate(player, game)
            xs[player] = here[0]
            ys[player] = here[1]
        }
        for (player in 0 until Board.PLAYERS) {
            var size = 0
            var index = 0
            for (other in 0 until Board.PLAYERS) {
                if (game.seats[other] == Seat.NONE) continue
                if (abs(xs[other] - xs[player]) > cell * 0.2f) continue
                if (abs(ys[other] - ys[player]) > cell * 0.2f) continue
                if (other < player) index++
                size++
            }
            stackIndex[player] = index
            stackSize[player] = size
        }
    }

    /** Where [player]'s token is drawn, into [here]: part way along while it moves. */
    private fun locate(player: Int, game: SnakesState) {
        val climb = moving
        when {
            player != movingPlayer || climb == null -> {
                here[0] = centreX(game.squares[player])
                here[1] = centreY(game.squares[player])
            }

            jumped < 0f -> {
                val low = floor(walkedTo).toInt()
                val frac = walkedTo - low
                val high = min(low + 1, Snakes.FINISH)
                here[0] = centreX(low) + (centreX(high) - centreX(low)) * frac
                here[1] = centreY(low) + (centreY(high) - centreY(low)) * frac
            }

            climb.bitten -> {
                // Down the snake's own body, head first.
                val body = bodies.getValue(climb.landed)
                val at = jumped * (body.size / 2 - 1)
                val i = min(floor(at).toInt(), body.size / 2 - 2)
                val frac = at - i
                here[0] = (body[2 * i] + (body[2 * i + 2] - body[2 * i]) * frac) * cell
                here[1] = (body[2 * i + 1] + (body[2 * i + 3] - body[2 * i + 1]) * frac) * cell
            }

            else -> {
                here[0] = centreX(climb.landed) + (centreX(climb.to) - centreX(climb.landed)) * jumped
                here[1] = centreY(climb.landed) + (centreY(climb.to) - centreY(climb.landed)) * jumped
            }
        }
    }

    private fun drawTokens(canvas: Canvas, game: SnakesState) {
        // The moving token last, so it passes over the others.
        for (pass in 0..1) {
            for (player in 0 until Board.PLAYERS) {
                if ((player == movingPlayer) != (pass == 1)) continue
                if (game.seats[player] == Seat.NONE) continue
                drawToken(canvas, player)
            }
        }
    }

    private fun drawToken(canvas: Canvas, player: Int) {
        val size = stackSize[player]
        var x = xs[player]
        var y = ys[player]
        var radius = cell * TOKEN_RADIUS
        if (size > 1) {
            // Fan a stack out around its square so every token stays visible.
            val angle = (stackIndex[player] / size.toFloat()) * 2.0 * PI
            x += (cos(angle) * cell * 0.2f).toFloat()
            y += (sin(angle) * cell * 0.2f).toFloat()
            radius *= 0.8f
        }

        // A ring round the token whose turn it is, so it is quick to find.
        if (player == turn && !bare) {
            fill.color = Board.colors[player]
            fill.alpha = 90
            canvas.drawCircle(x, y, radius * 1.45f, fill)
            fill.alpha = 255
        }

        canvas.drawCircle(x, y + radius * 0.14f, radius, shadow)
        fill.color = Board.colors[player]
        canvas.drawCircle(x, y, radius, fill)
        stroke.color = TOKEN_EDGE
        stroke.strokeWidth = (cell * 0.035f).coerceAtLeast(1f)
        canvas.drawCircle(x, y, radius, stroke)
        fill.color = 0x88FFFFFF.toInt()
        canvas.drawCircle(x - radius * 0.28f, y - radius * 0.3f, radius * 0.24f, fill)
    }

    // --- reactions ------------------------------------------------------------

    private fun expireReactions(now: Long) {
        var live = false
        for (player in 0 until Board.PLAYERS) {
            if (emojis[player] != null && now - emojiSince[player] >= EMOJI_MS) emojis[player] = null
            if (emojis[player] != null) live = true
        }
        if (!live && reactionTicker != null) {
            reactionTicker?.cancel()
            reactionTicker = null
        }
    }

    /** Each emoji just over its token, popping in, bobbing, then fading, as on the Ludo board. */
    private fun drawEmojis(canvas: Canvas, game: SnakesState, now: Long) {
        val animated = ValueAnimator.areAnimatorsEnabled()
        for (player in 0 until Board.PLAYERS) {
            val emoji = emojis[player] ?: continue
            if (game.seats[player] == Seat.NONE) continue
            val age = now - emojiSince[player]
            val bob = if (animated) sin(age * BOB_RATE) * cell * BOB_CELLS else 0f
            val x = xs[player]
            val y = ys[player] - cell * EMOJI_RISE + bob
            val scale = if (!animated || age >= POP_MS) 1f else popIn.getInterpolation(age / POP_MS.toFloat())

            canvas.save()
            canvas.scale(scale, scale, x, y)
            emojiPaint.textSize = cell * EMOJI_CELLS
            emojiPaint.alpha = if (!animated) 255 else (255 * ((EMOJI_MS - age) / FADE_MS.toFloat()).coerceIn(0f, 1f)).toInt()
            canvas.drawText(emoji, x, y - (emojiPaint.ascent() + emojiPaint.descent()) / 2, emojiPaint)
            canvas.restore()
        }
    }

    // --- names ----------------------------------------------------------------

    /**
     * Each seat's name centred over its side of the board, the top two above
     * it and the bottom two below, with the turn marker between the current
     * player's name and the board. Placed and sized as [BoardView] places its
     * names over the yards.
     */
    private fun drawLabels(canvas: Canvas, game: SnakesState) {
        val boardBottom = boardTop + Board.GRID * unit
        for (player in 0 until Board.PLAYERS) {
            if (game.seats[player] == Seat.NONE) continue
            val above = Board.yardOrigin[player][1] == 0
            val x = (Board.yardOrigin[player][0] + 3f) * unit
            val current = player == turn

            if (current) {
                val half = unit * 0.36f
                val base = if (above) boardTop - unit * MARKER_BASE else boardBottom + unit * MARKER_BASE
                val tip = if (above) boardTop - unit * MARKER_TIP else boardBottom + unit * MARKER_TIP
                path.reset()
                path.moveTo(x - half, base)
                path.lineTo(x + half, base)
                path.lineTo(x, tip)
                path.close()
                fill.color = Board.colors[player]
                canvas.drawPath(path, fill)
            }

            val y = if (above) boardTop - unit * NAME_DISTANCE else boardBottom + unit * NAME_DISTANCE
            label.textSize = unit * 0.62f
            label.typeface = if (current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            label.color = if (current) Color.WHITE else NAME_IDLE
            val text = nameText(player)
            val flipped = above && namesFaceTable
            if (flipped) {
                canvas.save()
                canvas.rotate(180f, x, y)
            }
            canvas.drawText(text, 0, text.length, x, y - (label.ascent() + label.descent()) / 2, label)
            if (flipped) canvas.restore()
        }
    }

    private fun nameText(player: Int): CharSequence = nameTexts[player] ?: run {
        // Measured bold, the wider of the two faces, so a name never changes
        // length when its turn comes round.
        label.textSize = unit * 0.62f
        label.typeface = Typeface.DEFAULT_BOLD
        TextUtils.ellipsize(names[player], label, unit * 5.6f, TextUtils.TruncateAt.END)
            .also { nameTexts[player] = it }
    }

    private fun centreX(square: Int) = (Snakes.colOf(square) + 0.5f) * cell

    private fun centreY(square: Int) = (Snakes.rowOf(square) + 0.5f) * cell

    private companion object {
        const val MS_PER_SQUARE = 95L
        const val MIN_MOVE_MS = 180L
        const val MAX_MOVE_MS = 700L

        const val MS_PER_JUMP_SQUARE = 110f
        const val MIN_JUMP_MS = 350L
        const val MAX_JUMP_MS = 900L

        // The name strips, in units, as on the Ludo board.
        const val LABEL_UNITS = 2f
        const val MARKER_TIP = 0.2f
        const val MARKER_BASE = 0.78f
        const val NAME_DISTANCE = 1.36f

        // The rest in squares.
        const val CORNER = 0.2f
        const val NUMBER_SIZE = 0.24f
        const val NUMBER_INSET = 0.07f
        const val TOKEN_RADIUS = 0.3f
        const val LADDER_HALF_WIDTH = 0.2f
        const val RUNG_GAP = 0.38f
        const val HEAD_WIDTH = 0.3f
        const val TAIL_WIDTH = 0.07f
        const val HEAD_RADIUS = 0.21f
        const val SPOT_EVERY = 4

        /** How much of a snake's colour is left in its outline, darkened towards black. */
        const val OUTLINE = 0.55f

        /** Points along each snake's body; enough for the longest to look smooth. */
        const val BODY_POINTS = 48

        /** How far the body swings either side of the line from head to tail. */
        const val WAVE = 0.28f

        /** Length of one full wave, in squares. */
        const val WAVELENGTH = 2.4f

        const val EMOJI_MS = 1800L
        const val POP_MS = 260L
        const val POP_OVERSHOOT = 3f
        const val FADE_MS = 350L
        const val BOB_RATE = 0.009f
        const val BOB_CELLS = 0.06f
        const val EMOJI_CELLS = 0.8f
        const val EMOJI_RISE = 0.7f

        const val PAPER = 0xFFF6F1E4.toInt()
        const val PAPER_DARK = 0xFFE8DDC2.toInt()
        const val FINISH_SQUARE = 0xFFFFD54F.toInt()
        const val EDGE = 0xFF8C8674.toInt()
        const val NUMBER = 0x8C000000.toInt()
        const val RAIL = 0xFF795548.toInt()
        const val RUNG = 0xFFA1887F.toInt()
        const val TONGUE = 0xFFD32F2F.toInt()
        const val TOKEN_EDGE = 0xFF2B2B2B.toInt()
        const val NAME_IDLE = 0xFF9AA3AF.toInt()

        /** Snakes take turns through these, none of them a seat's colour. */
        val SNAKE_COLOURS = intArrayOf(
            0xFF689F38.toInt(), // olive
            0xFF8E24AA.toInt(), // purple
            0xFFEF6C00.toInt(), // orange
            0xFF00897B.toInt(), // teal
            0xFF6D4C41.toInt(), // brown
        )

        /**
         * A snake from [head] to [tail] as [BODY_POINTS] x, y pairs in squares:
         * the straight line between their centres, swung side to side. The
         * swing eases in from the head and out at the tail, so the head sits
         * on its square and the tail ends on its own.
         */
        fun body(head: Int, tail: Int): FloatArray {
            val hx = Snakes.colOf(head) + 0.5f
            val hy = Snakes.rowOf(head) + 0.5f
            val dx = Snakes.colOf(tail) + 0.5f - hx
            val dy = Snakes.rowOf(tail) + 0.5f - hy
            val length = hypot(dx, dy)
            val nx = -dy / length
            val ny = dx / length
            val waves = max(1, (length / WAVELENGTH).roundToInt())
            val out = FloatArray(BODY_POINTS * 2)
            for (i in 0 until BODY_POINTS) {
                val t = i / (BODY_POINTS - 1f)
                val swing = sin(t * waves * 2 * PI).toFloat() * WAVE * sin(t * PI).toFloat()
                out[2 * i] = hx + dx * t + nx * swing
                out[2 * i + 1] = hy + dy * t + ny * swing
            }
            return out
        }
    }
}
