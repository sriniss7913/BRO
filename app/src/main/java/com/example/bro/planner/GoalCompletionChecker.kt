package com.example.bro.planner

import com.example.bro.model.Goal
import com.example.bro.model.ScreenState
import com.example.bro.accessibility.UiCanonicalizer

object GoalCompletionChecker {

    /**
     * Evaluates whether the current screen state indicates the user's goal has been achieved.
     * @param goal The user's original goal (e.g. "send hi to Ramesh")
     * @param screen The current UI representation
     * @param lastActionType The type of the action that was just executed
     * @param canonicalOutput The text output from the canonicalizer
     */
    fun isComplete(
        goal: Goal,
        screen: ScreenState,
        lastActionType: com.example.bro.model.ActionType?,
        canonicalOutput: String,
        sendWasClicked: Boolean = false
    ): Boolean {
        val goalText = goal.request.lowercase().trim()

        if (goalText.startsWith("send") || goalText.contains("whatsapp")) {
            if (canonicalOutput.contains("SCREEN_STATE: WHATSAPP_CHAT")) {
                val inputBlock = canonicalOutput.split(Regex("\\n\\s*\\n")).firstOrNull {
                    it.startsWith("[") && it.contains(" MESSAGE_INPUT")
                }
                val inputText = inputBlock?.let {
                    Regex("""(?m)^\s*text="(.*)"\s*$""").find(it)?.groupValues?.get(1)
                }
                val inputIsEmpty = inputBlock != null &&
                    (inputText.isNullOrBlank() ||
                        inputText.equals("Message", ignoreCase = true) ||
                        inputText.equals("Type a message", ignoreCase = true))
                val deliveryVisible = Regex(
                    """\b(sent|delivered)\b""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(canonicalOutput)
                if (sendWasClicked && inputIsEmpty && deliveryVisible) return true
            }
        }

        if (goalText.contains("alarm") &&
            lastActionType == com.example.bro.model.ActionType.CLICK &&
            screen.packageName.contains("clock", ignoreCase = true)
        ) {
            val requestedTime = Regex(
                """\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b|\b(\d{1,2}):(\d{2})\b|\bat\s+(\d{1,2})\b""",
                RegexOption.IGNORE_CASE
            ).find(goalText)
            if (requestedTime != null) {
                val hour = (requestedTime.groups[1]?.value
                    ?: requestedTime.groups[4]?.value
                    ?: requestedTime.groups[6]?.value)?.toIntOrNull()
                val minute = (requestedTime.groups[2]?.value
                    ?: requestedTime.groups[5]?.value)?.toIntOrNull() ?: 0
                if (hour != null) {
                    val visibleTime = Regex(
                        """(?<!\d)0?${hour}:${minute.toString().padStart(2, '0')}(?!\d)"""
                    ).containsMatchIn(canonicalOutput)
                    if (visibleTime) return true
                }
            }
        }

        // Switch / Toggle completion check
        val isToggleGoal = Regex(
            """\b(?:turn\s+(?:on|off)|switch\s+(?:on|off)|enable|disable|toggle)\s+([a-zA-Z\s]+)""",
            RegexOption.IGNORE_CASE
        ).find(goalText)
        if (isToggleGoal != null && lastActionType == com.example.bro.model.ActionType.CLICK) {
            val targetSubject = isToggleGoal.groupValues[1].trim()
            val wantsOn = goalText.contains("turn on") || goalText.contains("switch on") || goalText.contains("enable")
            val wantsOff = goalText.contains("turn off") || goalText.contains("switch off") || goalText.contains("disable")

            val switchBlocks = canonicalOutput.split(Regex("\\n\\s*\\n")).filter { it.contains("SWITCH") }
            for (block in switchBlocks) {
                val label = Regex("""label="([^"]+)"""", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)?.lowercase().orEmpty()
                val state = Regex("""state="([^"]+)"""", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)?.uppercase().orEmpty()
                if (label.contains(targetSubject, ignoreCase = true) || targetSubject.contains(label, ignoreCase = true)) {
                    if (wantsOn && state == "ON") return true
                    if (wantsOff && state == "OFF") return true
                    if (!wantsOn && !wantsOff) return true
                }
            }
        }
        
        return false
    }
}
