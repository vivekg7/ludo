package com.crylo.ludo

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * Colours and the few widgets the setup and game screens share. Built in code
 * like the rest of the UI: a Material or AppCompat button style would cost
 * more APK than the whole game.
 */
internal object Style {

    const val BACKGROUND = 0xFF12161C.toInt()
    const val SURFACE = 0xFF1D232C.toInt()
    const val OUTLINE = 0xFF3A4350.toInt()
    const val ACCENT = 0xFFFFB300.toInt()
    const val ON_ACCENT = 0xFF231800.toInt()
    const val TEXT = Color.WHITE
    const val TEXT_DIM = 0xFF9AA3AF.toInt()
    const val TEXT_FAINT = 0xFF6B7380.toInt()
    const val WARNING = 0xFFE57373.toInt()

    private const val RIPPLE_LIGHT = 0x29FFFFFF
    private const val RIPPLE_DARK = 0x29000000

    /** How strongly a button reads: the one thing to do, an alternative, or a way elsewhere. */
    enum class Kind { PRIMARY, SECONDARY, QUIET }

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    /** A rounded panel that ripples when touched; [outline] 0 for no border. */
    fun panel(context: Context, fill: Int, outline: Int = 0, radiusDp: Int = 14, darkRipple: Boolean = false): Drawable {
        val radius = dp(context, radiusDp).toFloat()
        val shape = GradientDrawable().apply {
            cornerRadius = radius
            setColor(fill)
            if (outline != 0) setStroke(dp(context, 1), outline)
        }
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(if (darkRipple) RIPPLE_DARK else RIPPLE_LIGHT), shape, mask)
    }

    fun button(context: Context, text: CharSequence, kind: Kind): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        // The platform button lifts and shadows itself on press, which looks
        // wrong on a flat panel.
        stateListAnimator = null
        minHeight = dp(context, 52)
        setPadding(dp(context, 20), 0, dp(context, 20), 0)
        restyle(this, kind)
    }

    fun restyle(button: Button, kind: Kind) {
        val context = button.context
        when (kind) {
            Kind.PRIMARY -> {
                button.background = panel(context, ACCENT, darkRipple = true)
                button.setTextColor(ON_ACCENT)
                button.typeface = Typeface.DEFAULT_BOLD
            }
            Kind.SECONDARY -> {
                button.background = panel(context, Color.TRANSPARENT, OUTLINE)
                button.setTextColor(TEXT)
                button.typeface = Typeface.DEFAULT
            }
            Kind.QUIET -> {
                button.background = panel(context, Color.TRANSPARENT)
                button.setTextColor(TEXT_DIM)
                button.typeface = Typeface.DEFAULT
            }
        }
    }

    /** Greys a button out, since a custom background does not show disabled by itself. */
    fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.4f
    }

    /** A player's colour as a dot: filled for a taken seat, a ring for an empty one. */
    fun seatDot(context: Context, color: Int, filled: Boolean): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        if (filled) {
            setColor(color)
        } else {
            setColor(Color.TRANSPARENT)
            setStroke(dp(context, 2), color)
        }
    }

    /** A small uppercase heading over a group of rows. */
    fun heading(context: Context, text: CharSequence): TextView = TextView(context).apply {
        this.text = text
        isAllCaps = true
        textSize = 12f
        letterSpacing = 0.08f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(TEXT_FAINT)
    }
}
