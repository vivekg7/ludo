package com.crylo.ludo

/** One turn of Snakes & Ladders, and everything the UI needs to animate and narrate it. */
class Climb(
    val player: Int,
    val from: Int,
    /** The square the roll walked to, before any ladder or snake. */
    val landed: Int,
    /** Where the turn ends: the top of a ladder, a snake's tail, or [landed]. */
    val to: Int,
    val extraTurn: Boolean,
) {
    val climbed get() = to > landed
    val bitten get() = to < landed
}

/**
 * How long a board's games run against [SnakesLayout.CLASSIC]: a quick game
 * for small children, about the same, or a long one with more setbacks.
 */
enum class Difficulty { EASY, NORMAL, HARD }

/**
 * One Snakes & Ladders board: where its ladders and snakes are. Each is the
 * same 10 × 10 grid; only these differ.
 *
 * A [Difficulty.NORMAL] board is there for variety, so it is tuned to take
 * about as many rolls to finish as the classic one. An easy board has more
 * and longer ladders and few short snakes, and finishes in a little over half
 * the rolls; a hard one has fewer ladders and long snakes crowded near the
 * top, and takes about half as many again. A test holds each to its band.
 * Listed easiest first, as the board picker shows them.
 */
enum class SnakesLayout(
    /** What a save calls the board. Never changed once released, unlike the entry's name. */
    val key: String,
    val difficulty: Difficulty,
    /** Foot of each ladder to its top. */
    val ladders: Map<Int, Int>,
    /** Head of each snake to its tail. */
    val snakes: Map<Int, Int>,
) {
    MEADOW(
        "meadow",
        Difficulty.EASY,
        ladders = mapOf(
            3 to 24, 7 to 29, 15 to 46, 22 to 53, 33 to 65, 41 to 62, 52 to 83, 60 to 78, 69 to 92, 76 to 97,
        ),
        snakes = mapOf(18 to 6, 38 to 27, 57 to 46, 74 to 66, 88 to 77, 96 to 86),
    ),
    GARDEN(
        "garden",
        Difficulty.EASY,
        ladders = mapOf(
            2 to 21, 10 to 32, 18 to 39, 26 to 57, 37 to 68, 45 to 66, 54 to 75, 63 to 85, 72 to 94, 81 to 99,
        ),
        snakes = mapOf(29 to 16, 49 to 36, 59 to 46, 79 to 65, 93 to 84),
    ),

    /**
     * The Milton Bradley layout, less its ladder on square 1: here that is
     * where everyone starts, and a ladder nobody can land on would only be
     * decoration.
     */
    CLASSIC(
        "classic",
        Difficulty.NORMAL,
        ladders = mapOf(4 to 14, 9 to 31, 21 to 42, 28 to 84, 36 to 44, 51 to 67, 71 to 91, 80 to 100),
        snakes = mapOf(
            16 to 6, 47 to 26, 49 to 11, 56 to 53, 62 to 19, 64 to 60, 87 to 24, 93 to 73, 95 to 75, 98 to 78,
        ),
    ),
    JUNGLE(
        "jungle",
        Difficulty.NORMAL,
        ladders = mapOf(3 to 22, 8 to 30, 20 to 41, 27 to 56, 40 to 59, 50 to 69, 63 to 81, 72 to 94),
        snakes = mapOf(17 to 7, 34 to 12, 46 to 25, 54 to 33, 62 to 43, 77 to 58, 88 to 67, 92 to 71, 97 to 78),
    ),
    RIVER(
        "river",
        Difficulty.NORMAL,
        ladders = mapOf(2 to 23, 11 to 33, 19 to 38, 35 to 57, 43 to 64, 61 to 79, 70 to 89, 76 to 96),
        snakes = mapOf(
            25 to 5, 31 to 9, 48 to 29, 52 to 32, 66 to 45, 74 to 55, 84 to 63, 91 to 72, 95 to 85, 98 to 77,
        ),
    ),
    TEMPLE(
        "temple",
        Difficulty.NORMAL,
        ladders = mapOf(6 to 26, 14 to 37, 24 to 45, 32 to 53, 47 to 68, 58 to 77, 67 to 86, 82 to 99),
        snakes = mapOf(
            21 to 3, 39 to 18, 44 to 16, 55 to 34, 65 to 42, 73 to 51, 85 to 60, 89 to 70, 94 to 75, 97 to 79,
        ),
    ),
    SWAMP(
        "swamp",
        Difficulty.HARD,
        ladders = mapOf(7 to 18, 23 to 35, 32 to 51, 44 to 57, 58 to 74, 70 to 86),
        snakes = mapOf(
            14 to 3, 27 to 10, 37 to 19, 48 to 30, 53 to 29, 66 to 38, 83 to 59, 91 to 68, 95 to 76, 98 to 79,
        ),
    ),
    VOLCANO(
        "volcano",
        Difficulty.HARD,
        ladders = mapOf(5 to 16, 12 to 28, 30 to 47, 39 to 58, 50 to 68, 67 to 78, 72 to 86),
        snakes = mapOf(
            17 to 4, 34 to 13, 46 to 23, 55 to 31, 69 to 45, 76 to 52, 89 to 66, 93 to 73, 96 to 87, 97 to 79,
        ),
    );

    /** Where a token that walks onto [square] ends up: up a ladder, down a snake, or there. */
    fun jumpFrom(square: Int): Int = ladders[square] ?: snakes[square] ?: square

    companion object {
        fun of(key: String): SnakesLayout? = entries.firstOrNull { it.key == key }

        /**
         * The boards Random draws from: the normal ones. Picking an easy or a
         * hard board is a choice made for who is playing, which a draw
         * should not make for them.
         */
        val forRandom: List<SnakesLayout> = entries.filter { it.difficulty == Difficulty.NORMAL }
    }
}

