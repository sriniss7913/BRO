package com.example.bro.planner
import com.example.bro.model.ScreenState
import android.util.Log
import com.example.bro.ChatResponse
import com.example.bro.agent.AgentMemory
import com.example.bro.model.Action
import com.example.bro.model.ActionType
import com.example.bro.model.ExecutionIncident
import com.example.bro.model.Goal
import com.example.bro.model.PlannerResponse
import com.example.bro.accessibility.ScreenReader
import com.example.bro.network.GroqRepository
import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonReader
import java.io.StringReader
import kotlinx.coroutines.CancellationException
import com.example.bro.AppLauncher

internal fun sanitizeJsonArtifacts(json: String): String =
    Regex("""("(?:nodeId|targetId|milliseconds)"\s*:\s*-?\d+)"(?=\s*[,}])""")
        .replace(json, "$1")

internal fun compactPlannerScreen(screenText: String, maxChars: Int = 4600): String {
    val blocks = screenText.split(Regex("\\n\\s*\\n"))
    val headers = blocks.takeWhile { !it.trimStart().startsWith("[") }
    val nodes = blocks.drop(headers.size)
        .filter { it.trimStart().startsWith("[") }
        .map { block ->
            Regex("""(?m)^(\s+\w+=")(.*)(")$""")
                .replace(block) { match ->
                    match.groupValues[1] + match.groupValues[2].take(160) +
                        (if (match.groupValues[2].length > 160) "…" else "") +
                        match.groupValues[3]
                }
        }
    fun priority(block: String): Int {
        val role = Regex("""^\[(\d+)]\s+(\w+)""").find(block)?.groupValues?.get(2)
        return when (role) {
            "SWITCH", "INPUT", "MESSAGE_INPUT", "SEND", "SEARCH", "NEW_CHAT" -> 0
            "CLICKABLE", "CHAT_ROW", "SEARCH_RESULT", "FILTER", "BACK" -> 1
            "TEXT" -> 2
            else -> 1
        }
    }

    val header = headers.joinToString("\n\n").trim()
    val selected = mutableListOf<String>()
    var usedChars = header.length
    for (block in nodes.sortedWith(compareBy(::priority).thenBy {
            Regex("""^\[(\d+)]""").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
        })
    ) {
        val required = block.length + if (selected.isEmpty()) 2 else 2
        if (usedChars + required <= maxChars) {
            selected.add(block)
            usedChars += required
        }
    }
    return (listOf(header).filter(String::isNotBlank) + selected.sortedBy {
        Regex("""^\[(\d+)]""").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
    }).joinToString("\n\n")
}

