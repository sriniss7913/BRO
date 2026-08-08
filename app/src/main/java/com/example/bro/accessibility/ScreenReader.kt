package com.example.bro.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.example.bro.model.ScreenState
import com.example.bro.model.UiElement

class ScreenReader {

    fun read(root: AccessibilityNodeInfo?): ScreenState {
        // Build flat UiElement list for ScreenState metadata
        val nodes = mutableListOf<UiElement>()
        traverse(root, nodes)
        return ScreenState(
            packageName = root?.packageName?.toString() ?: "",
            nodes = nodes,
            rawRoot = root   // carry the live root so toPrompt() can use it anywhere
        )
    }

    private fun traverse(
        node: AccessibilityNodeInfo?,
        list: MutableList<UiElement>
    ) {
        if (node == null) return

        val aggregatedText = extractAggregateText(node)?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val displayText = aggregatedText.orEmpty().ifBlank { desc.orEmpty() }

        val isUseful = node.isEditable ||
            displayText.isNotBlank() ||
            node.isClickable ||
            node.isCheckable ||
            node.isFocusable

        if (isUseful) {
            val id = list.size
            list.add(
                UiElement(
                    id = id,
                    text = aggregatedText,
                    contentDescription = desc,
                    className = node.className?.toString(),
                    clickable = node.isClickable,
                    enabled = node.isEnabled,
                    editable = node.isEditable,
                    checkable = node.isCheckable,
                    checked = node.isChecked
                )
            )
        }

        for (i in 0 until node.childCount) {
            traverse(node.getChild(i), list)
        }
    }

    private fun extractAggregateText(node: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth > 4) return null

        val directText = node.text?.toString()?.trim()
        if (!directText.isNullOrBlank()) return directText

        val directDesc = node.contentDescription?.toString()?.trim()
        if (!directDesc.isNullOrBlank()) return directDesc

        val builder = StringBuilder()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childText = extractAggregateText(child, depth + 1)
            if (!childText.isNullOrBlank()) {
                if (builder.isNotEmpty()) builder.append(" ")
                builder.append(childText)
            }
        }
        return if (builder.isNotEmpty()) builder.toString() else null
    }

    /**
     * Build hierarchical tree prompt. Uses rawRoot stored in ScreenState.
     */
    fun toPrompt(screen: ScreenState): String {
        return UiCanonicalizer.canonicalize(screen.rawRoot, screen.packageName)
    }

    /**
     * Direct call with raw root — used by BroAccessibilityService for logcat logging.
     */
    fun toPrompt(root: AccessibilityNodeInfo?): String {
        return UiCanonicalizer.canonicalize(root, root?.packageName?.toString() ?: "")
    }
}
