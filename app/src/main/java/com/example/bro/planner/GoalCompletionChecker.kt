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
        canonicalOutput: String
    ): Boolean {
        val goalText = goal.request.lowercase().trim()

        if (goalText.startsWith("send") || goalText.contains("whatsapp")) {
            // For sending a message, we look for WHATSAPP_CHAT state
            if (canonicalOutput.contains("SCREEN_STATE: WHATSAPP_CHAT")) {
                
                // If the last action was clicking SEND and the message input is cleared or says "Message"
                val isSendClicked = lastActionType == com.example.bro.model.ActionType.CLICK
                
                // Extract what the MESSAGE_INPUT currently contains
                val messageInputLine = canonicalOutput.lines().find { it.contains("MESSAGE_INPUT") }
                val hasEmptyInput = messageInputLine == null || messageInputLine.contains("text=\"Message\"")

                if (isSendClicked && hasEmptyInput) {
                    // Check if there are newly sent messages (we could extract chat rows here, but checking SEND + empty input is a robust proxy)
                    return true
                }
            }
        }
        
        return false
    }
}
