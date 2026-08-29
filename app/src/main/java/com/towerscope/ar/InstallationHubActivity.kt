package com.towerscope.ar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.towerscope.ar.ui.BottomNav
import com.towerscope.ar.ui.BottomNavTab
import com.towerscope.ar.ui.SystemBars

class InstallationHubActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_installation_hub)
        SystemBars.apply(
            root = findViewById(R.id.installHubRoot),
            alsoBottom = findViewById(R.id.installBottomNav)
        )
        BottomNav.bind(this, BottomNavTab.INSTALL)

        bindRow(
            rowId = R.id.hubCompassRow,
            icon = R.drawable.ic_compass_rose,
            iconTint = R.color.accent_yellow,
            title = R.string.home_job_aim,
            subtitle = R.string.home_job_aim_sub
        ) { startActivity(Intent(this, MainActivity::class.java)) }

        bindRow(
            rowId = R.id.hubLocateRow,
            icon = R.drawable.ic_satellite_map,
            iconTint = R.color.accent_teal,
            title = R.string.home_job_locate,
            subtitle = R.string.home_job_locate_sub
        ) { startActivity(Intent(this, MapActivity::class.java)) }

        bindRow(
            rowId = R.id.hubLosRow,
            icon = R.drawable.ic_terrain_profile,
            iconTint = R.color.accent_teal,
            title = R.string.home_job_los,
            subtitle = R.string.home_job_los_sub
        ) { startActivity(Intent(this, LosProfilesActivity::class.java)) }

        findViewById<View>(R.id.installHubImportButton).setOnClickListener {
            startActivity(Intent(this, DataMenuActivity::class.java))
        }
    }

    private fun bindRow(rowId: Int, icon: Int, iconTint: Int, title: Int, subtitle: Int, onClick: () -> Unit) {
        val row = findViewById<View>(rowId)
        row.findViewById<ImageView>(R.id.hubToolIcon).apply {
            setImageResource(icon)
            setColorFilter(ContextCompat.getColor(this@InstallationHubActivity, iconTint))
        }
        row.findViewById<TextView>(R.id.hubToolTitle).setText(title)
        row.findViewById<TextView>(R.id.hubToolSubtitle).setText(subtitle)
        row.setOnClickListener { onClick() }
    }
}
