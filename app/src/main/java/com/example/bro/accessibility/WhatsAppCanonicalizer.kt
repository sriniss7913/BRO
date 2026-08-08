package com.example.bro.accessibility

import com.example.bro.model.ScreenState
import com.example.bro.model.UiElement

object WhatsAppCanonicalizer {

    fun canonicalize(screen: ScreenState): List<CanonicalElement> {
        val canonicalList = mutableListOf<CanonicalElement>()
        val consumedIds = mutableSetOf<Int>()
        var hasSearchButtonEmitted = false
        var hasBackButtonEmitted = false

        val nodes = screen.nodes

        for (i in nodes.indices) {
            val element = nodes[i]
            if (element.id in consumedIds) continue

            val rawText = (element.text ?: element.contentDescription ?: "").trim()
            val lowerText = rawText.lowercase()

            when {
                // Rule 1: Search Button (deduplicate multiple search nodes)
                isSearchButton(element, lowerText) -> {
                    if (!hasSearchButtonEmitted) {
                        canonicalList.add(
                            CanonicalElement(
                                targetId = element.id,
                                role = "SEARCH_BUTTON",
                                text = "SEARCH_BUTTON",
                                clickable = true
                            )
                        )
                        hasSearchButtonEmitted = true
                    }
                    consumedIds.add(element.id)
                }

                // Rule 2: Send Button
                isSendButton(element, lowerText) -> {
                    canonicalList.add(
                        CanonicalElement(
                            targetId = element.id,
                            role = "SEND_BUTTON",
                            text = "SEND_BUTTON",
                            clickable = true
                        )
                    )
                    consumedIds.add(element.id)
                }

                // Rule 3: Message Input
                isMessageInput(element, lowerText) -> {
                    canonicalList.add(
                        CanonicalElement(
                            targetId = element.id,
                            role = "MESSAGE_INPUT",
                            text = "MESSAGE_INPUT",
                            editable = true
                        )
                    )
                    consumedIds.add(element.id)
                }

                // Rule 4: Back Button (deduplicate multiple back nodes)
                isBackButton(element, lowerText) -> {
                    if (!hasBackButtonEmitted) {
                        canonicalList.add(
                            CanonicalElement(
                                targetId = element.id,
                                role = "BACK_BUTTON",
                                text = "BACK_BUTTON",
                                clickable = true
                            )
                        )
                        hasBackButtonEmitted = true
                    }
                    consumedIds.add(element.id)
                }

                // Rule 5: Chat Rows
                isChatRowCandidate(element, rawText) -> {
                    val (canonicalRow, collapsedIds) = collapseChatRow(nodes, i)
                    if (canonicalRow != null) {
                        canonicalList.add(canonicalRow)
                        consumedIds.addAll(collapsedIds)
                    } else {
                        canonicalList.add(
                            CanonicalElement(
                                targetId = element.id,
                                role = if (element.editable) "INPUT" else if (element.clickable) "BUTTON" else "TEXT",
                                text = rawText,
                                clickable = element.clickable,
                                editable = element.editable
                            )
                        )
                        consumedIds.add(element.id)
                    }
                }

                else -> {
                    if (rawText.isNotBlank() || element.editable || element.clickable) {
                        canonicalList.add(
                            CanonicalElement(
                                targetId = element.id,
                                role = if (element.editable) "INPUT" else if (element.clickable) "BUTTON" else "TEXT",
                                text = rawText,
                                clickable = element.clickable,
                                editable = element.editable
                            )
                        )
                    }
                    consumedIds.add(element.id)
                }
            }
        }

        return canonicalList
    }

    private fun isSearchButton(element: UiElement, lowerText: String): Boolean {
        return lowerText == "search" ||
                lowerText.contains("ask meta or search") ||
                lowerText.contains("ask meta ai or search")
    }

    private fun isSendButton(element: UiElement, lowerText: String): Boolean {
        return (element.clickable || element.className?.contains("ImageButton", ignoreCase = true) == true) &&
                (lowerText == "send" || lowerText == "send message")
    }

    private fun isMessageInput(element: UiElement, lowerText: String): Boolean {
        return element.editable || lowerText == "type a message" || lowerText == "message"
    }

    private fun isBackButton(element: UiElement, lowerText: String): Boolean {
        return element.clickable && (lowerText == "back" || lowerText == "navigate up")
    }

    private fun isChatRowCandidate(element: UiElement, rawText: String): Boolean {
        if (rawText.contains("filter", ignoreCase = true) || rawText.contains("Add new list", ignoreCase = true)) {
            return false
        }
        return element.clickable || rawText.contains("picture", ignoreCase = true)
    }

    private fun collapseChatRow(nodes: List<UiElement>, startIndex: Int): Pair<CanonicalElement?, List<Int>> {
        val firstNode = nodes[startIndex]
        val collapsedIds = mutableListOf<Int>()
        collapsedIds.add(firstNode.id)

        var primaryTargetId = firstNode.id
        var contactName = ""
        var preview = ""

        val rawText = (firstNode.text ?: firstNode.contentDescription ?: "").trim()

        if (rawText.contains(" picture ", ignoreCase = true)) {
            val parts = rawText.split(" picture ", limit = 2)
            contactName = parts[0].trim()
            if (parts.size > 1) {
                val rem = parts[1].trim()
                preview = if (rem.startsWith(contactName, ignoreCase = true)) {
                    rem.substring(contactName.length).trim()
                } else rem
            }
        } else if (rawText.isNotBlank()) {
            contactName = rawText
        }

        var j = startIndex + 1
        while (j < nodes.size && j < startIndex + 4) {
            val nextNode = nodes[j]
            val nextText = (nextNode.text ?: nextNode.contentDescription ?: "").trim()

            val isSameRow = (contactName.isNotBlank() && nextText.contains(contactName, ignoreCase = true)) ||
                    nextText.contains("picture", ignoreCase = true) ||
                    (!nextNode.clickable && nextText.isNotBlank())

            if (isSameRow) {
                collapsedIds.add(nextNode.id)
                if (preview.isBlank() && nextText.isNotBlank() && !nextText.equals(contactName, ignoreCase = true) && !nextText.contains("picture", ignoreCase = true)) {
                    preview = nextText
                }
                if (firstNode.clickable) {
                    primaryTargetId = firstNode.id
                } else if (nextNode.clickable) {
                    primaryTargetId = nextNode.id
                }
                j++
            } else {
                break
            }
        }

        if (contactName.isNotBlank()) {
            val canonicalRow = CanonicalElement(
                targetId = primaryTargetId,
                role = "CHAT_ROW",
                text = "CHAT_ROW: $contactName${if (preview.isNotBlank()) " | $preview" else ""}",
                contact = contactName,
                preview = preview,
                clickable = true
            )
            return Pair(canonicalRow, collapsedIds)
        }

        return Pair(null, emptyList())
    }
}