package com.example.bro.executor

import android.content.Context
import com.example.bro.Message
import com.example.bro.accessibility.BroAccessibilityService
import com.example.bro.model.Action
import com.example.bro.model.ActionType
import com.example.bro.model.ScreenState
import com.example.bro.planner.GroqPlanner
import com.example.bro.planner.QuickSettingRequestParser

object PlannerExecutionHelper {

    suspend fun handlePreExecutionSpecialGoals(
        context: Context,
        goalStr: String,
        userOriginalPrompt: String,
        messages: MutableList<Message>,
        onComplete: ((goal: String, screen: ScreenState?, actions: List<Action>) -> Unit)?
    ): Boolean {
        val lower = goalStr.lowercase()
        if (lower.contains("lock screen") || lower == "lock") {
            val service = BroAccessibilityService.instance
            if (service == null) {
                messages.add(Message("⚠️ Enable BRO's Accessibility service to lock the screen.", false))
                return true
            }
            val lockAction = Action(type = ActionType.LOCK_SCREEN)
            if (ActionExecutor(service).execute(lockAction)) {
                val result = "✅ Screen locked."
                messages.add(Message(result, false))
                com.example.bro.agent.UserConversationMemory.addTurn(
                    context, userOriginalPrompt, goalStr, result
                )
                onComplete?.invoke(goalStr, null, listOf(lockAction))
            } else {
                messages.add(Message("⚠️ Could not lock the screen.", false))
            }
            return true
        }

        if (NotificationReader.isNotificationGoal(goalStr)) {
            val service = BroAccessibilityService.instance
            if (service == null) {
                messages.add(Message("⚠️ Enable BRO's Accessibility service to read notifications.", false))
                return true
            }
            messages.add(Message("🔔 Checking notifications from the notification bar...", false))
            val (ok, summary) = NotificationReader.readAndSummarizeNotifications(
                service = service,
                planner = GroqPlanner(),
                goal = goalStr
            )
            messages.add(Message(summary, false))
            com.example.bro.agent.UserConversationMemory.addTurn(
                context, userOriginalPrompt, goalStr, summary
            )
            if (ok) {
                onComplete?.invoke(goalStr, null, listOf(Action(type = ActionType.READ_NOTIFICATIONS)))
            }
            return true
        }

        val qsRequest = QuickSettingRequestParser.parse(goalStr)
        if (qsRequest != null) {
            val service = BroAccessibilityService.instance
            if (service == null) {
                messages.add(Message("⚠️ Enable BRO's Accessibility service to adjust settings.", false))
                return true
            }

            if (qsRequest.tileLabel == "Flashlight") {
                val torchAction = Action(
                    type = ActionType.TOGGLE_QUICK_SETTING,
                    target = qsRequest.tileLabel,
                    desiredState = qsRequest.enabled
                )
                if (ActionExecutor(service).execute(torchAction)) {
                    val state = when (qsRequest.enabled) {
                        true -> "on"
                        false -> "off"
                        null -> "toggled"
                    }
                    val result = "✅ Flashlight turned $state."
                    messages.add(Message(result, false))
                    com.example.bro.agent.UserConversationMemory.addTurn(
                        context, userOriginalPrompt, goalStr, result
                    )
                    onComplete?.invoke(goalStr, null, listOf(torchAction))
                } else {
                    messages.add(Message("⚠️ Could not toggle the flashlight. Check Camera permission and device flash availability.", false))
                }
                return true
            }

            val prefersQuickTiles = Regex(
                """\b(quick\s*tiles?|quick\s*settings?|notification\s*shade|notification\s*bar|dropdown)\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(goalStr)

            var executed = false
            // 1. Direct Android Intent flow (primary unless user explicitly asks for quick tiles)
            if (!prefersQuickTiles && DirectSettingsExecutor.canHandleDirectIntent(qsRequest.tileLabel)) {
                executed = DirectSettingsExecutor.execute(context, service, qsRequest)
            }

            // 2. Fallback to Quick Settings dropdown if direct intent was skipped or failed
            if (!executed) {
                val qsAction = Action(
                    type = ActionType.TOGGLE_QUICK_SETTING,
                    target = qsRequest.tileLabel,
                    desiredState = qsRequest.enabled
                )
                executed = ActionExecutor(service).execute(qsAction)
            }

            val state = when (qsRequest.enabled) {
                true -> "on"
                false -> "off"
                null -> "toggled"
            }
            if (executed) {
                val result = "✅ ${qsRequest.tileLabel} turned $state."
                messages.add(Message(result, false))
                com.example.bro.agent.UserConversationMemory.addTurn(
                    context, userOriginalPrompt, goalStr, result
                )
                onComplete?.invoke(goalStr, null, listOf(Action(
                    type = ActionType.TOGGLE_QUICK_SETTING,
                    target = qsRequest.tileLabel,
                    desiredState = qsRequest.enabled
                )))
            } else {
                messages.add(Message("⚠️ Could not toggle ${qsRequest.tileLabel}.", false))
            }
            return true
        }

        return false
    }

    suspend fun buildScreenSummaryDisplayText(
        planner: GroqPlanner,
        goalStr: String,
        normalizedGoal: String,
        screen: ScreenState,
        act: Action,
        messages: MutableList<Message>
    ): String {
        val visibleTexts = screen.nodes.mapNotNull {
            it.text?.trim() ?: it.contentDescription?.trim()
        }.filter { text ->
            text.length > 2 &&
                !text.contains("com.") &&
                !text.equals("Search", ignoreCase = true) &&
                !text.equals("Chats", ignoreCase = true) &&
                !text.equals("Calls", ignoreCase = true) &&
                !text.equals("Status", ignoreCase = true) &&
                !text.equals("Communities", ignoreCase = true)
        }

        val isReadGoal = normalizedGoal.contains("check") ||
            normalizedGoal.contains("read") ||
            normalizedGoal.contains("email") ||
            normalizedGoal.contains("inbox") ||
            normalizedGoal.contains("recent") ||
            normalizedGoal.contains("message") ||
            normalizedGoal.contains("order") ||
            normalizedGoal.contains("status") ||
            normalizedGoal.contains("summarize") ||
            normalizedGoal.contains("summary")

        return if (isReadGoal && visibleTexts.isNotEmpty()) {
            messages.add(Message("⏳ Analyzing and summarizing screen content...", false))
            try {
                val summary = planner.summarizeScreenOutcome(
                    goal = goalStr,
                    screenTexts = visibleTexts,
                    appName = screen.packageName
                )
                "📋 Summary:\n$summary"
            } catch (e: Exception) {
                val fallback = act.summary.orEmpty().ifBlank {
                    visibleTexts.take(5).joinToString("\n• ", prefix = "• ")
                }
                "✅ $fallback"
            }
        } else {
            val finishText = act.summary.orEmpty().ifBlank { "Task completed." }
            "✅ $finishText"
        }
    }
}
