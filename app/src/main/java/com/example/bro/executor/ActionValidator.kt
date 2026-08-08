package com.example.bro.executor

import android.util.Log
import com.example.bro.accessibility.NodeRepository
import com.example.bro.model.Action
import com.example.bro.model.ActionType
import com.example.bro.model.Goal

object ActionValidator {

    fun validate(action: Action, goal: Goal, canonicalOutput: String): String? {
        // Enforce AVAILABLE_ACTIONS
        val availableActionsLine = canonicalOutput.lines().find { it.startsWith("AVAILABLE_ACTIONS:") }
        val availableStr = availableActionsLine?.substringAfter(":")?.uppercase() ?: ""
        
        if (action.type == ActionType.SEARCH && !availableStr.contains("SEARCH")) {
            return "ACTION REJECTED: SEARCH is not available on this screen. Allowed actions: $availableStr"
        }
        if (action.type == ActionType.TYPE && !availableStr.contains("TYPE")) {
            return "ACTION REJECTED: TYPE is not available on this screen. Allowed actions: $availableStr"
        }
        
        if (action.type == ActionType.TYPE) {
            val targetId = action.targetId ?: return "ACTION REJECTED: TYPE action requires a valid nodeId."
            val role = com.example.bro.accessibility.NodeRepository.getRole(targetId)
            
            if (role != com.example.bro.accessibility.UiCanonicalizer.Role.INPUT && 
                role != com.example.bro.accessibility.UiCanonicalizer.Role.MESSAGE_INPUT) {
                return "ACTION REJECTED: TYPE requires INPUT or MESSAGE_INPUT. Node $targetId is $role."
            }

            val typeText = action.text?.trim()
            if (!typeText.isNullOrBlank() && canonicalOutput.contains("text=\"$typeText\"")) {
                 return "ACTION REJECTED: The text '$typeText' is already visible on the screen (likely already sent). Do not send it again."
            }

            return null
        }

        if (action.type != ActionType.CLICK) {
            return null // Only validate clicks and types for now
        }

        val targetId = action.targetId ?: return null
        val node = NodeRepository.get(targetId) ?: return null

        val nodeText = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").lowercase().trim()
        val goalText = goal.request.lowercase().trim()

        if (nodeText.isBlank()) return null // Can't validate empty nodes (e.g. icon-only buttons without contentDescription)

        // Allow common safe navigation buttons
        if (nodeText.contains("search") || 
            nodeText.contains("meta") || 
            nodeText.contains("new chat") || 
            nodeText.contains("message") || 
            nodeText.contains("send") ||
            nodeText.contains("back") ||
            nodeText.contains("navigate up") ||
            nodeText.contains("attach") ||
            nodeText.contains("camera") ||
            nodeText.contains("voice")
        ) {
            return null
        }

        // Extract key entities from goal
        val stopWords = setOf(
            "whatsapp", "app", "recent", "chats", "chat", "messages", "message",
            "check", "read", "inspect", "get", "show", "me", "my", "last", "latest",
            "the", "a", "an", "from", "for", "with", "of", "to", "send", "hi", "saying", "text", "on", "open"
        )
        
        val words = goalText.split(Regex("\\W+")).filter { it.isNotBlank() && it !in stopWords }
        
        // If it's a contact or list row, see if any goal word is in the node text
        if (words.isNotEmpty()) {
            val hasMatch = words.any { nodeText.contains(it) }
            if (!hasMatch) {
                // e.g. node is "Vaishak", goal words are ["ramesh"]
                val targetStr = words.joinToString(", ")
                val reason = "ACTION REJECTED: You tried to click '$nodeText', but the goal requires targeting [$targetStr]. If the target is not visible, use the SEARCH action."
                Log.w("BRO_VALIDATOR", reason)
                return reason
            }
        }

        return null
    }
}
