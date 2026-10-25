package com.example.bro.planner

enum class TaskIntent {
    READ,      // Read, view, check, show, summary, what was, last message, last order
    SEND,      // Send message, reply, write, type, post
    NAVIGATE   // Open app, navigate, search
}

enum class RequestRoute {
    CHAT,
    ACTION
}

sealed class UserRequestDecision {
    data class Chat(val reply: String) : UserRequestDecision()
    data class Action(val taskGoal: String, val conversationalReply: String? = null) : UserRequestDecision()
    data class WebSearch(val query: String, val conversationalReply: String? = null) : UserRequestDecision()
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

    fun parseRequestRoute(response: String): RequestRoute? {
        val intent = Regex(
            "\"intent\"\\s*:\\s*\"(CHAT|ACTION)\"",
            RegexOption.IGNORE_CASE
        ).find(response)?.groupValues?.get(1) ?: return null
        return runCatching { RequestRoute.valueOf(intent.uppercase()) }.getOrNull()
    }

    fun parseUserRequestDecision(rawResponse: String): UserRequestDecision {
        val trimmed = rawResponse.trim()
        val cleanJson = trimmed
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```").trim()

        if (cleanJson.startsWith("{") && cleanJson.endsWith("}")) {
            val jsonObject = runCatching {
                com.google.gson.JsonParser.parseString(cleanJson).asJsonObject
            }.getOrNull()

            if (jsonObject != null) {
                val toolName = jsonObject.get("tool")?.asString?.trim()
                if (toolName.equals("web_search", ignoreCase = true) ||
                    toolName.equals("search_web", ignoreCase = true) ||
                    toolName.equals("webSearch", ignoreCase = true) ||
                    (jsonObject.has("query") && !jsonObject.has("task"))
                ) {
                    val query = jsonObject.get("query")?.asString?.trim().orEmpty()
                    val reply = jsonObject.get("reply")?.asString?.trim()
                    if (query.isNotBlank()) {
                        return UserRequestDecision.WebSearch(query = query, conversationalReply = reply)
                    }
                }

                if (toolName.equals("execute_phone_task", ignoreCase = true) ||
                    jsonObject.has("task")
                ) {
                    val task = jsonObject.get("task")?.asString?.trim().orEmpty()
                    val reply = jsonObject.get("reply")?.asString?.trim()
                    if (task.isNotBlank()) {
                        return UserRequestDecision.Action(taskGoal = task, conversationalReply = reply)
                    }
                }

                val intent = jsonObject.get("intent")?.asString?.trim()
                if (intent.equals("ACTION", ignoreCase = true)) {
                    val task = jsonObject.get("task")?.asString?.trim()
                    return UserRequestDecision.Action(taskGoal = task ?: trimmed)
                } else if (intent.equals("CHAT", ignoreCase = true)) {
                    val reply = jsonObject.get("reply")?.asString?.trim()
                    return UserRequestDecision.Chat(reply = reply ?: trimmed)
                }
            }
        }

        // Natural conversational plain text response
        return UserRequestDecision.Chat(reply = trimmed)
    }

    /**
     * Checks if the user explicitly commanded a web search or requested online pricing/information.
     */
    fun isExplicitWebSearch(prompt: String): Boolean {
        val lower = prompt.lowercase().trim()
        val explicitWebPhrases = listOf(
            "web search", "websearch", "search the web", "search web",
            "search online", "search on web", "look up on web", "look up online",
            "do websearch", "do web search", "browse web", "browse the web",
            "google it", "google this", "search on google", "google search",
            "search for", "search about"
        )
        val isExplicitAppLaunch = lower.startsWith("open ") || lower.startsWith("launch ") || lower.contains("open app") || lower.contains("in app")
        if (explicitWebPhrases.any { lower.contains(it) } && !isExplicitAppLaunch) return true

        // Product / Price searches on the web (e.g. "search of amazon price", "price on amazon", "check iphone price")
        val hasPriceOrSearch = lower.contains("search of") || lower.contains("search for") || lower.contains("price") || lower.contains("cost")
        val hasShoppingSiteOrItem = lower.contains("amazon") || lower.contains("flipkart") || lower.contains("price")
        if (hasPriceOrSearch && hasShoppingSiteOrItem && !isExplicitAppLaunch) {
            return true
        }

        return false
    }

    /**
     * Determines whether a conversational user query requires live real-time web facts.
     */
    fun requiresWebSearch(prompt: String): Boolean {
        if (isExplicitWebSearch(prompt)) return true
        val lower = prompt.lowercase().trim()
        val searchKeywords = setOf(
            "weather", "temperature", "forecast",
            "score", "cricket", "football", "ipl", "match", "who won",
            "latest news", "today news", "breaking news",
            "price of", "stock", "crypto", "bitcoin", "amazon price", "flipkart price",
            "latest movie", "release date", "who is the current", "current president", "current prime minister",
            "holiday", "holidays", "festival", "festivals", "vacation", "calendar", "election", "who is", "when is"
        )
        if (searchKeywords.any { lower.contains(it) }) return true

        val timeTemporal = setOf("today", "yesterday", "tomorrow", "tonight", "current", "latest", "recent")
        val questionWords = setOf("what", "who", "when", "where", "how much", "which")
        val hasTemporal = timeTemporal.any { lower.contains(it) }
        val isQuestion = questionWords.any { lower.startsWith(it) || lower.contains(" $it ") } || lower.endsWith("?")

        return hasTemporal && isQuestion && !lower.contains("alarm") && !lower.contains("message") && !lower.contains("email")
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
