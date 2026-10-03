package com.crylo.ludo

/** Games finished and games won, in one game. */
data class Record(val played: Int = 0, val wins: Int = 0)

/**
 * A named player who keeps a record in each game between sessions. A Ludo win
 * and a Snakes & Ladders win are kept apart, since one is a game of choices
 * and the other pure luck, and the two leaderboards should not mix them.
 *
 * Seats refer to a profile by [id], never by name, so renaming someone
 * mid-game relabels their seat instead of orphaning it. Ids come from a
 * counter in [Saves] and are never reused, so a saved game cannot credit a
 * deleted profile's seat to whoever was created after it.
 */
data class Profile(
    val id: Int,
    val name: String,
    val ludo: Record = Record(),
    val snakes: Record = Record(),
) {
    fun record(kind: GameKind): Record = when (kind) {
        GameKind.LUDO -> ludo
        GameKind.SNAKES -> snakes
    }

    fun withRecord(kind: GameKind, record: Record): Profile = when (kind) {
        GameKind.LUDO -> copy(ludo = record)
        GameKind.SNAKES -> copy(snakes = record)
    }
}

/**
 * The list of profiles, as pure functions. Like [Rules], nothing here touches
 * Android, so it is tested from plain JVM unit tests.
 */
object Profiles {

    /** Seat value meaning "no profile": a guest, a bot, or an empty seat. */
    const val NONE = 0

    const val MAX_NAME_LENGTH = 16

    /** Why a name was refused, or null if it is fine to use. */
    enum class NameProblem { EMPTY, TOO_LONG, TAKEN }

    /** Collapses whitespace so a name always fits on one line of the save. */
    fun clean(name: String): String = name.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }.joinToString(" ")

    /** Checks a cleaned name, ignoring the profile being renamed ([exceptId]). */
    fun problemWith(profiles: List<Profile>, name: String, exceptId: Int = NONE): NameProblem? = when {
        name.isEmpty() -> NameProblem.EMPTY
        name.length > MAX_NAME_LENGTH -> NameProblem.TOO_LONG
        // Two "Mum"s at one table would be indistinguishable on the turn banner.
        profiles.any { it.id != exceptId && it.name.equals(name, ignoreCase = true) } -> NameProblem.TAKEN
        else -> null
    }

    fun create(profiles: List<Profile>, nextId: Int, name: String): List<Profile> =
        profiles + Profile(nextId, name)

    fun rename(profiles: List<Profile>, id: Int, name: String): List<Profile> =
        profiles.map { if (it.id == id) it.copy(name = name) else it }

    fun delete(profiles: List<Profile>, id: Int): List<Profile> =
        profiles.filterNot { it.id == id }

    /**
     * Credits a finished game to the record for its kind: one game played for
     * every seated profile, and a win for the winner's. Bots and guests carry
     * no profile and so no record. Only a game that reaches a winner counts —
     * an abandoned one is not a loss.
     */
    fun recordGame(profiles: List<Profile>, match: Match): List<Profile> {
        if (match.winner < 0) return profiles
        val seated = match.profiles.filter { it != NONE }.toSet()
        val winner = match.profiles[match.winner]
        return profiles.map {
            if (it.id !in seated) return@map it
            val record = it.record(match.kind)
            it.withRecord(match.kind, Record(record.played + 1, record.wins + if (it.id == winner) 1 else 0))
        }
    }

    /**
     * Best record in [kind] first: most wins, then fewest games taken to win
     * them, then by name. Ranked by wins rather than win rate, so one lucky
     * first game does not put a newcomer above someone who has won ten.
     */
    fun ranked(profiles: List<Profile>, kind: GameKind): List<Profile> =
        profiles.sortedWith(
            compareByDescending<Profile> { it.record(kind).wins }
                .thenBy { it.record(kind).played }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )

    /** Share of games won, rounded down so 100 means never lost. */
    fun winPercent(record: Record): Int = if (record.played == 0) 0 else record.wins * 100 / record.played

    /**
     * What each seat is called on screen: its profile's name, "Bot 1", "Bot 2"
     * and so on for bots in seat order, or its colour for a guest, an empty
     * seat, or a profile that has since been deleted. Shared by the game screen
     * and the setup screen so the two never disagree about who is who.
     */
    fun seatNames(
        seats: Array<Seat>,
        seatProfiles: IntArray,
        profiles: List<Profile>,
        botName: (Int) -> String,
    ): Array<String> {
        val byId = profiles.associateBy { it.id }
        var bots = 0
        return Array(Board.PLAYERS) { player ->
            when (seats[player]) {
                Seat.BOT -> botName(++bots)
                Seat.HUMAN -> byId[seatProfiles[player]]?.name ?: Board.names[player]
                Seat.NONE -> Board.names[player]
            }
        }
    }

    /**
     * A version line, then one profile per line: its id, its Ludo record, its
     * Snakes & Ladders record, and its name last so it may contain the
     * separator. A line cannot start with "#", since every profile line starts
     * with its id, which is how the version line is told apart.
     */
    fun encode(profiles: List<Profile>): String =
        (listOf(VERSION_LINE) + profiles.map {
            "${it.id},${it.ludo.played},${it.ludo.wins},${it.snakes.played},${it.snakes.wins},${it.name}"
        }).joinToString("\n")

    fun decode(saved: String?): List<Profile> {
        if (saved.isNullOrEmpty()) return emptyList()
        val lines = saved.split('\n')
        // Before there was a second game the save had no version line, and
        // each line held the one record, which was Ludo's.
        val current = lines[0] == VERSION_LINE
        val fields = if (current) 6 else 4
        // A damaged line costs that one profile, not everybody's.
        return lines.drop(if (current) 1 else 0).mapNotNull { line ->
            val parts = line.split(',', limit = fields)
            if (parts.size != fields) return@mapNotNull null
            val numbers = parts.dropLast(1).map { it.toIntOrNull() ?: return@mapNotNull null }
            val id = numbers[0]
            val name = clean(parts.last())
            if (id <= NONE || name.isEmpty()) return@mapNotNull null
            val ludo = Record(numbers[1], numbers[2])
            val snakes = if (current) Record(numbers[3], numbers[4]) else Record()
            Profile(id, name, ludo, snakes)
        }.distinctBy { it.id }
    }

    private const val VERSION_LINE = "#2"
}
