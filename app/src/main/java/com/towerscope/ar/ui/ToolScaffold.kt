package com.towerscope.ar.ui

import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.R

/**
 * Shared title + back chrome for field tool screens.
 */
object ToolScaffold {

    fun bind(
        activity: AppCompatActivity,
        titleRes: Int,
        subtitleRes: Int? = null,
        onShare: (() -> Unit)? = null
    ) {
        activity.findViewById<TextView>(R.id.toolTitle)?.setText(titleRes)
        subtitleRes?.let { res ->
            activity.findViewById<TextView>(R.id.toolSubtitle)?.apply {
                setText(res)
                visibility = android.view.View.VISIBLE
            }
        }
        activity.findViewById<MaterialButton>(R.id.toolBackButton)?.setOnClickListener {
            activity.finish()
        }
        activity.findViewById<MaterialButton>(R.id.toolShareButton)?.apply {
            if (onShare != null) {
                visibility = android.view.View.VISIBLE
                setOnClickListener { onShare() }
            } else {
                visibility = android.view.View.GONE
            }
        }
    }
}
