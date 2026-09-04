package com.towerscope.ar.ui

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.towerscope.ar.R

/**
 * Applies [HudTheme] colors to outdoor HUD chrome (Aim).
 * App-wide light/dark is handled by AppCompat night mode + color resources.
 */
object HudThemeApplier {

    data class Colors(
        val panel: Int,
        val text: Int,
        val mutedText: Int,
        val accent: Int,
        val secondary: Int,
        val warning: Int
    )

    fun colorsFor(theme: HudTheme, view: View): Colors {
        val ctx = view.context
        return when (theme) {
            HudTheme.LIGHT -> Colors(
                panel = ContextCompat.getColor(ctx, R.color.theme_day_panel),
                text = ContextCompat.getColor(ctx, R.color.theme_day_text),
                mutedText = ContextCompat.getColor(ctx, R.color.theme_day_muted),
                accent = ContextCompat.getColor(ctx, R.color.theme_day_accent),
                secondary = ContextCompat.getColor(ctx, R.color.theme_day_secondary),
                warning = ContextCompat.getColor(ctx, R.color.status_blocked)
            )
            HudTheme.DARK -> Colors(
                panel = ContextCompat.getColor(ctx, R.color.theme_night_panel),
                text = ContextCompat.getColor(ctx, R.color.theme_night_text),
                mutedText = ContextCompat.getColor(ctx, R.color.text_muted),
                accent = ContextCompat.getColor(ctx, R.color.theme_night_accent),
                secondary = ContextCompat.getColor(ctx, R.color.theme_night_secondary),
                warning = ContextCompat.getColor(ctx, R.color.status_blocked)
            )
            HudTheme.HIGH_CONTRAST -> Colors(
                panel = ContextCompat.getColor(ctx, R.color.theme_hc_panel),
                text = ContextCompat.getColor(ctx, R.color.theme_hc_text),
                mutedText = ContextCompat.getColor(ctx, R.color.theme_hc_muted),
                accent = ContextCompat.getColor(ctx, R.color.theme_hc_accent),
                secondary = ContextCompat.getColor(ctx, R.color.theme_hc_secondary),
                warning = ContextCompat.getColor(ctx, R.color.status_blocked)
            )
        }
    }

    fun apply(
        theme: HudTheme,
        topBar: View,
        compassStrip: View,
        bottomPanel: View,
        trackingWarning: TextView,
        appTitle: TextView,
        headingLabel: TextView,
        focusTowerLabel: TextView,
        visibleCount: TextView,
        nearestHeader: TextView
    ) {
        val colors = colorsFor(theme, topBar)
        appTitle.setTextColor(colors.text)
        headingLabel.setTextColor(colors.secondary)
        focusTowerLabel.setTextColor(colors.text)
        visibleCount.setTextColor(colors.text)
        nearestHeader.setTextColor(colors.text)
        trackingWarning.setTextColor(colors.warning)
    }

    fun statusChipBackground(view: View, fillColor: Int): android.graphics.drawable.GradientDrawable {
        val density = view.resources.displayMetrics.density
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            // Translucent fill + outline so the colored status text stays readable.
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(fillColor, 38))
            setStroke(
                (1f * density).toInt().coerceAtLeast(1),
                androidx.core.graphics.ColorUtils.setAlphaComponent(fillColor, 130)
            )
        }
    }
}
