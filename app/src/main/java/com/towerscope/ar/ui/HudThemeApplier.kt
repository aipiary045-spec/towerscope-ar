package com.towerscope.ar.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.towerscope.ar.R

/**
 * Applies [HudTheme] colors to the outdoor HUD chrome.
 */
object HudThemeApplier {

    data class Colors(
        val panel: Int,
        val text: Int,
        val accent: Int,
        val secondary: Int,
        val mutedText: Int
    )

    fun colorsFor(theme: HudTheme, view: View): Colors {
        val ctx = view.context
        return when (theme) {
            HudTheme.DAY -> Colors(
                panel = ContextCompat.getColor(ctx, R.color.theme_day_panel),
                text = ContextCompat.getColor(ctx, R.color.theme_day_text),
                accent = ContextCompat.getColor(ctx, R.color.theme_day_accent),
                secondary = ContextCompat.getColor(ctx, R.color.theme_day_secondary),
                mutedText = Color.argb(170, 26, 36, 51)
            )
            HudTheme.HIGH_CONTRAST -> Colors(
                panel = ContextCompat.getColor(ctx, R.color.theme_hc_panel),
                text = ContextCompat.getColor(ctx, R.color.theme_hc_text),
                accent = ContextCompat.getColor(ctx, R.color.theme_hc_accent),
                secondary = ContextCompat.getColor(ctx, R.color.theme_hc_secondary),
                mutedText = Color.argb(220, 255, 255, 255)
            )
            HudTheme.NIGHT -> Colors(
                panel = ContextCompat.getColor(ctx, R.color.theme_night_panel),
                text = ContextCompat.getColor(ctx, R.color.theme_night_text),
                accent = ContextCompat.getColor(ctx, R.color.theme_night_accent),
                secondary = ContextCompat.getColor(ctx, R.color.theme_night_secondary),
                mutedText = ContextCompat.getColor(ctx, R.color.text_muted)
            )
        }
    }

    fun apply(
        theme: HudTheme,
        topBar: View,
        compassStrip: View,
        bottomPanel: View,
        trackingWarning: View,
        appTitle: TextView,
        headingLabel: TextView,
        focusTowerLabel: TextView,
        visibleCount: TextView,
        nearestHeader: TextView
    ) {
        val c = colorsFor(theme, topBar)
        topBar.setBackgroundColor(c.panel)
        compassStrip.setBackgroundColor(c.panel)
        bottomPanel.setBackgroundColor(c.panel)
        trackingWarning.setBackgroundColor(c.panel)

        appTitle.setTextColor(c.accent)
        headingLabel.setTextColor(c.secondary)
        focusTowerLabel.setTextColor(c.text)
        visibleCount.setTextColor(c.text)
        nearestHeader.setTextColor(c.mutedText)
    }

    fun statusChipBackground(view: View, statusColor: Int): GradientDrawable {
        val mutedFill = Color.argb(36, Color.red(statusColor), Color.green(statusColor), Color.blue(statusColor))
        return roundedRect(
            mutedFill,
            Color.argb(120, Color.red(statusColor), Color.green(statusColor), Color.blue(statusColor)),
            8f,
            view
        )
    }

    private fun roundedRect(fill: Int, stroke: Int, radiusDp: Float, view: View): GradientDrawable {
        val density = view.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(fill)
            if (stroke != Color.TRANSPARENT) {
                setStroke((1 * density).toInt().coerceAtLeast(1), stroke)
            }
        }
    }
}
