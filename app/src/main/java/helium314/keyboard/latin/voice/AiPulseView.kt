// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Three horizontal bars whose height follows a live audio amplitude, falling back to a gentle
 * self-driven pulse whenever no amplitude is supplied.
 *
 * That fallback is the point: every AI action in this keyboard has a network wait in the middle of
 * it, and a strip showing static text ("Transcribing…", "Fixing…", "Translating…") reads as a
 * frozen keyboard. One small thing moving is the difference between "it is working" and "it is
 * stuck", and it costs a single 1200 ms animator that stops itself on detach.
 *
 * Extracted from RecordingOverlayView so the Text Fix and Translate overlays get the same
 * treatment instead of each inventing their own.
 */
internal class AiPulseView(context: Context) : View(context) {

    var meterColor: Int = Color.LTGRAY

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var level: Float = 0f // 0..1
    private var animator: ValueAnimator? = null
    private var pulsePhase: Float = 0f

    /** Starts (or restarts) the idle pulse. Safe to call when already running. */
    fun startPulse() {
        if (animator?.isRunning == true) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulsePhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopPulse() {
        animator?.cancel()
        animator = null
    }

    /**
     * Drops back to the pure idle pulse. Called when capture ends but the request is still in
     * flight: without it the bars would freeze at whatever level the last audio chunk left them.
     */
    fun resetToIdlePulse() {
        level = 0f
        startPulse()
        invalidate()
    }

    fun setAmplitude(meanAbs: Double) {
        // Map 0..~6000 to 0..1 with a gentle curve so quiet speech still moves the needle.
        val normalized = (meanAbs / 6000.0).coerceIn(0.0, 1.0)
        val curved = Math.sqrt(normalized).toFloat()
        // Smooth toward target to avoid jitter.
        level += (curved - level) * 0.35f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gap = width / 14f
        val barWidth = (width - gap * (BAR_COUNT + 1)) / BAR_COUNT
        val maxBarHeight = height.toFloat() * 0.85f
        val centerY = height / 2f
        paint.color = meterColor
        for (i in 0 until BAR_COUNT) {
            val phase = (pulsePhase + i * 0.2f) % 1f
            val pulse = (kotlin.math.sin(phase * Math.PI * 2).toFloat() * 0.5f + 0.5f)
            // With no live amplitude the pulse carries the whole animation, so weight it fully
            // rather than the 15% used while speech is driving the bars.
            val pulseWeight = if (level <= 0.01f) 1f else 0.15f
            val mix = (level * (1f - pulseWeight) + pulse * pulseWeight).coerceIn(0.15f, 1f)
            val h = maxBarHeight * mix
            val left = gap + i * (barWidth + gap)
            canvas.drawRoundRect(
                left,
                centerY - h / 2f,
                left + barWidth,
                centerY + h / 2f,
                barWidth / 2f,
                barWidth / 2f,
                paint,
            )
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopPulse()
    }

    private companion object {
        const val BAR_COUNT = 3
        const val PULSE_PERIOD_MS = 1200L
    }
}
