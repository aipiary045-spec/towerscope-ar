package com.towerscope.ar.ui

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.towerscope.ar.R

/**
 * Shared title chrome for field tool screens. Share actions only — use system back to exit.
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
                visibility = View.VISIBLE
            }
        }
        val actions = activity.findViewById<View>(R.id.toolActionsBar)
        val shareButton = actions?.findViewById<MaterialButton>(R.id.toolShareButton)
            ?: activity.findViewById(R.id.toolShareButton)
        if (onShare != null && shareButton != null) {
            actions?.visibility = View.VISIBLE
            shareButton.visibility = View.VISIBLE
            shareButton.setOnClickListener { onShare() }
        } else {
            actions?.visibility = View.GONE
            shareButton?.visibility = View.GONE
        }
    }
}
