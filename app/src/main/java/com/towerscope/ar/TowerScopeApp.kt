package com.towerscope.ar

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.towerscope.ar.ui.AppTheme
import com.towerscope.ar.ui.HudTheme
import org.osmdroid.config.Configuration
import java.io.File

class TowerScopeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        applyStoredTheme()
        try {
            val cfg = Configuration.getInstance()
            val base = File(cacheDir, "osmdroid")
            val tiles = File(base, "tiles")
            if (!base.exists()) base.mkdirs()
            if (!tiles.exists()) tiles.mkdirs()
            cfg.osmdroidBasePath = base
            cfg.osmdroidTileCache = tiles
            cfg.userAgentValue = "TowerScope/1.0 (Android; field map)"
            cfg.tileDownloadThreads = 4
            cfg.tileFileSystemCacheMaxBytes = 128L * 1024L * 1024L
            cfg.load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        } catch (t: Throwable) {
            Log.e("TowerScopeApp", "osmdroid config failed", t)
        }
    }

    private fun applyStoredTheme() {
        val prefs = getSharedPreferences("towerscope_prefs", Context.MODE_PRIVATE)
        // Keep key in sync with TowerScopeViewModel.KEY_HUD_THEME
        val theme = HudTheme.fromStored(prefs.getString("hud_theme", HudTheme.DARK.name))
        AppCompatDelegate.setDefaultNightMode(theme.nightMode)
        AppTheme.apply(theme)
    }
}
