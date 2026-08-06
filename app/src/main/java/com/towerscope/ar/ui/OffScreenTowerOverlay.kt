package com.towerscope.ar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.towerscope.ar.util.GeoUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws edge arrows + labels for towers that are within range but outside the camera FOV.
 */
class OffScreenTowerOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Indicator(
        val name: String,
        val relativeBearingDegrees: Double,
        val distanceMeters: Double
    )

    private var indicators: List<Indicator> = emptyList()

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD60A.toInt()
        style = Paint.Style.FILL
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC0B1C2C.toInt()
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 34f
        isFakeBoldText = true
    }
    private val distancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()
        textSize = 28f
        isFakeBoldText = true
    }
    private val path = Path()
    private val labelRect = RectF()

    fun setIndicators(items: List<Indicator>) {
        indicators = items
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (indicators.isEmpty() || width == 0 || height == 0) return

        val pad = 48f
        val cx = width / 2f
        val cy = height / 2f
        val maxX = width - pad
        val maxY = height - pad

        indicators.take(8).forEach { indicator ->
            // Approximate phone FOV half-angle; only draw when clearly off-screen.
            if (abs(indicator.relativeBearingDegrees) <= HALF_FOV_DEGREES) return@forEach

            val rad = Math.toRadians(indicator.relativeBearingDegrees)
            // Screen x increases to the right; y increases downward.
            val dirX = sin(rad).toFloat()
            val dirY = -cos(rad).toFloat()

            val edge = projectToEdge(cx, cy, dirX, dirY, pad, maxX, maxY)
            drawArrow(canvas, edge.first, edge.second, dirX, dirY)
            drawLabel(canvas, edge.first, edge.second, dirX, dirY, indicator)
        }
    }

    private fun projectToEdge(
        cx: Float,
        cy: Float,
        dirX: Float,
        dirY: Float,
        minX: Float,
        maxX: Float,
        maxY: Float
    ): Pair<Float, Float> {
        val minY = minX
        var t = Float.POSITIVE_INFINITY
        if (dirX > 0.001f) t = min(t, (maxX - cx) / dirX)
        if (dirX < -0.001f) t = min(t, (minX - cx) / dirX)
        if (dirY > 0.001f) t = min(t, (maxY - cy) / dirY)
        if (dirY < -0.001f) t = min(t, (minY - cy) / dirY)
        if (!t.isFinite()) t = 0f
        return cx + dirX * t to cy + dirY * t
    }

    private fun drawArrow(canvas: Canvas, x: Float, y: Float, dirX: Float, dirY: Float) {
        val len = 36f
        val half = 18f
        val nx = -dirY
        val ny = dirX
        path.reset()
        path.moveTo(x + dirX * len, y + dirY * len)
        path.lineTo(x - dirX * 10f + nx * half, y - dirY * 10f + ny * half)
        path.lineTo(x - dirX * 10f - nx * half, y - dirY * 10f - ny * half)
        path.close()
        canvas.drawPath(path, arrowPaint)
    }

    private fun drawLabel(
        canvas: Canvas,
        x: Float,
        y: Float,
        dirX: Float,
        dirY: Float,
        indicator: Indicator
    ) {
        val title = indicator.name.take(18)
        val distance = GeoUtils.formatDistance(indicator.distanceMeters)
        val titleWidth = labelPaint.measureText(title)
        val distWidth = distancePaint.measureText(distance)
        val boxW = max(titleWidth, distWidth) + 28f
        val boxH = 72f

        // Keep label inside the view, slightly inset from the arrow tip.
        var left = x - boxW / 2f - dirX * 56f
        var top = y - boxH / 2f - dirY * 56f
        left = left.coerceIn(12f, max(12f, width - boxW - 12f))
        top = top.coerceIn(12f, max(12f, height - boxH - 12f))

        labelRect.set(left, top, left + boxW, top + boxH)
        canvas.drawRoundRect(labelRect, 16f, 16f, labelBgPaint)
        canvas.drawText(title, left + 14f, top + 32f, labelPaint)
        canvas.drawText(distance, left + 14f, top + 60f, distancePaint)
    }

    companion object {
        /** Half of approximate horizontal FOV; towers within this cone are on-screen. */
        const val HALF_FOV_DEGREES = 32.0
    }
}
