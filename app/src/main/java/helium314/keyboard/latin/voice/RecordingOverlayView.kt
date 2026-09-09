// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.R
import kotlin.reflect.KMutableProperty0

/**
 * Recording indicator with live amplitude meter, elapsed time, cancel, and stop button.
 * Shown in the suggestion strip area during voice recording/transcription.
 */
class RecordingOverlayView(context: Context) : LinearLayout(context) {

    private val meterView: AiPulseView
    private val timerText: TextView
    private val statusText: TextView
    private val cancelButton: ImageView
    private val stopButton: ImageView
    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null
    // Per-button debounce: Stop and Cancel must not share a window, or tapping Stop then Cancel in
    // quick succession would silently swallow the Cancel.
    private var lastStopClickMs = 0L
    private var lastCancelClickMs = 0L
    private var lastShownSecond = -1L
    private val elapsedBuilder = StringBuilder(8)

    var onStopClick: (() -> Unit)? = null
    var onCancelClick: (() -> Unit)? = null

    /** Supplier for live amplitude (0..32767) and elapsed ms. Set by the controller. */
    var telemetryProvider: (() -> Pair<Double, Long>)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setPadding(dp(12), 0, dp(12), 0)

        meterView = AiPulseView(context).apply {
            layoutParams = LayoutParams(dp(44), dp(20)).apply { marginEnd = dp(12) }
        }
        timerText = TextView(context).apply {
            textSize = 12f
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(12)
            }
        }
        statusText = TextView(context).apply {
            textSize = 13f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        cancelButton = makeRoundButton(isCancel = true, descRes = R.string.voice_cancel) {
            debounceClick(::lastCancelClickMs) { onCancelClick?.invoke() }
        }
        stopButton = makeRoundButton(isCancel = false, descRes = R.string.voice_stop_recording) {
            debounceClick(::lastStopClickMs) {
                // Finalizing the WAV takes a moment, and until it lands showTranscribing() has not
                // run yet. Leaving Stop on screen through that window made a second tap look
                // ignored, because stopRecording() is already a no-op by then.
                stopButton.visibility = View.GONE
                onStopClick?.invoke()
            }
        }

        addView(meterView)
        addView(timerText)
        addView(statusText)
        addView(cancelButton)
        addView(stopButton)
    }

    // The suggestion strip is at least 48dp tall, so these critical controls meet Android's
    // minimum touch-target size without relying on a parent TouchDelegate.
    private fun makeRoundButton(isCancel: Boolean, descRes: Int, onClick: () -> Unit): ImageView {
        val size = dp(48)
        return ImageView(context).apply {
            layoutParams = LayoutParams(size, size).apply { marginStart = dp(8) }
            val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            background = bg
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val innerSize = dp(11)
            val icon = GradientDrawable().apply {
                shape = if (isCancel) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
                if (!isCancel) cornerRadius = dp(2).toFloat()
                setSize(innerSize, innerSize)
            }
            setImageDrawable(icon)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(descRes)
            setOnClickListener { onClick() }
        }
    }

    fun setColors(textColor: Int) {
        statusText.setTextColor(textColor)
        timerText.setTextColor((textColor and 0x00FFFFFF) or 0xAA000000.toInt())
        meterView.meterColor = textColor
        // Stop button gets normal text color; cancel gets a muted hue so users can tell them apart.
        (stopButton.background as? GradientDrawable)
            ?.setColor((textColor and 0x00FFFFFF) or 0x22000000)
        (stopButton.drawable as? GradientDrawable)?.setColor(textColor)
        (cancelButton.background as? GradientDrawable)
            ?.setColor((textColor and 0x00FFFFFF) or 0x11000000)
        (cancelButton.drawable as? GradientDrawable)?.setColor((textColor and 0x00FFFFFF) or 0x99000000.toInt())
    }

    fun showRecording() {
        statusText.text = context.getString(R.string.voice_recording)
        meterView.visibility = View.VISIBLE
        meterView.startPulse()
        timerText.visibility = View.VISIBLE
        stopButton.visibility = View.VISIBLE
        cancelButton.visibility = View.VISIBLE
        startTicking()
        announceForAccessibility(statusText.text)
    }

    fun showTranscribing() {
        statusText.text = context.getString(R.string.voice_transcribing)
        // Keep the meter on screen and animating. There is no audio to display any more, so it
        // falls back to its idle pulse — which is exactly what tells the user the upload is still
        // running rather than wedged. Hiding it here left the strip completely static for the
        // whole round trip.
        meterView.visibility = View.VISIBLE
        meterView.resetToIdlePulse()
        timerText.visibility = View.GONE
        stopButton.visibility = View.GONE
        // Cancel remains visible so the user can abort the upload.
        cancelButton.visibility = View.VISIBLE
        stopTicking()
        announceForAccessibility(statusText.text)
    }

    fun stopAnimation() {
        meterView.stopPulse()
        stopTicking()
    }

    private fun startTicking() {
        stopTicking()
        lastShownSecond = -1L
        val r = object : Runnable {
            override fun run() {
                val telemetry = telemetryProvider?.invoke()
                if (telemetry != null) {
                    meterView.setAmplitude(telemetry.first)
                    // The timer only changes once a second, but this runs at 12.5 Hz. Writing the
                    // same text back would relayout the strip 12 times a second for nothing.
                    val second = telemetry.second / 1000L
                    if (second != lastShownSecond) {
                        lastShownSecond = second
                        timerText.text = formatElapsed(second)
                    }
                } else {
                    // Nothing to display — stop self-posting instead of waking up at 12.5 Hz for nothing.
                    stopTicking()
                    return
                }
                tickHandler.postDelayed(this, 80L)
            }
        }
        tickRunnable = r
        tickHandler.post(r)
    }

    private fun stopTicking() {
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        tickRunnable = null
    }

    private fun debounceClick(lastClickMs: KMutableProperty0<Long>, action: () -> Unit) {
        // Stop/Cancel both have heavy side effects (stop the recorder, cancel an in-flight upload).
        // A spammed double-tap of the *same* button can race the state machine — 300ms is plenty of
        // breathing room. The window is per-button so the two never block each other.
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickMs.get() < 300L) return
        lastClickMs.set(now)
        action()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    private fun formatElapsed(totalSec: Long): String {
        val s = totalSec % 60
        elapsedBuilder.setLength(0)
        elapsedBuilder.append(totalSec / 60).append(':')
        if (s < 10) elapsedBuilder.append('0')
        elapsedBuilder.append(s)
        return elapsedBuilder.toString()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
