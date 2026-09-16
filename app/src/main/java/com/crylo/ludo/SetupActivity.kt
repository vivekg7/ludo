package com.crylo.ludo

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.TextView

/**
 * Seat picker: who is playing which colour, the profiles they play as, and a
 * way back into a saved game.
 */
class SetupActivity : Activity() {

    private val seats = arrayOf(Seat.HUMAN, Seat.BOT, Seat.BOT, Seat.BOT)

    /** Profile id per seat; only meaningful where the seat is [Seat.HUMAN]. */
    private val seatProfiles = IntArray(Board.PLAYERS)
    private val seatButtons = arrayOfNulls<Button>(Board.PLAYERS)

    private var profiles = emptyList<Profile>()

    private lateinit var resume: Button
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
        resume.visibility = if (Saves.load(this) != null) View.VISIBLE else View.GONE
        profiles = Saves.profiles(this)
        refreshSeats()
    }

    private fun start() {
        if (seats.count { it != Seat.NONE } < 2) {
            warning.text = getString(R.string.need_two_players)
            return
        }
        Saves.clear(this)
        startActivity(GameActivity.newGame(this, seats, seatProfiles))
    }

    // --- seats -------------------------------------------------------------

    private fun seat(player: Int, seat: Seat, profile: Int = Profiles.NONE) {
        seats[player] = seat
        seatProfiles[player] = if (seat == Seat.HUMAN) profile else Profiles.NONE
        warning.text = ""
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
            seatButtons[player]?.text = labelFor(player)
        }
        Saves.saveLineup(this, seats, seatProfiles)
    }

    private fun labelFor(player: Int): String = when (seats[player]) {
        Seat.HUMAN -> profiles.firstOrNull { it.id == seatProfiles[player] }?.name
            ?: getString(R.string.seat_guest)
        Seat.BOT -> getString(R.string.seat_bot)
        Seat.NONE -> getString(R.string.seat_off)
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
        refreshSeats()
    }

    private fun addProfile(name: String): Profile {
        val id = Saves.takeProfileId(this)
        updateProfiles(Profiles.create(profiles, id, name))
        return profiles.last()
    }

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
            val labels = profiles.map { getString(R.string.profile_record, it.name, it.wins, it.played) }
            builder.setItems(labels.toTypedArray()) { _, which -> editProfile(profiles[which]) }
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 40f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = getString(R.string.tagline)
            textSize = 14f
            setTextColor(0xFF9AA3AF.toInt())
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(28) })

        for (player in 0 until Board.PLAYERS) root.addView(seatRow(player))

        warning = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFE57373.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(warning, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        root.addView(Button(this).apply {
            text = getString(R.string.start_game)
            setOnClickListener { start() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        resume = Button(this).apply {
            text = getString(R.string.resume_game)
            visibility = View.GONE
            setOnClickListener { startActivity(GameActivity.resume(this@SetupActivity)) }
        }
        root.addView(resume, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(Button(this).apply {
            text = getString(R.string.profiles)
            setOnClickListener { manageProfiles() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.padForSystemBars()
        return root
    }

    private fun seatRow(player: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        row.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Board.colors[player])
            }
        }, LinearLayout.LayoutParams(dp(22), dp(22)))

        row.addView(TextView(this).apply {
            text = Board.names[player]
            textSize = 17f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(14) })

        val button = Button(this).apply {
            text = labelFor(player)
            // Names keep the case they were typed in.
            isAllCaps = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minWidth = dp(140)
            setOnClickListener { chooseSeat(player) }
        }
        seatButtons[player] = button
        row.addView(button, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        return row
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val BACKGROUND = 0xFF12161C.toInt()
    }
}
