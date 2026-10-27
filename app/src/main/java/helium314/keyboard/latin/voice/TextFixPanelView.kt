// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The full Text Fix proposal over the keyboard area (report TURTLEBOARD-UX-002).
 *
 * The strip shows two lines of the proposal only. A tap on them opens this panel, with the full
 * text in a scroll view and the Discard and Replace buttons, so the user can read all of the text
 * before Replace. [TextFixOverlayView] makes the panel and removes it when the overlay goes away.
 * The panel has a fixed height, so it covers the keys and does not make the keyboard taller.
 */
@SuppressLint("ViewConstructor") // made in code only, never from XML
class TextFixPanelView(
    context: Context,
    proposed: String,
    height: Int,
    textColor: Int,
    backgroundColor: Int,
    discardButton: View,
    replaceButton: View,
) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height)
        setBackgroundColor(backgroundColor)
        setPadding(dp(16), dp(12), dp(12), dp(8))
        // The keys under the panel must not get the touches.
        isClickable = true

        val scroller = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
            addView(TextView(context).apply {
                text = proposed
                textSize = 15f
                setTextColor(textColor)
                setPadding(0, 0, dp(4), dp(8))
            })
        }
        val buttons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            addView(discardButton)
            addView(replaceButton)
        }
        addView(scroller)
        addView(buttons)
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()
}
