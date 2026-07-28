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

        // BRO's own UI is irrelevant to external-app planning.
        if (
            screen.packageName == "com.example.bro" ||
            screen.packageName.endsWith(".bro")
        ) {
            return "Current App: com.example.bro (BRO Main Screen)\n"
        }

        val builder = StringBuilder()

        builder.append("Current App: ${screen.packageName}\n\n")

        if (screen.nodes.isEmpty()) {
            builder.append("Screen Elements:\n(empty screen)\n")
            return builder.toString()
        }

        val isWhatsApp = screen.packageName == "com.whatsapp"

        fun label(element: UiElement): String {
            return element.text?.trim()
                .orEmpty()
                .ifBlank {
                    element.contentDescription?.trim().orEmpty()
                }
        }

        /*
         * Detect WhatsApp chat.
         */
        val hasBackButton = screen.nodes.any {
            label(it).equals("Back", ignoreCase = true)
        }

        val hasChatIndicators = screen.nodes.any {
            val value = label(it).lowercase()

            it.editable ||
                    value == "message" ||
                    value.contains("type a message") ||
                    value.contains("video call") ||
                    value.contains("voice call") ||
                    value.contains("end-to-end")
        }

        val isInsideChat =
            isWhatsApp &&
                    hasBackButton &&
                    hasChatIndicators

        /*
         * Find high-priority interactive elements.
         *
         * These MUST reach the planner even if there are
         * hundreds of message-history nodes.
         */
        val priorityElements = screen.nodes.filter { element ->

            val value = label(element).lowercase()

            element.editable ||

                    value == "send" ||
                    value.contains("send message") ||

                    value.contains("type a message") ||
                    value == "message" ||

                    value.contains("search") ||

                    value.contains("attach") ||
                    value.contains("camera") ||
                    value.contains("emoji") ||

                    value == "back"
        }

        if (isInsideChat) {

            builder.append(
                "STATUS: INSIDE WHATSAPP CHAT.\n"
            )

            builder.append(
                "Choose actions only from CURRENT SCREEN elements. " +
                        "TYPE requires an editable input element. " +
                        "After typing, CLICK the Send element.\n\n"
            )
        }

        builder.append("Screen Elements:\n")

        val emittedIds = mutableSetOf<Int>()
        val seenTexts = mutableSetOf<String>()

        /*
         * Helper for writing one element.
         */
        fun appendElement(element: UiElement) {

            if (element.id in emittedIds) return

            val displayText = label(element)

            if (
                displayText.contains(
                    "Battery charging",
                    ignoreCase = true
                ) ||
                displayText.contains(
                    "notifications",
                    ignoreCase = true
                )
            ) {
                return
            }

            if (
                displayText.isNotBlank() &&
                isMetaAiCategoryChip(displayText)
            ) {
                return
            }

            val useful =
                displayText.isNotBlank() ||
                        element.editable ||
                        element.checkable ||
                        (
                                element.clickable &&
                                        !isGenericEmptyContainer(element)
                                )

            if (!useful) return

            /*
             * Deduplicate ordinary text, but NEVER deduplicate
             * interactive/editable elements.
             */
            val dedupKey =
                displayText
                    .take(50)
                    .lowercase()

            if (
                !element.editable &&
                !element.clickable &&
                dedupKey.isNotBlank() &&
                dedupKey in seenTexts
            ) {
                return
            }

            if (dedupKey.isNotBlank()) {
                seenTexts.add(dedupKey)
            }

            builder.append("[${element.id}] ")

            builder.append(
                displayText
                    .take(60)
                    .ifBlank {
                        (element.className ?: "Element")
                            .substringAfterLast('.')
                    }
            )

            when {

                element.editable -> {
                    builder.append(" [EDITABLE - TYPE HERE]")

                    /*
                     * Important:
                     * node.text can contain the currently entered
                     * message, so the planner can tell whether TYPE
                     * already happened.
                     */
                    val currentText =
                        element.text
                            ?.trim()
                            .orEmpty()

                    if (currentText.isNotBlank()) {
                        builder.append(
                            " [CURRENT TEXT=\"${currentText.take(80)}\"]"
                        )
                    }
                }

                element.clickable -> {
                    builder.append(" [CLICK]")
                }
            }

            if (element.checkable) {

                builder.append(" [CHK")

                if (element.checked) {
                    builder.append(":1")
                }

                builder.append("]")
            }

            if (!element.enabled) {
                builder.append(" [OFF]")
            }

            builder.append('\n')

            emittedIds.add(element.id)
        }

        /*
         * PASS 1:
         *
         * Always send interactive controls first.
         *
         * This is the important fix.
         */
        priorityElements.forEach {
            appendElement(it)
        }

        /*
         * PASS 2:
         *
         * Then send useful normal elements for context.
         */
        val maxTotalElements = 40

        for (element in screen.nodes) {

            if (emittedIds.size >= maxTotalElements) {
                break
            }

            /*
             * Don't waste planner context on giant pieces of
             * conversation history.
             */
            val displayText = label(element)

            if (
                displayText.length > 80 &&
                !element.editable &&
                !element.clickable
            ) {
                continue
            }

            appendElement(element)
        }

        if (screen.nodes.size > emittedIds.size) {

            builder.append(
                "...${screen.nodes.size - emittedIds.size} more elements omitted\n"
            )
        }

        return builder.toString()
    }

    private fun isMetaAiCategoryChip(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return normalized.contains("meta ai") ||
            normalized.contains("ask meta") ||
            normalized.contains("send message to meta ai") ||
            normalized in META_AI_CHIPS
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
