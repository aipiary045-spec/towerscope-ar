package com.towerscope.ar.ui

import androidx.appcompat.app.AppCompatDelegate

/**
 * App appearance. Persisted and applied via AppCompat night mode
 * so light/dark color resources swap across every screen.
 */
enum class HudTheme {
    LIGHT,
    DARK;

    fun next(): HudTheme = when (this) {
        LIGHT -> DARK
        DARK -> LIGHT
    }

    val label: String
        get() = when (this) {
            LIGHT -> "Light"
            DARK -> "Dark"
        }

    val nightMode: Int
        get() = when (this) {
            LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }

    companion object {
        fun fromStored(raw: String?): HudTheme = when (raw) {
            "DAY", "LIGHT" -> LIGHT
            "NIGHT", "DARK", "HIGH_CONTRAST" -> DARK
            else -> runCatching { valueOf(raw.orEmpty()) }.getOrDefault(DARK)
        }
    }
}
