package com.towerscope.ar.ui

import androidx.appcompat.app.AppCompatDelegate

/**
 * App appearance. Persisted and applied via AppCompat night mode
 * so light/dark color resources swap across every screen.
 * HIGH_CONTRAST is a sunlight-readable variant on the dark palette.
 */
enum class HudTheme {
    LIGHT,
    DARK,
    HIGH_CONTRAST;

    fun next(): HudTheme = when (this) {
        LIGHT -> DARK
        DARK -> HIGH_CONTRAST
        HIGH_CONTRAST -> LIGHT
    }

    val label: String
        get() = when (this) {
            LIGHT -> "Light"
            DARK -> "Dark"
            HIGH_CONTRAST -> "High contrast"
        }

    val nightMode: Int
        get() = when (this) {
            LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            DARK, HIGH_CONTRAST -> AppCompatDelegate.MODE_NIGHT_YES
        }

    companion object {
        fun fromStored(raw: String?): HudTheme = when (raw) {
            "DAY", "LIGHT" -> LIGHT
            "NIGHT", "DARK" -> DARK
            else -> runCatching { valueOf(raw.orEmpty()) }.getOrDefault(DARK)
        }
    }
}
