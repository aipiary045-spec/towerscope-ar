package com.towerscope.ar.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
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
        messageBanner: View,
        appTitle: TextView,
        headingLabel: TextView,
        focusTowerLabel: TextView,
        visibleCount: TextView,
        distanceLabel: TextView,
        nearestHeader: TextView,
        searchField: EditText,
        themeButton: TextView,
        dataButton: MaterialButton,
        showHiddenButton: MaterialButton
    ) {
        val c = colorsFor(theme, topBar)
        topBar.setBackgroundColor(c.panel)
        compassStrip.setBackgroundColor(c.panel)
        bottomPanel.setBackgroundColor(c.panel)
        trackingWarning.setBackgroundColor(c.panel)
        messageBanner.setBackgroundColor(c.panel)

        appTitle.setTextColor(c.accent)
        headingLabel.setTextColor(c.secondary)
        focusTowerLabel.setTextColor(c.text)
        visibleCount.setTextColor(c.text)
        distanceLabel.setTextColor(c.accent)
        nearestHeader.setTextColor(c.mutedText)

        searchField.setTextColor(c.text)
        searchField.setHintTextColor(c.mutedText)
        searchField.background = roundedRect(
            fill = if (theme == HudTheme.DAY) Color.argb(28, 0, 0, 0) else Color.argb(20, 255, 255, 255),
            stroke = if (theme == HudTheme.DAY) Color.argb(40, 0, 0, 0) else Color.argb(40, 255, 255, 255),
            radiusDp = 10f,
            view = searchField
        )

        themeButton.text = theme.label
        themeButton.setTextColor(c.mutedText)
        themeButton.background = roundedRect(
            fill = if (theme == HudTheme.DAY) Color.argb(30, 0, 0, 0) else Color.argb(30, 255, 255, 255),
            stroke = Color.TRANSPARENT,
            radiusDp = 8f,
            view = themeButton
        )

        dataButton.setBackgroundColor(c.accent)
        dataButton.setTextColor(
            if (theme == HudTheme.DAY) Color.WHITE else Color.parseColor("#0B1C2C")
        )
        showHiddenButton.setTextColor(c.text)
        showHiddenButton.strokeColor = android.content.res.ColorStateList.valueOf(
            if (theme == HudTheme.DAY) Color.argb(70, 0, 0, 0) else Color.argb(100, 255, 255, 255)
        )
    }

    fun statusChipBackground(view: View, statusColor: Int): GradientDrawable {
        val mutedFill = Color.argb(36, Color.red(statusColor), Color.green(statusColor), Color.blue(statusColor))
        return roundedRect(mutedFill, Color.argb(120, Color.red(statusColor), Color.green(statusColor), Color.blue(statusColor)), 8f, view)
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
