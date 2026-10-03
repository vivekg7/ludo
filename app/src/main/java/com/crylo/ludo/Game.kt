package com.crylo.ludo

/** Who is sitting in one of the four seats. */
enum class Seat { NONE, HUMAN, BOT }

/** One applied move, and everything the UI needs to animate and narrate it. */
class Move(
    val token: Int,
    val from: Int,
    val to: Int,
    val captured: IntArray,
    val finished: Boolean,
    val extraTurn: Boolean,
)

/**
 * A game of Ludo: the shared [Match] plus where all sixteen tokens are. Small
 * enough to copy, serialise and hand around whole, which is what keeps saving
 * a game to SharedPreferences a one-liner.
 */
class GameState(seats: Array<Seat>) : Match(seats) {

    override val kind = GameKind.LUDO

    /** Position of all sixteen tokens, indexed player * 4 + slot. See [Board]. */
    val steps = IntArray(Board.TOKENS)

    override val positions get() = steps
    override val positionRange get() = 0..Board.FINISH
    override val saveVersion get() = SAVE_VERSION

    /** Steps [player]'s four tokens have walked between them, out of 4 × [Board.FINISH]. */
    fun travelled(player: Int): Int {
        val first = Board.firstToken(player)
        return (first until first + Board.TOKENS_PER_PLAYER).sumOf { steps[it] }
    }

    /** How many of [player]'s tokens are on the centre square. */
    fun tokensHome(player: Int): Int {
        val first = Board.firstToken(player)
        return (first until first + Board.TOKENS_PER_PLAYER).count { steps[it] == Board.FINISH }
    }

    /** Tokens home first, then ground covered, which is always less than one more token home. */
    override fun score(player: Int) =
        tokensHome(player) * (Board.TOKENS_PER_PLAYER * Board.FINISH + 1) + travelled(player)

    override fun percent(player: Int) = Board.travelPercent(travelled(player))

    companion object {
        private const val SAVE_VERSION = 2

        // Version 1 predates profiles and is otherwise identical, so a game
        // left in progress across that update still resumes, with guests.
        fun decode(saved: String?): GameState? = Match.decode(saved, { seats, _ -> GameState(seats) }) {
            when (it) {
                1 -> Match.FIELDS - 1
                SAVE_VERSION -> Match.FIELDS
                else -> null
            }
        }
    }
}

/**
 * The rules, as pure functions over [GameState]. Nothing here touches Android,
 * so the whole game is exercisable from a plain JVM unit test.
 *
 * Variant implemented: a six is needed to leave the yard, a six grants another
 * turn (three in a row forfeits it), landing on a lone enemy off a safe square
 * sends it home and grants another turn, and a token must reach the centre on
 * an exact roll.
 */
object Rules {

    private val EMPTY = IntArray(0)

    /** Where `steps` would land with this roll, or -1 if the move is illegal. */
    fun targetOf(steps: Int, die: Int): Int = when {
        die !in 1..6 -> -1
        steps == 0 -> if (die == 6) 1 else -1        // only a six releases a token
        steps >= Board.FINISH -> -1                  // already home
        steps + die > Board.FINISH -> -1             // must land exactly on the centre
        else -> steps + die
    }

    /** Tokens of the player to move that can legally use this roll. */
    fun legalMoves(state: GameState, die: Int): IntArray {
        if (die !in 1..6 || state.winner >= 0) return EMPTY
        val first = Board.firstToken(state.current)
        var count = 0
        val buffer = IntArray(Board.TOKENS_PER_PLAYER)
        for (t in first until first + Board.TOKENS_PER_PLAYER) {
            if (targetOf(state.steps[t], die) > 0) buffer[count++] = t
        }
        return buffer.copyOf(count)
    }

    /**
     * Whether [moves] leave nothing to decide: every token that can move
     * stands on the same square, so whichever is picked lands in the same
     * place with the same outcome. Tokens in the yard count as one square.
     */
    fun isForced(state: GameState, moves: IntArray): Boolean =
        moves.isNotEmpty() && moves.all { state.steps[it] == state.steps[moves[0]] }

    /** Applies a legal move in place and reports what it did. */
    fun apply(state: GameState, token: Int, die: Int): Move {
        val player = Board.owner(token)
        val from = state.steps[token]
        val to = targetOf(from, die)
        require(to > 0) { "illegal move: token $token at $from with die $die" }

        val captured = victims(state, player, to)
        state.steps[token] = to
        for (enemy in captured) state.steps[enemy] = 0

        val finished = to == Board.FINISH
        if (finished && hasWon(state, player)) state.winner = player

        return Move(
            token = token,
            from = from,
            to = to,
            captured = captured,
            finished = finished,
            // Rolling a six, a capture, or getting a token home all buy
            // another roll rather than passing the dice on.
            extraTurn = die == 6 || captured.isNotEmpty() || finished,
        )
    }

    /**
     * Enemy tokens a token of [player] would send home by landing on [to]:
     * everyone else's on that ring square, unless it is a safe one. Shared by
     * [apply] and the board's preview of where a move lands.
     */
    fun victims(state: GameState, player: Int, to: Int): IntArray {
        val landedOn = Board.ringIndex(player, to)
        if (landedOn < 0 || landedOn in Board.safe) return EMPTY
        var count = 0
        val buffer = IntArray(Board.TOKENS)
        for (enemy in 0 until Board.TOKENS) {
            val owner = Board.owner(enemy)
            if (owner == player || state.seats[owner] == Seat.NONE) continue
            if (Board.ringIndex(owner, state.steps[enemy]) == landedOn) buffer[count++] = enemy
        }
        return buffer.copyOf(count)
    }

    fun hasWon(state: GameState, player: Int): Boolean {
        val first = Board.firstToken(player)
        for (t in first until first + Board.TOKENS_PER_PLAYER) {
            if (state.steps[t] != Board.FINISH) return false
        }
        return true
    }

    /**
     * Finishes the turn a move from [apply] leaves open: the same player rolls
     * again after an extra turn, otherwise the dice pass on. A won game is
     * left as it is.
     */
    fun settle(state: GameState, move: Move) {
        if (state.winner >= 0) return
        if (move.extraTurn) state.die = 0 else Turns.passTurn(state)
    }
}
