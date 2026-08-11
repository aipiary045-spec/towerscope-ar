package com.towerscope.ar.ui

import androidx.appcompat.app.AppCompatDelegate

/**
 * Applies persisted [HudTheme] through AppCompat night mode so
 * `values` / `values-night` color resources swap app-wide.
 */
object AppTheme {
    fun apply(theme: HudTheme) {
        val mode = theme.nightMode
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
