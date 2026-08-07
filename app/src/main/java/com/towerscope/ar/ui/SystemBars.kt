package com.towerscope.ar.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Pads screen content for transparent status/navigation bars (edge-to-edge theme).
 *
 * @param root main content (gets top / side insets; bottom inset unless [alsoBottom] is set)
 * @param alsoBottom optional footer that receives the bottom system-bar inset instead of [root]
 */
object SystemBars {
    fun apply(root: View, alsoBottom: View? = null) {
        val rootLeft = root.paddingLeft
        val rootTop = root.paddingTop
        val rootRight = root.paddingRight
        val rootBottom = root.paddingBottom
        val footerBottom = alsoBottom?.paddingBottom ?: 0

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = rootLeft + bars.left,
                top = rootTop + bars.top,
                right = rootRight + bars.right,
                bottom = if (alsoBottom == null) rootBottom + bars.bottom else rootBottom
            )
            alsoBottom?.updatePadding(bottom = footerBottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
