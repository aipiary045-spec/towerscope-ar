package com.towerscope.ar.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.towerscope.ar.R
import com.towerscope.ar.data.LosProfile
import com.towerscope.ar.util.Fresnel
import kotlin.math.max
import kotlin.math.min

/**
 * Distance (X) vs elevation (Y) LOS profile with terrain, Fresnel envelope,
 * obstruction marker, and You / Tower endpoint icons.
 */
class LosProfileChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var profile: LosProfile? = null
    private var clutterMeters: Double = 0.0
    private var frequencyGhz: Double = Fresnel.DEFAULT_FREQUENCY_GHZ
    private var showFresnel: Boolean = true

    private val terrainStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        color = 0xFF6FBF8F.toInt()
    }
    private val losPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.6f)
        color = ContextCompat.getColor(context, R.color.accent_yellow)
    }
    private val blockedLosPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.6f)
        color = ContextCompat.getColor(context, R.color.status_blocked)
    }
    private val fresnelFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x333EC9D6
    }
    private val fresnelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = 0x883EC9D6.toInt()
    }
    private val fresnel60StrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        color = 0xAAF0D060.toInt()
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0x28FFFFFF
    }
    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted)
        textSize = sp(10f)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted)
        textSize = sp(9f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val obstructionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.status_blocked)
    }
    private val obstructionRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = ContextCompat.getColor(context, R.color.status_blocked)
    }

    private val terrainPath = Path()
    private val fillPath = Path()
    private val fresnelPath = Path()
    private val fresnel60Upper = Path()
    private val fresnel60Lower = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val personIcon: Bitmap? =
        ContextCompat.getDrawable(context, R.drawable.ic_person)?.toBitmap(
            dp(14f).toInt(), dp(14f).toInt(), Bitmap.Config.ARGB_8888
        )
    private val towerIcon: Bitmap? = run {
        val d = ContextCompat.getDrawable(context, R.drawable.ic_tower_lattice) ?: return@run null
        val bmp = d.toBitmap(dp(12f).toInt(), dp(16f).toInt(), Bitmap.Config.ARGB_8888)
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(
            bmp, 0f, 0f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = PorterDuffColorFilter(
                    ContextCompat.getColor(context, R.color.accent_teal),
                    PorterDuff.Mode.SRC_IN
                )
            }
        )
        out
    }

    fun setProfile(
        profile: LosProfile?,
        clutterHeightMeters: Double,
        frequencyGhz: Double = Fresnel.DEFAULT_FREQUENCY_GHZ,
        showFresnel: Boolean = true
    ) {
        this.profile = profile
        this.clutterMeters = clutterHeightMeters.coerceAtLeast(0.0)
        this.frequencyGhz = frequencyGhz.coerceAtLeast(0.1)
        this.showFresnel = showFresnel
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
        val padR = dp(16f)
        val padT = dp(14f)
        val padB = dp(30f)
        val w = width - padL - padR
        val h = height - padT - padB
        if (w <= 0f || h <= 0f) return

        var minY = min(data.observerEyeElevationMeters, data.towerTipElevationMeters)
        var maxY = max(data.observerEyeElevationMeters, data.towerTipElevationMeters)
        data.samples.forEach { sample ->
            val t = sample.effectiveTerrainMeters(clutterMeters)
            minY = min(minY, t)
            maxY = max(maxY, t)
            if (showFresnel) {
                val los = data.losElevationAt(sample.distanceMeters)
                val r = Fresnel.radiusMeters(frequencyGhz, data.totalDistanceMeters, sample.distanceMeters)
                minY = min(minY, los - r)
                maxY = max(maxY, los + r)
            }
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

        for (i in 0..3) {
            val gy = padT + h * i / 3f
            canvas.drawLine(padL, gy, padL + w, gy, gridPaint)
        }

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
        fillPaint.shader = LinearGradient(
            0f, padT, 0f, padT + h,
            intArrayOf(0x883DDC97.toInt(), 0x223D8B6E.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)
        fillPaint.shader = null
        canvas.drawPath(terrainPath, terrainStrokePaint)

        if (showFresnel) {
            fresnelPath.reset()
            fresnel60Upper.reset()
            fresnel60Lower.reset()
            val samples = data.samples
            samples.forEachIndexed { index, sample ->
                val los = data.losElevationAt(sample.distanceMeters)
                val r = Fresnel.radiusMeters(frequencyGhz, data.totalDistanceMeters, sample.distanceMeters)
                val x = xOf(sample.distanceMeters)
                val yTop = yOf(los + r)
                if (index == 0) fresnelPath.moveTo(x, yTop) else fresnelPath.lineTo(x, yTop)
            }
            for (i in samples.indices.reversed()) {
                val sample = samples[i]
                val los = data.losElevationAt(sample.distanceMeters)
                val r = Fresnel.radiusMeters(frequencyGhz, data.totalDistanceMeters, sample.distanceMeters)
                fresnelPath.lineTo(xOf(sample.distanceMeters), yOf(los - r))
            }
            fresnelPath.close()
            canvas.drawPath(fresnelPath, fresnelFillPaint)
            canvas.drawPath(fresnelPath, fresnelStrokePaint)

            samples.forEachIndexed { index, sample ->
                val los = data.losElevationAt(sample.distanceMeters)
                val r60 = Fresnel.CLEARANCE_FRACTION *
                    Fresnel.radiusMeters(frequencyGhz, data.totalDistanceMeters, sample.distanceMeters)
                val x = xOf(sample.distanceMeters)
                if (index == 0) {
                    fresnel60Upper.moveTo(x, yOf(los + r60))
                    fresnel60Lower.moveTo(x, yOf(los - r60))
                } else {
                    fresnel60Upper.lineTo(x, yOf(los + r60))
                    fresnel60Lower.lineTo(x, yOf(los - r60))
                }
            }
            canvas.drawPath(fresnel60Upper, fresnel60StrokePaint)
            canvas.drawPath(fresnel60Lower, fresnel60StrokePaint)
        }

        val fresnelOk = if (showFresnel) {
            data.isFresnelClear(clutterMeters, frequencyGhz)
        } else {
            data.isClear(clutterMeters)
        }
        val clear = data.isClear(clutterMeters)
        val los = if (fresnelOk) losPaint else blockedLosPaint
        val x0 = xOf(0.0)
        val y0 = yOf(data.observerEyeElevationMeters)
        val x1 = xOf(data.totalDistanceMeters)
        val y1 = yOf(data.towerTipElevationMeters)
        canvas.drawLine(x0, y0, x1, y1, los)

        if (!clear || !fresnelOk) {
            data.worstClearanceSample(clutterMeters)?.let { (sample, _) ->
                val ox = xOf(sample.distanceMeters)
                val oy = yOf(sample.effectiveTerrainMeters(clutterMeters))
                canvas.drawCircle(ox, oy, dp(5f), obstructionPaint)
                canvas.drawCircle(ox, oy, dp(9f), obstructionRing)
            }
        }

        personIcon?.let {
            canvas.drawBitmap(it, x0 - it.width / 2f, y0 - it.height - dp(2f), null)
        }
        towerIcon?.let {
            canvas.drawBitmap(it, x1 - it.width / 2f, y1 - it.height + dp(2f), null)
        }

        val startLabel = "CPE"
        canvas.drawText(startLabel, padL, padT + h + dp(18f), axisLabelPaint)
        val endLabel = "AP"
        val endW = axisLabelPaint.measureText(endLabel)
        canvas.drawText(endLabel, padL + w - endW, padT + h + dp(18f), axisLabelPaint)
        canvas.drawText(String.format("%.0f m", maxY), dp(4f), padT + sp(10f), monoPaint)
        canvas.drawText(String.format("%.0f m", minY), dp(4f), padT + h, monoPaint)
        if (showFresnel) {
            canvas.drawText(
                String.format("%.1f GHz · F1 60%%", frequencyGhz),
                padL + dp(4f),
                padT + sp(11f),
                monoPaint
            )
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
