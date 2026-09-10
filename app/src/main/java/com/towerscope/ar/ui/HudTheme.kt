package com.towerscope.ar.ui

import android.content.SharedPreferences
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
            LIGHT -> "Day"
            DARK -> "Night"
        }

    val outdoorHint: String
        get() = when (this) {
            LIGHT -> "Day · full sun"
            DARK -> "Night · low light"
        }

    val nightMode: Int
        get() = when (this) {
            LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }

    companion object {
        private const val PREF_THEME = "hud_theme"
        private const val PREF_SUN_DAY_DEFAULT = "sun_day_default_v1"

        fun fromStored(raw: String?): HudTheme = when (raw) {
            "DAY", "LIGHT" -> LIGHT
            "NIGHT", "DARK", "HIGH_CONTRAST" -> DARK
            else -> runCatching { valueOf(raw.orEmpty()) }.getOrDefault(LIGHT)
        }

        /**
         * One-time switch to Day so older installs that defaulted to Night
         * are readable in full sun. After this, the user's Theme toggle wins.
         */
        fun loadFromPrefs(prefs: SharedPreferences): HudTheme {
            if (!prefs.getBoolean(PREF_SUN_DAY_DEFAULT, false)) {
                prefs.edit()
                    .putBoolean(PREF_SUN_DAY_DEFAULT, true)
                    .putString(PREF_THEME, LIGHT.name)
                    .apply()
                return LIGHT
            }
            return fromStored(prefs.getString(PREF_THEME, LIGHT.name))
        }
    }
}
