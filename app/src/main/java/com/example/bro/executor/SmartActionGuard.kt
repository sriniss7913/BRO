package com.example.bro.executor

import com.example.bro.accessibility.NodeRepository
import com.example.bro.accessibility.UiCanonicalizer
import com.example.bro.model.Action
import com.example.bro.model.ActionType
import com.example.bro.model.Goal
import com.example.bro.planner.TaskIntentClassifier
import kotlin.math.min

data class VisibleContact(
    val nodeId: Int,
    val name: String,
    val role: String
)

object SmartActionGuard {

    private val COMMON_GREETINGS_AND_MESSAGES = setOf(
        "hi", "hello", "hey", "good morning", "good evening", "good night",
        "thanks", "thank you", "ok", "okay", "yes", "no", "bye", "hru", "how are you"
    )

    private val ACTION_STOP_WORDS = setOf(
        "send", "to", "message", "text", "tell", "whatsapp", "chat", "with",
        "open", "call", "sms", "on", "in", "app", "please", "bro", "write",
        "a", "an", "the", "my", "him", "her", "that", "saying", "say",
        "i", "me", "my", "we", "us", "you", "he", "she", "it", "they", "them",
        "will", "would", "shall", "should", "can", "could", "is", "are", "am", "was", "were", "be", "been"
    )