class GroqPlanner(
    private val groqRepository: GroqRepository = GroqRepository(),
    private val screenReader: ScreenReader = ScreenReader()
) {
    private val gson = GsonBuilder().setLenient().create()

    suspend fun evaluateUserRequest(
        prompt: String,
        context: android.content.Context,
        visionMode: Boolean
    ): UserRequestDecision {
        if (com.example.bro.search.WebSearchEngine.enabled &&
            TaskIntentClassifier.isExplicitWebSearch(prompt)
        ) {
            Log.i("BRO_ROUTER", "Explicit web search detected for prompt: $prompt")
            return UserRequestDecision.WebSearch(query = prompt)
        }

        val recentTurns = com.example.bro.agent.UserConversationMemory.getActiveChatTurns(context)
        val userPrompt = buildString {
            if (recentTurns.isNotEmpty()) {
                appendLine("Recent conversation, for context only:")
                recentTurns.forEach { turn ->
                    appendLine("User: ${turn.userPrompt.take(300)}")
                    appendLine("BRO: ${turn.assistantReply.take(300)}")
                }
            }
            appendLine("Current user message:")
            append(prompt)
        }
        val useOnDevice = visionMode ||
            (com.example.bro.agent.OnDeviceLlmManager.isModelLoaded &&
             (com.example.bro.network.GroqRepository.providerMode == "ON_DEVICE" || !com.example.bro.network.GroqRepository.isCloudConfigured()))
        val rawResponse = if (useOnDevice && com.example.bro.agent.OnDeviceLlmManager.isModelLoaded) {
            val manager = com.example.bro.agent.OnDeviceLlmManager
            manager.generateResponse(SystemPromptManager.UNIFIED_ROUTER_SYSTEM_PROMPT, userPrompt)
        } else {
            groqRepository.sendPrompt(SystemPromptManager.UNIFIED_ROUTER_SYSTEM_PROMPT, userPrompt)
        }
        val decision = TaskIntentClassifier.parseUserRequestDecision(rawResponse)
        Log.i("BRO_ROUTER", "Evaluated request -> $decision (raw: ${rawResponse.take(1000)})")
        return decision
    }

    suspend fun classifyRequestRoute(
        prompt: String,
        context: android.content.Context,
        visionMode: Boolean
    ): RequestRoute {
        val recentTurns = com.example.bro.agent.UserConversationMemory.getActiveChatTurns(context)
        val userPrompt = buildString {
            if (recentTurns.isNotEmpty()) {
                appendLine("Recent conversation, for context only:")
                recentTurns.forEach { turn ->
                    appendLine("User: ${turn.userPrompt.take(300)}")
                    appendLine("BRO: ${turn.assistantReply.take(300)}")
                }
            }
            appendLine("Classify this current user request:")
            append(prompt)
        }
        val useOnDevice = visionMode ||
            (com.example.bro.agent.OnDeviceLlmManager.isModelLoaded &&
             (com.example.bro.network.GroqRepository.providerMode == "ON_DEVICE" || !com.example.bro.network.GroqRepository.isCloudConfigured()))
        val rawResponse = if (useOnDevice && com.example.bro.agent.OnDeviceLlmManager.isModelLoaded) {
            val manager = com.example.bro.agent.OnDeviceLlmManager
            manager.generateResponse(SystemPromptManager.REQUEST_INTENT_SYSTEM_PROMPT, userPrompt)
        } else {
            groqRepository.sendPrompt(SystemPromptManager.REQUEST_INTENT_SYSTEM_PROMPT, userPrompt)
        }
        val route = TaskIntentClassifier.parseRequestRoute(rawResponse)
            ?: throw IllegalStateException("The model could not classify this request. Response: ${rawResponse.take(200)}")
        Log.i("BRO_INTENT", "Model classified request as $route")
        return route
    }

    suspend fun respondConversationally(
        prompt: String,
        context: android.content.Context,
        visionMode: Boolean
    ): String {
        val systemPrompt = SystemPromptManager.DEFAULT_CHAT_SYSTEM_PROMPT
        val recentTurns = com.example.bro.agent.UserConversationMemory.getActiveChatTurns(context)
        val conversationalPrompt = buildString {
            if (recentTurns.isNotEmpty()) {
                appendLine("Recent conversation, for context only:")
                recentTurns.forEach { turn ->
                    appendLine("User: ${turn.userPrompt.take(300)}")
                    appendLine("BRO: ${turn.assistantReply.take(300)}")
                }
            }
            appendLine("Current user message:")
            append(prompt)
        }
        val reply = if (visionMode) {
            val manager = com.example.bro.agent.OnDeviceLlmManager
            if (!manager.isModelLoaded || !manager.loadedModelName.endsWith(".litertlm", ignoreCase = true)) {
                throw IllegalStateException("Vision mode needs a loaded on-device LiteRT-LM model to answer chat questions.")
            }
            manager.generateResponse(systemPrompt, conversationalPrompt)
        } else {
            groqRepository.sendPrompt(systemPrompt, conversationalPrompt)
        }
        return reply.trim().ifBlank { throw IllegalStateException("The selected model returned an empty reply.") }
    }

    suspend fun summarizeScreenOutcome(
        goal: String,
        screenTexts: List<String>,
        appName: String
    ): String {
        if (screenTexts.isEmpty()) return "Task completed. (No readable content found on screen)"
        val systemPrompt = """
            You are B.R.O., an intelligent personal assistant on Android.
            The user gave a task to check, inspect, or retrieve information from their phone.
            Analyze the visible screen text collected from the target app ($appName) and provide a concise, friendly, and structured summary directly answering the user's goal.
            Format with clear bullet points or short paragraphs. Avoid mentioning internal UI details, node numbers, or system buttons. Speak directly to the user.
        """.trimIndent()

        val userPrompt = """
            User Goal: $goal
            Active App: $appName
            
            Visible Screen Content:
            ${screenTexts.take(40).joinToString("\n") { "• $it" }}
            
            Provide a clean, informative summary answering the User Goal:
        """.trimIndent()

        return try {
            val response = groqRepository.sendPrompt(systemPrompt, userPrompt)
            response.trim().ifBlank { "Task completed." }
        } catch (e: Exception) {
            Log.w("GROQ_PLANNER", "Failed to summarize screen outcome: ${e.message}")
            screenTexts.take(5).joinToString(" · ")
        }
    }

    suspend fun plan(
        goal: Goal,
        screen: ScreenState,
        memory: AgentMemory,
        context: android.content.Context? = null,
        recoveryFeedback: String? = null,
        userGuidance: String? = null,
        recentStepHistory: List<String> = emptyList(),
        blacklistedActions: List<String> = emptyList(),
        activeIncident: ExecutionIncident? = null
    ): PlannerResponse {
        val systemPrompt = if (context != null) {
            SystemPromptManager.getSystemPrompt(context)
        } else {
            SystemPromptManager.DEFAULT_SYSTEM_PROMPT
        }
        val screenPrompt = buildScreenPrompt(screen, memory)

        val guidanceHeader = if (!userGuidance.isNullOrBlank()) {
            """
            ================================================================================
            CRITICAL USER DIRECTIVE & DYNAMIC PLAN UPDATE (HIGHEST PRIORITY):
            $userGuidance
            You MUST execute this exact decision / instruction on the current screen.
            Do NOT repeat the previous action, do NOT ask the user again, and do NOT ignore this.
            After resolving this step, continue automatically with the remaining plan towards the goal.
            ================================================================================
            """.trimIndent() + "\n\n"
        } else ""

        val incidentHeader = if (activeIncident != null) {
            """
            ================================================================================
            ${activeIncident.formatForPrompt()}
            ================================================================================
            """.trimIndent() + "\n\n"
        } else ""

        val contextHistory = if (context != null) {
            com.example.bro.agent.UserConversationMemory.buildMemoryPromptContext(context).takeIf { it.isNotBlank() }?.let {
                "$it\n"
            }.orEmpty()
        } else ""

        val isBroHomeScreen = context != null && screen.packageName == context.packageName
        val initialAppGuidance = if (isBroHomeScreen) {
            val mentionedApps = com.example.bro.AppLauncher.getLaunchableAppsMentionedIn(goal.request)
            val appHints = if (mentionedApps.isNotEmpty()) {
                "Target apps detected for this goal: ${mentionedApps.joinToString(", ")}"
            } else {
                "No specific installed app matched directly from goal text."
            }
            """
            NOTE: You are currently on the BRO Assistant Home Screen.
            $appHints
            Your very first action MUST be to open the target application required by the Goal (e.g. {"type":"OPEN_APP","app":"<TargetApp>"} or {"type":"FIND_APP","query":"<keyword>"}).
            Do NOT attempt to CLICK or TYPE on the BRO Home Screen. Do NOT output OPEN_APP for BRO itself.
            """.trimIndent() + "\n\n"
        } else ""

        val dynamicPlanningGuidance = """
        PLANNING & STATE-AWARENESS DIRECTIVE:
        1. Look at RECENT STEP HISTORY and the current screen: What stage of the task have you reached?
        2. Identify your IMMEDIATE SUBGOAL right now (e.g. "type message", "pick restaurant", "choose dish", "confirm order") and do NOT regress to an earlier stage (e.g. do NOT search again if results are already visible; do NOT re-select contact if chat input is visible).
        3. If there are multiple candidate options to choose from (e.g. several restaurants or dishes), use ASK_USER to prompt the user with clear choices grounded in the screen.
        4. TABS & SUB-VIEWS NAVIGATION: If the goal or user hint specifies navigating to a particular tab (e.g. 'Chats tab', 'Messages tab', 'Updates', 'Calls') but you are inside a search sub-view or channels list where main tabs are hidden, emit {"type":"BACK"} to exit the sub-view and reveal the main tabs.
        5. WEB SEARCH TOOL: If you need external facts, live prices, or navigation guidance, you can emit {"type":"WEB_SEARCH","query":"<search terms>"}.
        """.trimIndent() + "\n\n"

        val userPrompt = """
        ${guidanceHeader}${incidentHeader}${contextHistory}${initialAppGuidance}${dynamicPlanningGuidance}Goal: ${goal.request}
        Current app: ${screen.packageName}
        ${if (recentStepHistory.isNotEmpty()) "RECENT STEP HISTORY:\n${recentStepHistory.takeLast(5).joinToString("\n")}\n" else ""}
        Current screen (read-only; IDs apply only here):
        $screenPrompt
        ${if (blacklistedActions.isNotEmpty()) "BLACKLISTED ACTIONS ON THIS SCREEN: ${blacklistedActions.joinToString(", ")}. Pick a different node or action; do not repeat a blacklisted action." else ""}
        ${recoveryFeedback?.let { "\nPrevious attempt feedback: $it Choose a different valid action based on this screen." }.orEmpty()}
        """.trimIndent()

        Log.d("GROQ_PLANNER", "Sending planner prompt to selected provider...")

        val rawResponse = try {
            groqRepository.sendPrompt(systemPrompt, userPrompt)
        } catch (e: CancellationException) {
            throw e
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

        val parsedActions = chatResponse.actions?.map { mapResponseToAction(it, goal.request) }
        val actionList = parsedActions
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
            tokenUsage = "~${(userPrompt.length + systemPrompt.length)/4} text prompt tokens | ~${rawResponse.length/4} completion tokens"
        )

        return PlannerResponse(
            thought = replyText,
            action = primaryAction,
            actions = actionList,
            choices = (chatResponse.actions?.firstOrNull() ?: chatResponse).options.orEmpty()
                .map(String::trim).filter(String::isNotBlank).distinct().take(4)
        )
    }

    suspend fun planVision(
        goal: Goal,
        screenshot: ByteArray,
        memory: AgentMemory,
        context: android.content.Context,
        recoveryFeedback: String? = null,
        userGuidance: String? = null,
        activeIncident: ExecutionIncident? = null
    ): PlannerResponse {
        val manager = com.example.bro.agent.OnDeviceLlmManager
        if (!manager.isModelLoaded || !manager.loadedModelName.endsWith(".litertlm", ignoreCase = true)) {
            throw IllegalStateException("Vision mode requires a loaded .litertlm model. Screenshots are not sent to remote providers.")
        }

        val systemPrompt = SystemPromptManager.getVisionSystemPrompt(context)
        val userPrompt = buildString {
            if (!userGuidance.isNullOrBlank()) {
                appendLine("================================================================================")
                appendLine("CRITICAL USER DIRECTIVE & DYNAMIC PLAN UPDATE (HIGHEST PRIORITY):")
                appendLine(userGuidance)
                appendLine("You MUST execute this exact decision / instruction on the current screen.")
                appendLine("After resolving this step, continue automatically with the remaining plan towards the goal.")
                appendLine("================================================================================")
                appendLine()
            }
            if (activeIncident != null) {
                appendLine("================================================================================")
                appendLine(activeIncident.formatForPrompt())
                appendLine("================================================================================")
                appendLine()
            }
            append("Goal: ${goal.request}\n")
            append("Current screenshot is attached.")
            if (!recoveryFeedback.isNullOrBlank()) {
                append("\nPrevious attempt feedback: $recoveryFeedback Choose a different valid action based on this screenshot.")
            }
        }
        Log.d("BRO_VISION", "Sending screenshot-only prompt to on-device LiteRT-LM.")

        val rawResponse = manager.generateResponse(systemPrompt, userPrompt, screenshot)
        Log.d("BRO_VISION", "Raw vision response: $rawResponse")

        val response = parseJson(rawResponse)
        val firstResponse = response.actions?.firstOrNull() ?: response
        val action = mapResponseToAction(firstResponse, goal.request)
        val reply = firstResponse.reply ?: response.reply
        val screenPrompt = "[Screenshot attached; accessibility tree not included]"
        val promptSent = "$userPrompt\n[Screenshot image attached]"
        val debugImagePath = com.example.bro.util.ExecutionLogger.saveVisionDebugImage(
            context = context,
            imageBytes = screenshot,
            stepNumber = memory.step + 1,
            action = action.type.name,
            x = action.x,
            y = action.y
        )

        com.example.bro.util.ExecutionLogger.log(
            context = context,
            taskGoal = goal.request,
            stepNumber = memory.step + 1,
            appPackage = "vision",
            screenPrompt = screenPrompt,
            userPromptSent = promptSent,
            rawLlmResponse = rawResponse,
            parsedAction = "${action.type} ${action.x ?: ""},${action.y ?: ""} ${action.summary.orEmpty()}".trim(),
            tokenUsage = "~${(userPrompt.length + systemPrompt.length) / 4} text prompt tokens + screenshot image | ~${rawResponse.length / 4} completion tokens",
            visionDebugImagePath = debugImagePath
        )

        return PlannerResponse(
            thought = reply ?: response.thought.orEmpty(),
            action = action,
            actions = listOf(action),
            choices = firstResponse.options.orEmpty()
                .map(String::trim).filter(String::isNotBlank).distinct().take(4)
        )
    }

    internal fun mapResponseToAction(chatResponse: ChatResponse, goalRequest: String): Action {
        val rawType = (chatResponse.type ?: "CHAT").trim().uppercase()
        val normalizedTypeStr = when (rawType) {
            "SWITCH_APP", "LAUNCH_APP", "START_APP" -> "OPEN_APP"
            "PRESS", "TAP", "SELECT" -> "CLICK"
            "INPUT", "ENTER_TEXT", "WRITE", "TYPE_MESSAGE" -> "TYPE"
            "SEND" -> "SEND_MESSAGE"
            "LOCK" -> "LOCK_SCREEN"
            "COMPLETE", "DONE" -> "FINISH"
            "READ", "REPLY", "INSPECT", "SUMMARY", "INFO", "CHECK" -> "FINISH"
            "SWIPE" -> "SCROLL"  // Remap SWIPE → SCROLL to prevent physical swipe-down opening notification shade
            else -> rawType
        }

        val actionType = try {
            ActionType.valueOf(normalizedTypeStr)
        } catch (e: Exception) {
            Log.w("GROQ_PLANNER", "Unsupported action type '$rawType'; returning a structured CHAT response.")
            ActionType.CHAT
        }

        val replyText = chatResponse.reply.orEmpty()

        return when (actionType) {
            ActionType.ASK_USER -> Action(
                type = ActionType.ASK_USER,
                question = chatResponse.question ?: replyText.ifBlank { chatResponse.text },
                options = chatResponse.options,
                subgoal = chatResponse.subgoal,
                remainingPlan = chatResponse.remainingPlan,
                summary = (chatResponse.question ?: replyText).ifBlank { "Asking user for decision..." }
            )
            ActionType.CHAT -> {
                if (!chatResponse.options.isNullOrEmpty() || !chatResponse.question.isNullOrBlank()) {
                    Action(
                        type = ActionType.ASK_USER,
                        question = chatResponse.question ?: replyText.ifBlank { chatResponse.text },
                        options = chatResponse.options,
                        subgoal = chatResponse.subgoal,
                        remainingPlan = chatResponse.remainingPlan,
                        summary = (chatResponse.question ?: replyText).ifBlank { "Awaiting user choice..." }
                    )
                } else {
                    Action(
                        type = ActionType.CHAT,
                        summary = replyText.ifBlank {
                            if (rawType == "CHAT") "" else
                                "Unsupported action type '$rawType'. Return one supported Android action type."
                        }
                    )
                }
            }
            ActionType.OPEN_APP -> Action(
                type = ActionType.OPEN_APP,
                packageName = chatResponse.app ?: chatResponse.packageName ?: chatResponse.target,
                app = chatResponse.app,
                summary = replyText.ifBlank { "Opening app..." }
            )
            ActionType.FIND_APP -> Action(
                type = ActionType.FIND_APP,
                text = chatResponse.query ?: chatResponse.text ?: chatResponse.target ?: chatResponse.app,
                app = chatResponse.app,
                summary = replyText.ifBlank { "Searching installed apps..." }
            )
            ActionType.CLICK -> Action(
                type = ActionType.CLICK,
                targetId = chatResponse.nodeId ?: chatResponse.targetId,
                x = chatResponse.x,
                y = chatResponse.y,
                target = chatResponse.target,
                summary = replyText
            )
            ActionType.TYPE -> Action(
                type = ActionType.TYPE,
                targetId = chatResponse.nodeId ?: chatResponse.targetId,
                x = chatResponse.x,
                y = chatResponse.y,
                text = chatResponse.text,
                summary = replyText
            )
            ActionType.SEARCH -> Action(
                type = ActionType.SEARCH,
                targetId = chatResponse.nodeId ?: chatResponse.targetId,
                text = chatResponse.query ?: chatResponse.text,
                summary = replyText
            )
            ActionType.SEND_MESSAGE -> Action(
                type = ActionType.SEND_MESSAGE,
                summary = replyText
            )
            ActionType.TOGGLE_QUICK_SETTING -> Action(
                type = ActionType.TOGGLE_QUICK_SETTING,
                target = chatResponse.target ?: chatResponse.text ?: chatResponse.query,
                summary = replyText,
                desiredState = chatResponse.desiredState
                    ?: QuickSettingRequestParser.parse(goalRequest)?.enabled
            )
            ActionType.LOCK_SCREEN -> Action(
                type = ActionType.LOCK_SCREEN,
                summary = replyText
            )
            ActionType.SCROLL -> Action(
                type = ActionType.SCROLL,
                text = chatResponse.direction?.uppercase()?.takeIf { it in setOf("UP", "DOWN", "LEFT", "RIGHT") } ?: "DOWN",
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
            ActionType.WEB_SEARCH -> Action(
                type = ActionType.WEB_SEARCH,
                text = chatResponse.query ?: chatResponse.text,
                summary = replyText
            )
            ActionType.READ_NOTIFICATIONS -> Action(
                type = ActionType.READ_NOTIFICATIONS,
                summary = replyText.ifBlank { "Reading notifications" }
            )
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
        val sanitizedJson = sanitizeJsonArtifacts(jsonCandidate)

        Log.d("GROQ_PLANNER", "Parsed JSON candidate: $sanitizedJson")

        return try {
            val reader = JsonReader(StringReader(sanitizedJson))
            reader.isLenient = true
            gson.fromJson(reader, ChatResponse::class.java)
        } catch (e: Exception) {
            Log.e("GROQ_PLANNER", "JSON parse failed: [${e.message}] for input: [$sanitizedJson]")
            recoverPartialResponse(text) ?: run {
                val cleanText = text.trim()
                if (cleanText.isNotBlank()) {
                    ChatResponse(
                        type = "CHAT",
                        reply = cleanText
                    )
                } else {
                    ChatResponse(
                        type = "CHAT",
                        reply = "I couldn't produce a valid action. Please clarify what you'd like me to do."
                    )
                }
            }
        }
    }

    private fun recoverPartialResponse(text: String): ChatResponse? {
        val type = Regex("\"type\"\\s*:\\s*\"([A-Za-z_]+)").find(text)
            ?.groupValues?.get(1) ?: return null
        fun intField(name: String): Int? =
            Regex("\"$name\"\\s*:\\s*(-?\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        fun floatField(name: String): Float? =
            Regex("\"$name\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").find(text)?.groupValues?.get(1)?.toFloatOrNull()
        fun stringField(name: String): String? =
            Regex("\"$name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)").find(text)
                ?.groupValues?.get(1)
                ?.replace("\\\"", "\"")
                ?.replace("\\n", "\n")

        return ChatResponse(
            type = type,
            reply = stringField("reply"),
            target = stringField("target"),
            packageName = stringField("packageName"),
            targetId = intField("targetId"),
            nodeId = intField("nodeId"),
            x = floatField("x"),
            y = floatField("y"),
            text = stringField("text"),
            query = stringField("query"),
            direction = stringField("direction")?.uppercase()?.takeIf { it in setOf("UP", "DOWN", "LEFT", "RIGHT") } ?: "DOWN",
            milliseconds = intField("milliseconds")?.toLong()
        )
    }

    private fun buildScreenPrompt(
        screen: ScreenState,
        memory: AgentMemory
    ): String {
        return compactPlannerScreen(screenReader.toPrompt(screen))
    }

}