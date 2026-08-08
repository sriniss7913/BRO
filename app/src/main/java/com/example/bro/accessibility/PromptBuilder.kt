package com.example.bro.accessibility

import com.example.bro.model.ScreenState
import com.example.bro.model.UiElement

fun buildPrompt(screen: ScreenState): String {
    return PromptBuilder.build(screen)
}

object PromptBuilder {

    fun build(screen: ScreenState): String {

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
         * Detect WhatsApp chat screen correctly supporting both raw & canonical elements.
         */
        val hasBackButton = screen.nodes.any {
            val value = label(it).lowercase()
            value == "back" || value == "back_button" || value == "navigate up"
        }

        val hasChatIndicators = screen.nodes.any {
            val value = label(it).lowercase()

            elementIsEditableOrInput(it, value) ||
                    value == "message_input" ||
                    value == "send_button" ||
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

        val isWhatsAppArchived =
            isWhatsApp &&
                    hasBackButton &&
                    !hasChatIndicators &&
                    screen.nodes.any {
                        val value = label(it).lowercase()
                        value == "archived" || value.contains("these chats stay archived")
                    }

        val isWhatsAppMainList = isWhatsApp && !isInsideChat && !isWhatsAppArchived

        if (isInsideChat) {
            builder.append(
                "STATUS: INSIDE WHATSAPP CHAT.\n" +
                "IMPORTANT: You are ALREADY inside the target chat room.\n" +
                "To send a message, TYPE in MESSAGE_INPUT, then CLICK SEND_BUTTON.\n" +
                "DO NOT click BACK_BUTTON or SEARCH_BUTTON when inside a chat.\n\n"
            )
        } else if (isWhatsAppArchived) {
            builder.append(
                "STATUS: INSIDE WHATSAPP ARCHIVED CHATS SCREEN.\n" +
                "IMPORTANT: You are currently inside the Archived Chats folder, NOT the main chats list.\n" +
                "If you need to find or message a contact from the main list, CLICK the 'BACK_BUTTON' element first!\n\n"
            )
        } else if (isWhatsAppMainList) {
            builder.append(
                "STATUS: ON WHATSAPP MAIN CHATS SCREEN.\n" +
                "IMPORTANT: To find a contact not visible in the CHAT_ROW list, CLICK SEARCH_BUTTON first.\n" +
                "Then TYPE the contact name into the search bar.\n\n"
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
                displayText.contains("Battery charging", ignoreCase = true) ||
                displayText.contains("notifications", ignoreCase = true) ||
                displayText.contains("Swipe down to reveal", ignoreCase = true) ||
                displayText.contains("Inbox filters", ignoreCase = true) ||
                displayText.contains("Radio Group", ignoreCase = true)
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
            val dedupKey = displayText.take(50).lowercase()

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

            val rawVal = displayText
                .take(60)
                .ifBlank {
                    (element.className ?: "Element")
                        .substringAfterLast('.')
                }

            builder.append(rawVal)

            when {
                element.editable || rawVal.equals("MESSAGE_INPUT", ignoreCase = true) -> {
                    builder.append(" [EDITABLE - TYPE HERE]")

                    val currentText = element.text?.trim().orEmpty()
                    if (currentText.isNotBlank() && !currentText.equals("MESSAGE_INPUT", ignoreCase = true)) {
                        builder.append(" [CURRENT TEXT=\"${currentText.take(80)}\"]")
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
         * PASS 1: Interactive controls first.
         */
        val priorityElements = screen.nodes.filter { element ->
            val value = label(element).lowercase()

            element.editable ||
                    value == "send_button" ||
                    value == "send" ||
                    value.contains("send message") ||
                    value == "message_input" ||
                    value.contains("type a message") ||
                    value == "message" ||
                    value == "search_button" ||
                    value.contains("search") ||
                    value.contains("ask meta") ||
                    value.contains("attach") ||
                    value.contains("camera") ||
                    value.contains("emoji") ||
                    value == "back_button" ||
                    value == "back"
        }

        priorityElements.forEach {
            appendElement(it)
        }

        /*
         * PASS 2: Useful normal elements for context.
         */
        val maxTotalElements = 40

        for (element in screen.nodes) {
            if (emittedIds.size >= maxTotalElements) {
                break
            }

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

    private fun elementIsEditableOrInput(element: UiElement, labelLower: String): Boolean {
        return element.editable || labelLower == "message_input"
    }

    private fun isMetaAiCategoryChip(text: String): Boolean {
        val normalized = text.trim().lowercase()
        if (normalized == "search_button" || normalized.contains("search") || normalized.contains("ask meta or search") || normalized.contains("ask meta ai or search")) {
            return false
        }
        return normalized.contains("send message to meta ai") ||
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

    private val META_AI_CHIPS = setOf(
        "photos", "videos", "audio", "documents", "links", "gifs",
        "favorites", "ask", "meta ai", "unread"
    )
}