    fun isCommunicationPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val lower = pkg.lowercase()
        return lower.contains("whatsapp") ||
            lower.contains("messaging") ||
            lower.contains("mms") ||
            lower.contains("telegram") ||
            lower.contains("dialer") ||
            lower.contains("orca") ||
            lower.contains("signal") ||
            lower.contains("viber") ||
            lower.contains("skype") ||
            lower.contains("line") ||
            lower.contains("contacts") ||
            lower.contains("telecom") ||
            lower.contains("incallui")
    }

    fun isNodeChatCandidate(targetId: Int, canonicalOutput: String): Boolean {
        // Extract strictly the block belonging to targetId
        val blockRegex = Regex("""(?m)^\[$targetId\]\s+[^\n]*(?:\n(?!\s*\[\d+\])[^\n]*)*""")
        val block = blockRegex.find(canonicalOutput)?.value ?: return false
        val lower = block.lowercase()
        return lower.contains("contact=") ||
            lower.contains("conversations_row") ||
            lower.contains("conversation_contact") ||
            lower.contains("contact_photo")
    }

    /**
     * Extracts target entity/person names from a goal string.
     * E.g. "send hi to Ramesh on WhatsApp" -> "Ramesh"
     *      "message Ramesh Babu" -> "Ramesh Babu"
     *      "text Mom I will be late" -> "Mom"
     */
    fun extractTargetEntity(goalStr: String): String? {
        val cleanGoal = goalStr.trim()

        // Exclude travel / routing patterns e.g. "from Chennai to Madurai"
        if (Regex("""\bfrom\s+.+\s+to\s+""", RegexOption.IGNORE_CASE).containsMatchIn(cleanGoal)) {
            return null
        }

        // 1. Anchor on "to <name>" e.g. "send hi to Ramesh", "say hello to Mom"
        val toMatch = Regex("""\bto\s+([A-Za-z0-9_]+(?:\s+[A-Za-z0-9_]+)?)""", RegexOption.IGNORE_CASE).find(cleanGoal)
        if (toMatch != null) {
            val words = toMatch.groupValues[1].trim().split(Regex("\\s+"))
            val nameWords = words.takeWhile { it.lowercase() !in ACTION_STOP_WORDS && it.lowercase() !in COMMON_GREETINGS_AND_MESSAGES }
            if (nameWords.isNotEmpty()) {
                return nameWords.joinToString(" ")
            }
        }

        // 2. Direct action verbs: "message <name>", "text <name>", "whatsapp <name>", "call <name>", "chat with <name>"
        val directMatch = Regex("""\b(?:message|text|whatsapp|call|chat\s+with)\s+([A-Za-z0-9_]+(?:\s+[A-Za-z0-9_]+)?)""", RegexOption.IGNORE_CASE).find(cleanGoal)
        if (directMatch != null) {
            val words = directMatch.groupValues[1].trim().split(Regex("\\s+"))
            val nameWords = words.takeWhile { it.lowercase() !in ACTION_STOP_WORDS && it.lowercase() !in COMMON_GREETINGS_AND_MESSAGES }
            if (nameWords.isNotEmpty()) {
                return nameWords.joinToString(" ")
            }
        }

        // 3. Fallback to keyword extraction
        val keywords = TaskIntentClassifier.extractTargetKeywords(cleanGoal)
            .filter { it.lowercase() !in COMMON_GREETINGS_AND_MESSAGES && it.lowercase() !in ACTION_STOP_WORDS }

        return keywords.firstOrNull()
    }

    /**
     * Parses all visible contact / conversation items from the canonical screen representation.
     */
    fun parseVisibleContacts(canonicalOutput: String): List<VisibleContact> {
        val results = mutableListOf<VisibleContact>()
        val blocks = canonicalOutput.split(Regex("\\n\\s*\\n"))

        for (block in blocks) {
            val headerMatch = Regex("""^\[(\d+)]\s+(\w+)""").find(block.trimStart()) ?: continue
            val id = headerMatch.groupValues[1].toIntOrNull() ?: continue
            val role = headerMatch.groupValues[2]

            val contactMatch = Regex("""contact="([^"]+)"""").find(block)
            val labelMatch = Regex("""label="([^"]+)"""").find(block)
            val textMatch = Regex("""text="([^"]+)"""").find(block)

            val name = (contactMatch?.groupValues?.get(1)
                ?: labelMatch?.groupValues?.get(1)
                ?: textMatch?.groupValues?.get(1))?.trim().orEmpty()

            if (name.isNotBlank()) {
                results.add(VisibleContact(id, name, role))
            }
        }
        return results
    }

    /**
     * Tier 1: Instant deterministic check (0 ms).
     * Handles:
     * - Substrings: "Ramesh" in "Rameshbabu", "Dr. Ramesh"
     * - Token overlap: "Ramesh Kumar" vs "Ramesh"
     * - Minor typos: Levenshtein distance <= 1
     */
    fun tier1Match(target: String, candidate: String): Boolean {
        val t = target.lowercase().trim()
        val c = candidate.lowercase().trim()

        if (t.isEmpty() || c.isEmpty()) return false
        if (t == c) return true

        // Substring / compound word containment
        if (c.contains(t) || t.contains(c)) return true

        // Token intersection (e.g. "Ramesh Kumar" contains token "Ramesh")
        val tTokens = t.split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }
        val cTokens = c.split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }
        if (tTokens.any { it in cTokens } || cTokens.any { it in tTokens }) {
            return true
        }

        // Levenshtein distance <= 1 for single-word typos (e.g. "Rameesh" vs "Ramesh")
        if (t.length >= 4 && c.length >= 4 && kotlin.math.abs(t.length - c.length) <= 1) {
            if (levenshtein(t, c) <= 1) return true
        }

        return false
    }

    /**
     * Two-Tier Smart Validator for actions:
     * - Tier 1: Fast Heuristic (0 ms). Passes matches instantly; rejects blatant mismatches if right target is visible.
     * - Tier 2: Micro-LLM Critic (invoked only if Tier 1 is uncertain).
     */
    suspend fun validateTargetEntity(
        action: Action,
        goal: Goal,
        canonicalOutput: String,
        currentPackage: String? = null,
        semanticCritic: (suspend (goal: String, candidate: String) -> Boolean)? = null
    ): String? {
        if (action.type != ActionType.CLICK || action.targetId == null) return null

        // Detect package: from parameter or canonicalOutput header
        val pkg = currentPackage ?: Regex("""SCREEN:\s*([^\s\n]+)""").find(canonicalOutput)?.groupValues?.get(1)
        if (pkg != null && !isCommunicationPackage(pkg)) {
            // Never apply contact entity validation to non-communication apps (e.g. RedBus, Zomato, Swiggy, etc.)
            return null
        }

        val targetId = action.targetId
        val role = NodeRepository.getRole(targetId)

        // Only validate contact / chat selection actions
        val isChatCandidate = role == UiCanonicalizer.Role.CHAT_ROW ||
            (role == UiCanonicalizer.Role.CLICKABLE && isNodeChatCandidate(targetId, canonicalOutput)) ||
            (role == UiCanonicalizer.Role.SEARCH_RESULT && pkg != null && isCommunicationPackage(pkg))

        if (!isChatCandidate) return null

        val targetEntity = extractTargetEntity(goal.request) ?: return null

        val visibleContacts = parseVisibleContacts(canonicalOutput)
        val candidateContact = visibleContacts.find { it.nodeId == targetId }
            ?: run {
                val node = NodeRepository.get(targetId)
                val text = listOfNotNull(node?.text?.toString(), node?.contentDescription?.toString())
                    .firstOrNull(String::isNotBlank)?.trim().orEmpty()
                if (text.isNotBlank()) VisibleContact(targetId, text, role?.name ?: "CLICKABLE") else null
            } ?: return null

        val candidateName = candidateContact.name.trim()
        if (candidateName.length < 2) return null

        // Exclude system controls (like search, settings, menu)
        val lowerCandidate = candidateName.lowercase()
        if (lowerCandidate in setOf("search", "ask meta ai or search", "new chat", "more options", "camera")) {
            return null
        }

        // --- TIER 1: FAST HEURISTIC ---
        if (tier1Match(targetEntity, candidateName)) {
            // Direct / compound / token match -> Instant PASS (0 ms)
            return null
        }

        // Check if there is another visible node that DOES match the requested target
        val matchingVisibleContact = visibleContacts.firstOrNull {
            it.nodeId != targetId && tier1Match(targetEntity, it.name)
        }

        if (matchingVisibleContact != null) {
            // Blatant error: Clicked "Arun" when "Ramesh" is right there on screen!
            return "ACTION REJECTED: You selected contact '$candidateName' (node [$targetId]), but the goal requested '$targetEntity'. Click node [${matchingVisibleContact.nodeId}] ('${matchingVisibleContact.name}') instead!"
        }

        // --- TIER 2: MICRO-LLM CRITIC (When Tier 1 is uncertain) ---
        if (semanticCritic != null) {
            val isPlausible = semanticCritic.invoke(goal.request, candidateName)
            if (isPlausible) {
                // Tier 2 confirmed plausible relation (e.g. Mom -> Amma) -> PASS
                return null
            }
        }

        // Screen has search element?
        val searchNodeId = Regex("""\[(\d+)]\s+SEARCH""").find(canonicalOutput)?.groupValues?.get(1)
        return if (searchNodeId != null) {
            "ACTION REJECTED: Contact '$candidateName' (node [$targetId]) is not a match for '$targetEntity'. Click SEARCH (node [$searchNodeId]) to find '$targetEntity' instead of selecting an incorrect contact!"
        } else {
            "ACTION REJECTED: Selected contact '$candidateName' (node [$targetId]) does not match requested contact '$targetEntity'."
        }
    }

    private fun levenshtein(s: String, t: String): Int {
        val m = s.length
        val n = t.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s[i - 1] == t[j - 1]) 0 else 1
                dp[i][j] = min(
                    dp[i - 1][j] + 1,
                    min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
                )
            }
        }
        return dp[m][n]
    }
}
