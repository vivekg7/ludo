package com.crylo.ludo

import android.view.View
import android.view.WindowInsets

/**
 * Keeps content clear of the status and navigation bars.
 *
 * From Android 15 apps targeting SDK 35+ are laid out edge to edge whether they
 * ask for it or not, so without this the turn banner sits under the clock.
 * Call it once the view's own padding is set: that padding is treated as the
 * baseline and the system bars are added on top.
 */
internal fun View.padForSystemBars() {
    val basePadding = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)

    setOnApplyWindowInsetsListener { view, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        view.setPadding(
            basePadding[0] + bars.left,
            basePadding[1] + bars.top,
            basePadding[2] + bars.right,
            basePadding[3] + bars.bottom,
        )
        insets
    }
    requestApplyInsets()
}
