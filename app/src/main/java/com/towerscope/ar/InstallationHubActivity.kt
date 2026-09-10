package com.towerscope.ar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
            rowId = R.id.hubLocateRow,
            index = "01",
            icon = R.drawable.ic_satellite_map,
            title = R.string.home_job_locate,
            subtitle = R.string.home_job_locate_sub
        ) { startActivity(Intent(this, MapActivity::class.java)) }

        bindRow(
            rowId = R.id.hubLosRow,
            index = "02",
            icon = R.drawable.ic_terrain_profile,
            title = R.string.home_job_los,
            subtitle = R.string.home_job_los_sub
        ) { startActivity(Intent(this, LosProfilesActivity::class.java)) }

        bindRow(
            rowId = R.id.installHubImportButton,
            index = "03",
            icon = R.drawable.ic_tower_lattice,
            title = R.string.home_import_sites,
            subtitle = R.string.home_import_hint
        ) { startActivity(Intent(this, DataMenuActivity::class.java)) }
    }

    private fun bindRow(
        rowId: Int,
        index: String,
        icon: Int,
        title: Int,
        subtitle: Int,
        onClick: () -> Unit
    ) {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.hubToolIndex).text = index
        row.findViewById<ImageView>(R.id.hubToolIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.hubToolTitle).setText(title)
        row.findViewById<TextView>(R.id.hubToolSubtitle).setText(subtitle)
        row.setOnClickListener { onClick() }
    }
}
