package code.name.monkey.retromusic.views

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.sin

/**
 * A wavy ("squiggly") progress track: the played part is a sine wave that drifts while music
 * plays and flattens to a line when paused; the rest is a faint straight line.
 *
 * Works two ways:
 *  - as a SeekBar's progressDrawable ([useLevel] = true): progress comes from the drawable level;
 *  - as a Slider's background ([useLevel] = false): set [fraction], and inset by the slider's
 *    track side padding so the wave lines up with the thumb.
 */
class SquigglyProgressDrawable(
    density: Float,
    private val useLevel: Boolean,
    /** Whether the wave should move; read every frame. */
    private val isPlaying: () -> Boolean,
) : Drawable() {

    private val strokeWidth = 3f * density
    private val maxAmplitude = 2.5f * density
    private val wavelength = 24f * density
    private val phaseSpeed = 12f * density // px per second
    private val gap = 4f * density // between the wave end and the remaining line

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = this@SquigglyProgressDrawable.strokeWidth
    }
    private val linePaint = Paint(wavePaint)
    private val path = Path()

    private var phase = 0f
    private var amplitude = 0f
    private var lastFrame = 0L

    var color: Int = 0
        set(value) {
            field = value
            wavePaint.color = value
            linePaint.color = ColorUtils.setAlphaComponent(value, (0.3f * android.graphics.Color.alpha(value)).toInt())
            invalidateSelf()
        }

    /** Played fraction (0..1) when not driven by the level. */
    var fraction: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped != field) {
                field = clamped
                invalidateSelf()
            }
        }

    /** Horizontal insets of the track inside the bounds (Slider track side padding). */
    var insetStart = 0f
    var insetEnd = 0f

    override fun onLevelChange(level: Int): Boolean {
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame).coerceAtMost(100)) / 1000f
        lastFrame = now

        val playing = isPlaying()
        val target = if (playing) maxAmplitude else 0f
        // Ease the amplitude in/out over about 300 ms.
        val step = maxAmplitude * dt / 0.3f
        amplitude = if (amplitude < target) minOf(target, amplitude + step) else maxOf(target, amplitude - step)
        if (playing) phase = (phase + phaseSpeed * dt) % wavelength

        val b = bounds
        val start = b.left + insetStart
        val end = b.right - insetEnd
        if (end <= start) return
        val played = if (useLevel) level / 10_000f else fraction
        val progressX = start + (end - start) * played
        val cy = b.exactCenterY()

        // Remaining part: straight, faint.
        if (progressX + gap < end) canvas.drawLine(progressX + gap, cy, end, cy, linePaint)

        // Played part: the wave, sampled every 2 px.
        if (progressX > start) {
            path.rewind()
            val k = (2 * PI / wavelength).toFloat()
            var x = start
            path.moveTo(x, cy + amplitude * sin(k * (x - start - phase)))
            while (x < progressX) {
                x = minOf(x + 2f, progressX)
                path.lineTo(x, cy + amplitude * sin(k * (x - start - phase)))
            }
            canvas.drawPath(path, wavePaint)
        }

        // Keep animating while it moves, but only when it can actually be seen.
        if ((playing || amplitude > 0f) && isOnScreen()) invalidateSelf() else lastFrame = 0L
    }

    /** False when the view is gone or faded out (e.g. the player panel is collapsed). */
    private fun isOnScreen(): Boolean {
        var view = callback as? View ?: return false
        if (!view.isShown) return false
        var alpha = view.alpha
        while (true) {
            val parent = view.parent as? View ?: break
            alpha *= parent.alpha
            view = parent
        }
        return alpha > 0.01f
    }

    override fun getIntrinsicHeight(): Int = (2 * maxAmplitude + strokeWidth).toInt() + 1

    override fun setAlpha(alpha: Int) {
        wavePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        wavePaint.colorFilter = colorFilter
        linePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
