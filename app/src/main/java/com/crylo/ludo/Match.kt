package com.crylo.ludo

import kotlin.random.Random

/** Which game a table is playing. */
enum class GameKind {
    LUDO,
    SNAKES;

    /** Rebuilds a saved game of this kind, or null if there is none or it is damaged. */
    fun decode(saved: String?): Match? = when (this) {
        LUDO -> GameState.decode(saved)
        SNAKES -> SnakesState.decode(saved)
    }
}

/**
 * What every game's state has in common: who sits where and as whom, whose
 * turn it is, the die, and who has won. The turn loop on the game screen, the
 * saves, the profiles' records and the results all work on this, so none of
 * them needs a copy per game.
 *
 * Each game adds its own [positions], one integer per token, and the whole
 * thing saves as one line: `version|seats|positions|current|die|streak|winner|profiles`,
 * followed by any [extraFields] the game keeps besides.
 */
abstract class Match(val seats: Array<Seat>) {

    abstract val kind: GameKind

    /** Where every token stands, in whatever units the game counts in. */
    abstract val positions: IntArray

    /** Values a position may take; a save holding anything else is refused. */
    protected abstract val positionRange: IntRange

    protected abstract val saveVersion: Int

    /** Profile id per seat, or [Profiles.NONE] for a guest, a bot or an empty seat. */
    val profiles = IntArray(Board.PLAYERS)

    var current = seats.indexOfFirst { it != Seat.NONE }.coerceAtLeast(0)

    /** Current face, or 0 when the player still has to roll. */
    var die = 0

    /** Consecutive sixes this turn; three in a row forfeits it. */
    var sixStreak = 0

    /** Player who has won, or -1 while the game is running. */
    var winner = -1

    fun isBot(player: Int) = seats[player] == Seat.BOT

    /** How far along [player] is, for ranking them: higher is further. */
    abstract fun score(player: Int): Int

    /** How far along [player] is out of 100, rounded down so 100 means won. */
    abstract fun percent(player: Int): Int

    /** Fields this game saves after the shared ones; none of them may hold a "|". */
    protected open fun extraFields(): List<String> = emptyList()

    /**
     * Occupied seats in finishing order: the winner, if there is one, then the
     * rest by [score]. Seat order breaks a tie, which [sameStanding] lets a
     * caller show as a shared place.
     */
    fun standings(): IntArray =
        (0 until Board.PLAYERS)
            .filter { seats[it] != Seat.NONE }
            .sortedWith(compareByDescending<Int> { it == winner }.thenByDescending { score(it) })
            .toIntArray()

    /** Whether two players are level: neither has won, and they are equally far along. */
    fun sameStanding(a: Int, b: Int): Boolean =
        a != winner && b != winner && score(a) == score(b)

    fun encode(): String = buildString {
        append(saveVersion).append('|')
        seats.joinTo(this, ",") { it.ordinal.toString() }
        append('|')
        positions.joinTo(this, ",")
        append('|').append(current)
        append('|').append(die)
        append('|').append(sixStreak)
        append('|').append(winner)
        append('|')
        profiles.joinTo(this, ",")
        for (field in extraFields()) append('|').append(field)
    }

    companion object {
        /** Fields in a save that carries profile ids; see [decode]. */
        const val FIELDS = 8

        /**
         * Rebuilds a game from [encode]'s string, or null if it is damaged:
         * [make] builds an empty game for the saved seats and the game's own
         * [extraFields], or returns null if those are damaged, and [fieldsFor]
         * says how many fields a save of a given version has, or null for a
         * version this build does not know. A save with fewer than [FIELDS]
         * predates profiles, and its human seats come back as guests.
         */
        fun <M : Match> decode(
            saved: String?,
            make: (seats: Array<Seat>, extra: List<String>) -> M?,
            fieldsFor: (Int) -> Int?,
        ): M? {
            val parts = saved?.split('|') ?: return null
            val fields = parts[0].toIntOrNull()?.let(fieldsFor) ?: return null
            if (parts.size != fields) return null
            return try {
                val seats = parts[1].split(',').map { Seat.entries[it.toInt()] }.toTypedArray()
                if (seats.size != Board.PLAYERS) return null
                if (seats.count { it != Seat.NONE } < 2) return null

                make(seats, parts.drop(FIELDS))?.apply {
                    val values = parts[2].split(',').map { it.toInt() }
                    if (values.size != positions.size) return null
                    if (values.any { it !in positionRange }) return null
                    values.forEachIndexed { i, v -> positions[i] = v }

                    current = parts[3].toInt().coerceIn(0, Board.PLAYERS - 1)
                    die = parts[4].toInt().coerceIn(0, 6)
                    sixStreak = parts[5].toInt().coerceIn(0, Turns.SIX_STREAK_LIMIT - 1)
                    winner = parts[6].toInt().let { if (it in 0 until Board.PLAYERS) it else -1 }
                    if (seats[current] == Seat.NONE) current = seats.indexOfFirst { it != Seat.NONE }
                    if (fields >= FIELDS) {
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

/** Passing the dice, the same in every game. */
object Turns {

    const val SIX_STREAK_LIMIT = 3

    /**
     * Hands the first roll of a new game to an occupied seat chosen at random.
     * Going first is a small edge, and without this it would always fall to
     * whoever sits in the lowest seat.
     */
    fun pickStarter(match: Match, random: Random) {
        val occupied = match.seats.indices.filter { match.seats[it] != Seat.NONE }
        match.current = occupied[random.nextInt(occupied.size)]
    }

    /** Next occupied seat clockwise. */
    fun nextPlayer(match: Match): Int {
        var p = match.current
        do {
            p = (p + 1) % Board.PLAYERS
        } while (match.seats[p] == Seat.NONE)
        return p
    }

    /** Hands the dice on and clears the per-turn state. */
    fun passTurn(match: Match) {
        match.current = nextPlayer(match)
        match.die = 0
        match.sixStreak = 0
    }
}
