package com.crylo.ludo

import android.os.Build
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
        val left: Int
        val top: Int
        val right: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            left = bars.left
            top = bars.top
            right = bars.right
            bottom = bars.bottom
        } else {
            @Suppress("DEPRECATION")
            left = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            right = insets.systemWindowInsetRight
            @Suppress("DEPRECATION")
            bottom = insets.systemWindowInsetBottom
        }

        view.setPadding(
            basePadding[0] + left,
            basePadding[1] + top,
            basePadding[2] + right,
            basePadding[3] + bottom,
        )
        insets
    }
    requestApplyInsets()
}
