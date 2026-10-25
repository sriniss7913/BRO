package com.example.bro.executor

import android.graphics.Rect
import android.util.Log
import com.example.bro.accessibility.NodeRepository
import com.example.bro.accessibility.UiCanonicalizer
import com.example.bro.model.Action
import com.example.bro.model.ActionType
import com.example.bro.model.Goal

data class SystemOverlayInterception(
    val type: String,
    val packageName: String,
    val message: String,
    val suggestedAction: String
)

object ActionValidator {
    private val destructiveActions = setOf(
        "delete", "remove", "erase", "clear", "uninstall", "format", "reset", "factory reset"
    )

    fun findAlarmSaveAction(
        goal: Goal,
        canonicalOutput: String
    ): Action? {
        if (!Regex("""\balarm\b""", RegexOption.IGNORE_CASE).containsMatchIn(goal.request)) return null
        val requestedTime = findRequestedTime(goal.request) ?: return null
        val blocks = canonicalOutput.split(Regex("\\n\\s*\\n"))

        fun inputValue(fieldName: String): Int? {
            val inputBlock = blocks.firstOrNull { block ->
                Regex("""^\[\d+] INPUT""").containsMatchIn(block) &&
                    Regex("""label="[^"]*\b$fieldName\b""", RegexOption.IGNORE_CASE)
                        .containsMatchIn(block)
            } ?: return null
            val label = Regex("""label="([^"]+)"""").find(inputBlock)?.groupValues?.get(1)
                ?: return null
            return Regex("""\b(\d{1,2})\b""").find(label)?.groupValues?.get(1)?.toIntOrNull()
        }

        if (inputValue("hour") != requestedTime.first ||
            inputValue("minute") != requestedTime.second
        ) {
            return null
        }

        val goalLower = goal.request.lowercase()
        val screenLower = canonicalOutput.lowercase()
        if (Regex("""\btomorrow\b""").containsMatchIn(goalLower) &&
            !Regex("""\btomorrow\b""").containsMatchIn(screenLower)
        ) return null
        if (Regex("""\btoday\b""").containsMatchIn(goalLower) &&
            !Regex("""\btoday\b""").containsMatchIn(screenLower)
        ) return null
        val requestedWeekday = listOf(
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
        ).firstOrNull { Regex("""\b$it\b""").containsMatchIn(goalLower) }
        if (requestedWeekday != null && !screenLower.contains(requestedWeekday)) return null

        val saveButton = blocks.firstNotNullOfOrNull { block ->
            if (!Regex("""^\[(\d+)] CLICKABLE""").containsMatchIn(block)) {
                return@firstNotNullOfOrNull null
            }
            val label = Regex("""label="([^"]+)"""").find(block)?.groupValues?.get(1)?.trim()
                ?: return@firstNotNullOfOrNull null
            if (Regex("""\bsave\b|\bdone\b|\bconfirm\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(label)
            ) {
                Regex("""^\[(\d+)]""").find(block)?.groupValues?.get(1)?.toIntOrNull()
            } else {
                null
            }
        } ?: return null

        return Action(
            type = ActionType.CLICK,
            targetId = saveButton,
            summary = "Save the alarm with the requested time"
        )
    }

    fun isTypeValueAlreadyPresent(action: Action): Boolean {
        if (action.type != ActionType.TYPE || action.targetId == null) return false
        val requested = action.text?.trim().orEmpty()
        if (requested.isBlank()) return false
        val node = NodeRepository.get(action.targetId) ?: return false
        if (NodeRepository.getRole(action.targetId) != UiCanonicalizer.Role.MESSAGE_INPUT) return false
        val current = node.text?.toString()?.trim().orEmpty()
        return current.isNotBlank() && current == requested
    }

    fun autoHealAction(action: Action, canonicalOutput: String): Action {
        // Auto-heal SEARCH on screens with no search bar into WEB_SEARCH
        if (action.type == ActionType.SEARCH && action.targetId == null) {
            val queryText = action.text?.takeIf(String::isNotBlank)
                ?: action.target
            return action.copy(type = ActionType.WEB_SEARCH, text = queryText)
        }

        if (action.type != ActionType.TYPE || action.targetId == null) return action
        val targetId = action.targetId
        val role = NodeRepository.getRole(targetId)
        if (role == UiCanonicalizer.Role.SEARCH) {
            return action.copy(type = ActionType.SEARCH)
        }
        val available = canonicalOutput.lines()
            .firstOrNull { it.startsWith("AVAILABLE_ACTIONS:") }
            ?.substringAfter(":")
            .orEmpty()
            .uppercase()
        val typeAvailable = available.contains("TYPE") || available.contains("TYPE_MESSAGE")
        val node = NodeRepository.get(targetId)
        if (!typeAvailable && node != null &&
            (role == UiCanonicalizer.Role.CLICKABLE ||
                role == UiCanonicalizer.Role.NEW_CHAT ||
                role == UiCanonicalizer.Role.CHAT_ROW ||
                node.isClickable)
        ) {
            return action.copy(type = ActionType.CLICK, text = null)
        }
        return action
    }

    fun detectObscuringSystemOverlay(
        packageName: String?,
        rootText: String? = null
    ): SystemOverlayInterception? {
        val pkg = packageName?.lowercase().orEmpty()
        val text = rootText?.lowercase().orEmpty()

        if (pkg.contains("permissioncontroller") || pkg.contains("packageinstaller")) {
            return SystemOverlayInterception(
                type = "PERMISSION_DIALOG",
                packageName = pkg,
                message = "Screen is covered by an Android system permission request dialog.",
                suggestedAction = "Grant or deny the requested permission to reveal the target app."
            )
        }

        if (pkg == "android" && (text.contains("isn't responding") || text.contains("close app") || text.contains("has stopped") || text.contains("anr"))) {
            return SystemOverlayInterception(
                type = "ANR_CRASH_DIALOG",
                packageName = pkg,
                message = "An Application Not Responding (ANR) or crash dialog is blocking the screen.",
                suggestedAction = "Dismiss the dialog ('Wait' or 'Close app') before continuing."
            )
        }

        if (pkg.contains("google.android.gms") && (text.contains("update") || text.contains("sign in") || text.contains("verify it's you"))) {
            return SystemOverlayInterception(
                type = "GMS_OVERLAY_DIALOG",
                packageName = pkg,
                message = "Google Play Services verification or update prompt is active.",
                suggestedAction = "Complete or dismiss the Google Play prompt."
            )
        }

        return null
    }

    suspend fun validate(
        action: Action,
        goal: Goal,
        canonicalOutput: String,
        currentPackage: String? = null,
        semanticCritic: (suspend (goal: String, candidate: String) -> Boolean)? = null
    ): String? {
        // Artemis Pre-Execution Safety Net: Obscuring System Overlay check
        val overlay = detectObscuringSystemOverlay(currentPackage, canonicalOutput)
        if (overlay != null && !goal.request.contains("permission", ignoreCase = true)) {
            return "ACTION REJECTED: ${overlay.message} ${overlay.suggestedAction}"
        }

        if (action.type == ActionType.TYPE && action.text.isNullOrBlank()) {
            return """ACTION REJECTED: TYPE requires a non-empty 'text' field (e.g., {"type":"TYPE","nodeId":1,"text":"05"})."""
        }
        val nodeIds = Regex("""^\[(\d+)]""", RegexOption.MULTILINE)
            .findAll(canonicalOutput)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toList()
        val targetId = action.targetId
        if (targetId != null) {
            val maxId = nodeIds.maxOrNull()
            if (maxId == null || targetId !in nodeIds) {
                val validRange = if (maxId == null) "none (no nodes are exposed)" else "0..$maxId"
                return "ACTION REJECTED: nodeId $targetId is outside the valid node ID range $validRange for this screen."
            }

            // Artemis Physical Bounds & Visibility Safety check
            val node = NodeRepository.get(targetId)
            if (node != null) {
                runCatching {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.width() <= 0 || rect.height() <= 0) {
                        return "ACTION REJECTED: Node [$targetId] has zero dimensions (width=${rect.width()}, height=${rect.height()}). Element is invisible or obscured."
                    }
                    if (rect.bottom <= 0 || rect.top >= 3000) {
                        return "ACTION REJECTED: Node [$targetId] is scrolled offscreen (top=${rect.top}, bottom=${rect.bottom}). Issue SCROLL first."
                    }
                }
            }
        }

        // Enforce AVAILABLE_ACTIONS
        val availableActionsLine = canonicalOutput.lines().find { it.startsWith("AVAILABLE_ACTIONS:") }
        val availableStr = availableActionsLine?.substringAfter(":")?.uppercase() ?: ""
        
        if (action.type == ActionType.SEARCH &&
            !availableStr.contains("SEARCH") &&
            !availableStr.contains("SEARCH_CONTACT")
        ) {
            return "ACTION REJECTED: SEARCH is not available on this screen. Allowed actions: $availableStr"
        }
        if (action.type == ActionType.TYPE &&
            !availableStr.contains("TYPE") &&
            !availableStr.contains("TYPE_MESSAGE")
        ) {
            return "ACTION REJECTED: TYPE is not available on this screen. Allowed actions: $availableStr"
        }
        if (action.type == ActionType.SEND_MESSAGE && !availableStr.contains("SEND_MESSAGE")) {
            return "ACTION REJECTED: SEND_MESSAGE is not available on this screen. Allowed actions: $availableStr"
        }
        
        if (action.type == ActionType.TYPE) {
            val targetId = action.targetId
            if (targetId == null) {
                return "ACTION REJECTED: TYPE requires an accessibility nodeId in accessibility mode."
            } else {
                val role = com.example.bro.accessibility.NodeRepository.getRole(targetId)
                if (role != com.example.bro.accessibility.UiCanonicalizer.Role.INPUT &&
                    role != com.example.bro.accessibility.UiCanonicalizer.Role.MESSAGE_INPUT) {
                    return "ACTION REJECTED: Node $targetId is $role (not an editable INPUT field). If this is a form field or selector, CLICK node $targetId first to open the search/input dialog."
                }
            }

            return null
        }

        if (action.type != ActionType.CLICK) {
            return null // Only validate clicks and types for now
        }

        val clickTargetId = action.targetId ?: return if (action.x != null || action.y != null) {
            "ACTION REJECTED: Coordinates are allowed only in Vision mode."
        } else null
        val node = NodeRepository.get(clickTargetId)
        if (node != null && NodeRepository.getRole(clickTargetId) == UiCanonicalizer.Role.TEXT &&
            !node.isClickable &&
            node.parent?.isClickable != true
        ) {
            return "ACTION REJECTED: nodeId $clickTargetId is non-clickable TEXT with no clickable parent. Choose a clickable control or container."
        }

        val nodeText = (node?.text?.toString() ?: node?.contentDescription?.toString() ?: "").lowercase().trim()
        val goalText = goal.request.lowercase().trim()
        val destructiveVerb = destructiveActions
            .sortedByDescending(String::length)
            .firstOrNull { verb -> Regex("""\b${Regex.escape(verb)}\b""").containsMatchIn(goalText) }
        val nodeDestructiveVerb = destructiveActions.firstOrNull { verb ->
            Regex("""\b${Regex.escape(verb)}\b""").containsMatchIn(nodeText)
        }
        if (nodeDestructiveVerb != null && nodeDestructiveVerb != destructiveVerb) {
            val reason = "ACTION REJECTED: The selected node '$nodeText' is a destructive control unrelated to the requested action."
            runCatching { Log.w("BRO_VALIDATOR", reason) }
            return reason
        }

        // Two-Tier Target Entity Guard (Ramesh vs Arun / Rameshbabu / Mom->Amma)
        val entityMismatch = SmartActionGuard.validateTargetEntity(action, goal, canonicalOutput, currentPackage, semanticCritic)
        if (entityMismatch != null) {
            runCatching { Log.w("BRO_VALIDATOR", entityMismatch) }
            return entityMismatch
        }

        // WhatsApp Chat Screen: If on WHATSAPP_CHAT and message input is present, forbid clicking the chat header / contact name
        if (canonicalOutput.contains("SCREEN_STATE: WHATSAPP_CHAT") &&
            (canonicalOutput.contains("MESSAGE_INPUT") || canonicalOutput.contains("Role.MESSAGE_INPUT"))
        ) {
            val role = NodeRepository.getRole(clickTargetId)
            val resName = node?.viewIdResourceName?.lowercase().orEmpty()
            if (role == UiCanonicalizer.Role.CHAT_HEADER ||
                resName.contains("conversation_contact") ||
                resName.contains("contact_photo_view")
            ) {
                val inputNodeId = Regex("""\[(\d+)]\s+MESSAGE_INPUT""").find(canonicalOutput)?.groupValues?.get(1)
                val hint = if (inputNodeId != null) " Type your message directly into MESSAGE_INPUT (node [$inputNodeId])." else ""
                return "ACTION REJECTED: You are already inside the requested chat conversation. Do not click the contact header or profile picture.$hint"
            }
        }

        return null
    }

    private fun findRequestedTime(goal: String): Pair<Int, Int>? {
        val match = Regex(
            """\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b|\b(\d{1,2}):(\d{2})\b|\bat\s+(\d{1,2})\b""",
            RegexOption.IGNORE_CASE
        ).find(goal) ?: return null
        val hour = (match.groups[1]?.value ?: match.groups[4]?.value ?: match.groups[6]?.value)
            ?.toIntOrNull() ?: return null
        val minute = (match.groups[2]?.value ?: match.groups[5]?.value)?.toIntOrNull() ?: 0
        return hour to minute
    }
}
