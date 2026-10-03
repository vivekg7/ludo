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
 * A game of Snakes & Ladders: the shared [Match] plus the square each seat's
 * one token stands on.
 */
class SnakesState(seats: Array<Seat>) : Match(seats) {

    override val kind = GameKind.SNAKES

    /** Square per seat, 1 to 100. Empty seats keep a token on 1 that is never drawn. */
    val squares = IntArray(Board.PLAYERS) { Snakes.START }

    override val positions get() = squares
    override val positionRange get() = Snakes.START..Snakes.FINISH
    override val saveVersion get() = SAVE_VERSION

    override fun score(player: Int) = squares[player]

    override fun percent(player: Int) = (squares[player] - Snakes.START) * 100 / (Snakes.FINISH - Snakes.START)

    companion object {
        private const val SAVE_VERSION = 1

        fun decode(saved: String?): SnakesState? = Match.decode(saved, ::SnakesState) {
            if (it == SAVE_VERSION) Match.FIELDS else null
        }
    }
}

/**
 * The board and rules of Snakes & Ladders, as pure functions over
 * [SnakesState] like [Rules] is for Ludo.
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

    /**
     * Foot of each ladder to its top. The classic Milton Bradley layout, less
     * its ladder on square 1: here that is where everyone starts, and a ladder
     * nobody can land on would only be decoration.
     */
    val ladders: Map<Int, Int> = mapOf(
        4 to 14, 9 to 31, 21 to 42, 28 to 84, 36 to 44, 51 to 67, 71 to 91, 80 to 100,
    )

    /** Head of each snake to its tail, from the same layout. */
    val snakes: Map<Int, Int> = mapOf(
        16 to 6, 47 to 26, 49 to 11, 56 to 53, 62 to 19, 64 to 60, 87 to 24, 93 to 73, 95 to 75, 98 to 78,
    )

    /** Where a token on [square] walks to with this roll, or -1 if it would overshoot. */
    fun targetOf(square: Int, die: Int): Int = when {
        die !in 1..6 -> -1
        square + die > FINISH -> -1
        else -> square + die
    }

    /** Where a token that walks onto [square] ends up: up a ladder, down a snake, or there. */
    fun jumpFrom(square: Int): Int = ladders[square] ?: snakes[square] ?: square

    /** Plays the current player's roll in place and reports what it did; the roll must be playable. */
    fun apply(state: SnakesState, die: Int): Climb {
        val player = state.current
        val from = state.squares[player]
        val landed = targetOf(from, die)
        require(landed > 0) { "illegal move: square $from with die $die" }
        val to = jumpFrom(landed)
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
