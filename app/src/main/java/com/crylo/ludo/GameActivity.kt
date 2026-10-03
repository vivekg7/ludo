package com.crylo.ludo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.random.Random

/** What the game screen asks of a game's board, whichever game it is. */
interface TableBoard<S : Match> {

    /** What each seat is called, in seat order. */
    var names: Array<String>

    /** Seat the turn marker points at, or -1 for none. */
    var turn: Int

    /** Whether the names above the board face the far side of a phone lying flat. */
    var namesFaceTable: Boolean

    fun showState(newState: S)

    fun clearReactions()

    fun cancelAnimations()
}

/**
 * The game screen, and the turn loop that drives it: everything a game of
 * Ludo and a game of Snakes & Ladders do alike. A subclass supplies the board
 * and plays a roll; this class rolls the die, passes the dice, saves, and
 * shows the results.
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
abstract class GameActivity<S : Match, B> : Activity() where B : View, B : TableBoard<S> {

    protected abstract val kind: GameKind

    protected lateinit var state: S
    protected lateinit var board: B
    protected lateinit var hint: TextView
    protected lateinit var sounds: Sounds
    private lateinit var die: DieView
    private lateinit var status: TextView
    private lateinit var showResults: Button
    private lateinit var results: FrameLayout
    private lateinit var resultsCard: LinearLayout
    private lateinit var confetti: ConfettiView

    /**
     * What the banner and the board call each seat: its profile's name, "Bot"
     * and a number for a bot, or its colour for a guest.
     */
    protected lateinit var names: Array<String>

    protected val handler = Handler(Looper.getMainLooper())
    protected val random = Random.Default
    protected val picker = Picker(random)

    /**
     * Whether the board reacts with emoji and taunts. Read with the other
     * settings in [onResume].
     */
    protected var reactionsOn = true

    /** True while an animation or a scheduled step owns the turn. */
    protected var busy = false

    /** A saved game of this kind rebuilt from its string, or null if it is damaged. */
    protected abstract fun decode(saved: String?): S?

    /** A fresh game for these seats, before anyone has moved. */
    protected abstract fun newMatch(seats: Array<Seat>): S

    protected abstract fun createBoard(): B

    /** Hooks the board's callbacks up, once the screen is built and before the first turn. */
    protected open fun wireBoard() {}

    /**
     * Plays the current player's roll of [face], which is known to count: it
     * is not a forfeited third six. Ends by passing the turn on through
     * [handOver] or [pauseThenBeginTurn], rolling again through [beginTurn],
     * or finishing the game through [celebrateWin].
     */
    protected abstract fun playRoll(face: Int)

    /** How far [player] got, under their name in the results. */
    protected abstract fun progressLine(player: Int): String

    /** Takes back any choice the board is offering, when the game ends. */
    protected open fun clearChoices() {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android rebuilds this activity from its original intent after a
        // configuration change or after killing the process in the background.
        // That intent says "new game", so without the state kept below the
        // game would restart, and the next onPause would save over the real one.
        state = savedInstanceState?.getString(KEY_STATE)?.let(::decode)
            ?: (if (intent.getBooleanExtra(EXTRA_RESUME, false)) decode(Saves.saved(this, kind)) else null)
            ?: newStateFromIntent()

        // Bots are numbered in seat order, so two of them can be told apart; a
        // profile deleted while its game was saved just falls back to the colour.
        names = Profiles.seatNames(state.seats, state.profiles, Saves.profiles(this)) { getString(R.string.bot_name, it) }

        sounds = Sounds()

        setContentView(buildUi())

        die.onRollRequested = { roll() }
        wireBoard()

        beginTurn()
    }

    private fun newStateFromIntent(): S {
        val raw = intent.getIntArrayExtra(EXTRA_SEATS)
        val seats = Array(Board.PLAYERS) { player ->
            raw?.getOrNull(player)?.let { Seat.entries[it] } ?: Seat.NONE
        }
        val ids = intent.getIntArrayExtra(EXTRA_PROFILES)
        return newMatch(seats).apply {
            for (player in 0 until Board.PLAYERS) {
                if (seats[player] == Seat.HUMAN) profiles[player] = ids?.getOrNull(player) ?: Profiles.NONE
            }
            Turns.pickStarter(this, random)
        }
    }

    // --- turn loop ---------------------------------------------------------

    protected fun beginTurn() {
        board.showState(state)

        if (state.winner >= 0) {
            announceWinner()
            return
        }

        // Set before the restored-roll check below, which returns early: a game
        // saved with the dice rolled would otherwise come back with no banner.
        status.text = getString(R.string.turn_of, names[state.current])
        board.turn = state.current
        die.tint = Board.colors[state.current]
        die.face = state.die

        // A restored game can come back with the dice already rolled.
        if (state.die != 0) {
            resolveRoll(state.die)
            return
        }

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
            if (face == 6 && !state.isBot(state.current)) buzz(HapticFeedbackConstants.CONFIRM)
            state.die = face
            state.sixStreak = if (face == 6) state.sixStreak + 1 else 0
            resolveRoll(face)
        }
    }

    private fun resolveRoll(face: Int) {
        if (state.sixStreak >= Turns.SIX_STREAK_LIMIT) {
            hint.text = getString(R.string.three_sixes)
            sounds.play(Sound.NO_MOVE)
            handOver()
            return
        }
        playRoll(face)
    }

    /**
     * Credits a game the move just applied has won. Called from the one place
     * a win happens, as the move is applied, rather than once its animation
     * ends, when an activity closed mid-slide would never get to it; a
     * restored finished game only announces, so none counts twice.
     */
    protected fun creditIfWon() {
        if (state.winner >= 0) Saves.saveProfiles(this, Profiles.recordGame(Saves.profiles(this), state))
    }

    /**
     * The fanfare, the buzzes, the confetti and the results, once the winning
     * move has landed. Played from there, rather than from [announceWinner],
     * which also runs for a finished game restored after a rotation.
     */
    protected fun celebrateWin() {
        sounds.play(Sound.WIN)
        announceWinner(justWon = true)
        // After announceWinner, which clears the handler these are queued on.
        for (i in 0 until WIN_BUZZES) {
            handler.postDelayed({ buzz(HapticFeedbackConstants.CONFIRM) }, i * WIN_BUZZ_GAP_MS)
        }
        // The results wait a moment so the winning token is seen arriving.
        confetti.burst()
        handler.postDelayed({ openResults() }, RESULTS_DELAY_MS)
    }

    /** Passes the dice on a roll that cannot be played. */
    protected fun handOver() {
        // Passed now rather than after the pause, so a game saved during the
        // pause belongs to the next player instead of handing this one a
        // fresh roll.
        Turns.passTurn(state)
        pauseThenBeginTurn()
    }

    /** Pauses a beat so the player can read the outcome before the next turn. */
    protected fun pauseThenBeginTurn() {
        busy = true
        handler.postDelayed({
            busy = false
            beginTurn()
        }, HAND_OVER_MS)
    }

    /**
     * Ends the game on screen. A game restored already won opens its results
     * straight away; a win that has just happened ([justWon]) opens them from
     * [celebrateWin], once the winning token has been seen arriving.
     */
    private fun announceWinner(justWon: Boolean = false) {
        handler.removeCallbacksAndMessages(null)
        clearChoices()
        die.rollable = false
        busy = true
        board.turn = state.winner
        status.text = getString(R.string.wins, names[state.winner])
        hint.text = ""
        Saves.clear(this, kind)
        if (!justWon) openResults()
    }

    // --- results -----------------------------------------------------------

    /**
     * Fills and shows the results card over the screen: everyone in finishing
     * order with how far they got and, for a profile, their record in this
     * game now this one is counted, and the choice of a rematch or a new lineup.
     */
    private fun openResults() {
        resultsCard.removeAllViews()

        resultsCard.addView(TextView(this).apply {
            text = getString(R.string.wins, names[state.winner])
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Style.TEXT)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(16) })

        val profiles = Saves.profiles(this).associateBy { it.id }
        val places = resources.getStringArray(R.array.places)
        val order = state.standings()
        var place = 0
        order.forEachIndexed { i, player ->
            // Level players share a place, as in any race.
            if (i == 0 || !state.sameStanding(order[i - 1], player)) place = i
            resultsCard.addView(resultRow(places[place], player, profiles[state.profiles[player]]))
        }

        resultsCard.addView(Style.button(this, getString(R.string.rematch), Style.Kind.PRIMARY).apply {
            setOnClickListener { rematch() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(20) })
        resultsCard.addView(Style.button(this, getString(R.string.change_players), Style.Kind.SECONDARY).apply {
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        results.visibility = View.VISIBLE
        showResults.visibility = View.GONE
    }

    /** Tucks the results away to look at the final board; the bar can bring them back. */
    private fun closeResults() {
        results.visibility = View.GONE
        showResults.visibility = View.VISIBLE
    }

    private fun resultRow(place: String, player: Int, profile: Profile?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(this).apply {
            text = place
            textSize = 14f
            setTextColor(Style.TEXT_DIM)
        }, LinearLayout.LayoutParams(dp(36), WRAP_CONTENT))
        row.addView(View(this).apply {
            background = Style.seatDot(this@GameActivity, Board.colors[player], true)
        }, LinearLayout.LayoutParams(dp(14), dp(14)))

        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(this).apply {
            this.text = names[player]
            textSize = 16f
            setTextColor(Style.TEXT)
            if (player == state.winner) typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        text.addView(TextView(this).apply {
            this.text = progressLine(player)
            textSize = 13f
            setTextColor(Style.TEXT_DIM)
        })
        row.addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })

        if (profile != null) {
            val record = profile.record(kind)
            row.addView(TextView(this).apply {
                this.text = getString(R.string.record, record.wins, record.played)
                textSize = 13f
                setTextColor(Style.TEXT_DIM)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
        }
        return row
    }

    /**
     * The same seats and profiles again, in place rather than through the
     * setup screen. The finished game was cleared from the save when it was
     * won, and the new one is saved like any other when the screen pauses.
     */
    private fun rematch() {
        handler.removeCallbacksAndMessages(null)
        board.cancelAnimations()
        confetti.stop()
        val finished = state
        state = newMatch(finished.seats.copyOf()).also {
            finished.profiles.copyInto(it.profiles)
            // Drawn afresh, like any new game, rather than going to the winner.
            Turns.pickStarter(it, random)
        }
        results.visibility = View.GONE
        showResults.visibility = View.GONE
        hint.text = ""
        busy = false
        beginTurn()
    }

    /**
     * A short vibration through the view, which needs no permission and is
     * skipped by the system when the player has touch feedback turned off.
     */
    protected fun buzz(kind: Int) {
        board.performHapticFeedback(kind)
    }

    // --- lifecycle ---------------------------------------------------------

    override fun onResume() {
        super.onResume()
        // Read here rather than once in onCreate, so what was changed on the
        // settings page opened from this screen applies on coming back.
        sounds.enabled = Saves.soundOn(this)
        board.namesFaceTable = Saves.namesFaceTable(this)
        reactionsOn = Saves.reactionsOn(this)
        if (!reactionsOn) board.clearReactions()
        sounds.resume()
    }

    override fun onPause() {
        super.onPause()
        sounds.pause()
        if (state.winner < 0) Saves.save(this, state) else Saves.clear(this, kind)
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
        // The settings button sits at the end of the banner row; the banner is
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
        header.addView(Style.settingsButton(this) {
            startActivity(SettingsActivity.open(this))
        }, FrameLayout.LayoutParams(dp(TOGGLE_DP), dp(TOGGLE_DP), Gravity.END or Gravity.CENTER_VERTICAL))

        hint = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF9AA3AF.toInt())
            gravity = Gravity.CENTER
        }

        board = createBoard()
        board.names = names
        val holder = FrameLayout(this).apply {
            addView(board, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        die = DieView(this)
        bar.addView(die, LinearLayout.LayoutParams(dp(76), dp(76)))

        showResults = Style.button(this, getString(R.string.show_results), Style.Kind.SECONDARY).apply {
            visibility = View.GONE
            setOnClickListener { openResults() }
        }
        bar.addView(showResults, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = dp(16)
        })

        val root = if (Style.isLandscape(this)) {
            // The board takes the whole height on the left, and the banner,
            // the hint and the die stack in a column beside it: stacked above
            // and below the board as in portrait, they would leave it a strip.
            val side = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
                addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(24) })
            }
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                addView(holder, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                addView(side, LinearLayout.LayoutParams(dp(SIDE_DP), MATCH_PARENT).apply { leftMargin = dp(16) })
            }
        } else {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(20), dp(12), dp(20))
                addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
                addView(holder, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
                    topMargin = dp(14)
                    bottomMargin = dp(14)
                })
                addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
        }
        root.setBackgroundColor(BACKGROUND)
        root.padForSystemBars()

        // The results and the confetti lie over the whole screen, not just the
        // board, so the card has room for four players on a short phone.
        val screen = FrameLayout(this)
        screen.addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        results = buildResults()
        screen.addView(results, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        confetti = ConfettiView(this)
        screen.addView(confetti, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return screen
    }

    /**
     * A dimmed layer holding the results card, filled in by [openResults].
     * Tapping the dimmed part puts the card away to show the final board.
     */
    private fun buildResults(): FrameLayout {
        resultsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Style.panel(this@GameActivity, Style.SURFACE, radiusDp = 20)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            // Taps on the card are its own, not a tap on the dimmed layer behind.
            isClickable = true
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            val centre = FrameLayout(this@GameActivity).apply {
                setOnClickListener { closeResults() }
                // Capped in width, or on a wide screen the rows spread too far apart to read.
                addView(resultsCard, FrameLayout.LayoutParams(Style.readableWidth(this@GameActivity), WRAP_CONTENT, Gravity.CENTER).apply {
                    setMargins(dp(24), dp(24), dp(24), dp(24))
                })
            }
            addView(centre, MATCH_PARENT, MATCH_PARENT)
        }
        scroll.padForSystemBars()
        return FrameLayout(this).apply {
            setBackgroundColor(SCRIM)
            visibility = View.GONE
            addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_SEATS = "seats"
        private const val EXTRA_PROFILES = "profiles"
        private const val EXTRA_RESUME = "resume"
        private const val KEY_STATE = "state"

        internal const val BOT_THINK_MS = 620L
        internal const val AUTO_MOVE_MS = 180L
        private const val HAND_OVER_MS = 750L

        private const val TOGGLE_DP = 48

        /** Width of the column beside the board in landscape. */
        private const val SIDE_DP = 240

        private const val WIN_BUZZES = 3
        private const val WIN_BUZZ_GAP_MS = 180L
        private const val RESULTS_DELAY_MS = 900L
        private const val SCRIM = 0xB3000000.toInt()

        private const val BACKGROUND = 0xFF12161C.toInt()

        private fun screenFor(kind: GameKind): Class<out Activity> = when (kind) {
            GameKind.LUDO -> LudoActivity::class.java
            GameKind.SNAKES -> SnakesActivity::class.java
        }

        fun newGame(context: Context, kind: GameKind, seats: Array<Seat>, profiles: IntArray): Intent =
            Intent(context, screenFor(kind))
                .putExtra(EXTRA_SEATS, IntArray(seats.size) { seats[it].ordinal })
                .putExtra(EXTRA_PROFILES, profiles)

        fun resume(context: Context, kind: GameKind): Intent =
            Intent(context, screenFor(kind)).putExtra(EXTRA_RESUME, true)
    }
}
