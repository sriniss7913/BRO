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
        return try {
            val type = object : TypeToken<MutableList<ConversationTurn>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun addTurn(context: Context, userPrompt: String, resolvedGoal: String, reply: String) {
        val turns = getTurns(context)
        turns.add(ConversationTurn(userPrompt, resolvedGoal, reply))
        // Keep last 10 turns for token budget
        if (turns.size > 10) {
            turns.removeAt(0)
        }
        val json = gson.toJson(turns)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TURNS, json).apply()
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
     * Relates shorthand requests like "what about pasu" or "for pasu" to previous goals!
     */
    fun resolveGoalWithContext(context: Context, newPrompt: String): String {
        val lowerNew = newPrompt.trim().lowercase()
        val turns = getTurns(context)
        if (turns.isEmpty()) return newPrompt

        val lastTurn = turns.last()
        val lastGoalLower = lastTurn.resolvedGoal.lowercase()

        // Pattern 1: Target Replacement ("what about pasu", "and for pasu", "what of pasu", "how about pasu")
        val shorthandPrefixes = listOf("what about ", "and for ", "what of ", "how about ", "check for ", "for ")
        for (prefix in shorthandPrefixes) {
            if (lowerNew.startsWith(prefix)) {
                val newSubject = newPrompt.substring(prefix.length).trim()
                if (newSubject.isNotBlank()) {
                    // Extract verb / action from last goal (e.g. "check recent message for ramesh" -> "check recent message for ")
                    val lastSubjectMatch = Regex("(?:for|to|about|of|check|read)\\s+([a-zA-Z0-9\\s]+)$").find(lastGoalLower)
                    if (lastSubjectMatch != null) {
                        val prefixPart = lastTurn.resolvedGoal.substring(0, lastSubjectMatch.range.first).trim()
                        val relWord = lastSubjectMatch.groupValues[0].substringBefore(" ")
                        return "$prefixPart $relWord $newSubject"
                    } else {
                        return "check recent message for $newSubject"
                    }
                }
            }
        }

        // Pattern 2: App/Medium Specifier ("in sms app", "in whatsapp", "not in whatsapp")
        if (lowerNew.startsWith("in ") || lowerNew.contains("app") || lowerNew.contains("not in ")) {
            return "${lastTurn.resolvedGoal} ($newPrompt)"
        }

        return newPrompt
    }

    /**
     * Formats memory context string to inject into LLM system prompt.
     */
    fun buildMemoryPromptContext(context: Context): String {
        val sb = StringBuilder()
        val facts = getFacts(context)
        val turns = getTurns(context)

        if (facts.isNotEmpty()) {
            sb.append("USER MEMORY & KNOWN FACTS:\n")
            facts.forEach { (k, v) ->
                sb.append("• $k: $v\n")
            }
            sb.append("\n")
        }

        if (turns.isNotEmpty()) {
            sb.append("RECENT CONVERSATION HISTORY (Use to relate user requests):\n")
            turns.takeLast(4).forEachIndexed { i, turn ->
                sb.append("• Turn ${i + 1}: User: \"${turn.userPrompt}\" → Task Goal: \"${turn.resolvedGoal}\" | Outcome: \"${turn.assistantReply}\"\n")
            }
            sb.append("\n")
        }

        return sb.toString()
    }
}
