package com.crylo.ludo

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
 *
 * For that to hold, [state] must never be ahead of or behind the rules while
 * the screen catches up. A move and the turn it settles are both written to
 * [state] the moment they happen; the animations and pauses that follow only
 * show them, so a save taken during one restores to what comes next.
 */
class GameActivity : Activity() {

    private lateinit var state: GameState
    private lateinit var board: BoardView
    private lateinit var die: DieView
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var newGame: Button
    private lateinit var soundToggle: TextView
    private lateinit var sounds: Sounds

    /**
     * What the banner and the board call each seat: its profile's name, "Bot"
     * and a number for a bot, or its colour for a guest.
     */
    private val names = Board.names.copyOf()

    private val handler = Handler(Looper.getMainLooper())
    private val random = Random.Default

    /** True while an animation or a scheduled step owns the turn. */
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android rebuilds this activity from its original intent after a
        // configuration change or after killing the process in the background.
        // That intent says "new game", so without the state kept below the
        // game would restart, and the next onPause would save over the real one.
        state = savedInstanceState?.getString(KEY_STATE)?.let(GameState::decode)
            ?: (if (intent.getBooleanExtra(EXTRA_RESUME, false)) Saves.load(this) else null)
            ?: newStateFromIntent()

        val profiles = Saves.profiles(this).associateBy { it.id }
        var bots = 0
        for (player in 0 until Board.PLAYERS) {
            // Bots are numbered in seat order, so two of them can be told apart.
            if (state.isBot(player)) names[player] = getString(R.string.bot_name, ++bots)
            // A profile deleted while its game was saved just falls back to the colour.
            profiles[state.profiles[player]]?.let { names[player] = it.name }
        }

        sounds = Sounds()
        sounds.enabled = Saves.soundOn(this)

        setContentView(buildUi())
        showSoundToggle()

        board.onTokenPicked = { token -> play(token) }
        board.onSquareReached = { sounds.play(Sound.STEP) }
        die.onRollRequested = { roll() }

