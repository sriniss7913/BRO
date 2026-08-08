package com.example.bro.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object HierarchicalFormatter {

    /**
     * Entry point. Clears NodeRepository, does two-pass traversal,
     * and returns a compact plain-text tree for the LLM.
     *
     * Trimming rules (app-agnostic, no resource/class blacklists):
     *   1. packageName == com.example.bro  → return simple stub (BRO's own UI is irrelevant)
     *   2. isVisibleToUser == false         → trim
     *   3. Zero-area bounds                → trim
     *   4. Leaf with nothing useful        → trim
     *   5. Container all children trimmed  → trim
     */
    fun format(root: AccessibilityNodeInfo?, packageName: String): String {
        NodeRepository.clear()

        // BRO's own UI is never sent to the LLM planner
        if (packageName == "com.example.bro" || packageName.endsWith(".bro")) {
            return "├── Node 0\n│   App: com.example.bro\n│   Text: BRO Main Screen"
        }

        if (root == null) return "├── Node 0\n│   App: $packageName\n│   Text: Empty Screen"

        // --- Pass 1: bottom-up mark useful nodes ---
        val usefulSet = mutableSetOf<AccessibilityNodeInfo>()
        markUseful(root, usefulSet)

        // --- Pass 2: top-down emit as compact plain text tree ---
        val sb = StringBuilder()
        
        val idCounter = intArrayOf(0)
        formatNode(root, usefulSet, sb, indent = "", isLast = true, idCounter = idCounter)

        return sb.toString().trimEnd()
    }

    // -------------------------------------------------------------------------
    // Pass 1 — bottom-up mark useful nodes
    // -------------------------------------------------------------------------
    private fun markUseful(
        node: AccessibilityNodeInfo?,
        usefulSet: MutableSet<AccessibilityNodeInfo>
    ): Boolean {
        if (node == null) return false

        var anyChildUseful = false
        for (i in 0 until node.childCount) {
            if (markUseful(node.getChild(i), usefulSet)) anyChildUseful = true
        }

        val hasContent =
            !node.text.isNullOrBlank() ||
            !node.contentDescription.isNullOrBlank() ||
            !node.hintText.isNullOrBlank() ||
            !node.viewIdResourceName.isNullOrBlank()

        val hasInteraction =
            node.isClickable || node.isEditable ||
            node.isCheckable || node.isLongClickable || node.isScrollable

        val useful = hasContent || hasInteraction || anyChildUseful
        if (useful) usefulSet.add(node)
        return useful
    }

    // -------------------------------------------------------------------------
    // Pass 2 — top-down emit as compact plain text tree
    // -------------------------------------------------------------------------
    private fun formatNode(
        node: AccessibilityNodeInfo?,
        usefulSet: Set<AccessibilityNodeInfo>,
        sb: StringBuilder,
        indent: String,
        isLast: Boolean,
        idCounter: IntArray
    ) {
        if (node == null) return
        if (node !in usefulSet) return
        if (!node.isVisibleToUser) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() == 0 || bounds.height() == 0) return

        val id = idCounter[0]++
        NodeRepository.put(id, node)

        val branch = if (indent.isEmpty()) "├── " else if (isLast) "└── " else "├── "
        val childIndentPrefix = if (indent.isEmpty()) "│   " else if (isLast) "    " else "│   "
        val nextIndent = indent + childIndentPrefix
        
        sb.append(indent).append(branch).append("Node $id\n")

        val cls = node.className?.toString()?.substringAfterLast('.').orEmpty()
        if (cls.isNotBlank()) sb.append(nextIndent).append("Class: $cls\n")

        val res = node.viewIdResourceName?.toString()?.substringAfterLast('/').orEmpty()
        if (res.isNotBlank()) sb.append(nextIndent).append("Resource: $res\n")

        val text = node.text?.toString()?.trim()?.replace("\n", " ") ?: ""
        if (text.isNotBlank()) sb.append(nextIndent).append("Text: $text\n")

        val desc = node.contentDescription?.toString()?.trim()?.replace("\n", " ") ?: ""
        if (desc.isNotBlank() && desc != text) sb.append(nextIndent).append("Desc: $desc\n")

        val hint = node.hintText?.toString()?.trim()?.replace("\n", " ") ?: ""
        if (hint.isNotBlank()) sb.append(nextIndent).append("Hint: $hint\n")

        sb.append(nextIndent).append("Bounds: ${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}\n")

        if (node.isClickable) sb.append(nextIndent).append("Clickable: true\n")
        if (node.isEditable) sb.append(nextIndent).append("Editable: true\n")
        if (node.isLongClickable) sb.append(nextIndent).append("LongClickable: true\n")
        if (node.isCheckable) sb.append(nextIndent).append("Checkable: true\n")
        if (node.isChecked) sb.append(nextIndent).append("Checked: true\n")
        if (node.isScrollable) sb.append(nextIndent).append("Scrollable: true\n")
        if (!node.isEnabled) sb.append(nextIndent).append("Disabled: true\n")

        // Collect useful children
        val usefulChildren = (0 until node.childCount)
            .mapNotNull { node.getChild(it) }
            .filter { child ->
                child in usefulSet &&
                child.isVisibleToUser &&
                Rect().also { child.getBoundsInScreen(it) }.let { r -> r.width() > 0 && r.height() > 0 }
            }

        usefulChildren.forEachIndexed { index, child ->
            val childIsLast = index == usefulChildren.size - 1
            formatNode(child, usefulSet, sb, nextIndent, childIsLast, idCounter)
        }
    }
}

