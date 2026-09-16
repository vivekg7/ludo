package com.crylo.ludo

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Seat picker: who is playing which colour, plus a way back into a saved game. */
class SetupActivity : Activity() {

    private val seats = arrayOf(Seat.HUMAN, Seat.BOT, Seat.BOT, Seat.BOT)
    private val seatButtons = arrayOfNulls<Button>(Board.PLAYERS)

    private lateinit var resume: Button
    private lateinit var warning: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        // The saved game may have been finished or abandoned since we last looked.
        resume.visibility = if (Saves.load(this) != null) View.VISIBLE else View.GONE
    }

    private fun start() {
        if (seats.count { it != Seat.NONE } < 2) {
            warning.text = getString(R.string.need_two_players)
            return
        }
        Saves.clear(this)
        startActivity(GameActivity.newGame(this, seats))
    }

    private fun cycle(player: Int) {
        seats[player] = when (seats[player]) {
            Seat.HUMAN -> Seat.BOT
            Seat.BOT -> Seat.NONE
            Seat.NONE -> Seat.HUMAN
        }
        seatButtons[player]?.text = labelFor(seats[player])
        warning.text = ""
    }

    private fun labelFor(seat: Seat) = getString(
        when (seat) {
            Seat.HUMAN -> R.string.seat_human
            Seat.BOT -> R.string.seat_bot
            Seat.NONE -> R.string.seat_off
        }
    )

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
            text = labelFor(seats[player])
            minWidth = dp(112)
            setOnClickListener { cycle(player) }
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
