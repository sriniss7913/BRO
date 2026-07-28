package com.example.bro.planner

import com.example.bro.model.Action
import com.example.bro.model.ActionType
import com.example.bro.model.ScreenState

object DirectElementMatcher {

    /**
     * Checks if the target subject (e.g. contact name "Soundarya", "Ramesh", "Goutham")
     * is ALREADY directly visible and clickable on the current screen.
     * If so, returns a direct CLICK action on that element without needing search or LLM round trips.
     */
    fun findDirectMatch(goalStr: String, screen: ScreenState): Action? {
        val targetKeywords = TaskIntentClassifier.extractTargetKeywords(goalStr)
        if (targetKeywords.isEmpty()) return null

        for (node in screen.nodes) {
            if (!node.clickable) continue
            val text = node.text?.trim()
            val description = node.contentDescription?.trim()
            val displayText = text.orEmpty().ifBlank { description.orEmpty() }

            if (displayText.length < 2) continue

            // Exclude search bars, action bars, and back buttons
            val lowerText = displayText.lowercase()
            if (lowerText.contains("search") || lowerText.contains("ask meta") ||
                lowerText.contains("back") || lowerText.contains("more options") ||
                lowerText.contains("camera") || lowerText.contains("gallery")) continue

            for (keyword in targetKeywords) {
                if (displayText.contains(keyword, ignoreCase = true)) {
                    return Action(
                        type = ActionType.CLICK,
                        targetId = node.id,
                        target = displayText,
                        summary = "Direct match found on screen: Clicking '$displayText'"
                    )
                }
            }
        }
        return null
    }
}