/**
 * A game of Snakes & Ladders: the shared [Match], the board it is played on,
 * and the square each seat's one token stands on.
 */
class SnakesState(seats: Array<Seat>, val layout: SnakesLayout = SnakesLayout.CLASSIC) : Match(seats) {

    override val kind = GameKind.SNAKES

    /** Square per seat, 1 to 100. Empty seats keep a token on 1 that is never drawn. */
    val squares = IntArray(Board.PLAYERS) { Snakes.START }

    override val positions get() = squares
    override val positionRange get() = Snakes.START..Snakes.FINISH
    override val saveVersion get() = SAVE_VERSION

    override fun score(player: Int) = squares[player]

    override fun percent(player: Int) = (squares[player] - Snakes.START) * 100 / (Snakes.FINISH - Snakes.START)

    override fun extraFields() = listOf(layout.key)

    companion object {
        private const val SAVE_VERSION = 2

        // Version 1 predates there being more than one board, so its game
        // was on the classic one; version 2 adds the board's key. A key this
        // build does not know refuses the save, like any other damage.
        fun decode(saved: String?): SnakesState? = Match.decode(
            saved,
            { seats, extra ->
                val layout = if (extra.isEmpty()) SnakesLayout.CLASSIC else SnakesLayout.of(extra[0])
                layout?.let { SnakesState(seats, it) }
            },
        ) {
            when (it) {
                1 -> Match.FIELDS
                SAVE_VERSION -> Match.FIELDS + 1
                else -> null
            }
        }
    }
}

/**
 * The grid and rules of Snakes & Ladders, as pure functions over
 * [SnakesState] like [Rules] is for Ludo. Where the snakes and ladders are is
 * the game's [SnakesLayout].
 *
 * The board is 10 × 10, numbered from 1 in the bottom left, left to right
 * along the bottom row and back the other way along the next, up to 100 in
 * the top left. Everyone starts on square 1 and the first to land on 100
 * exactly wins; a roll that would overshoot is not played. A six rolls again,
 * and three in a row forfeits the turn, as in Ludo. Tokens never capture, so
 * any number may share a square.
 */
object Snakes {

    const val GRID = 10
    const val START = 1
    const val FINISH = GRID * GRID

    /** Where a token on [square] walks to with this roll, or -1 if it would overshoot. */
    fun targetOf(square: Int, die: Int): Int = when {
        die !in 1..6 -> -1
        square + die > FINISH -> -1
        else -> square + die
    }

    /** Plays the current player's roll in place and reports what it did; the roll must be playable. */
    fun apply(state: SnakesState, die: Int): Climb {
        val player = state.current
        val from = state.squares[player]
        val landed = targetOf(from, die)
        require(landed > 0) { "illegal move: square $from with die $die" }
        val to = state.layout.jumpFrom(landed)
        state.squares[player] = to
        if (to == FINISH) state.winner = player
        return Climb(player, from, landed, to, extraTurn = die == 6 && to != FINISH)
    }

    /** As [Rules.settle]: the same player rolls again after a six, otherwise the dice pass on. */
    fun settle(state: SnakesState, climb: Climb) {
        if (state.winner >= 0) return
        if (climb.extraTurn) state.die = 0 else Turns.passTurn(state)
    }

    /** Column of [square], 0 on the left. */
    fun colOf(square: Int): Int {
        val index = square - 1
        val along = index % GRID
        return if ((index / GRID) % 2 == 0) along else GRID - 1 - along
    }

    /** Row of [square], 0 at the top, where 100 is. */
    fun rowOf(square: Int): Int = GRID - 1 - (square - 1) / GRID
}
