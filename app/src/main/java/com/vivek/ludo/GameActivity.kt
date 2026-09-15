package com.vivek.ludo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

/**
 * The game screen, and the turn loop that drives it.
 *
 * Every transition goes through [beginTurn], which reads the current state and
 * decides what should happen next. That means a game restored from disk
 * mid-turn resumes exactly where it left off, with no separate resume path.
 */
class GameActivity : Activity() {

    private lateinit var state: GameState
    private lateinit var board: BoardView
    private lateinit var die: DieView
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var newGame: Button

    private val handler = Handler(Looper.getMainLooper())
    private val random = Random.Default

    /** True while an animation or a scheduled step owns the turn. */
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        state = (if (intent.getBooleanExtra(EXTRA_RESUME, false)) Saves.load(this) else null)
            ?: GameState(seatsFromIntent())

        setContentView(buildUi())

        board.onTokenPicked = { token -> play(token) }
        die.onRollRequested = { roll() }

        beginTurn()
    }

    private fun seatsFromIntent(): Array<Seat> {
        val raw = intent.getIntArrayExtra(EXTRA_SEATS)
        return Array(Board.PLAYERS) { player ->
            raw?.getOrNull(player)?.let { Seat.entries[it] } ?: Seat.NONE
        }
    }

    // --- turn loop ---------------------------------------------------------

    private fun beginTurn() {
        board.showState(state)

        if (state.winner >= 0) {
            announceWinner()
            return
        }

        die.tint = Board.colors[state.current]
        die.face = state.die

        // A restored game can come back with the dice already rolled.
        if (state.die != 0) {
            resolveRoll(state.die)
            return
        }

        status.text = getString(R.string.turn_of, Board.names[state.current])
        if (state.isBot(state.current)) {
            hint.text = getString(R.string.thinking)
            die.rollable = false
            busy = true
            handler.postDelayed({ busy = false; roll() }, BOT_THINK_MS)
        } else {
            hint.text = getString(R.string.tap_to_roll)
            die.rollable = true
        }
    }

    private fun roll() {
        if (busy || state.die != 0) return
        busy = true
        die.rollable = false

        val face = random.nextInt(6) + 1
        die.roll(face) {
            state.die = face
            state.sixStreak = if (face == 6) state.sixStreak + 1 else 0
            resolveRoll(face)
        }
    }

    private fun resolveRoll(face: Int) {
        if (state.sixStreak >= Rules.SIX_STREAK_LIMIT) {
            hint.text = getString(R.string.three_sixes)
            handOver()
            return
        }

        val moves = Rules.legalMoves(state, face)
        val bot = state.isBot(state.current)
        when {
            moves.isEmpty() -> {
                hint.text = getString(R.string.no_move, face)
                handOver()
            }

            // With a single option there is nothing to decide, so play it
            // rather than making the player tap the only legal token.
            moves.size == 1 || bot -> {
                val token = if (bot) Bot.chooseMove(state, face, moves, random) else moves[0]
                busy = true
                handler.postDelayed({ busy = false; play(token) }, if (bot) BOT_THINK_MS else AUTO_MOVE_MS)
            }

            else -> {
                busy = false
                hint.text = getString(R.string.pick_token)
                board.setHighlights(moves)
            }
        }
    }

    private fun play(token: Int) {
        if (busy || state.die == 0) return
        busy = true
        board.clearHighlights()

        val face = state.die
        val before = state.steps.copyOf()
        val move = Rules.apply(state, token, face)
        // Captured tokens are drawn where they stood until the move lands.
        val capturedFrom = IntArray(move.captured.size) { before[move.captured[it]] }

        board.showState(state)
        board.animateMove(move.token, move.from, move.to, move.captured, capturedFrom) {
            afterMove(move)
        }
    }

    private fun afterMove(move: Move) {
        if (state.winner >= 0) {
            announceWinner()
            return
        }

        hint.text = when {
            move.captured.isNotEmpty() -> getString(R.string.captured)
            move.finished -> getString(R.string.token_home)
            move.extraTurn -> getString(R.string.rolled_six)
            else -> ""
        }

        if (move.extraTurn) {
            state.die = 0
            busy = false
            beginTurn()
        } else {
            handOver()
        }
    }

    /** Pauses a beat so the player can read the outcome, then passes the dice. */
    private fun handOver() {
        busy = true
        // Cleared now rather than in the callback so a game saved during the
        // pause cannot be restored into a free extra roll.
        state.die = 0
        handler.postDelayed({
            Rules.passTurn(state)
            busy = false
            beginTurn()
        }, HAND_OVER_MS)
    }

    private fun announceWinner() {
        handler.removeCallbacksAndMessages(null)
        board.clearHighlights()
        die.rollable = false
        busy = true
        status.text = getString(R.string.wins, Board.names[state.winner])
        hint.text = ""
        newGame.visibility = View.VISIBLE
        Saves.clear(this)
    }

    // --- lifecycle ---------------------------------------------------------

    override fun onPause() {
        super.onPause()
        if (state.winner < 0) Saves.save(this, state) else Saves.clear(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        board.cancelAnimations()
    }

    // --- ui ----------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            setPadding(dp(12), dp(20), dp(12), dp(20))
        }

        status = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        hint = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF9AA3AF.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        board = BoardView(this)
        val holder = FrameLayout(this).apply {
            addView(board, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        }
        root.addView(holder, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(14)
            bottomMargin = dp(14)
        })

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        die = DieView(this)
        bar.addView(die, LinearLayout.LayoutParams(dp(76), dp(76)))

        newGame = Button(this).apply {
            text = getString(R.string.new_game)
            visibility = View.GONE
            setOnClickListener { finish() }
        }
        bar.addView(newGame, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = dp(16)
        })
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.padForSystemBars()
        return root
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_SEATS = "seats"
        private const val EXTRA_RESUME = "resume"

        private const val BOT_THINK_MS = 620L
        private const val AUTO_MOVE_MS = 180L
        private const val HAND_OVER_MS = 750L

        private const val BACKGROUND = 0xFF12161C.toInt()

        fun newGame(context: Context, seats: Array<Seat>): Intent =
            Intent(context, GameActivity::class.java)
                .putExtra(EXTRA_SEATS, IntArray(seats.size) { seats[it].ordinal })

        fun resume(context: Context): Intent =
            Intent(context, GameActivity::class.java).putExtra(EXTRA_RESUME, true)
    }
}
