package com.towerscope.ar

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** @deprecated Use [WifiMonitorActivity] — channel planner is built into Wi‑Fi survey. */
class ChannelPlannerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, WifiMonitorActivity::class.java))
        finish()
    }
}
