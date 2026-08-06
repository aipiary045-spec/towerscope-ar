package com.towerscope.ar.ui

import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.EditText
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
                mutedText = Color.argb(180, 11, 28, 44)
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
                mutedText = Color.argb(230, 255, 255, 255)
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
        themeButton: Button,
        dataButton: Button
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
        searchField.setBackgroundColor(
            if (theme == HudTheme.DAY) Color.argb(40, 0, 0, 0) else Color.argb(26, 255, 255, 255)
        )

        themeButton.text = theme.label
        themeButton.setBackgroundColor(
            if (theme == HudTheme.DAY) Color.parseColor("#CBD5E1") else Color.parseColor("#334155")
        )
        themeButton.setTextColor(if (theme == HudTheme.DAY) c.text else Color.WHITE)

        dataButton.setBackgroundColor(c.accent)
        dataButton.setTextColor(
            if (theme == HudTheme.DAY) Color.WHITE else Color.parseColor("#0B1C2C")
        )
    }
}
