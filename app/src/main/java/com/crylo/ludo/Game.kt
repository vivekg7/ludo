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
 * The entire game. Small enough to copy, serialise and hand around whole,
 * which is what keeps saving a game to SharedPreferences a one-liner.
 */
class GameState(val seats: Array<Seat>) {

    /** Profile id per seat, or [Profiles.NONE] for a guest, a bot or an empty seat. */
    val profiles = IntArray(Board.PLAYERS)

    /** Position of all sixteen tokens, indexed player * 4 + slot. See [Board]. */
    val steps = IntArray(Board.TOKENS)

    var current = seats.indexOfFirst { it != Seat.NONE }.coerceAtLeast(0)

    /** Current face, or 0 when the player still has to roll. */
    var die = 0

    /** Consecutive sixes this turn; three in a row forfeits it. */
    var sixStreak = 0

    /** Player who has all four tokens home, or -1 while the game is running. */
    var winner = -1

    fun isBot(player: Int) = seats[player] == Seat.BOT

    fun encode(): String = buildString {
        append(SAVE_VERSION).append('|')
        seats.joinTo(this, ",") { it.ordinal.toString() }
        append('|')
        steps.joinTo(this, ",")
        append('|').append(current)
        append('|').append(die)
        append('|').append(sixStreak)
        append('|').append(winner)
        append('|')
        profiles.joinTo(this, ",")
    }

    companion object {
        private const val SAVE_VERSION = 2

        fun decode(saved: String?): GameState? {
            val parts = saved?.split('|') ?: return null
            // Version 1 predates profiles and is otherwise identical, so a game
            // left in progress across the update still resumes, with guests.
            val fields = when (parts[0].toIntOrNull()) {
                1 -> 7
                SAVE_VERSION -> 8
                else -> return null
            }
            if (parts.size != fields) return null
            return try {
                val seats = parts[1].split(',').map { Seat.entries[it.toInt()] }.toTypedArray()
                val positions = parts[2].split(',').map { it.toInt() }
                if (seats.size != Board.PLAYERS || positions.size != Board.TOKENS) return null
                if (seats.count { it != Seat.NONE } < 2) return null
                if (positions.any { it !in 0..Board.FINISH }) return null

                GameState(seats).apply {
                    positions.forEachIndexed { i, v -> steps[i] = v }
                    current = parts[3].toInt().coerceIn(0, Board.PLAYERS - 1)
                    die = parts[4].toInt().coerceIn(0, 6)
                    sixStreak = parts[5].toInt().coerceIn(0, 2)
                    winner = parts[6].toInt().let { if (it in 0 until Board.PLAYERS) it else -1 }
                    if (seats[current] == Seat.NONE) current = seats.indexOfFirst { it != Seat.NONE }
                    if (fields == 8) {
                        val ids = parts[7].split(',').map { it.toInt() }
                        if (ids.size != Board.PLAYERS) return null
                        for (p in 0 until Board.PLAYERS) {
                            profiles[p] = if (seats[p] == Seat.HUMAN && ids[p] > 0) ids[p] else Profiles.NONE
                        }
                    }
                }
            } catch (e: RuntimeException) {
                // A corrupt or older save is not worth crashing over; the
                // caller just starts a fresh game instead.
                null
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

    const val SIX_STREAK_LIMIT = 3

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

    /** Next occupied seat clockwise. */
    fun nextPlayer(state: GameState): Int {
        var p = state.current
        do {
            p = (p + 1) % Board.PLAYERS
        } while (state.seats[p] == Seat.NONE)
        return p
    }

    /**
     * Finishes the turn a move from [apply] leaves open: the same player rolls
     * again after an extra turn, otherwise the dice pass on. A won game is
     * left as it is.
     */
    fun settle(state: GameState, move: Move) {
        if (state.winner >= 0) return
        if (move.extraTurn) state.die = 0 else passTurn(state)
    }

    /** Hands the dice on and clears the per-turn state. */
    fun passTurn(state: GameState) {
        state.current = nextPlayer(state)
        state.die = 0
        state.sixStreak = 0
    }
}
