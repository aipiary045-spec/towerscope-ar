package com.towerscope.ar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.towerscope.ar.util.GeoUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws direction arrows + labels for in-range towers relative to device heading.
 * Touches pass through except taps on labels, which select a tower.
 */
class OffScreenTowerOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Indicator(
        val towerId: String,
        val name: String,
        val relativeBearingDegrees: Double,
        val distanceMeters: Double
    )

    private data class HitTarget(
        val towerId: String,
        val bounds: RectF
    )

    private var indicators: List<Indicator> = emptyList()
    private val hitTargets = mutableListOf<HitTarget>()
    private var onTowerSelected: ((String) -> Unit)? = null
    private var pressedTowerId: String? = null

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE6C84A.toInt()
        style = Paint.Style.FILL
    }
    private val aheadRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5EC8D6.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE60B1C2C.toInt()
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F7FA.toInt()
        textSize = 32f
        isFakeBoldText = true
    }
    private val distancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5EC8D6.toInt()
        textSize = 26f
        isFakeBoldText = true
    }
    private val path = Path()
    private val labelRect = RectF()

    init {
        isClickable = false
        isFocusable = false
    }

    fun setOnTowerSelectedListener(listener: ((String) -> Unit)?) {
        onTowerSelected = listener
    }

    fun setIndicators(items: List<Indicator>) {
        indicators = items
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedTowerId = hitTargets
                    .lastOrNull { it.bounds.contains(event.x, event.y) }
                    ?.towerId
                return pressedTowerId != null
            }
            MotionEvent.ACTION_UP -> {
                val id = pressedTowerId
                pressedTowerId = null
                if (id != null) {
                    val stillHit = hitTargets
                        .lastOrNull { it.bounds.contains(event.x, event.y) }
                        ?.towerId
                    if (stillHit == id) onTowerSelected?.invoke(id)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedTowerId = null
                return false
            }
        }
        return pressedTowerId != null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hitTargets.clear()
        if (indicators.isEmpty() || width == 0 || height == 0) return

        val pad = 56f
        val cx = width / 2f
        val cy = height / 2f
        val maxX = width - pad
        val maxY = height - pad

        indicators.take(10).forEach { indicator ->
            val rad = Math.toRadians(indicator.relativeBearingDegrees)
            val dirX = sin(rad).toFloat()
            val dirY = -cos(rad).toFloat()
            val ahead = abs(indicator.relativeBearingDegrees) <= HALF_FOV_DEGREES

            if (ahead) {
                val edge = projectToEdge(cx, cy, dirX, dirY, pad, maxX, maxY)
                val t = 0.42f
                val x = cx + (edge.first - cx) * t
                val y = cy + (edge.second - cy) * t
                canvas.drawCircle(x, y, 18f, aheadRingPaint)
                drawLabel(canvas, x, y, dirX, dirY, indicator)
            } else {
                val edge = projectToEdge(cx, cy, dirX, dirY, pad, maxX, maxY)
                drawArrow(canvas, edge.first, edge.second, dirX, dirY)
                drawLabel(canvas, edge.first, edge.second, dirX, dirY, indicator)
            }
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
        val len = 34f
        val half = 16f
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
        val boxH = 68f

        var left = x - boxW / 2f - dirX * 52f
        var top = y - boxH / 2f - dirY * 52f
        left = left.coerceIn(12f, max(12f, width - boxW - 12f))
        top = top.coerceIn(12f, max(12f, height - boxH - 12f))

        labelRect.set(left, top, left + boxW, top + boxH)
        canvas.drawRoundRect(labelRect, 14f, 14f, labelBgPaint)
        canvas.drawText(title, left + 14f, top + 30f, labelPaint)
        canvas.drawText(distance, left + 14f, top + 56f, distancePaint)
        hitTargets += HitTarget(indicator.towerId, RectF(labelRect))
    }

    companion object {
        /** Approx half horizontal FOV; within this cone cues sit in-view. */
        const val HALF_FOV_DEGREES = 28.0
    }
}
