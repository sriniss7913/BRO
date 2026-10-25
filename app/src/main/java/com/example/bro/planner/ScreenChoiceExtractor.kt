package com.example.bro.planner

import com.example.bro.model.ScreenState
import com.example.bro.model.UiElement

object ScreenChoiceExtractor {

    // Common location and address keywords to identify location-selection screens
    private val LOCATION_KEYWORDS = listOf(
        "location", "address", "deliver to", "delivery location", "select location",
        "saved address", "choose address", "current location", "detect location"
    )

    private val ADDRESS_TAGS = listOf("home", "work", "office", "other", "current location", "use current location")

    // Buttons or text to ignore when extracting options
    private val IGNORED_TEXTS = setOf(
        "back", "close", "cancel", "clear", "search", "menu", "navigate up", "more options",
        "settings", "help", "privacy policy", "terms of service", "skip", "continue"
    )

    /**
     * Extracts grounded, contextual choices from the screen (e.g., location options,
     * dialog options, radio buttons) to present to the user when doubt or disambiguation occurs.
     */
    fun extractChoices(
        suggested: List<String> = emptyList(),
        screen: ScreenState? = null
    ): List<String> {
        val result = mutableListOf<String>()

        // 1. Process and clean suggested choices from the model
        val cleanedSuggested = suggested
            .map { cleanOptionLabel(it) }
            .filter { isValidOption(it) }
            .distinct()

        result.addAll(cleanedSuggested)

        // If we already have 2+ high-quality suggestions, merge with cancel and return
        if (result.size >= 2) {
            val topSuggested = result.distinct().filter { !it.equals("Cancel task", ignoreCase = true) }.take(3)
            return (topSuggested + listOf("Cancel task")).distinct()
        }

        if (screen == null || screen.nodes.isEmpty()) {
            val fallback = result.distinct().filter { !it.equals("Cancel task", ignoreCase = true) }.take(3)
            return (fallback + listOf("Cancel task")).distinct()
        }

        val allNodeTexts = screen.nodes.mapNotNull { node ->
            val text = (node.text ?: node.contentDescription).orEmpty().trim()
            if (text.isNotBlank()) text to node else null
        }

        val screenTextCombined = allNodeTexts.joinToString(" ") { it.first }.lowercase()
        val isLocationScreen = LOCATION_KEYWORDS.any { screenTextCombined.contains(it) }

        // 2. If it's a location/address screen, extract address cards or location options
        if (isLocationScreen) {
            val locationCandidates = extractLocationCandidates(allNodeTexts)
            for (candidate in locationCandidates) {
                if (!result.any { it.equals(candidate, ignoreCase = true) }) {
                    result.add(candidate)
                }
            }
        }

        // 3. Extract interactive dialog choices or checkable items (radio buttons, selection pills)
        if (result.size < 3) {
            val interactiveCandidates = extractInteractiveCandidates(screen)
            for (candidate in interactiveCandidates) {
                if (!result.any { it.equals(candidate, ignoreCase = true) }) {
                    result.add(candidate)
                }
            }
        }

        // 4. Extract prominent button texts if in a dialog
        if (result.size < 3) {
            val dialogButtons = screen.nodes
                .filter { it.clickable && !it.text.isNullOrBlank() }
                .map { cleanOptionLabel(it.text.orEmpty()) }
                .filter { isValidOption(it) && it.length <= 30 }
            for (btn in dialogButtons) {
                if (!result.any { it.equals(btn, ignoreCase = true) }) {
                    result.add(btn)
                }
            }
        }

        // 5. Always ensure "Cancel task" is available as the final exit option
        val topContextual = result.distinct().filter { !it.equals("Cancel task", ignoreCase = true) }.take(3)
        return (topContextual + listOf("Cancel task")).distinct()
    }

    private fun extractLocationCandidates(allNodeTexts: List<Pair<String, UiElement>>): List<String> {
        val candidates = mutableListOf<String>()

        // Look for prominent address labels ("Home", "Work", "Office", "Use current location")
        for ((text, _) in allNodeTexts) {
            val lower = text.lowercase()
            val matchesTag = ADDRESS_TAGS.any { tag ->
                lower == tag || lower.startsWith("$tag ") || lower.startsWith("$tag\n") || lower.startsWith("$tag,")
            }

            if (matchesTag || lower.contains("current location")) {
                val cleaned = cleanOptionLabel(text.lines().firstOrNull() ?: text)
                if (isValidOption(cleaned)) {
                    candidates.add(cleaned)
                }
            }
        }

        // Also check nodes that are clickable with reasonable length (e.g. saved address titles)
        for ((text, node) in allNodeTexts) {
            if (node.clickable && text.length in 4..40 && isValidOption(text)) {
                val lower = text.lowercase()
                if (ADDRESS_TAGS.any { lower.contains(it) } || lower.contains("road") || lower.contains("nagar") || lower.contains("street") || lower.contains("sector")) {
                    candidates.add(cleanOptionLabel(text))
                }
            }
        }

        return candidates.distinct()
    }

    private fun extractInteractiveCandidates(screen: ScreenState): List<String> {
        return screen.nodes
            .filter { (it.clickable || it.checkable) && !it.text.isNullOrBlank() }
            .map { cleanOptionLabel(it.text.orEmpty()) }
            .filter { isValidOption(it) && it.length in 3..35 }
            .distinct()
    }

    private fun cleanOptionLabel(label: String): String {
        return label
            .replace(Regex("""\(nodeId\s*\d+\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""nodeId\s*=\s*\d+""", RegexOption.IGNORE_CASE), "")
            .trim()
            .take(45)
    }

    private fun isValidOption(label: String): Boolean {
        val clean = label.trim()
        if (clean.length < 2) return false
        if (clean.all { it.isDigit() || it.isWhitespace() || it == ':' || it == '.' }) return false
        if (IGNORED_TEXTS.contains(clean.lowercase())) return false
        if (clean.startsWith("nodeId", ignoreCase = true)) return false
        return true
    }
}
