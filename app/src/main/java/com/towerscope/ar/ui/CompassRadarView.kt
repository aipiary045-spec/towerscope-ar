package com.towerscope.ar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.towerscope.ar.R
import com.towerscope.ar.util.GeoUtils
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hybrid compass: rotating radar disc (heading up) with tower dots by bearing/distance,
 * plus a focused-tower readout at the top of the disc.
 */
class CompassRadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class TowerMarker(
        val towerId: String,
        val name: String,
        /** Degrees clockwise from device heading (0 = ahead / up on disc). */
        val relativeBearingDegrees: Double,
        val distanceMeters: Double
    )

    private var headingDegrees: Double? = null
    private var maxDistanceMeters: Float = 2000f
    private var markers: List<TowerMarker> = emptyList()
    private var focusTowerId: String? = null
    private var focusLine: String = "No tower in range"
    private var accentColor: Int = ContextCompat.getColor(context, R.color.accent_yellow)
    private var secondaryColor: Int = ContextCompat.getColor(context, R.color.accent_cyan)
    private var textColor: Int = ContextCompat.getColor(context, R.color.text_primary)
    private var mutedColor: Int = ContextCompat.getColor(context, R.color.text_muted)

    private var onTowerSelected: ((String) -> Unit)? = null
    private val hitTargets = mutableListOf<HitTarget>()

    private data class HitTarget(
        val towerId: String,
        val x: Float,
        val y: Float,
        val radius: Float
    )

    private val density = resources.displayMetrics.density

    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xE60F2438.toInt()
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0x55FFFFFF
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x33FFFFFF
    }
    private val forwardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = ContextCompat.getColor(context, R.color.accent_cyan)
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.accent_yellow)
    }
    private val towerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val focusRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = ContextCompat.getColor(context, R.color.text_muted)
    }
    private val focusTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val focusDetailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f * density
    }
    private val towerLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val arrowPath = Path()

    fun setOnTowerSelectedListener(listener: ((String) -> Unit)?) {
        onTowerSelected = listener
    }

    fun update(
        headingDegrees: Double?,
        maxDistanceMeters: Float,
        markers: List<TowerMarker>,
        focusTowerId: String?,
        focusLine: String,
        accentColor: Int,
        secondaryColor: Int,
        textColor: Int,
        mutedColor: Int
    ) {
        this.headingDegrees = headingDegrees
        this.maxDistanceMeters = maxDistanceMeters.coerceAtLeast(1f)
        this.markers = markers
        this.focusTowerId = focusTowerId
        this.focusLine = focusLine
        this.accentColor = accentColor
        this.secondaryColor = secondaryColor
        this.textColor = textColor
        this.mutedColor = mutedColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hitTargets.clear()

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - 12f * density

        discPaint.color = 0xE60F2438.toInt()
        canvas.drawCircle(cx, cy, radius, discPaint)

        // Range rings at 25 / 50 / 75 / 100% of max distance.
        for (i in 1..4) {
            val r = radius * (i / 4f)
            ringPaint.color = if (i == 4) 0x66FFFFFF else 0x33FFFFFF
            canvas.drawCircle(cx, cy, r, ringPaint)
        }

        // Crosshair
        canvas.drawLine(cx - radius, cy, cx + radius, cy, crossPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, crossPaint)

        val heading = headingDegrees
        if (heading != null) {
            drawCardinals(canvas, cx, cy, radius, heading)
        }

        // Forward wedge at top of disc
        forwardPaint.color = secondaryColor
        canvas.drawLine(cx, cy - radius + 4f * density, cx, cy - radius + 28f * density, forwardPaint)
        arrowPath.reset()
        arrowPath.moveTo(cx, cy - radius + 2f * density)
        arrowPath.lineTo(cx - 7f * density, cy - radius + 14f * density)
        arrowPath.lineTo(cx + 7f * density, cy - radius + 14f * density)
        arrowPath.close()
        val fillArrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = secondaryColor
        }
        canvas.drawPath(arrowPath, fillArrow)

        // Outer range label
        labelPaint.color = mutedColor
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            GeoUtils.formatDistance(maxDistanceMeters.toDouble()),
            cx,
            cy + radius - 10f * density,
            labelPaint
        )

        // You-are-here
        cardPaint.color = accentColor
        canvas.drawCircle(cx, cy, 7f * density, cardPaint)
        val youPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = 0xFFFFFFFF.toInt()
        }
        canvas.drawCircle(cx, cy, 7f * density, youPaint)

        // Towers: relative bearing 0 = up on disc
        val focus = markers.firstOrNull { it.towerId == focusTowerId }
        markers.forEach { marker ->
            val clamped = marker.distanceMeters.coerceIn(0.0, maxDistanceMeters.toDouble())
            val frac = sqrt(clamped / maxDistanceMeters).toFloat()
            val screenAngleRad = Math.toRadians(marker.relativeBearingDegrees)
            // 0° relative = up (-Y); clockwise positive
            val x = cx + (sin(screenAngleRad) * radius * frac).toFloat()
            val y = cy - (cos(screenAngleRad) * radius * frac).toFloat()
            val isFocus = marker.towerId == focusTowerId
            val dotR = if (isFocus) 9f * density else 6.5f * density
            towerPaint.color = if (isFocus) accentColor else secondaryColor
            canvas.drawCircle(x, y, dotR, towerPaint)
            if (isFocus) {
                focusRingPaint.color = accentColor
                canvas.drawCircle(x, y, dotR + 4f * density, focusRingPaint)
            }
            hitTargets.add(HitTarget(marker.towerId, x, y, dotR + 14f * density))

            if (isFocus || markers.size <= 8) {
                towerLabelPaint.color = textColor
                val shortName = if (marker.name.length > 14) {
                    marker.name.take(12) + "…"
                } else {
                    marker.name
                }
                canvas.drawText(shortName, x, y - dotR - 6f * density, towerLabelPaint)
            }
        }

        // Focus readout near top of disc
        focusTitlePaint.color = accentColor
        focusDetailPaint.color = textColor
        val parts = focusLine.split("  ·  ")
        val title = parts.firstOrNull().orEmpty()
        val detail = if (parts.size > 1) parts.drop(1).joinToString("  ·  ") else ""
        canvas.drawText(title, cx, cy - radius + 48f * density, focusTitlePaint)
        if (detail.isNotEmpty()) {
            canvas.drawText(detail, cx, cy - radius + 64f * density, focusDetailPaint)
        } else if (focus != null) {
            canvas.drawText(
                GeoUtils.formatDistance(focus.distanceMeters),
                cx,
                cy - radius + 64f * density,
                focusDetailPaint
            )
        }
    }

    private fun drawCardinals(canvas: Canvas, cx: Float, cy: Float, radius: Float, heading: Double) {
        val labels = listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)
        labels.forEach { (label, absoluteBearing) ->
            val relative = ((absoluteBearing - heading + 540.0) % 360.0) - 180.0
            val rad = Math.toRadians(relative)
            val r = radius - 18f * density
            val x = cx + (sin(rad) * r).toFloat()
            val y = cy - (cos(rad) * r).toFloat()
            labelPaint.color = if (label == "N") accentColor else mutedColor
            labelPaint.textSize = if (label == "N") 13f * density else 11f * density
            canvas.drawText(label, x, y + 4f * density, labelPaint)
        }
        labelPaint.textSize = 11f * density
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) {
            return true
        }
        val x = event.x
        val y = event.y
        val hit = hitTargets.minByOrNull { target ->
            hypot((target.x - x).toDouble(), (target.y - y).toDouble())
        } ?: return true
        val dist = hypot((hit.x - x).toDouble(), (hit.y - y).toDouble())
        if (dist <= hit.radius) {
            onTowerSelected?.invoke(hit.towerId)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
