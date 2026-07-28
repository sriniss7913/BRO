package com.example.bro.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.example.bro.model.ScreenState
import com.example.bro.model.UiElement

class ScreenReader {

    fun read(root: AccessibilityNodeInfo?): ScreenState {
        NodeRepository.clear()
        val nodes = mutableListOf<UiElement>()
        traverse(root, nodes)
        return ScreenState(
            packageName = root?.packageName?.toString() ?: "",
            nodes = nodes
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
            NodeRepository.put(id, node)
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
     * Builds the screen context string sent to the LLM planner.
     * Element IDs are preserved so NodeRepository stays in sync with click targets.
     */
    fun toPrompt(screen: ScreenState): String {
        // Token Optimization: In BRO main app, don't send 80 internal chat UI elements
        if (screen.packageName == "com.example.bro" || screen.packageName.endsWith(".bro")) {
            return "Current App: com.example.bro (BRO Main Screen)\n"
        }

        val builder = StringBuilder()
        builder.append("Current App: ${screen.packageName}\n\n")

        if (screen.nodes.isEmpty()) {
            builder.append("Screen Elements:\n(empty screen)\n")
            return builder.toString()
        }

        // Detect if we are inside a WhatsApp chat thread (NOT the main list or search screen)
        // Signals: WhatsApp + Back button + (Chat input box OR Voice/Video Call header icons OR end-to-end encryption text)
        val isWhatsApp = screen.packageName == "com.whatsapp"
        val hasBackButton = screen.nodes.any {
            val t = (it.text ?: it.contentDescription ?: "").toString().lowercase()
            t == "back" && it.clickable
        }
        val hasChatThreadIndicators = screen.nodes.any {
            val t = (it.text ?: it.contentDescription ?: "").toString().lowercase()
            val textRaw = (it.text ?: it.contentDescription ?: "").toString().trim()
            textRaw.equals("Message", ignoreCase = true) ||
            t.contains("type a message") ||
            t.contains("video call") ||
            t.contains("voice call") ||
            t.contains("end-to-end")
        }
        val isInsideChat = isWhatsApp && hasBackButton && hasChatThreadIndicators

        if (isInsideChat) {
            builder.append("STATUS: YOU ARE INSIDE THE CHAT. Messages are visible below. Use TYPE on [CHAT INPUT BOX] to type. Use CLICK on Send to send.\n\n")
        }

        builder.append("Screen Elements:\n")

        var included = 0
        val maxElements = 35  // Cap at 35 useful elements for token savings
        val seenTexts = mutableSetOf<String>()

        // Track first few element IDs to identify header zone (first 4 elements in WhatsApp chat are header)
        var elementIndex = 0

        for (element in screen.nodes) {
            if (included >= maxElements) break
            elementIndex++

            val text = element.text?.trim()
            val description = element.contentDescription?.trim()
            val displayText = text.orEmpty().ifBlank { description.orEmpty() }

            // Filter out system UI overlays (battery %, notifications summary)
            if (displayText.contains("Battery charging", ignoreCase = true) ||
                displayText.contains("notifications", ignoreCase = true)
            ) continue

            val hasText = displayText.isNotBlank()

            val useful = hasText ||
                element.editable ||
                element.checkable ||
                (element.clickable && !isGenericEmptyContainer(element))

            if (!useful) continue

            if (hasText && isMetaAiCategoryChip(displayText)) continue

            // Trim long non-interactive text to save tokens
            if (displayText.length > 50 && !element.editable && !element.clickable) continue

            // Skip duplicate text
            val dedupKey = displayText.take(40).lowercase()
            if (!element.editable && seenTexts.contains(dedupKey)) continue
            seenTexts.add(dedupKey)

            // Determine if this is a header element inside a WhatsApp chat
            // When inside chat: ALL clickable elements in the first ~8 positions are header
            // (Back, Contact Name, Call, Video Call, More Options)
            val isHeaderElement = isInsideChat && elementIndex <= 8 && !element.editable &&
                element.clickable && !displayText.lowercase().let { d ->
                    d.contains("send") || d.contains("type a message") ||
                    d.contains("emoji") || d.contains("attach") || d.contains("camera")
                }

            builder.append("[${element.id}] ")
            builder.append(
                displayText.take(40).ifBlank {
                    (element.className ?: "Element").substringAfterLast('.')
                }
            )

            if (element.editable) {
                builder.append(" [CHAT INPUT BOX - TYPE HERE]")
            } else if (isHeaderElement) {
                builder.append(" [HEADER - DO NOT CLICK]")
            } else if (element.clickable) {
                builder.append(" [CLICK]")
            }
            if (element.checkable) {
                builder.append(" [CHK")
                if (element.checked) builder.append(":1")
                builder.append("]")
            }
            if (!element.enabled) builder.append(" [OFF]")
            builder.append('\n')
            included++
        }

        if (screen.nodes.size > included) {
            builder.append("...${screen.nodes.size - included} more\n")
        }

        return builder.toString()
    }

    private fun isMetaAiCategoryChip(text: String): Boolean {
        val normalized = text.trim().lowercase()
        if (normalized.length > 30) return false
        return normalized in META_AI_CHIPS || normalized == "ask meta ai"
    }

    private fun isGenericEmptyContainer(element: UiElement): Boolean {
        val cls = element.className.orEmpty()
        val isLayout = cls.contains("Layout") ||
            cls.contains("ViewGroup") ||
            cls.contains("Frame")
        return isLayout &&
            element.text.isNullOrBlank() &&
            element.contentDescription.isNullOrBlank()
    }

    companion object {
        private val META_AI_CHIPS = setOf(
            "photos", "videos", "audio", "documents", "links", "gifs",
            "favorites", "ask", "meta ai", "unread"
        )
    }
}
