package com.towerscope.ar

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import com.towerscope.ar.ui.BottomNav
import com.towerscope.ar.ui.BottomNavTab
import com.towerscope.ar.ui.FieldTool
import com.towerscope.ar.ui.FieldTools
import com.towerscope.ar.ui.SystemBars
import com.towerscope.ar.util.ToolFocus
import com.towerscope.ar.util.ToolFinder
import com.towerscope.ar.util.ToolGroup
import com.towerscope.ar.util.ToolIndex

/**
 * Single tools launcher: every field tool is one tap away, with optional
 * Network / Install filters and type-to-find.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var searchField: EditText
    private lateinit var titleView: TextView
    private lateinit var subtitle: TextView
    private lateinit var emptyState: TextView
    private lateinit var networkHeader: View
    private lateinit var installHeader: View
    private lateinit var networkList: LinearLayout
    private lateinit var installList: LinearLayout
    private lateinit var toolsScroll: ScrollView

    private var focus: ToolFocus = ToolFocus.ALL
    private var index: List<ToolIndex> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_home)
        SystemBars.apply(
            root = findViewById(R.id.homeRoot),
            alsoBottom = findViewById(R.id.homeBottomNav)
        )

        searchField = findViewById(R.id.homeToolSearch)
        titleView = findViewById(R.id.homeToolsTitle)
        subtitle = findViewById(R.id.homeToolsSubtitle)
        emptyState = findViewById(R.id.homeEmptyState)
        networkHeader = findViewById(R.id.homeNetworkHeader)
        installHeader = findViewById(R.id.homeInstallHeader)
        networkList = findViewById(R.id.homeNetworkList)
        installList = findViewById(R.id.homeInstallList)
        toolsScroll = findViewById(R.id.homeToolsScroll)

        index = FieldTools.indexFrom { tool ->
            "${getString(tool.titleRes)} ${getString(tool.subtitleRes)} ${tool.extraKeywords}"
        }

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                render()
            }
        })

        applyFocus(ToolFinder.parseFocus(intent.getStringExtra(EXTRA_FOCUS)), scrollToTop = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyFocus(ToolFinder.parseFocus(intent.getStringExtra(EXTRA_FOCUS)))
    }

    fun applyFocus(newFocus: ToolFocus, scrollToTop: Boolean = true) {
        focus = newFocus
        titleView.setText(
            when (newFocus) {
                ToolFocus.ALL -> R.string.home_tools_title
                ToolFocus.NETWORK -> R.string.home_hub_network
                ToolFocus.INSTALL -> R.string.home_hub_install
            }
        )
        subtitle.setText(
            when (newFocus) {
                ToolFocus.ALL -> R.string.home_tools_sub
                ToolFocus.NETWORK -> R.string.home_hub_network_sub
                ToolFocus.INSTALL -> R.string.home_hub_install_sub
            }
        )
        BottomNav.bind(this, tabFor(newFocus))
        render()
        if (scrollToTop) {
            toolsScroll.post { toolsScroll.smoothScrollTo(0, 0) }
        }
    }

    private fun render() {
        val query = searchField.text?.toString().orEmpty()
        val visible = ToolFinder.visible(index, focus, query)
        val byId = FieldTools.all.associateBy { it.id }
        val network = visible.filter { it.group == ToolGroup.NETWORK }
        val install = visible.filter { it.group == ToolGroup.INSTALL }

        bindSection(networkHeader, networkList, network, byId)
        bindSection(installHeader, installList, install, byId)

        emptyState.isVisible = visible.isEmpty()
        if (visible.isEmpty()) {
            emptyState.text = getString(R.string.home_no_matching_tools, query.trim())
        }
    }

    private fun bindSection(
        header: View,
        list: LinearLayout,
        items: List<ToolIndex>,
        byId: Map<String, FieldTool>
    ) {
        header.isVisible = items.isNotEmpty()
        list.isVisible = items.isNotEmpty()
        list.removeAllViews()
        items.forEach { item ->
            val tool = byId.getValue(item.id)
            list.addView(
                FieldTools.inflateRow(layoutInflater, list, tool) {
                    startActivity(Intent(this, tool.target))
                }
            )
        }
    }

    companion object {
        const val EXTRA_FOCUS = "tool_focus"

        fun intent(context: Context, focus: ToolFocus): Intent =
            Intent(context, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_FOCUS, focus.name)

        fun tabFor(focus: ToolFocus): BottomNavTab = when (focus) {
            ToolFocus.ALL -> BottomNavTab.HOME
            ToolFocus.NETWORK -> BottomNavTab.NETWORK
            ToolFocus.INSTALL -> BottomNavTab.INSTALL
        }
    }
}
