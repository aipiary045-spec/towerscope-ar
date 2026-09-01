package com.towerscope.ar

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** @deprecated Use [LanScannerActivity] — kept for deep links and bookmarks. */
class SubnetScannerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, LanScannerActivity::class.java).apply {
                putExtra(LanScannerActivity.EXTRA_MODE, LanScannerActivity.MODE_DISCOVER)
            }
        )
        finish()
    }
}
