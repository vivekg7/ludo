package com.crylo.ludo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * The switches that used to sit round the game: sound, the reactions to a
 * capture, and which way the top names face. Opened from the setup screen and
 * from the game's settings button, which picks the changes up when it comes back.
 *
 * Each switch writes through to [Saves] as it is flipped, so there is nothing
 * to confirm and nothing lost by leaving with Back.
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(20))
        }

        // Back at the start and the title centred, as the game's banner is.
        val header = FrameLayout(this)
        header.addView(TextView(this).apply {
            text = getString(R.string.settings)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Style.TEXT)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        header.addView(TextView(this).apply {
            text = "\u2039" // ‹
            textSize = 34f
            setTextColor(Style.TEXT_DIM)
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.back)
            background = Style.panel(this@SettingsActivity, Color.TRANSPARENT, radiusDp = 24)
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL))
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(16) })

        root.addView(switchRow(R.string.setting_sound, R.string.setting_sound_detail, Saves.soundOn(this)) {
            Saves.setSoundOn(this, it)
        })
        root.addView(switchRow(R.string.setting_reactions, R.string.setting_reactions_detail, Saves.reactionsOn(this)) {
            Saves.setReactionsOn(this, it)
        })
        root.addView(switchRow(R.string.setting_face_table, R.string.setting_face_table_detail, Saves.namesFaceTable(this)) {
            Saves.setNamesFaceTable(this, it)
        })

        // No wider than reads well, on a phone turned sideways or a tablet.
        val centre = FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(Style.readableWidth(this@SettingsActivity), WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Style.BACKGROUND)
            isFillViewport = true
            addView(centre, MATCH_PARENT, WRAP_CONTENT)
        }
        scroll.padForSystemBars()
        return scroll
    }

    /**
     * A whole-width row with a title, a line saying what it does, and a switch.
     * Tapping anywhere on the row flips it, as on the system's own settings.
     */
    private fun switchRow(title: Int, detail: Int, on: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Style.panel(this@SettingsActivity, Style.SURFACE)
            minimumHeight = dp(64)
            setPadding(dp(16), dp(12), dp(12), dp(12))
        }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(this).apply {
            setText(title)
            textSize = 17f
            setTextColor(Style.TEXT)
        })
        text.addView(TextView(this).apply {
            setText(detail)
            textSize = 13f
            setTextColor(Style.TEXT_DIM)
        })
        row.addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val switch = Switch(this).apply {
            isChecked = on
            contentDescription = getString(title)
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        row.addView(switch, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(12) })
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { switch.toggle() }

        return row.also {
            it.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
    }

    private fun dp(value: Int) = Style.dp(this, value)

    companion object {
        fun open(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}
