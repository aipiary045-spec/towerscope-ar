package com.towerscope.ar

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.towerscope.ar.ui.SettingsBottomSheet
import com.towerscope.ar.ui.SystemBars

/**
 * Root launcher: tile navigation into Network Hub and Installation Hub.
 */
class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_home)
        SystemBars.apply(findViewById(R.id.homeRoot))

        findViewById<View>(R.id.homeNetworkHubButton).setOnClickListener {
            startActivity(Intent(this, NetworkHubActivity::class.java))
        }
        findViewById<View>(R.id.homeInstallHubButton).setOnClickListener {
            startActivity(Intent(this, InstallationHubActivity::class.java))
        }
        findViewById<View>(R.id.homeSettingsButton).setOnClickListener {
            if (supportFragmentManager.findFragmentByTag(SettingsBottomSheet.TAG) == null) {
                SettingsBottomSheet.newInstance().show(supportFragmentManager, SettingsBottomSheet.TAG)
            }
        }
    }
}
