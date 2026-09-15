package com.vivek.ludo

import android.content.Context

/**
 * A game in progress, kept in SharedPreferences. The whole state encodes to a
 * single short string, so there is nothing here worth a database.
 */
object Saves {

    private const val FILE = "ludo"
    private const val KEY = "game"

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
}
