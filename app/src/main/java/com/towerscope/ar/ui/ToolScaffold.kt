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
        onShare: (() -> Unit)? = null,
        onBack: (() -> Unit)? = null
    ) {
        activity.findViewById<TextView>(R.id.toolTitle)?.setText(titleRes)
        subtitleRes?.let { res ->
            activity.findViewById<TextView>(R.id.toolSubtitle)?.apply {
                setText(res)
                visibility = android.view.View.VISIBLE
            }
        }
        val actions = activity.findViewById<android.view.View>(R.id.toolActionsBar)
        val backButton = actions?.findViewById<MaterialButton>(R.id.toolBackButton)
            ?: activity.findViewById(R.id.toolBackButton)
        val shareButton = actions?.findViewById<MaterialButton>(R.id.toolShareButton)
            ?: activity.findViewById(R.id.toolShareButton)
        backButton?.setOnClickListener {
            if (onBack != null) onBack() else activity.finish()
        }
        shareButton?.apply {
            if (onShare != null) {
                visibility = android.view.View.VISIBLE
                setOnClickListener { onShare() }
            } else {
                visibility = android.view.View.GONE
            }
        }
    }
}
