package com.towerscope.ar.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.towerscope.ar.R
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Semicircle speed gauge with eased needle / value transitions.
 */
class SpeedGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 18f
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.border_luminous)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 18f
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.accent_teal)
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.accent_yellow)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted)
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_dim)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = ContextCompat.getColor(context, R.color.text_dim)
    }

    private val arcRect = RectF()
    /** Displayed (eased) Mbps under the needle. */
    private var displayMbps: Float = 0f
    /** Target Mbps from the latest sample. */
    private var targetMbps: Float = 0f
    private var maxMbps: Float = 100f
    private var phaseLabel: String = "READY"
    private var accentColor: Int = ContextCompat.getColor(context, R.color.accent_teal)

    private var speedAnimator: ValueAnimator? = null
    private val easeInterpolator = DecelerateInterpolator(1.6f)

    fun setPhase(label: String, accent: Int) {
        phaseLabel = label
        accentColor = accent
        progressPaint.color = accent
        invalidate()
    }

    fun setSpeed(mbpsValue: Double, suggestedMax: Double = Double.NaN) {
        val next = if (mbpsValue.isFinite()) mbpsValue.toFloat().coerceAtLeast(0f) else 0f
        val floorMax = when {
            suggestedMax.isFinite() && suggestedMax > 0 -> suggestedMax.toFloat()
            next <= 50f -> 100f
            next <= 150f -> 250f
            next <= 400f -> 500f
            else -> 1000f
        }
        maxMbps = max(maxMbps * 0.92f, floorMax)
        maxMbps = max(maxMbps, max(next * 1.25f, 50f))

        if (kotlin.math.abs(next - targetMbps) < 0.05f) {
            targetMbps = next
            return
        }
        targetMbps = next
        animateToward(next)
    }

    fun reset() {
        speedAnimator?.cancel()
        speedAnimator = null
        displayMbps = 0f
        targetMbps = 0f
        maxMbps = 100f
        phaseLabel = "READY"
        progressPaint.color = ContextCompat.getColor(context, R.color.accent_teal)
        invalidate()
    }

    private fun animateToward(to: Float) {
        speedAnimator?.cancel()
        val from = displayMbps
        val delta = kotlin.math.abs(to - from)
        // Longer ease for bigger jumps; snap-feel avoided.
        val duration = (180L + (delta / max(maxMbps, 1f) * 420L).toLong()).coerceIn(160L, 520L)
        speedAnimator = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = easeInterpolator
            addUpdateListener { anim ->
                displayMbps = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        speedAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * 0.62f).toInt().coerceAtLeast(160)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val stroke = trackPaint.strokeWidth
        val pad = stroke + 8f
        val diameter = min(w - pad * 2f, (h - pad) * 2f)
        val left = (w - diameter) / 2f
        val top = pad
        arcRect.set(left, top, left + diameter, top + diameter)

        canvas.drawArc(arcRect, 180f, 180f, false, trackPaint)

        val fraction = (displayMbps / maxMbps).coerceIn(0f, 1f)
        progressPaint.color = accentColor
        canvas.drawArc(arcRect, 180f, 180f * fraction, false, progressPaint)

        val cx = arcRect.centerX()
        val cy = arcRect.centerY()
        val radius = diameter / 2f
        for (i in 0..4) {
            val t = i / 4f
            val ang = Math.toRadians((180.0 + 180.0 * t))
            val inner = radius - stroke * 0.35f
            val outer = radius + 4f
            val x1 = cx + cos(ang).toFloat() * inner
            val y1 = cy + sin(ang).toFloat() * inner
            val x2 = cx + cos(ang).toFloat() * outer
            val y2 = cy + sin(ang).toFloat() * outer
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        val needleAng = Math.toRadians((180.0 + 180.0 * fraction))
        val needleLen = radius - stroke * 0.9f
        canvas.drawLine(
            cx,
            cy,
            cx + cos(needleAng).toFloat() * needleLen,
            cy + sin(needleAng).toFloat() * needleLen,
            needlePaint
        )

        valuePaint.textSize = diameter * 0.16f
        unitPaint.textSize = diameter * 0.055f
        labelPaint.textSize = diameter * 0.05f
        labelPaint.letterSpacing = 0.12f

        val valueY = cy - diameter * 0.08f
        val text = if (displayMbps < 10f) {
            String.format("%.1f", displayMbps)
        } else {
            String.format("%.0f", displayMbps)
        }
        canvas.drawText(text, cx, valueY, valuePaint)
        canvas.drawText("Mbps", cx, valueY + unitPaint.textSize * 1.3f, unitPaint)
        canvas.drawText(phaseLabel, cx, cy + diameter * 0.18f, labelPaint)
    }
}
