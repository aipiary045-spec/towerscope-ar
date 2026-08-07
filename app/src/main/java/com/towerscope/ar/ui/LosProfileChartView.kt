package com.towerscope.ar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.towerscope.ar.R
import com.towerscope.ar.data.LosProfile
import kotlin.math.max
import kotlin.math.min

/**
 * Distance (X) vs elevation (Y) LOS profile chart with terrain+clutter fill and LOS chord.
 */
class LosProfileChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var profile: LosProfile? = null
    private var clutterMeters: Double = 0.0

    private val terrainFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x663D8B6E.toInt()
    }
    private val terrainStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = 0xFF6FBF8F.toInt()
    }
    private val losPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = ContextCompat.getColor(context, R.color.accent_yellow)
    }
    private val blockedLosPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = ContextCompat.getColor(context, R.color.chip_poor)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0x33FFFFFF
    }
    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted)
        textSize = sp(10f)
    }
    private val endpointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.accent_cyan)
    }

    private val terrainPath = Path()
    private val fillPath = Path()

    fun setProfile(profile: LosProfile?, clutterHeightMeters: Double) {
        this.profile = profile
        this.clutterMeters = clutterHeightMeters.coerceAtLeast(0.0)
        invalidate()
    }

    fun setClutterHeightMeters(meters: Double) {
        clutterMeters = meters.coerceAtLeast(0.0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = profile ?: return
        if (data.samples.size < 2 || data.totalDistanceMeters <= 0.0) return

        val padL = dp(36f)
        val padR = dp(12f)
        val padT = dp(12f)
        val padB = dp(28f)
        val w = width - padL - padR
        val h = height - padT - padB
        if (w <= 0f || h <= 0f) return

        var minY = min(data.observerEyeElevationMeters, data.towerTipElevationMeters)
        var maxY = max(data.observerEyeElevationMeters, data.towerTipElevationMeters)
        data.samples.forEach { sample ->
            val t = sample.effectiveTerrainMeters(clutterMeters)
            minY = min(minY, t)
            maxY = max(maxY, t)
        }
        val span = (maxY - minY).coerceAtLeast(10.0)
        val yPad = span * 0.08
        minY -= yPad
        maxY += yPad
        val ySpan = (maxY - minY).coerceAtLeast(1.0)

        fun xOf(distance: Double): Float =
            padL + (distance / data.totalDistanceMeters).toFloat() * w

        fun yOf(elevation: Double): Float =
            padT + h - ((elevation - minY) / ySpan).toFloat() * h

        // Grid
        for (i in 0..3) {
            val gy = padT + h * i / 3f
            canvas.drawLine(padL, gy, padL + w, gy, gridPaint)
        }

        // Terrain fill + stroke
        terrainPath.reset()
        fillPath.reset()
        data.samples.forEachIndexed { index, sample ->
            val x = xOf(sample.distanceMeters)
            val y = yOf(sample.effectiveTerrainMeters(clutterMeters))
            if (index == 0) {
                terrainPath.moveTo(x, y)
                fillPath.moveTo(x, padT + h)
                fillPath.lineTo(x, y)
            } else {
                terrainPath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(xOf(data.samples.last().distanceMeters), padT + h)
        fillPath.close()
        canvas.drawPath(fillPath, terrainFillPaint)
        canvas.drawPath(terrainPath, terrainStrokePaint)

        // LOS vector
        val clear = data.isClear(clutterMeters)
        val los = if (clear) losPaint else blockedLosPaint
        canvas.drawLine(
            xOf(0.0),
            yOf(data.observerEyeElevationMeters),
            xOf(data.totalDistanceMeters),
            yOf(data.towerTipElevationMeters),
            los
        )
        canvas.drawCircle(xOf(0.0), yOf(data.observerEyeElevationMeters), dp(4f), endpointPaint)
        canvas.drawCircle(
            xOf(data.totalDistanceMeters),
            yOf(data.towerTipElevationMeters),
            dp(4f),
            endpointPaint
        )

        // Axis labels
        canvas.drawText("You", padL, padT + h + dp(16f), axisLabelPaint)
        val endLabel = "Tower"
        val endW = axisLabelPaint.measureText(endLabel)
        canvas.drawText(endLabel, padL + w - endW, padT + h + dp(16f), axisLabelPaint)
        canvas.drawText(
            String.format("%.0f m", maxY),
            dp(4f),
            padT + sp(10f),
            axisLabelPaint
        )
        canvas.drawText(
            String.format("%.0f m", minY),
            dp(4f),
            padT + h,
            axisLabelPaint
        )
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
