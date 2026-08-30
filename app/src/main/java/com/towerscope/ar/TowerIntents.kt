package com.towerscope.ar

import android.content.Context
import android.content.Intent

/** Shared intent extras when opening compass / map / LOS for a specific site. */
object TowerIntents {
    const val EXTRA_TOWER_ID = "tower_id"

    fun open(context: Context, activityClass: Class<*>, towerId: String): Intent =
        Intent(context, activityClass).putExtra(EXTRA_TOWER_ID, towerId)

    fun towerIdFrom(intent: Intent): String? = intent.getStringExtra(EXTRA_TOWER_ID)
}
