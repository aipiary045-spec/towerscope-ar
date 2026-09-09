package com.towerscope.ar.util

enum class ToolGroup {
    NETWORK,
    INSTALL
}

enum class ToolFocus {
    ALL,
    NETWORK,
    INSTALL
}

data class ToolIndex(
    val id: String,
    val group: ToolGroup,
    val searchText: String
)

/**
 * Pure filter for the Home tools list: section focus plus a type-to-find query.
 * Search narrows the current tab. Use the Tools tab (ALL) to search everything.
 */
object ToolFinder {
    fun parseFocus(raw: String?): ToolFocus = when (raw?.trim()?.uppercase()) {
        ToolFocus.NETWORK.name -> ToolFocus.NETWORK
        ToolFocus.INSTALL.name -> ToolFocus.INSTALL
        else -> ToolFocus.ALL
    }

    fun visible(
        tools: List<ToolIndex>,
        focus: ToolFocus,
        query: String
    ): List<ToolIndex> {
        val needle = query.trim().lowercase()
        return tools.filter { tool ->
            val inFocus = when (focus) {
                ToolFocus.ALL -> true
                ToolFocus.NETWORK -> tool.group == ToolGroup.NETWORK
                ToolFocus.INSTALL -> tool.group == ToolGroup.INSTALL
            }
            if (!inFocus) return@filter false
            needle.isEmpty() || tool.searchText.lowercase().contains(needle)
        }
    }
}
