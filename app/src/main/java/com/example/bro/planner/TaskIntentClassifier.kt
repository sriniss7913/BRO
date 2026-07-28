package com.example.bro.planner

enum class TaskIntent {
    READ,      // Read, view, check, show, summary, what was, last message, last order
    SEND,      // Send message, reply, write, type, post
    NAVIGATE   // Open app, navigate, search
}

object TaskIntentClassifier {
    private val STOP_WORDS = setOf(
        "what", "was", "is", "my", "last", "recent", "message", "messages", "sms",
        "check", "from", "the", "send", "to", "reply", "order", "show", "tell",
        "zomato", "whatsapp", "app", "not", "in", "didn't", "ask", "know", "him", "her"
    )

    fun classify(goalStr: String): TaskIntent {
        val lower = goalStr.lowercase().trim()

        val isRead = lower.contains("check") ||
            lower.contains("read") ||
            lower.contains("what was") ||
            lower.contains("what is") ||
            lower.contains("what about") ||
            lower.contains("show me") ||
            lower.contains("show") ||
            lower.contains("recent") ||
            lower.contains("last message") ||
            lower.contains("last order") ||
            lower.contains("last sms") ||
            lower.contains("history") ||
            lower.contains("status") ||
            lower.startsWith("view")

        val isSend = (lower.contains("send") ||
            lower.contains("reply") ||
            lower.contains("type") ||
            lower.contains("tell") ||
            lower.contains("post") ||
            lower.contains("write")) && !lower.contains("didn't ask") && !lower.contains("don't send")

        return when {
            isSend -> TaskIntent.SEND
            isRead -> TaskIntent.READ
            else -> TaskIntent.NAVIGATE
        }
    }

    /**
     * Extracts subject keywords (e.g. contact names "Soundarya", "Ramesh", "Goutham") from goal.
     */
    fun extractTargetKeywords(goalStr: String): List<String> {
        return goalStr.split(Regex("[^a-zA-Z0-9]+"))
            .filter { it.length >= 3 }
            .filter { word -> word.lowercase() !in STOP_WORDS }
    }
}
