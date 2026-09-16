package com.crylo.ludo

import android.content.Context

/**
 * Everything the game keeps between launches, in SharedPreferences: the game
 * in progress, the profiles, who last sat where, whether sound is on, and which
 * way the names above the board face. Each encodes to a short string, so there
 * is nothing here worth a database.
 */
object Saves {

    private const val FILE = "ludo"
    private const val KEY = "game"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_NEXT_PROFILE_ID = "next_profile_id"
    private const val KEY_LINEUP = "lineup"
    private const val KEY_SOUND = "sound"
    private const val KEY_NAMES_FACE_TABLE = "names_face_table"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(context: Context, state: GameState) {
        prefs(context).edit().putString(KEY, state.encode()).apply()
    }

    fun load(context: Context): GameState? =
        GameState.decode(prefs(context).getString(KEY, null))

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
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
}
