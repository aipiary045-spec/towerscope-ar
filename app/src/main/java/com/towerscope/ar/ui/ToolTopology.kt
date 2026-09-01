package com.towerscope.ar.ui

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.towerscope.ar.network.ConnectionSnapshotCollector
import kotlinx.coroutines.launch

object ToolTopology {

    fun bindWhenResumed(activity: AppCompatActivity, root: View) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val snapshot = ConnectionSnapshotCollector.collect(activity, fetchPublicIp = false)
                NetworkTopologyBinder.bind(root, snapshot)
            }
        }
    }

    fun refresh(activity: AppCompatActivity, root: View) {
        activity.lifecycleScope.launch {
            val snapshot = ConnectionSnapshotCollector.collect(activity, fetchPublicIp = false)
            NetworkTopologyBinder.bind(root, snapshot)
        }
    }
}
