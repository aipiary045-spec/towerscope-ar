package com.towerscope.ar

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.towerscope.ar.ui.BottomNavTab

/** Legacy entry — forwards to [MainHostActivity]. */
class HomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(MainHostActivity.intent(this, BottomNavTab.HOME))
        finish()
    }
}
