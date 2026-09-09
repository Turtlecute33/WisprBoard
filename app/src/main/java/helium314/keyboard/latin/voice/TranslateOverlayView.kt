// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.R

/**
 * Overlay shown in the suggestion strip for the Translate action.
 *
 * States:
 *  - languages: the middle menu — one pill per configured target language.
 *  - working: "Translating…" plus Cancel while the request is in flight.
 *  - error: the failure reason plus Dismiss.
 *
 * The translated text itself is never shown here: it goes straight into the editor.
 */
class TranslateOverlayView(context: Context) : LinearLayout(context) {

    private val pulseView: AiPulseView
    private val statusText: TextView
    private val languageScroller: HorizontalScrollView
    private val languageRow: LinearLayout
    private val retryButton: TextView
    private val cancelButton: TextView

    /** Invoked with the language the user picked in the middle menu. */
    var onLanguageClick: ((String) -> Unit)? = null
    var onCancelClick: (() -> Unit)? = null
    /** Re-runs the failed translation with the same source and language. Null hides the pill. */
    var onRetryClick: (() -> Unit)? = null

    private var textColor = 0
    private var lastClickMs = 0L

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setPadding(dp(12), 0, dp(12), 0)

        pulseView = AiPulseView(context).apply {
            layoutParams = LayoutParams(dp(44), dp(20)).apply { marginEnd = dp(12) }
            visibility = View.GONE
        }
        statusText = TextView(context).apply {
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) }
            visibility = View.GONE
        }
        languageRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        languageScroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            // FrameLayout params on purpose: HorizontalScrollView is a FrameLayout, and handing it
            // the enclosing LinearLayout's params would silently drop the weight anyway.
            addView(
                languageRow,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        retryButton = makePill(context.getString(R.string.translate_retry), primary = true) {
            onRetryClick?.invoke()
        }.apply { visibility = View.GONE }
        cancelButton = makePill(context.getString(R.string.translate_cancel), primary = false) {
            onCancelClick?.invoke()
        }

        addView(pulseView)
        addView(statusText)
        addView(languageScroller)
        addView(retryButton)
        addView(cancelButton)
    }

    fun setColors(color: Int) {
        textColor = color
        pulseView.meterColor = color
        statusText.setTextColor(color)
        applyPillColors(retryButton, primary = true)
        applyPillColors(cancelButton, primary = false)
        for (i in 0 until languageRow.childCount) {
            (languageRow.getChildAt(i) as? TextView)?.let { applyPillColors(it, primary = true) }
        }
    }

    /** Shows the middle menu. [languages] is already de-duplicated and trimmed. */
    fun showLanguages(languages: List<String>) {
        stopPulse()
        languageRow.removeAllViews()
        for (language in languages) {
            val pill = makePill(language, primary = true) { onLanguageClick?.invoke(language) }
            applyPillColors(pill, primary = true)
            languageRow.addView(pill)
        }
        languageScroller.scrollTo(0, 0)
        statusText.visibility = View.GONE
        languageScroller.visibility = View.VISIBLE
        retryButton.visibility = View.GONE
        cancelButton.visibility = View.VISIBLE
        announceForAccessibility(context.getString(R.string.translate_pick_language))
    }

    fun showWorking() {
        statusText.text = context.getString(R.string.translate_working)
        // A translation is committed straight into the editor, so this status line is the only
        // signal the user gets that anything is happening. Keep it alive.
        pulseView.visibility = View.VISIBLE
        pulseView.resetToIdlePulse()
        statusText.visibility = View.VISIBLE
        languageScroller.visibility = View.GONE
        retryButton.visibility = View.GONE
        cancelButton.visibility = View.VISIBLE
        announceForAccessibility(statusText.text)
    }

    /**
     * @param canRetry whether the source text and target language are still known, so the request
     *   can simply be repeated. Most translate failures are transient (rate limit, provider
     *   hiccup); without this the user has to reselect the text and walk the long-press-Return
     *   menu again just to try the same thing twice.
     */
    fun showError(message: String, canRetry: Boolean) {
        stopPulse()
        statusText.text = message
        statusText.visibility = View.VISIBLE
        languageScroller.visibility = View.GONE
        retryButton.visibility = if (canRetry) View.VISIBLE else View.GONE
        cancelButton.visibility = View.VISIBLE
        announceForAccessibility(message)
    }

    /** Every state that is not "waiting" must stop the animator, or the IME window never idles. */
    private fun stopPulse() {
        pulseView.stopPulse()
        pulseView.visibility = View.GONE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseView.stopPulse()
    }

    private fun makePill(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            isAllCaps = false
            maxLines = 1
            minHeight = dp(48)
            minWidth = dp(56)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
            }
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
            setOnClickListener {
                // Every pill either commits text or cancels an in-flight request, so a double tap
                // must not fire twice. The window is shared across pills on purpose: two different
                // languages tapped in quick succession is a mis-tap, not two deliberate requests.
                val now = SystemClock.elapsedRealtime()
                if (now - lastClickMs < 300L) return@setOnClickListener
                lastClickMs = now
                onClick()
            }
            if (textColor != 0) applyPillColors(this, primary)
        }

    private fun applyPillColors(pill: TextView, primary: Boolean) {
        if (textColor == 0) return
        if (primary) {
            pill.setTextColor(textColor)
            (pill.background as? GradientDrawable)?.setColor((textColor and 0x00FFFFFF) or 0x55000000)
        } else {
            pill.setTextColor((textColor and 0x00FFFFFF) or 0xB0000000.toInt())
            (pill.background as? GradientDrawable)?.setColor((textColor and 0x00FFFFFF) or 0x18000000)
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()
}
