package com.example.bro.agent

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class ConversationTurn(
    val userPrompt: String,
    val resolvedGoal: String,
    val assistantReply: String,
    val timestamp: Long = System.currentTimeMillis()
)

object UserConversationMemory {
    private const val PREFS_NAME = "bro_user_memory_prefs"
    private const val KEY_FACTS = "user_facts_json"
    private const val KEY_TURNS = "conversation_turns_json"
    private const val KEY_LAST_TASK_GOAL = "last_task_goal"

    private val gson = Gson()

    // Facts memory (e.g. name -> "Srini", city -> "Chennai")
    fun getFacts(context: Context): MutableMap<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_FACTS, null) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, String>>() {}.type
            gson.fromJson(json, type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    fun saveFact(context: Context, key: String, value: String) {
        val facts = getFacts(context)
        facts[key] = value
        val json = gson.toJson(facts)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_FACTS, json).apply()
    }

    // Turns memory (recent conversation history)
    fun getTurns(context: Context): MutableList<ConversationTurn> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_TURNS, null) ?: return mutableListOf()
        val rawTurns = try {
            val type = object : TypeToken<MutableList<ConversationTurn>>() {}.type
            gson.fromJson<MutableList<ConversationTurn>>(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }

        // Filter out corrupted error turns / java explanations from stored history
        return rawTurns.filterTo(mutableListOf()) { turn ->
            val reply = turn.assistantReply.lowercase()
            !reply.contains("resetapp") &&
            !reply.contains("public class") &&
            !reply.contains("system.out") &&
            !reply.contains("message.ondone") &&
            !reply.contains("standalonecoroutine") &&
            !reply.contains("error") &&
            !reply.contains("issue you're")
        }
    }

    fun addTurn(context: Context, userPrompt: String, resolvedGoal: String, reply: String) {
        val rawReply = reply.trim()

        // Ignore raw errors or coroutine cancellation stack traces from being stored as conversation history
        if (rawReply.contains("❌") ||
            rawReply.contains("Groq Error") ||
            rawReply.contains("StandaloneCoroutine") ||
            rawReply.contains("Model error")
        ) {
            return
        }

        val cleanReply = rawReply
            .replace(Regex("```[a-zA-Z]*\\n[\\s\\S]*?```"), "") // Strip out code blocks
            .replace("\n", " ")
            .trim()
            .take(120)

        if (cleanReply.isBlank()) return

        val turn = ConversationTurn(userPrompt, resolvedGoal, cleanReply)
        val turns = getTurns(context)
        turns.add(turn)
        // Keep active lively turns capped to 3 for immediate context
        if (turns.size > 3) {
            turns.removeAt(0)
        }
        val json = gson.toJson(turns)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TURNS, json).apply()

        // Append to long-term persistent archive file on disk
        appendToArchiveFile(context, turn)
    }

    // Active lively chat turns for the prompt (capped to last 2 turns to prevent context pollution)
    fun getActiveChatTurns(context: Context): List<ConversationTurn> {
        val allTurns = getTurns(context)
        return allTurns.takeLast(2)
    }

    // Long-term archive stored in JSON file on disk
    fun appendToArchiveFile(context: Context, turn: ConversationTurn) {
        try {
            val file = java.io.File(context.filesDir, "conversation_history.json")
            val existing = if (file.exists()) {
                val type = object : TypeToken<MutableList<ConversationTurn>>() {}.type
                gson.fromJson<MutableList<ConversationTurn>>(file.readText(), type) ?: mutableListOf()
            } else {
                mutableListOf()
            }
            existing.add(turn)
            if (existing.size > 200) {
                existing.removeAt(0)
            }
            file.writeText(gson.toJson(existing))
        } catch (e: Exception) {
            android.util.Log.w("UserConversationMemory", "Failed to archive conversation turn: ${e.message}")
        }
    }

    fun searchHistoryArchive(context: Context, query: String = ""): List<ConversationTurn> {
        return try {
            val file = java.io.File(context.filesDir, "conversation_history.json")
            if (!file.exists()) return emptyList()
            val type = object : TypeToken<List<ConversationTurn>>() {}.type
            val turns: List<ConversationTurn> = gson.fromJson(file.readText(), type) ?: emptyList()
            if (query.isBlank()) {
                turns.takeLast(5)
            } else {
                val lower = query.lowercase()
                turns.filter { it.userPrompt.contains(lower, ignoreCase = true) || it.assistantReply.contains(lower, ignoreCase = true) }.takeLast(5)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun rememberTaskGoal(context: Context, goal: String) {
        val normalized = goal.trim()
        if (normalized.isBlank() || isContinuationPrompt(normalized)) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_TASK_GOAL, normalized).apply()
    }

    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_TURNS).apply()
    }

    /**
     * Inspects prompt for direct user facts (e.g. "my name is srini", "i live in chennai")
     * Returns true if it was purely a fact registration prompt.
     */
    fun processFactRegistration(context: Context, userPrompt: String): String? {
        val lower = userPrompt.trim().lowercase()

        val nameMatch = Regex("(?:my name is|call me|i am)\\s+([a-zA-Z]+)", RegexOption.IGNORE_CASE).find(lower)
        if (nameMatch != null) {
            val name = nameMatch.groupValues[1].capitalize()
            saveFact(context, "userName", name)
            return "⚡ Got it! Nice to meet you, $name. I'll remember your name."
        }

        return null
    }

    /**
     * Contextual Goal Resolution:
     * Relates shorthand requests like "turn it off", "what about pasu" or "for pasu" to previous goals!
     */
    fun resolveGoalWithContext(context: Context, newPrompt: String): String {
        val storedGoal = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_TASK_GOAL, null)
            ?.takeIf(String::isNotBlank)
        val previousGoal = storedGoal
            ?: getTurns(context).lastOrNull { it.resolvedGoal.isNotBlank() }?.resolvedGoal
        return resolveGoalWithContextText(previousGoal, newPrompt)
    }

    /**
     * Pure testable logic for contextual goal resolution.
     */
    fun resolveGoalWithContextText(previousGoal: String?, newPrompt: String): String {
        val cleanPrompt = newPrompt.trim()
        if (cleanPrompt.isBlank()) return newPrompt
        val lowerNew = cleanPrompt.lowercase()

        if (isContinuationPrompt(cleanPrompt)) {
            return previousGoal ?: cleanPrompt
        }

        if (previousGoal.isNullOrBlank()) return cleanPrompt
        val prevLower = previousGoal.trim().lowercase()

        val settingKeywords = listOf(
            "flashlight", "torch", "bluetooth", "wifi", "wi-fi", "hotspot",
            "do not disturb", "dnd", "airplane mode", "mobile data", "auto-rotate", "autorotate",
            "location", "gps", "nfc"
        )

        val hasOwnSubject = settingKeywords.any { lowerNew.contains(it) } ||
            lowerNew.startsWith("open ") || lowerNew.startsWith("launch ") ||
            lowerNew.contains("message") || lowerNew.contains("whatsapp") ||
            lowerNew.contains("alarm") || lowerNew.contains("timer") ||
            lowerNew.contains("holiday") || lowerNew.contains("weather") ||
            lowerNew.contains("search") || lowerNew.contains("price")

        // If user provided a complete command with its own subject, never overwrite it with old context!
        if (hasOwnSubject && !Regex("""\b(it|that|instead|again|too|also|now)\b""").containsMatchIn(lowerNew)) {
            return cleanPrompt
        }

        // Pattern 1: Follow-up Actions on previous target (e.g. "turn it off", "turn off", "cancel it", "delete it", "disable it")
        val isTurnOff = Regex("""\b(?:turn(?:ing)?\s+(?:it\s+|that\s+)?off|switch(?:ing)?\s+(?:it\s+|that\s+)?off|turn\s*off|switch\s*off|disable(?:\s+(?:it|that))?|cancel(?:\s+(?:it|that))?|delete(?:\s+(?:it|that))?|stop(?:\s+(?:it|that))?|remove(?:\s+(?:it|that))?|shut(?:\s+(?:it\s+|that\s+)?)?down)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(lowerNew)

        val isTurnOn = Regex("""\b(?:turn(?:ing)?\s+(?:it\s+|that\s+)?on|switch(?:ing)?\s+(?:it\s+|that\s+)?on|turn\s*on|switch\s*on|enable(?:\s+(?:it|that))?|start(?:\s+(?:it|that))?|resume(?:\s+(?:it|that))?)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(lowerNew)

        val isChangeTime = Regex("""\b(?:change|reschedule|move|set|make)\s+(?:it\s+|time\s+)?to\s+([0-9a-zA-Z:\s]+)\b""", RegexOption.IGNORE_CASE)
            .find(lowerNew)

        // Alarm domain
        if (prevLower.contains("alarm")) {
            // Extract time details from previous goal (e.g. "at 5 am tomorrow morning", "5 am", "5:00")
            val timeMatch = Regex("""\b(?:at|for)?\s*(\d{1,2}(?::\d{2})?\s*(?:am|pm)?(?:\s+(?:tomorrow|morning|evening|night|today))*)\b""", RegexOption.IGNORE_CASE)
                .find(prevLower.substringAfter("alarm")) ?: Regex("""\b(\d{1,2}(?::\d{2})?\s*(?:am|pm)?(?:\s+(?:tomorrow|morning|evening|night|today))*)\b""", RegexOption.IGNORE_CASE).find(prevLower)
            val timeStr = timeMatch?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }?.let { " at $it" } ?: ""

            if (isTurnOff) {
                val actionVerb = if (lowerNew.contains("delete") || lowerNew.contains("remove")) "delete"
                    else if (lowerNew.contains("cancel")) "cancel"
                    else "turn off"
                return "$actionVerb the alarm$timeStr".trim()
            }
            if (isTurnOn) {
                return "turn on the alarm$timeStr".trim()
            }
            if (isChangeTime != null) {
                val newTargetTime = isChangeTime.groupValues[1].trim()
                return "change the alarm time to $newTargetTime"
            }
        }

        // Timer domain
        if (prevLower.contains("timer")) {
            val durationMatch = Regex("""\b(?:for\s+)?(\d+\s*(?:second|sec|seconds|minute|min|minutes|hour|hours))\b""", RegexOption.IGNORE_CASE)
                .find(prevLower)
            val durationStr = durationMatch?.groupValues?.get(1)?.trim()?.let { " for $it" } ?: ""

            if (isTurnOff) {
                return "cancel the timer$durationStr".trim()
            }
            if (isTurnOn) {
                return "start timer$durationStr".trim()
            }
        }

        // Quick Setting toggles (Flashlight, Torch, Bluetooth, WiFi, Hotspot, DND, etc.)
        val matchedSetting = settingKeywords.firstOrNull { prevLower.contains(it) }
        val newMentionsAnySetting = settingKeywords.any { lowerNew.contains(it) }
        if (matchedSetting != null && !newMentionsAnySetting) {
            val normalizedSetting = if (matchedSetting == "torch") "flashlight" else matchedSetting
            if (isTurnOff) {
                return "turn off $normalizedSetting"
            }
            if (isTurnOn) {
                return "turn on $normalizedSetting"
            }
        }

        // Messaging domain: "send message to Ramesh saying hello" -> "send to Arun instead" or "what about Arun"
        if (prevLower.contains("message") || prevLower.contains("whatsapp") || prevLower.contains("sms") || prevLower.contains("chat")) {
            val insteadMatch = Regex("""(?:send\s+(?:it\s+)?to|to)\s+([a-zA-Z0-9\s]+?)(?:\s+instead)?$""", RegexOption.IGNORE_CASE).find(cleanPrompt)
            if (insteadMatch != null) {
                val newRecipient = insteadMatch.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                // Extract message body if present in previous goal
                val bodyMatch = Regex("""(?:saying|with text)\s+["']?([^"']+)["']?""", RegexOption.IGNORE_CASE).find(previousGoal)
                return if (bodyMatch != null) {
                    "send message to $newRecipient saying ${bodyMatch.groupValues[1].trim()}"
                } else {
                    "send message to $newRecipient"
                }
            }
        }

        // App navigation: "open youtube" -> "close it"
        if (prevLower.startsWith("open ") || prevLower.startsWith("launch ")) {
            val appName = prevLower.removePrefix("open ").removePrefix("launch ").trim()
            if (isTurnOff || lowerNew.contains("close") || lowerNew.contains("exit")) {
                return "close $appName"
            }
        }

        // General pronoun reference: if prompt contains " it " or ends with " it"
        if (Regex("""\b(?:it|that|this|them)\b""", RegexOption.IGNORE_CASE).containsMatchIn(lowerNew)) {
            if (isTurnOff) {
                return "turn off $previousGoal"
            }
            if (isTurnOn) {
                return "turn on $previousGoal"
            }
        }

        // Pattern 2: Target Replacement ("what about pasu", "and for pasu", "what of pasu", "how about pasu")
        val shorthandPrefixes = listOf("what about ", "and for ", "what of ", "how about ", "check for ", "for ")
        for (prefix in shorthandPrefixes) {
            if (lowerNew.startsWith(prefix)) {
                val newSubject = cleanPrompt.substring(prefix.length).trim()
                if (newSubject.isNotBlank()) {
                    val lastSubjectMatch = Regex("(?:for|to|about|of|check|read)\\s+([a-zA-Z0-9\\s]+)$").find(prevLower)
                    if (lastSubjectMatch != null) {
                        val prefixPart = previousGoal.substring(0, lastSubjectMatch.range.first).trim()
                        val relWord = lastSubjectMatch.groupValues[0].substringBefore(" ")
                        return "$prefixPart $relWord $newSubject"
                    } else {
                        return "check recent message for $newSubject"
                    }
                }
            }
        }

        // Pattern 3: App/Medium Specifier ("in sms app", "in whatsapp", "not in whatsapp") - Only for short prompts (<4 words)
        val wordCount = cleanPrompt.split("\\s+".toRegex()).size
        if (wordCount <= 4 && (lowerNew.startsWith("in ") || lowerNew.startsWith("on ") || lowerNew.contains("app") || lowerNew.contains("not in "))) {
            return "$previousGoal ($cleanPrompt)"
        }

        // Pattern 4: Parameter or location/entity clarification ("from Chennai to Bangalore", "to Ramesh", "at 5pm", "between ...")
        if (cleanPrompt.length < 80 && (
            lowerNew.startsWith("from ") || lowerNew.startsWith("to ") || lowerNew.startsWith("between ") ||
            lowerNew.startsWith("at ") || lowerNew.startsWith("with ") || lowerNew.startsWith("route ") ||
            lowerNew.startsWith("towards ")
        )) {
            return "$previousGoal ($cleanPrompt)"
        }

        return cleanPrompt
    }

    private fun isContinuationPrompt(prompt: String): Boolean {
        val normalized = prompt.trim()
            .lowercase()
            .replace(Regex("""[.!?,]+$"""), "")
            .replace(Regex("""\s+"""), " ")
        return normalized in setOf(
            "ok do that", "okay do that", "do that", "try again", "yes",
            "yes please", "ok", "okay", "continue", "go ahead", "proceed", "retry"
        )
    }

    /**
     * Formats memory context string to inject into LLM system prompt.
     */
    fun buildMemoryPromptContext(context: Context): String {
        val sb = StringBuilder()
        val facts = getFacts(context)
        val turns = getTurns(context).filter { turn ->
            val reply = turn.assistantReply.lowercase()
            !reply.contains("resetapp") &&
            !reply.contains("public class") &&
            !reply.contains("system.out") &&
            !reply.contains("error")
        }

        if (facts.isNotEmpty()) {
            sb.append("USER MEMORY & KNOWN FACTS:\n")
            facts.forEach { (k, v) ->
                sb.append("• $k: $v\n")
            }
            sb.append("\n")
        }

        if (turns.isNotEmpty()) {
            sb.append("RECENT CONVERSATION HISTORY (Use to relate user requests):\n")
            turns.takeLast(3).forEachIndexed { i, turn ->
                sb.append("• Turn ${i + 1}: User: \"${turn.userPrompt}\" → Task Goal: \"${turn.resolvedGoal}\" | Outcome: \"${turn.assistantReply}\"\n")
            }
            sb.append("\n")
        }

        return sb.toString()
    }
}
