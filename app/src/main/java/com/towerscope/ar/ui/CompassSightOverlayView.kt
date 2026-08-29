package com.towerscope.ar.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.towerscope.ar.R
import com.towerscope.ar.util.GeoUtils
import kotlin.math.abs

/**
 * 2D sight picture over a camera preview: compass bearings mapped to horizontal
 * screen position using camera field-of-view. Heading and marker positions are
 * heavily smoothed so chevrons do not swim with sensor noise.
 */
class CompassSightOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class SightMarker(
        val towerId: String,
        val name: String,
        /** True bearing from install position to the site. */
        val bearingDegrees: Double,
        val distanceMeters: Double,
        val isFocus: Boolean
    )

    private var markers: List<SightMarker> = emptyList()
    private var targetHeadingDegrees: Double? = null
    private var displayedHeadingDegrees: Double? = null
    private var rotationRateDps: Double = 0.0
    private var horizontalFovDegrees: Float = DEFAULT_HORIZONTAL_FOV_DEGREES
    private var accentColor: Int = ContextCompat.getColor(context, R.color.accent_yellow)
    private var secondaryColor: Int = ContextCompat.getColor(context, R.color.accent_teal)
    private var textColor: Int = ContextCompat.getColor(context, R.color.text_primary)

    private val displayedMarkerX = mutableMapOf<String, Float>()
    private var headingAnimator: ValueAnimator? = null
    private var frameLoopActive = false

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (frameLoopActive && isAttachedToWindow) {
                invalidate()
                choreographer.postFrameCallback(this)
            }
        }
    }

    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val shadePaint = Paint().apply { color = 0x66000000 }

    fun update(
        headingDegrees: Double?,
        markers: List<SightMarker>,
        rotationRateDps: Double,
        horizontalFovDegrees: Float,
        accentColor: Int,
        secondaryColor: Int,
        textColor: Int
    ) {
        this.markers = markers
        this.rotationRateDps = rotationRateDps.coerceAtLeast(0.0)
        this.horizontalFovDegrees = horizontalFovDegrees.coerceIn(40f, 100f)
        this.accentColor = accentColor
        this.secondaryColor = secondaryColor
        this.textColor = textColor
        pruneStaleMarkerPositions(markers.map { it.towerId }.toSet())
        animateHeadingTo(headingDegrees)
        startFrameLoop()
        invalidate()
    }

    fun stopFrameLoop() {
        frameLoopActive = false
        choreographer.removeFrameCallback(frameCallback)
        headingAnimator?.cancel()
    }

    override fun onDetachedFromWindow() {
        stopFrameLoop()
        super.onDetachedFromWindow()
    }

    private fun startFrameLoop() {
        if (frameLoopActive || !isAttachedToWindow) return
        frameLoopActive = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun pruneStaleMarkerPositions(activeIds: Set<String>) {
        displayedMarkerX.keys.retainAll(activeIds)
    }

    private fun animateHeadingTo(heading: Double?) {
        targetHeadingDegrees = heading
        if (heading == null) {
            displayedHeadingDegrees = null
            headingAnimator?.cancel()
            return
        }
        val from = displayedHeadingDegrees
        if (from == null) {
            displayedHeadingDegrees = heading
            return
        }
        var delta = heading - from
        while (delta > 180.0) delta -= 360.0
        while (delta < -180.0) delta += 360.0
        if (abs(delta) < 0.2) {
            displayedHeadingDegrees = heading
            return
        }
        headingAnimator?.cancel()
        val start = from
        val duration = when {
            rotationRateDps > 40.0 -> 120L
            rotationRateDps > 12.0 -> 200L
            else -> 320L
        }
        headingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                displayedHeadingDegrees = GeoUtils.normalizeBearing(start + delta * t)
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val centerX = w / 2f
        val aimY = h * 0.42f
        val heading = displayedHeadingDegrees

        canvas.drawRect(0f, 0f, w, h * 0.18f, shadePaint)
        canvas.drawRect(0f, h * 0.82f, w, h, shadePaint)

        reticlePaint.color = accentColor
        reticlePaint.strokeWidth = 3f
        canvas.drawLine(centerX, aimY - 42f, centerX, aimY + 42f, reticlePaint)
        reticlePaint.strokeWidth = 2f
        canvas.drawLine(centerX - 28f, aimY, centerX + 28f, aimY, reticlePaint)

        if (heading != null) {
            val tickPaint = Paint(reticlePaint).apply {
                color = secondaryColor
                strokeWidth = 1.5f
                alpha = 180
            }
            listOf(-30.0, -15.0, 15.0, 30.0).forEach { tick ->
                val relative = GeoUtils.relativeBearingDegrees(heading, heading + tick)
                val x = bearingToScreenX(relative, centerX, w) ?: return@forEach
                canvas.drawLine(x, h * 0.14f, x, h * 0.20f, tickPaint)
            }
        }

        if (heading == null) return

        markers.forEach { marker ->
            val relative = GeoUtils.relativeBearingDegrees(heading, marker.bearingDegrees)
            val targetX = bearingToScreenX(relative, centerX, w) ?: run {
                displayedMarkerX.remove(marker.towerId)
                return@forEach
            }
            val x = smoothMarkerX(marker.towerId, targetX)
            val color = if (marker.isFocus) accentColor else secondaryColor
            markerPaint.color = color
            val halfWidth = if (marker.isFocus) 10f else 7f
            val top = aimY - if (marker.isFocus) 56f else 44f
            val bottom = aimY + 10f
            val path = Path().apply {
                moveTo(x, top)
                lineTo(x - halfWidth, bottom)
                lineTo(x + halfWidth, bottom)
                close()
            }
            canvas.drawPath(path, markerPaint)

            labelPaint.color = if (marker.isFocus) accentColor else textColor
            labelPaint.textSize = if (marker.isFocus) 30f else 24f
            val label = marker.name.take(10)
            val textWidth = labelPaint.measureText(label)
            canvas.drawText(label, x - textWidth / 2f, top - 8f, labelPaint)
        }

        hintPaint.color = textColor
        hintPaint.alpha = 210
        val hint = context.getString(R.string.compass_sight_hint)
        val hintWidth = hintPaint.measureText(hint)
        canvas.drawText(hint, centerX - hintWidth / 2f, h * 0.94f, hintPaint)
    }

    private fun smoothMarkerX(towerId: String, targetX: Float): Float {
        val previous = displayedMarkerX[towerId]
        if (previous == null) {
            displayedMarkerX[towerId] = targetX
            return targetX
        }
        val alpha = markerSmoothingAlpha(rotationRateDps)
        val delta = targetX - previous
        if (abs(delta) < 1.5f && rotationRateDps < 8.0) {
            return previous
        }
        val next = previous + alpha * delta
        displayedMarkerX[towerId] = next
        return next
    }

    private fun bearingToScreenX(relativeBearingDegrees: Double, centerX: Float, width: Float): Float? {
        val fraction = bearingToScreenFraction(relativeBearingDegrees, horizontalFovDegrees) ?: return null
        return centerX + fraction * (width * 0.46f)
    }

    companion object {
        const val DEFAULT_HORIZONTAL_FOV_DEGREES = 64f

        fun markerSmoothingAlpha(rotationRateDps: Double): Float = when {
            rotationRateDps > 40.0 -> 0.42f
            rotationRateDps > 15.0 -> 0.26f
            rotationRateDps > 5.0 -> 0.16f
            else -> 0.09f
        }

        /**
         * Maps signed relative bearing to [-1, 1] across the preview width.
         * Returns null when the target is outside the camera's horizontal FOV.
         */
        fun bearingToScreenFraction(relativeBearingDegrees: Double, horizontalFovDegrees: Float): Float? {
            val half = horizontalFovDegrees / 2f
            if (abs(relativeBearingDegrees) > half + 2.0) return null
            return (relativeBearingDegrees / half).toFloat().coerceIn(-1f, 1f)
        }
    }
}
