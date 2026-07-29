package com.example.bro.planner

import android.util.Log
import com.example.bro.ChatResponse
import com.example.bro.agent.AgentMemory
import com.example.bro.model.Action
import com.example.bro.model.ActionType
import com.example.bro.model.Goal
import com.example.bro.model.PlannerResponse
import com.example.bro.accessibility.ScreenReader
import com.example.bro.model.ScreenState
import com.example.bro.network.GroqRepository
import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonReader
import java.io.StringReader

class GroqPlanner(
    private val groqRepository: GroqRepository = GroqRepository(),
    private val screenReader: ScreenReader = ScreenReader()
) {
    private val gson = GsonBuilder().setLenient().create()

    suspend fun plan(
        goal: Goal,
        screen: ScreenState,
        memory: AgentMemory,
        context: android.content.Context? = null
    ): PlannerResponse {
        val systemPrompt = if (context != null) SystemPromptManager.getSystemPrompt(context) else SystemPromptManager.DEFAULT_SYSTEM_PROMPT

        val memoryContext = if (context != null) com.example.bro.agent.UserConversationMemory.buildMemoryPromptContext(context) else ""
        val screenPrompt = buildScreenPrompt(screen, memory)

        val userPrompt = """
${if (memoryContext.isNotBlank()) memoryContext else ""}GOAL: ${goal.request}

$screenPrompt

Respond with ONE JSON action only. No explanation. No extra text.
        """.trimIndent()

        Log.d("GROQ_PLANNER", "Sending prompt to Groq API...")

        val rawResponse = try {
            groqRepository.sendPrompt(systemPrompt, userPrompt)
        } catch (e: Exception) {
            Log.e("GROQ_PLANNER", "Groq error: ${e.message}")
            return PlannerResponse(
                thought = "Error from Groq: ${e.message}",
                action = Action(
                    type = ActionType.CHAT,
                    summary = "❌ Groq Error: ${e.message}"
                )
            )
        }

        Log.d("GROQ_PLANNER", "Raw Response: $rawResponse")

        val chatResponse = parseJson(rawResponse)

        val actionList = chatResponse.actions?.map { mapResponseToAction(it, goal.request) }
        val primaryAction = actionList?.firstOrNull() ?: mapResponseToAction(chatResponse, goal.request)
        val replyText = chatResponse.reply.orEmpty().ifBlank { primaryAction.summary.orEmpty() }

        val parsedActionSummary = if (!actionList.isNullOrEmpty()) {
            "MULTI-ACTION (${actionList.size} steps): " + actionList.joinToString(" -> ") { "${it.type}(${it.targetId ?: it.text ?: ""})" }
        } else {
            "${primaryAction.type} ${primaryAction.targetId ?: primaryAction.packageName ?: primaryAction.target ?: ""} - ${primaryAction.summary ?: ""}".trim()
        }

        com.example.bro.util.ExecutionLogger.log(
            context = context,
            taskGoal = goal.request,
            stepNumber = memory.step + 1,
            appPackage = screen.packageName,
            screenPrompt = screenPrompt,
            userPromptSent = userPrompt,
            rawLlmResponse = rawResponse,
            parsedAction = parsedActionSummary,
            tokenUsage = "~${(userPrompt.length + systemPrompt.length)/4} prompt tokens | ~${rawResponse.length/4} completion tokens"
        )

        return PlannerResponse(
            thought = replyText,
            action = primaryAction,
            actions = actionList
        )
    }

    private fun mapResponseToAction(chatResponse: ChatResponse, goalRequest: String): Action {
        val rawType = (chatResponse.type ?: "CHAT").trim().uppercase()
        val normalizedTypeStr = when (rawType) {
            "SWITCH_APP", "LAUNCH_APP", "START_APP" -> "OPEN_APP"
            "PRESS", "TAP", "SELECT" -> "CLICK"
            "INPUT", "ENTER_TEXT", "WRITE" -> "TYPE"
            "COMPLETE", "DONE" -> "FINISH"
            "READ", "REPLY", "INSPECT", "SUMMARY", "INFO", "CHECK" -> "FINISH"
            "SWIPE" -> "SCROLL"  // Remap SWIPE → SCROLL to prevent physical swipe-down opening notification shade
            else -> rawType
        }

        val actionType = try {
            ActionType.valueOf(normalizedTypeStr)
        } catch (e: Exception) {
            ActionType.CHAT
        }

        val replyText = chatResponse.reply.orEmpty()

        return when (actionType) {
            ActionType.CHAT -> Action(
                type = ActionType.CHAT,
                summary = replyText
            )
            ActionType.OPEN_APP -> Action(
                type = ActionType.OPEN_APP,
                packageName = chatResponse.target ?: extractAppFromGoal(goalRequest),
                summary = replyText.ifBlank { "Opening app..." }
            )
            ActionType.CLICK -> Action(
                type = ActionType.CLICK,
                targetId = chatResponse.targetId,
                target = chatResponse.target,
                summary = replyText
            )
            ActionType.TYPE -> Action(
                type = ActionType.TYPE,
                targetId = chatResponse.targetId,
                text = chatResponse.text,
                summary = replyText
            )
            ActionType.SCROLL -> Action(
                type = ActionType.SCROLL,
                text = chatResponse.direction,
                summary = replyText
            )
            ActionType.WAIT -> Action(
                type = ActionType.WAIT,
                text = chatResponse.milliseconds?.toString(),
                summary = replyText
            )
            ActionType.BACK -> Action(
                type = ActionType.BACK,
                summary = replyText
            )
            ActionType.HOME -> Action(
                type = ActionType.HOME,
                summary = replyText
            )
            ActionType.READ_SCREEN -> Action(
                type = ActionType.READ_SCREEN,
                summary = replyText
            )
            ActionType.FINISH -> Action(
                type = ActionType.FINISH,
                summary = replyText
            )
            ActionType.SWIPE -> Action(
                type = ActionType.SWIPE,
                text = chatResponse.direction,
                summary = replyText
            )
        }
    }

    private fun extractAppFromGoal(goalStr: String): String? {
        val lower = goalStr.lowercase()
        return when {
            "whatsapp" in lower -> "WhatsApp"
            "youtube" in lower -> "YouTube"
            "chrome" in lower -> "Chrome"
            "instagram" in lower -> "Instagram"
            "zomato" in lower -> "Zomato"
            "calculator" in lower -> "Calculator"
            "settings" in lower -> "Settings"
            else -> null
        }
    }

    /**
     * Extracts the FIRST complete JSON object from the response using bracket depth tracking.
     * This prevents the common model failure where multiple JSON objects are output.
     */
    private fun extractFirstJson(text: String): String? {
        var depth = 0
        var start = -1
        for (i in text.indices) {
            when (text[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start != -1) {
                        return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun parseJson(text: String): ChatResponse {
        // Step 1: Extract the FIRST complete JSON object using bracket depth
        val jsonCandidate = extractFirstJson(text)
            ?: text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        Log.d("GROQ_PLANNER", "Parsed JSON candidate: $jsonCandidate")

        return try {
            val reader = JsonReader(StringReader(jsonCandidate))
            reader.isLenient = true
            gson.fromJson(reader, ChatResponse::class.java)
        } catch (e: Exception) {
            Log.e("GROQ_PLANNER", "JSON parse failed: [${e.message}] for input: [$jsonCandidate]")
            // Fallback: pull type and reply via regex
            val typeMatch = Regex("\"type\"\\s*:\\s*\"(\\w+)\"").find(text)
            val replyMatch = Regex("\"reply\"\\s*:\\s*\"([^\"]+)\"").find(text)
            ChatResponse(
                type = typeMatch?.groupValues?.get(1) ?: "CHAT",
                reply = replyMatch?.groupValues?.get(1) ?: text.take(200)
            )
        }
    }

    private fun buildScreenPrompt(
        screen: ScreenState,
        memory: AgentMemory
    ): String {
        val rawPrompt = screenReader.toPrompt(screen)
        val processedPrompt = if (rawPrompt.contains("YOU ARE INSIDE THE CHAT")) {
            rawPrompt.lines().filter { line ->
                !line.contains("[HEADER - DO NOT CLICK]")
            }.joinToString("\n")
        } else {
            rawPrompt
        }

        val hasActiveSearchBox = screen.nodes.any { node ->
            val t = (node.text ?: node.contentDescription ?: "").toString().lowercase()
            node.editable && (t.contains("name, number") || t.contains("search") || t.contains("type a name"))
        }

        return buildString {
            if (hasActiveSearchBox && !processedPrompt.contains("YOU ARE INSIDE THE CHAT")) {
                appendLine("⚠️ SEARCH BAR IS OPEN: Type the target contact/item name into the [CHAT INPUT BOX - TYPE HERE] element now. DO NOT click contacts below until searched!\n")
            }
            append(processedPrompt)
            if (memory.previousActions.isNotEmpty()) {
                appendLine("History:")
                memory.previousActions.takeLast(2).forEach { prev ->
                    append("- ${prev.type}")
                    prev.targetId?.let { id ->
                        // Look up what element text was at that ID for semantic context
                        val node = com.example.bro.accessibility.NodeRepository.get(id)
                        val nodeText = node?.text?.toString()?.take(30)
                            ?: node?.contentDescription?.toString()?.take(30)
                        if (nodeText != null) {
                            append(" \"$nodeText\"")
                        } else {
                            append(" id=$id")
                        }
                    }
                    prev.text?.let { append(" text=\"$it\"") }
                    // Add semantic hint for contact selection
                    if (prev.type == ActionType.CLICK && prev.summary?.contains("chat", ignoreCase = true) == true) {
                        append(" → ENTERED CHAT")
                    }
                    appendLine()
                }
            }
        }
    }
}