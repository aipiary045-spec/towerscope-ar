package com.towerscope.ar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.towerscope.ar.R
import kotlin.math.max
import kotlin.math.min

/**
 * Simple live latency sparkline for the ping monitor.
 */
class LatencyGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val values = ArrayDeque<Float>()
    private val maxPoints = 60
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = ContextCompat.getColor(context, R.color.accent_teal)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = ContextCompat.getColor(context, R.color.border_luminous)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.accent_teal_soft)
    }
    private val path = Path()
    private val fillPath = Path()

    fun addSample(latencyMs: Double?) {
        val v = latencyMs?.toFloat()
        if (v == null || !v.isFinite()) {
            // Keep timeline moving with a gap marker (NaN → skip in draw).
            values.addLast(Float.NaN)
        } else {
            values.addLast(v.coerceIn(0f, 5_000f))
        }
        while (values.size > maxPoints) values.removeFirst()
        invalidate()
    }

    fun clear() {
        values.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val pad = 8f
        canvas.drawLine(pad, pad, w - pad, pad, gridPaint)
        canvas.drawLine(pad, h / 2f, w - pad, h / 2f, gridPaint)
        canvas.drawLine(pad, h - pad, w - pad, h - pad, gridPaint)

        if (values.size < 2) return
        val finite = values.filter { it.isFinite() }
        if (finite.isEmpty()) return
        val maxV = max(finite.max(), 1f)
        val minV = min(finite.min(), 0f)
        val span = max(maxV - minV, 1f)
        val stepX = (w - pad * 2f) / (maxPoints - 1).toFloat()

        path.reset()
        fillPath.reset()
        var started = false
        values.forEachIndexed { index, value ->
            if (!value.isFinite()) {
                started = false
                return@forEachIndexed
            }
            val x = pad + index * stepX
            val y = h - pad - ((value - minV) / span) * (h - pad * 2f)
            if (!started) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h - pad)
                fillPath.lineTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        if (started) {
            val lastX = pad + (values.size - 1) * stepX
            fillPath.lineTo(lastX, h - pad)
            fillPath.close()
            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(path, linePaint)
        }
    }
}
