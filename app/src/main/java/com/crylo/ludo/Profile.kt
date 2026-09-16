package com.crylo.ludo

/**
 * A named player who keeps their record between games.
 *
 * Seats refer to a profile by [id], never by name, so renaming someone
 * mid-game relabels their seat instead of orphaning it. Ids come from a
 * counter in [Saves] and are never reused, so a saved game cannot credit a
 * deleted profile's seat to whoever was created after it.
 */
data class Profile(val id: Int, val name: String, val played: Int = 0, val wins: Int = 0)

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
     * Credits a finished game: one game played for every seated profile, and a
     * win for the winner's. Bots and guests carry no profile and so no record.
     * Only a game that reaches a winner counts — an abandoned one is not a loss.
     */
    fun recordGame(profiles: List<Profile>, state: GameState): List<Profile> {
        if (state.winner < 0) return profiles
        val seated = state.profiles.filter { it != NONE }.toSet()
        val winner = state.profiles[state.winner]
        return profiles.map {
            if (it.id !in seated) it
            else it.copy(played = it.played + 1, wins = it.wins + if (it.id == winner) 1 else 0)
        }
    }

    /** One profile per line, name last so it may contain the separator. */
    fun encode(profiles: List<Profile>): String =
        profiles.joinToString("\n") { "${it.id},${it.played},${it.wins},${it.name}" }

    fun decode(saved: String?): List<Profile> {
        if (saved.isNullOrEmpty()) return emptyList()
        // A damaged line costs that one profile, not everybody's.
        return saved.split('\n').mapNotNull { line ->
            val parts = line.split(',', limit = 4)
            if (parts.size != 4) return@mapNotNull null
            val id = parts[0].toIntOrNull() ?: return@mapNotNull null
            val played = parts[1].toIntOrNull() ?: return@mapNotNull null
            val wins = parts[2].toIntOrNull() ?: return@mapNotNull null
            val name = clean(parts[3])
            if (id <= NONE || name.isEmpty()) null else Profile(id, name, played, wins)
        }.distinctBy { it.id }
    }
}
