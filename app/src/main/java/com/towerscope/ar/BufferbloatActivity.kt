package com.towerscope.ar

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** @deprecated Use [SpeedTestActivity] — kept for deep links and bookmarks. */
class BufferbloatActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, SpeedTestActivity::class.java))
        finish()
    }
}
