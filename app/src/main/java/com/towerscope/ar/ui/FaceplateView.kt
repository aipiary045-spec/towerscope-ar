package com.towerscope.ar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.towerscope.ar.R

/**
 * Engraved instrument plate: edge ticks and corner brackets.
 * Used behind Home / hub chrome so screens are not a flat Material fill.
 */
class FaceplateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.bg_navy)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.1f
        color = ContextCompat.getColor(context, R.color.grid_faint)
    }
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.6f
        color = ContextCompat.getColor(context, R.color.border_strong)
        strokeCap = Paint.Cap.SQUARE
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, fillPaint)

        val d = resources.displayMetrics.density
        val inset = 10f * d
        val major = 18f * d
        val minor = 8f * d

        var y = inset
        var i = 0
        while (y < h - inset) {
            val len = if (i % 5 == 0) major else minor
            canvas.drawLine(0f, y, len, y, tickPaint)
            canvas.drawLine(w - len, y, w, y, tickPaint)
            y += 10f * d
            i++
        }

        var x = inset
        i = 0
        while (x < w - inset) {
            val len = if (i % 5 == 0) major else minor
            canvas.drawLine(x, 0f, x, len, tickPaint)
            canvas.drawLine(x, h - len, x, h, tickPaint)
            x += 10f * d
            i++
        }

        val b = 22f * d
        val m = 8f * d
        canvas.drawLine(m, m, m + b, m, bracketPaint)
        canvas.drawLine(m, m, m, m + b, bracketPaint)
        canvas.drawLine(w - m, m, w - m - b, m, bracketPaint)
        canvas.drawLine(w - m, m, w - m, m + b, bracketPaint)
        canvas.drawLine(m, h - m, m + b, h - m, bracketPaint)
        canvas.drawLine(m, h - m, m, h - m - b, bracketPaint)
        canvas.drawLine(w - m, h - m, w - m - b, h - m, bracketPaint)
        canvas.drawLine(w - m, h - m, w - m, h - m - b, bracketPaint)
    }
}
