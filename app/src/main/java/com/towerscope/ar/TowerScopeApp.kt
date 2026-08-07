package com.towerscope.ar

import android.app.Application
import android.content.Context
import org.osmdroid.config.Configuration
import java.io.File

class TowerScopeApp : Application() {
    override fun onCreate() {
        super.onCreate()
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
    }
}