        beginTurn()
    }

    private fun newStateFromIntent(): GameState {
        val raw = intent.getIntArrayExtra(EXTRA_SEATS)
        val seats = Array(Board.PLAYERS) { player ->
            raw?.getOrNull(player)?.let { Seat.entries[it] } ?: Seat.NONE
        }
        val ids = intent.getIntArrayExtra(EXTRA_PROFILES)
        return GameState(seats).apply {
            for (player in 0 until Board.PLAYERS) {
                if (seats[player] == Seat.HUMAN) profiles[player] = ids?.getOrNull(player) ?: Profiles.NONE
            }
        }
    }

    // --- turn loop ---------------------------------------------------------

    private fun beginTurn() {
        board.showState(state)

        if (state.winner >= 0) {
            announceWinner()
            return
        }

        board.turn = state.current
        die.tint = Board.colors[state.current]
        die.face = state.die

        // A restored game can come back with the dice already rolled.
        if (state.die != 0) {
            resolveRoll(state.die)
            return
        }

        status.text = getString(R.string.turn_of, names[state.current])
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
        sounds.play(Sound.ROLL)
        die.roll(face) {
            sounds.play(Sound.LAND)
            state.die = face
            state.sixStreak = if (face == 6) state.sixStreak + 1 else 0
            resolveRoll(face)
        }
    }

    private fun resolveRoll(face: Int) {
        if (state.sixStreak >= Rules.SIX_STREAK_LIMIT) {
            hint.text = getString(R.string.three_sixes)
            sounds.play(Sound.NO_MOVE)
            handOver()
            return
        }

        val moves = Rules.legalMoves(state, face)
        val bot = state.isBot(state.current)
        when {
            moves.isEmpty() -> {
                hint.text = getString(R.string.no_move, face)
                sounds.play(Sound.NO_MOVE)
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
        // Settled before the token starts to slide: were the die left set, a
        // game saved mid-animation would restore with the token already moved
        // and the same roll still to play.
        Rules.settle(state, move)
        // Credited here, the one place a win happens, rather than once the
        // animation ends, when an activity closed mid-slide would never get
        // to it; a restored finished game only announces, so none counts twice.
        if (state.winner >= 0) Saves.saveProfiles(this, Profiles.recordGame(Saves.profiles(this), state))
        // Captured tokens are drawn where they stood until the move lands.
        val capturedFrom = IntArray(move.captured.size) { before[move.captured[it]] }

        board.showState(state)
        board.animateMove(move.token, move.from, move.to, move.captured, capturedFrom) {
            afterMove(move)
        }
    }

    private fun afterMove(move: Move) {
        // Played here, as the move lands, rather than in announceWinner, which
        // also runs for a finished game restored after a rotation.
        if (state.winner >= 0) {
            sounds.play(Sound.WIN)
            announceWinner()
            return
        }

        when {
            move.captured.isNotEmpty() -> sounds.play(Sound.CAPTURE)
            move.finished -> sounds.play(Sound.HOME)
        }

        hint.text = when {
            move.captured.isNotEmpty() -> getString(R.string.captured)
            move.finished -> getString(R.string.token_home)
            move.extraTurn -> getString(R.string.rolled_six)
            else -> ""
        }

        if (move.extraTurn) {
            busy = false
            beginTurn()
        } else {
            // play() has already passed the dice.
            pauseThenBeginTurn()
        }
    }

    /** Passes the dice on a roll that cannot be played. */
    private fun handOver() {
        // Passed now rather than after the pause, so a game saved during the
        // pause belongs to the next player instead of handing this one a
        // fresh roll.
        Rules.passTurn(state)
        pauseThenBeginTurn()
    }

    /** Pauses a beat so the player can read the outcome before the next turn. */
    private fun pauseThenBeginTurn() {
        busy = true
        handler.postDelayed({
            busy = false
            beginTurn()
        }, HAND_OVER_MS)
    }

    private fun announceWinner() {
        handler.removeCallbacksAndMessages(null)
        board.clearHighlights()
        die.rollable = false
        busy = true
        board.turn = state.winner
        status.text = getString(R.string.wins, names[state.winner])
        hint.text = ""
        newGame.visibility = View.VISIBLE
        Saves.clear(this)
    }

    // --- lifecycle ---------------------------------------------------------

    override fun onResume() {
        super.onResume()
        sounds.resume()
    }

    override fun onPause() {
        super.onPause()
        sounds.pause()
        if (state.winner < 0) Saves.save(this, state) else Saves.clear(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STATE, state.encode())
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        board.cancelAnimations()
        sounds.release()
    }

    // --- ui ----------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            setPadding(dp(12), dp(20), dp(12), dp(20))
        }

        // The sound toggle sits at the end of the banner row; the banner is
        // padded by its width on both sides so the name stays centred.
        val header = FrameLayout(this)
        status = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(TOGGLE_DP), 0, dp(TOGGLE_DP), 0)
        }
        header.addView(status, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        soundToggle = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setOnClickListener {
                sounds.enabled = !sounds.enabled
                Saves.setSoundOn(this@GameActivity, sounds.enabled)
                showSoundToggle()
            }
        }
        header.addView(soundToggle, FrameLayout.LayoutParams(dp(TOGGLE_DP), dp(TOGGLE_DP), Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        hint = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF9AA3AF.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        board = BoardView(this)
        board.names = names
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

    private fun showSoundToggle() {
        soundToggle.text = if (sounds.enabled) "\uD83D\uDD0A" else "\uD83D\uDD07" // 🔊 / 🔇
        soundToggle.contentDescription = getString(if (sounds.enabled) R.string.mute else R.string.unmute)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_SEATS = "seats"
        private const val EXTRA_PROFILES = "profiles"
        private const val EXTRA_RESUME = "resume"
        private const val KEY_STATE = "state"

        private const val BOT_THINK_MS = 620L
        private const val AUTO_MOVE_MS = 180L
        private const val HAND_OVER_MS = 750L

        private const val TOGGLE_DP = 48

        private const val BACKGROUND = 0xFF12161C.toInt()

        fun newGame(context: Context, seats: Array<Seat>, profiles: IntArray): Intent =
            Intent(context, GameActivity::class.java)
                .putExtra(EXTRA_SEATS, IntArray(seats.size) { seats[it].ordinal })
                .putExtra(EXTRA_PROFILES, profiles)

        fun resume(context: Context): Intent =
            Intent(context, GameActivity::class.java).putExtra(EXTRA_RESUME, true)
    }
}
