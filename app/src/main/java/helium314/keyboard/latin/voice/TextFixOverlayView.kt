// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.R

/**
 * Overlay shown in the suggestion strip during a text-fix round-trip.
 *
 * States:
 *  - working: shows "Fixing…" status while the request is in flight.
 *  - result: shows the proposed text plus Replace/Discard buttons.
 */
class TextFixOverlayView(context: Context) : LinearLayout(context) {

    private val statusText: TextView
    private val resultText: TextView
    private val replaceButton: TextView
    private val discardButton: TextView

    var onReplaceClick: (() -> Unit)? = null
    var onDiscardClick: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setPadding(dp(8), 0, dp(8), 0)

        statusText = TextView(context).apply {
            textSize = 13f
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            }
            visibility = View.GONE
        }
        resultText = TextView(context).apply {
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        discardButton = makePillButton(R.string.text_fix_discard) { onDiscardClick?.invoke() }
        replaceButton = makePillButton(R.string.text_fix_replace) { onReplaceClick?.invoke() }

        addView(statusText)
        addView(resultText)
        addView(discardButton)
        addView(replaceButton)
    }

    private fun makePillButton(labelRes: Int, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = context.getString(labelRes)
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            minHeight = dp(40)
            minWidth = dp(56)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
            }
            background = bg
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(4)
            }
            setOnClickListener { onClick() }
        }
    }

    fun setColors(textColor: Int) {
        statusText.setTextColor(textColor)
        resultText.setTextColor(textColor)
        replaceButton.setTextColor(textColor)
        discardButton.setTextColor((textColor and 0x00FFFFFF) or 0xAA000000.toInt())
        (replaceButton.background as? GradientDrawable)
            ?.setColor((textColor and 0x00FFFFFF) or 0x33000000)
        (discardButton.background as? GradientDrawable)
            ?.setColor((textColor and 0x00FFFFFF) or 0x11000000)
    }

    fun showWorking() {
        statusText.text = context.getString(R.string.text_fix_working)
        statusText.visibility = View.VISIBLE
        resultText.visibility = View.GONE
        replaceButton.visibility = View.GONE
        discardButton.visibility = View.VISIBLE
    }

    fun showResult(proposed: String) {
        statusText.visibility = View.GONE
        resultText.text = proposed
        resultText.visibility = View.VISIBLE
        replaceButton.visibility = View.VISIBLE
        discardButton.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()
}
