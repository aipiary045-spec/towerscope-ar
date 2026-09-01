package com.towerscope.ar.ui

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.towerscope.ar.R
import com.towerscope.ar.network.ConnectionSnapshot

object NetworkTopologyBinder {

    fun bind(root: View, snapshot: ConnectionSnapshot) {
        root.findViewById<TextView>(R.id.wifiTopoInternetDetail)?.text =
            snapshot.publicIpv4 ?: snapshot.publicIpv4Error ?: "—"
        root.findViewById<TextView>(R.id.wifiTopoInternetStatus)?.apply {
            text = when {
                snapshot.isValidated -> "Online"
                snapshot.isConnected -> "Limited"
                else -> "Offline"
            }
            setTextColor(
                ContextCompat.getColor(
                    context,
                    when {
                        snapshot.isValidated -> R.color.status_clear
                        snapshot.isConnected -> R.color.status_warn
                        else -> R.color.status_blocked
                    }
                )
            )
        }
        root.findViewById<TextView>(R.id.wifiTopoApDetail)?.text = when {
            snapshot.linkType == "Wi‑Fi" -> snapshot.wifiSsid ?: snapshot.gatewayIpv4 ?: "—"
            snapshot.gatewayIpv4 != null -> snapshot.gatewayIpv4
            else -> snapshot.linkType
        }
        root.findViewById<TextView>(R.id.wifiTopoDeviceLabel)?.text = snapshot.linkType
        root.findViewById<TextView>(R.id.wifiTopoDeviceDetail)?.text =
            snapshot.localIpv4 ?: "—"
    }
}
