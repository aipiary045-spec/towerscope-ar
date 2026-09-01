package com.towerscope.ar.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.towerscope.ar.R
import com.towerscope.ar.util.GeoUtils
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hybrid compass: rotating radar with full azimuth ring, lattice tower icons,
 * labeled markers, heading beam, and damped heading updates.
 */
class CompassRadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class TowerMarker(
        val towerId: String,
        val name: String,
        /** True bearing from install position to the site. */
        val bearingDegrees: Double,
        val distanceMeters: Double
    )

    private data class PlacedLabel(
        val towerId: String,
        val text: String,
        val markerX: Float,
        val markerY: Float,
        val labelX: Float,
        val labelY: Float,
        val isFocus: Boolean
    )

    private data class HitTarget(
        val towerId: String,
        val x: Float,
        val y: Float,
        val radius: Float
    )

    private var targetHeadingDegrees: Double? = null
    private var displayedHeadingDegrees: Double? = null
    private var rotationRateDps: Double = 0.0
    private var maxDistanceMeters: Float = 2000f
    private var markers: List<TowerMarker> = emptyList()
    private var focusTowerId: String? = null
    private var focusTitle: String = "No tower in range"
    private var focusDetail: String = ""
    private var accentColor: Int = ContextCompat.getColor(context, R.color.accent_yellow)
    private var secondaryColor: Int = ContextCompat.getColor(context, R.color.accent_teal)
    private var textColor: Int = ContextCompat.getColor(context, R.color.text_primary)
    private var mutedColor: Int = ContextCompat.getColor(context, R.color.text_muted)

    private var onTowerSelected: ((String) -> Unit)? = null
    private val hitTargets = mutableListOf<HitTarget>()
    private val density = resources.displayMetrics.density

    private val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private val condensedBold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val sansBold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.25f * density
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x28FFFFFF
    }
    private val youPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val youStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val leaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x66FFFFFF
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = sansBold
        textSize = 10f * density
    }
    private val tickLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = mono
        textSize = 9f * density
    }
    private val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = condensedBold
    }
    private val rangeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = mono
        textSize = 9f * density
    }
    private val focusTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = condensedBold
        textSize = 15f * density
    }
    private val focusDetailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = mono
        textSize = 11f * density
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beamPath = Path()
    private val lugPath = Path()

    private var towerBitmapTeal: Bitmap? = null
    private var towerBitmapYellow: Bitmap? = null
    private var frameLoopActive = false
    private var lastDrawNanos = 0L
    private val minDrawIntervalNanos = 50_000_000L // ~20 fps cap

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!frameLoopActive || !isAttachedToWindow) return
            stepHeadingTowardTarget()
            if (frameTimeNanos - lastDrawNanos >= minDrawIntervalNanos) {
                lastDrawNanos = frameTimeNanos
                invalidate()
            }
            choreographer.postFrameCallback(this)
        }
    }

    init {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_tower_lattice)
        if (drawable != null) {
            val w = (14 * density).toInt().coerceAtLeast(12)
            val h = (18 * density).toInt().coerceAtLeast(16)
            towerBitmapTeal = tintedBitmap(drawable, secondaryColor, w, h)
            towerBitmapYellow = tintedBitmap(drawable, accentColor, w, h)
        }
    }

    fun setOnTowerSelectedListener(listener: ((String) -> Unit)?) {
        onTowerSelected = listener
    }

    fun update(
        headingDegrees: Double?,
        maxDistanceMeters: Float,
        markers: List<TowerMarker>,
        focusTowerId: String?,
        focusLine: String,
        rotationRateDps: Double,
        accentColor: Int,
        secondaryColor: Int,
        textColor: Int,
        mutedColor: Int
    ) {
        this.maxDistanceMeters = maxDistanceMeters.coerceAtLeast(1f)
        this.markers = markers
        this.focusTowerId = focusTowerId
        this.rotationRateDps = rotationRateDps.coerceAtLeast(0.0)
        this.accentColor = accentColor
        this.secondaryColor = secondaryColor
        this.textColor = textColor
        this.mutedColor = mutedColor

        val parts = focusLine.split("  ·  ")
        focusTitle = parts.firstOrNull().orEmpty().ifBlank { "No tower in range" }
        focusDetail = if (parts.size > 1) parts.drop(1).joinToString("  ·  ") else ""

        ContextCompat.getDrawable(context, R.drawable.ic_tower_lattice)?.let { d ->
            val w = (14 * density).toInt().coerceAtLeast(12)
            val h = (18 * density).toInt().coerceAtLeast(16)
            towerBitmapTeal = tintedBitmap(d, secondaryColor, w, h)
            towerBitmapYellow = tintedBitmap(d, accentColor, w, h)
        }

        targetHeadingDegrees = headingDegrees
        if (headingDegrees != null && displayedHeadingDegrees == null) {
            displayedHeadingDegrees = headingDegrees
        }
        if (headingDegrees != null) {
            startFrameLoop()
        } else {
            stopFrameLoop()
            displayedHeadingDegrees = null
        }
        invalidate()
    }

    private fun startFrameLoop() {
        if (frameLoopActive || !isAttachedToWindow) return
        frameLoopActive = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopFrameLoop() {
        frameLoopActive = false
        choreographer.removeFrameCallback(frameCallback)
    }

    private fun stepHeadingTowardTarget() {
        val target = targetHeadingDegrees ?: run {
            displayedHeadingDegrees = null
            return
        }
        val current = displayedHeadingDegrees ?: run {
            displayedHeadingDegrees = target
            return
        }
        displayedHeadingDegrees = CompassHeadingSmoother.stepToward(
            current = current,
            target = target,
            rotationRateDps = rotationRateDps
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hitTargets.clear()

        val cx = width / 2f
        val minDim = min(width, height)
        val compact = minDim < 220f * density
        val outerPad = min(28f * density, minDim * 0.14f).coerceAtLeast(10f * density)
        val cy = height / 2f + if (compact) 2f * density else 6f * density
        val radius = minDim / 2f - outerPad
        if (radius < 14f * density) return

        drawDisc(canvas, cx, cy, radius)
        drawRangeRings(canvas, cx, cy, radius, compact)
        drawCrosshair(canvas, cx, cy, radius)
        drawHeadingBeam(canvas, cx, cy, radius)

        val heading = displayedHeadingDegrees
        if (heading != null) {
            drawAzimuthRing(canvas, cx, cy, radius, heading, compact)
        }

        drawHeadingLug(canvas, cx, cy, radius, compact)
        drawYou(canvas, cx, cy, compact)
        drawTowers(canvas, cx, cy, radius, compact)
        if (!compact) {
            drawFocusReadout(canvas, cx, cy, radius)
        }
    }

    private fun drawDisc(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        discPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(0xE6142A3C.toInt(), 0xF00B1C2C.toInt()),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, discPaint)
        discPaint.shader = null

        // Faint topo grid
        ringPaint.color = ContextCompat.getColor(context, R.color.grid_faint)
        ringPaint.strokeWidth = 0.8f * density
        for (i in -3..3) {
            val o = i * radius / 3.5f
            canvas.drawLine(cx - radius, cy + o, cx + radius, cy + o, ringPaint)
            canvas.drawLine(cx + o, cy - radius, cx + o, cy + radius, ringPaint)
        }
    }

    private fun drawRangeRings(canvas: Canvas, cx: Float, cy: Float, radius: Float, compact: Boolean) {
        rangeLabelPaint.color = mutedColor
        rangeLabelPaint.textSize = if (compact) 7.5f * density else 9f * density
        for (i in 1..4) {
            val frac = i / 4f
            val r = radius * frac
            ringPaint.strokeWidth = if (i == 4) 1.8f * density else 1.1f * density
            ringPaint.color = if (i == 4) 0x55A8C5D4 else 0x28A8C5D4
            canvas.drawCircle(cx, cy, r, ringPaint)
            val dist = maxDistanceMeters * frac
            val label = GeoUtils.formatDistance(dist.toDouble())
            canvas.drawText(
                label,
                cx + 4f * density,
                cy - r + 3f * density,
                rangeLabelPaint
            )
        }
    }

    private fun drawCrosshair(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawLine(cx - radius * 0.92f, cy, cx + radius * 0.92f, cy, crossPaint)
        canvas.drawLine(cx, cy - radius * 0.92f, cx, cy + radius * 0.92f, crossPaint)
    }

    private fun drawHeadingBeam(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        beamPath.reset()
        val half = 7.5f
        beamPath.moveTo(cx, cy)
        val left = Math.toRadians((-half).toDouble())
        val right = Math.toRadians(half.toDouble())
        beamPath.lineTo(
            cx + (sin(left) * radius).toFloat(),
            cy - (cos(left) * radius).toFloat()
        )
        beamPath.lineTo(
            cx + (sin(right) * radius).toFloat(),
            cy - (cos(right) * radius).toFloat()
        )
        beamPath.close()
        beamPaint.shader = LinearGradient(
            cx, cy, cx, cy - radius,
            intArrayOf(Color.argb(55, Color.red(secondaryColor), Color.green(secondaryColor), Color.blue(secondaryColor)),
                Color.argb(0, Color.red(secondaryColor), Color.green(secondaryColor), Color.blue(secondaryColor))),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(beamPath, beamPaint)
        beamPaint.shader = null
    }

    private fun drawHeadingLug(canvas: Canvas, cx: Float, cy: Float, radius: Float, compact: Boolean) {
        val tipY = cy - radius - 2f * density
        val lugHalf = if (compact) 6f * density else 8f * density
        val lugDepth = if (compact) 10f * density else 14f * density
        lugPath.reset()
        lugPath.moveTo(cx, tipY)
        lugPath.lineTo(cx - lugHalf, tipY + lugDepth)
        lugPath.lineTo(cx + lugHalf, tipY + lugDepth)
        lugPath.close()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = secondaryColor
        }
        canvas.drawPath(lugPath, fill)
        // Fine crosshair tick at top of ring
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
            color = accentColor
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(cx, cy - radius + 2f * density, cx, cy - radius + 18f * density, tick)
    }

    private fun drawAzimuthRing(canvas: Canvas, cx: Float, cy: Float, radius: Float, heading: Double, compact: Boolean) {
        val tickStep = if (compact) 15 else 5
        for (deg in 0 until 360 step tickStep) {
            val relative = ((deg - heading + 540.0) % 360.0) - 180.0
            val rad = Math.toRadians(relative)
            val major = deg % 30 == 0
            val cardinal = deg % 45 == 0
            val outer = radius
            val inner = when {
                cardinal && deg % 90 == 0 -> radius - 14f * density
                major -> radius - 10f * density
                else -> radius - 5f * density
            }
            tickPaint.strokeWidth = when {
                cardinal && deg % 90 == 0 -> 2.2f * density
                major -> 1.6f * density
                else -> 1f * density
            }
            tickPaint.color = when {
                deg == 0 -> accentColor
                major -> 0xAAFFFFFF.toInt()
                else -> 0x55FFFFFF
            }
            val ox = cx + (sin(rad) * outer).toFloat()
            val oy = cy - (cos(rad) * outer).toFloat()
            val ix = cx + (sin(rad) * inner).toFloat()
            val iy = cy - (cos(rad) * inner).toFloat()
            canvas.drawLine(ox, oy, ix, iy, tickPaint)

            if (major && !compact) {
                val labelR = radius - 22f * density
                val lx = cx + (sin(rad) * labelR).toFloat()
                val ly = cy - (cos(rad) * labelR).toFloat()
                tickLabelPaint.color = mutedColor
                canvas.drawText(String.format("%03d", deg), lx, ly + 3.5f * density, tickLabelPaint)
            }
        }

        val cardinals = if (compact) {
            listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)
        } else {
            listOf(
                "N" to 0.0, "NE" to 45.0, "E" to 90.0, "SE" to 135.0,
                "S" to 180.0, "SW" to 225.0, "W" to 270.0, "NW" to 315.0
            )
        }
        cardinals.forEach { (label, absolute) ->
            val relative = ((absolute - heading + 540.0) % 360.0) - 180.0
            val rad = Math.toRadians(relative)
            val r = radius + if (compact) 8f * density else 12f * density
            val x = cx + (sin(rad) * r).toFloat()
            val y = cy - (cos(rad) * r).toFloat()
            val primary = label == "N" || (!compact && label.length == 1)
            cardinalPaint.textSize = when {
                label == "N" -> if (compact) 11f * density else 13f * density
                compact -> 9f * density
                primary -> 13f * density
                else -> 10f * density
            }
            cardinalPaint.color = if (label == "N") accentColor else textColor
            cardinalPaint.alpha = if (primary) 255 else 200
            canvas.drawText(label, x, y + 4f * density, cardinalPaint)
        }
    }

    private fun drawYou(canvas: Canvas, cx: Float, cy: Float, compact: Boolean) {
        val dot = if (compact) 5f * density else 6.5f * density
        youPaint.color = accentColor
        canvas.drawCircle(cx, cy, dot, youPaint)
        canvas.drawCircle(cx, cy, dot, youStroke)
    }

    private fun drawTowers(canvas: Canvas, cx: Float, cy: Float, radius: Float, compact: Boolean) {
        val heading = displayedHeadingDegrees ?: return
        val dense = markers.size > 24
        val veryDense = markers.size > 48
        val placements = ArrayList<PlacedLabel>(markers.size)

        markers.forEach { marker ->
            val relative = GeoUtils.relativeBearingDegrees(heading, marker.bearingDegrees)
            val clamped = marker.distanceMeters.coerceIn(0.0, maxDistanceMeters.toDouble())
            val frac = sqrt(clamped / maxDistanceMeters).toFloat().coerceIn(0.04f, 1f)
            val screenAngleRad = Math.toRadians(relative)
            val x = cx + (sin(screenAngleRad) * radius * frac).toFloat()
            val y = cy - (cos(screenAngleRad) * radius * frac).toFloat()
            val isFocus = marker.towerId == focusTowerId
            val proximityScale = (1.15f - 0.35f * frac).coerceIn(0.75f, 1.2f)
            val iconScale = if (isFocus) proximityScale * 1.25f else proximityScale
            val bmp = if (isFocus) towerBitmapYellow else towerBitmapTeal
            if (bmp != null) {
                val scale = if (compact) 0.82f else 1f
                val iw = bmp.width * iconScale * scale
                val ih = bmp.height * iconScale * scale
                val dest = RectF(x - iw / 2f, y - ih * 0.85f, x + iw / 2f, y + ih * 0.15f)
                if (isFocus) {
                    glowPaint.color = Color.argb(160, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                    canvas.drawCircle(x, y - ih * 0.25f, iw * 0.7f, glowPaint)
                }
                canvas.drawBitmap(bmp, null, dest, iconPaint)
                hitTargets.add(HitTarget(marker.towerId, x, y - ih * 0.25f, maxOf(iw, ih) * 0.7f + 10f * density))
            } else {
                hitTargets.add(HitTarget(marker.towerId, x, y, 14f * density))
            }

            if (!compact) {
                val rawName = when {
                    isFocus -> marker.name
                    veryDense && marker.name.length > 6 -> abbreviate(marker.name, 5)
                    dense && marker.name.length > 10 -> abbreviate(marker.name, 8)
                    marker.name.length > 16 -> abbreviate(marker.name, 14)
                    else -> marker.name
                }
                val label = if (isFocus) {
                    val dist = GeoUtils.formatDistance(marker.distanceMeters)
                    "$rawName  $dist"
                } else {
                    rawName
                }
                // Initial label position: radially outward a bit, prefer above marker
                var lx = x
                var ly = y - 18f * density * iconScale
                placements.add(PlacedLabel(marker.towerId, label, x, y, lx, ly, isFocus))
            }
        }

        if (!compact) {
            resolveLabelCollisions(placements)

            placements.forEach { p ->
                labelPaint.color = if (p.isFocus) accentColor else textColor
                labelPaint.textSize = if (p.isFocus) 11.5f * density else 9.5f * density
                labelPaint.alpha = if (p.isFocus) 255 else 220
                if (hypot((p.labelX - p.markerX).toDouble(), (p.labelY - p.markerY).toDouble()) > 10.0) {
                    leaderPaint.color = if (p.isFocus) accentColor else 0x55FFFFFF
                    canvas.drawLine(p.markerX, p.markerY - 8f * density, p.labelX, p.labelY + 2f * density, leaderPaint)
                }
                canvas.drawText(p.text, p.labelX, p.labelY, labelPaint)
            }
        }
    }

    private fun resolveLabelCollisions(placements: MutableList<PlacedLabel>) {
        // Simple radial nudge / vertical stack for overlaps
        placements.sortByDescending { it.isFocus }
        val occupied = ArrayList<RectF>()
        for (i in placements.indices) {
            val p = placements[i]
            val w = labelPaint.measureText(p.text)
            val h = 12f * density
            var lx = p.labelX
            var ly = p.labelY
            var attempts = 0
            while (attempts < 8) {
                val rect = RectF(lx - w / 2f - 2f, ly - h, lx + w / 2f + 2f, ly + 2f)
                val clash = occupied.any { RectF.intersects(it, rect) }
                if (!clash) {
                    placements[i] = p.copy(labelX = lx, labelY = ly)
                    occupied.add(rect)
                    break
                }
                // Nudge radially away from center-ish (up first, then outward)
                ly -= 10f * density
                if (attempts >= 3) {
                    val dx = p.markerX - width / 2f
                    val dy = p.markerY - height / 2f
                    val len = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
                    lx += dx / len * 8f * density
                    ly += dy / len * 4f * density
                }
                attempts++
            }
            if (attempts >= 8) {
                occupied.add(RectF(lx - w / 2f, ly - h, lx + w / 2f, ly + 2f))
                placements[i] = p.copy(labelX = lx, labelY = ly)
            }
        }
    }

    private fun drawFocusReadout(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        focusTitlePaint.color = accentColor
        focusDetailPaint.color = textColor
        val y0 = cy - radius + 36f * density
        canvas.drawText(focusTitle, cx, y0, focusTitlePaint)
        if (focusDetail.isNotEmpty()) {
            canvas.drawText(focusDetail, cx, y0 + 16f * density, focusDetailPaint)
        }
    }

    private fun abbreviate(name: String, max: Int): String {
        if (name.length <= max) return name
        return name.take(max - 1) + "…"
    }

    private fun tintedBitmap(drawable: Drawable, color: Int, w: Int, h: Int): Bitmap {
        val bmp = drawable.toBitmap(w, h, Bitmap.Config.ARGB_8888)
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        c.drawBitmap(bmp, 0f, 0f, p)
        return out
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val hit = hitTargets.minByOrNull { target ->
            hypot((target.x - event.x).toDouble(), (target.y - event.y).toDouble())
        } ?: return true
        if (hypot((hit.x - event.x).toDouble(), (hit.y - event.y).toDouble()) <= hit.radius) {
            onTowerSelected?.invoke(hit.towerId)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        stopFrameLoop()
        super.onDetachedFromWindow()
    }
}
