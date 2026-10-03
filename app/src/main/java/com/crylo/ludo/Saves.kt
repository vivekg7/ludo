package com.crylo.ludo

import android.content.Context

/**
 * Everything the game keeps between launches, in SharedPreferences: a game in
 * progress for each kind of game, the profiles, who last sat where, which game
 * was picked last, whether sound is on, which way the names above the board
 * face, and whether the board reacts to captures. Each encodes to a short
 * string, so there is nothing here worth a database.
 */
object Saves {

    private const val FILE = "ludo"
    // Ludo keeps the key it had before there was a second game, so a game
    // left in progress across that update still resumes.
    private const val KEY_LUDO = "game"
    private const val KEY_SNAKES = "game_snakes"
    private const val KEY_KIND = "kind"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_NEXT_PROFILE_ID = "next_profile_id"
    private const val KEY_LINEUP = "lineup"
    private const val KEY_SOUND = "sound"
    private const val KEY_NAMES_FACE_TABLE = "names_face_table"
    private const val KEY_REACTIONS = "reactions"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- saved games -------------------------------------------------------

    /**
     * One slot per kind of game, so starting a game of one does not throw
     * away a game of the other left half played.
     */
    private fun keyOf(kind: GameKind) = when (kind) {
        GameKind.LUDO -> KEY_LUDO
        GameKind.SNAKES -> KEY_SNAKES
    }

    fun save(context: Context, match: Match) {
        prefs(context).edit().putString(keyOf(match.kind), match.encode()).apply()
    }

    /** The saved game of [kind], still encoded, or null if there is none. */
    fun saved(context: Context, kind: GameKind): String? = prefs(context).getString(keyOf(kind), null)

    fun load(context: Context, kind: GameKind): Match? = kind.decode(saved(context, kind))

    fun clear(context: Context, kind: GameKind) {
        prefs(context).edit().remove(keyOf(kind)).apply()
    }

    /** The game picked last on the setup screen, so it opens on that one again. */
    fun kind(context: Context): GameKind =
        GameKind.entries.getOrNull(prefs(context).getInt(KEY_KIND, 0)) ?: GameKind.LUDO

    fun setKind(context: Context, kind: GameKind) {
        prefs(context).edit().putInt(KEY_KIND, kind.ordinal).apply()
    }

    // --- profiles ----------------------------------------------------------

    fun profiles(context: Context): List<Profile> =
        Profiles.decode(prefs(context).getString(KEY_PROFILES, null))

    fun saveProfiles(context: Context, profiles: List<Profile>) {
        prefs(context).edit().putString(KEY_PROFILES, Profiles.encode(profiles)).apply()
    }

    /**
     * Hands out a fresh profile id. A counter rather than max + 1, because
     * deleting the newest profile would otherwise free its id for the next one,
     * and a saved game still seating the old id would credit the newcomer.
     */
    fun takeProfileId(context: Context): Int {
        val prefs = prefs(context)
        val existing = profiles(context).maxOfOrNull { it.id } ?: Profiles.NONE
        val id = maxOf(prefs.getInt(KEY_NEXT_PROFILE_ID, 1), existing + 1)
        prefs.edit().putInt(KEY_NEXT_PROFILE_ID, id + 1).apply()
        return id
    }

    // --- lineup ------------------------------------------------------------

    /** Remembers the seating so the same family does not pick seats every game. */
    fun saveLineup(context: Context, seats: Array<Seat>, profiles: IntArray) {
        val encoded = seats.indices.joinToString(",") { "${seats[it].ordinal}:${profiles[it]}" }
        prefs(context).edit().putString(KEY_LINEUP, encoded).apply()
    }

    /** Fills [seats] and [profiles] from the last lineup; returns false if there is none. */
    fun loadLineup(context: Context, seats: Array<Seat>, profiles: IntArray): Boolean {
        val entries = prefs(context).getString(KEY_LINEUP, null)?.split(',') ?: return false
        if (entries.size != seats.size) return false
        val parsed = entries.map { entry ->
            val (seat, id) = entry.split(':').takeIf { it.size == 2 } ?: return false
            (Seat.entries.getOrNull(seat.toIntOrNull() ?: -1) ?: return false) to (id.toIntOrNull() ?: return false)
        }
        parsed.forEachIndexed { i, (seat, id) ->
            seats[i] = seat
            profiles[i] = if (seat == Seat.HUMAN) id else Profiles.NONE
        }
        return true
    }

    // --- sound -------------------------------------------------------------

    fun soundOn(context: Context): Boolean = prefs(context).getBoolean(KEY_SOUND, true)

    fun setSoundOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SOUND, on).apply()
    }

    // --- board -------------------------------------------------------------

    fun namesFaceTable(context: Context): Boolean = prefs(context).getBoolean(KEY_NAMES_FACE_TABLE, false)

    fun setNamesFaceTable(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_NAMES_FACE_TABLE, on).apply()
    }

    fun reactionsOn(context: Context): Boolean = prefs(context).getBoolean(KEY_REACTIONS, true)

    fun setReactionsOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_REACTIONS, on).apply()
    }
}
