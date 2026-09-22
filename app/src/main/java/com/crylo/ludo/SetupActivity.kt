package com.crylo.ludo

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Seat picker: who is playing which colour, the profiles they play as, and a
 * way back into a saved game.
 */
class SetupActivity : Activity() {

    private val seats = arrayOf(Seat.HUMAN, Seat.BOT, Seat.BOT, Seat.BOT)

    /** Profile id per seat; only meaningful where the seat is [Seat.HUMAN]. */
    private val seatProfiles = IntArray(Board.PLAYERS)
    private val seatTiles = arrayOfNulls<View>(Board.PLAYERS)
    private val seatColours = arrayOfNulls<TextView>(Board.PLAYERS)
    private val seatTitles = arrayOfNulls<TextView>(Board.PLAYERS)
    private val seatCaptions = arrayOfNulls<TextView>(Board.PLAYERS)

    private var profiles = emptyList<Profile>()

    /** The saved game, if there is one, as of the last time this screen came back. */
    private var saved: GameState? = null

    private lateinit var preview: BoardView
    private lateinit var savedSection: View
    private lateinit var savedSummary: TextView
    private lateinit var startButton: Button
    private lateinit var warning: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Saves.loadLineup(this, seats, seatProfiles)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        // The saved game may have been finished or abandoned since we last
        // looked, and a finished game has changed the profiles' records.
        profiles = Saves.profiles(this)
        saved = Saves.load(this)
        refreshSaved()
        refreshSeats()
    }

    private fun start() {
        if (seats.count { it != Seat.NONE } < 2) return
        // Starting throws the saved game away, so that is never done on one
        // tap that sits right next to the button for getting it back.
        val game = saved
        if (game == null) {
            startNewGame()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.replace_save_title)
            .setMessage(getString(R.string.replace_save_detail, summaryOf(game)))
            .setPositiveButton(R.string.replace_save_confirm) { _, _ -> startNewGame() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startNewGame() {
        Saves.clear(this)
        startActivity(GameActivity.newGame(this, seats, seatProfiles))
    }

    // --- saved game --------------------------------------------------------

    /**
     * Shows the saved game above the lineup, with its players and how far each
     * has got, and makes resuming it the main action rather than starting over.
     */
    private fun refreshSaved() {
        val game = saved
        savedSection.visibility = if (game != null) View.VISIBLE else View.GONE
        if (game != null) savedSummary.text = summaryOf(game)
        Style.restyle(startButton, if (game != null) Style.Kind.SECONDARY else Style.Kind.PRIMARY)
    }

    /** "Vivek 34% · Jyoti 21% · Bot 1 12%", leader first. */
    private fun summaryOf(game: GameState): String {
        val names = Profiles.seatNames(game.seats, game.profiles, profiles) { getString(R.string.bot_name, it) }
        return Rules.standings(game).joinToString(" · ") { player ->
            getString(R.string.saved_player, names[player], Board.travelPercent(game.travelled(player)))
        }
    }

    // --- seats -------------------------------------------------------------

    private fun seat(player: Int, seat: Seat, profile: Int = Profiles.NONE) {
        seats[player] = seat
        seatProfiles[player] = if (seat == Seat.HUMAN) profile else Profiles.NONE
        refreshSeats()
    }

    /**
     * Relabels every seat, turning any whose profile has gone into a guest, and
     * remembers the lineup. Saved on every change rather than on Start, so
     * picking seats and then leaving the app does not throw the picks away.
     */
    private fun refreshSeats() {
        val ids = profiles.mapTo(HashSet()) { it.id }
        for (player in 0 until Board.PLAYERS) {
            if (seatProfiles[player] !in ids) seatProfiles[player] = Profiles.NONE
        }
        val names = Profiles.seatNames(seats, seatProfiles, profiles) { getString(R.string.bot_name, it) }
        for (player in 0 until Board.PLAYERS) showSeat(player, names[player])

        val players = seats.count { it != Seat.NONE }
        val ready = players >= 2
        startButton.text = if (ready) getString(R.string.start_game_count, players) else getString(R.string.start_game)
        Style.setEnabled(startButton, ready)
        warning.text = if (ready) "" else getString(R.string.need_two_players)
        warning.visibility = if (ready) View.GONE else View.VISIBLE

        // A copy, so the preview's state is not changed under it by the next pick.
        preview.showState(GameState(seats.copyOf()))
        Saves.saveLineup(this, seats, seatProfiles)
    }

    /**
     * One seat's tile: its colour, who sits there, and what kind of seat it is
     * — a profile's record, a guest, a bot, or empty. A taken seat is tinted
     * in its colour and an empty one only outlined and dimmed, so the players
     * stand out at a glance.
     */
    private fun showSeat(player: Int, name: String) {
        val colour = Board.names[player]
        val profile = profiles.firstOrNull { it.id == seatProfiles[player] }
        val title = when (seats[player]) {
            Seat.HUMAN -> profile?.name ?: getString(R.string.seat_guest)
            Seat.BOT -> name
            Seat.NONE -> getString(R.string.seat_off)
        }
        val detail = when {
            seats[player] == Seat.BOT -> getString(R.string.seat_bot_caption)
            seats[player] == Seat.NONE -> getString(R.string.seat_off_caption)
            profile == null -> getString(R.string.seat_guest_caption)
            profile.played == 0 -> getString(R.string.seat_no_games)
            else -> getString(R.string.record, profile.wins, profile.played)
        }
        val taken = seats[player] != Seat.NONE

        seatTitles[player]?.apply {
            text = title
            setTextColor(if (taken) Style.TEXT else Style.TEXT_DIM)
            typeface = if (taken) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        seatCaptions[player]?.text = detail
        seatColours[player]?.alpha = if (taken) 1f else EMPTY_ALPHA
        seatTiles[player]?.apply {
            background = Style.seatTile(this@SetupActivity, Board.colors[player], taken)
            contentDescription = getString(R.string.seat_title, colour) + ": " + title + ", " + detail
        }
    }

    private fun chooseSeat(player: Int) {
        // One person cannot sit in two seats, so profiles seated elsewhere are left out.
        val free = profiles.filter { p -> (0 until Board.PLAYERS).none { it != player && seatProfiles[it] == p.id } }
        val labels = free.map { it.name } + listOf(
            getString(R.string.seat_guest),
            getString(R.string.seat_bot),
            getString(R.string.seat_off),
            getString(R.string.new_profile),
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.seat_title, Board.names[player]))
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < free.size) {
                    seat(player, Seat.HUMAN, free[which].id)
                    return@setItems
                }
                when (which - free.size) {
                    0 -> seat(player, Seat.HUMAN)
                    1 -> seat(player, Seat.BOT)
                    2 -> seat(player, Seat.NONE)
                    else -> askName(getString(R.string.new_profile_title), "") { name ->
                        val profile = addProfile(name)
                        seat(player, Seat.HUMAN, profile.id)
                    }
                }
            }
            .show()
    }

    // --- profiles ----------------------------------------------------------

    private fun updateProfiles(updated: List<Profile>) {
        profiles = updated
        Saves.saveProfiles(this, updated)
        // The saved game's summary names its players too.
        refreshSaved()
        refreshSeats()
    }

    private fun addProfile(name: String): Profile {
        val id = Saves.takeProfileId(this)
        updateProfiles(Profiles.create(profiles, id, name))
        return profiles.last()
    }

    /** The profiles as a leaderboard, best record first; tapping one renames or deletes it. */
    private fun manageProfiles() {
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.profiles)
            .setPositiveButton(R.string.done, null)
            .setNeutralButton(R.string.new_profile) { _, _ ->
                askName(getString(R.string.new_profile_title), "") { name ->
                    addProfile(name)
                    manageProfiles()
                }
            }
        if (profiles.isEmpty()) {
            builder.setMessage(R.string.no_profiles)
        } else {
            val ranked = Profiles.ranked(profiles)
            val labels = ranked.map {
                if (it.played == 0) getString(R.string.profile_unplayed, it.name)
                else getString(R.string.profile_record, it.name, it.wins, it.played, Profiles.winPercent(it))
            }
            builder.setItems(labels.toTypedArray()) { _, which -> editProfile(ranked[which]) }
        }
        builder.show()
    }

    private fun editProfile(profile: Profile) {
        AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setItems(arrayOf(getString(R.string.rename), getString(R.string.delete))) { _, which ->
                if (which == 0) {
                    askName(getString(R.string.rename), profile.name, profile.id) { name ->
                        updateProfiles(Profiles.rename(profiles, profile.id, name))
                        manageProfiles()
                    }
                } else {
                    confirmDelete(profile)
                }
            }
            .setOnCancelListener { manageProfiles() }
            .show()
    }

    private fun confirmDelete(profile: Profile) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_profile, profile.name))
            .setMessage(R.string.delete_profile_detail)
            .setPositiveButton(R.string.delete) { _, _ ->
                updateProfiles(Profiles.delete(profiles, profile.id))
                manageProfiles()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> manageProfiles() }
            .show()
    }

    /**
     * Prompts for a name and hands [onName] a cleaned, valid one. The dialog
     * stays open on a bad name, so the positive button is wired after show():
     * AlertDialog's own listener would always dismiss it.
     */
    private fun askName(title: String, initial: String, exceptId: Int = Profiles.NONE, onName: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(initial.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(InputFilter.LengthFilter(Profiles.MAX_NAME_LENGTH))
            hint = getString(R.string.name_hint)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val frame = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(frame)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            // A hardware Enter reports both key down and key up; acting on both
            // would save twice and, after a rename, reopen the list twice.
            input.setOnEditorActionListener { _, _, event ->
                if (event == null || event.action == KeyEvent.ACTION_DOWN) save.performClick()
                true
            }
            save.setOnClickListener {
                val name = Profiles.clean(input.text.toString())
                when (Profiles.problemWith(profiles, name, exceptId)) {
                    null -> {
                        dialog.dismiss()
                        onName(name)
                    }
                    Profiles.NameProblem.EMPTY -> input.error = getString(R.string.name_empty)
                    Profiles.NameProblem.TOO_LONG -> input.error = getString(R.string.name_too_long)
                    Profiles.NameProblem.TAKEN -> input.error = getString(R.string.name_taken)
                }
            }
        }
        dialog.show()
        input.requestFocus()
    }

    // --- ui ----------------------------------------------------------------

    private fun buildUi(): View {
        // The board, the title and the tagline; then everything to do with
        // the next game. Stacked on a phone held upright, side by side on one
        // turned sideways, where stacked they would need scrolling to reach Start.
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // The lineup drawn as the board it will be played on: seated colours
        // with their tokens waiting, empty ones greyed out, as in the game.
        // Kept small: the seat grid below is where the lineup is picked.
        preview = BoardView(this).apply {
            bare = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        brand.addView(preview, LinearLayout.LayoutParams(dp(PREVIEW_DP), dp(PREVIEW_DP)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // The title centred, with the settings button at the end of its row,
        // where the game screen has it too.
        val title = FrameLayout(this)
        title.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 32f
            setTextColor(Style.TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        title.addView(Style.settingsButton(this) {
            startActivity(SettingsActivity.open(this))
        }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
        brand.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        brand.addView(TextView(this).apply {
            text = getString(R.string.tagline)
            textSize = 14f
            setTextColor(Style.TEXT_DIM)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })

        savedSection = buildSavedSection()
        form.addView(savedSection, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        form.addView(Style.heading(this, getString(R.string.heading_new)), headingParams())
        // Seats laid out as the yards are on the board, so it is plain which
        // are neighbours and which sit opposite: a list put Red and Green, the
        // first two, side by side on the board.
        for (row in BOARD_ROWS) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { i, player ->
                line.addView(seatTile(player), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
                    if (i > 0) leftMargin = dp(8)
                })
            }
            form.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }

        warning = TextView(this).apply {
            textSize = 13f
            setTextColor(Style.WARNING)
            gravity = Gravity.CENTER
        }
        form.addView(warning, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        startButton = Style.button(this, getString(R.string.start_game), Style.Kind.PRIMARY).apply {
            setOnClickListener { start() }
        }
        form.addView(startButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        form.addView(Style.button(this, getString(R.string.profiles), Style.Kind.QUIET).apply {
            setOnClickListener { manageProfiles() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

        val landscape = Style.isLandscape(this)
        val root = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        if (landscape) {
            root.addView(brand, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            root.addView(form, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.4f).apply { leftMargin = dp(24) })
        } else {
            root.addView(brand, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            root.addView(form, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        // Scrolls on a short screen or at a large font size; centred otherwise,
        // and no wider than reads well on a tablet held upright.
        val centre = FrameLayout(this).apply {
            val width = if (landscape) MATCH_PARENT else Style.readableWidth(this@SetupActivity)
            addView(root, FrameLayout.LayoutParams(width, WRAP_CONTENT, Gravity.CENTER))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Style.BACKGROUND)
            isFillViewport = true
            addView(centre, MATCH_PARENT, WRAP_CONTENT)
        }
        scroll.padForSystemBars()
        return scroll
    }

    /** The saved game, when there is one: a heading and a card that resumes it. */
    private fun buildSavedSection(): View {
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        section.addView(Style.heading(this, getString(R.string.heading_saved)), headingParams())

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Style.panel(this@SetupActivity, Style.ACCENT, darkRipple = true)
            minimumHeight = dp(64)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { startActivity(GameActivity.resume(this@SetupActivity)) }
        }
        card.addView(TextView(this).apply {
            text = getString(R.string.resume_game)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Style.ON_ACCENT)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        savedSummary = TextView(this).apply {
            textSize = 13f
            setTextColor(Style.ON_ACCENT)
            alpha = 0.8f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(savedSummary, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        section.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return section
    }

    /** A seat as a tile, tapped to pick who sits there. Filled in by [showSeat]. */
    private fun seatTile(player: Int): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(96)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { chooseSeat(player) }
        }
        val colour = TextView(this).apply {
            text = Board.names[player]
            isAllCaps = true
            textSize = 12f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Board.colors[player])
        }
        val title = TextView(this).apply {
            textSize = 17f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val caption = TextView(this).apply {
            textSize = 13f
            setTextColor(Style.TEXT_DIM)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        tile.addView(colour)
        tile.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        tile.addView(caption)

        seatTiles[player] = tile
        seatColours[player] = colour
        seatTitles[player] = title
        seatCaptions[player] = caption
        return tile
    }

    private fun headingParams() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        topMargin = dp(20)
        bottomMargin = dp(8)
        leftMargin = dp(4)
    }

    private fun dp(value: Int) = Style.dp(this, value)

    private companion object {
        /** Seats row by row as their yards sit on the board: Red, Green over Blue, Yellow. */
        val BOARD_ROWS = arrayOf(intArrayOf(0, 1), intArrayOf(3, 2))

        const val EMPTY_ALPHA = 0.5f

        const val PREVIEW_DP = 112
    }
}
