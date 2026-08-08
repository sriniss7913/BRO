package com.example.bro.accessibility

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

object UiCanonicalizer {
    private const val TAG = "UiCanonicalizer"

    enum class Role {
        SEARCH,
        CHAT_ROW,
        SEARCH_RESULT,
        MESSAGE_INPUT,
        SEND,
        NEW_CHAT,
        FILTER,
        BACK,
        ATTACH,
        PAYMENT,
        CAMERA,
        VOICE,
        CLICKABLE,
        INPUT,
        TEXT
    }

    data class SemanticNode(
        val role: Role,
        val originalNode: AccessibilityNodeInfo,
        val targetNode: AccessibilityNodeInfo,
        val fields: Map<String, String>
    )

    enum class ScreenState {
        WHATSAPP_CHAT,
        WHATSAPP_SEARCH_RESULTS,
        WHATSAPP_MAIN,
        WHATSAPP_PROFILE,
        WHATSAPP_DIALOG,
        UNKNOWN
    }

    private fun detectScreenState(nodes: List<SemanticNode>): ScreenState {
        val hasMessageInput = nodes.any { it.role == Role.MESSAGE_INPUT }
        val hasSearch = nodes.any { it.role == Role.SEARCH }
        val hasInput = nodes.any { it.role == Role.INPUT }
        val hasChatRow = nodes.any { it.role == Role.CHAT_ROW }
        val hasNewChat = nodes.any { it.role == Role.NEW_CHAT }
        
        // Detect Dialogs: A few generic text/clickable nodes with OK/Cancel/Allow buttons, no typical whatsapp chrome
        val hasDialogButtons = nodes.any { it.role == Role.CLICKABLE && 
            (it.fields["label"]?.lowercase() in listOf("ok", "cancel", "allow", "deny", "dismiss")) 
        }

        if (hasDialogButtons && !hasMessageInput && !hasNewChat && !hasChatRow) return ScreenState.WHATSAPP_DIALOG
        if (hasMessageInput) return ScreenState.WHATSAPP_CHAT
        if ((hasSearch || hasInput) && hasChatRow) return ScreenState.WHATSAPP_SEARCH_RESULTS
        if ((hasNewChat || hasChatRow) && !hasMessageInput && !hasInput) return ScreenState.WHATSAPP_MAIN
        if (nodes.count { it.role == Role.TEXT } > 3 && !hasMessageInput && !hasInput && !hasSearch) return ScreenState.WHATSAPP_PROFILE

        return ScreenState.UNKNOWN
    }

    fun canonicalize(root: AccessibilityNodeInfo?, packageName: String): String {
        NodeRepository.clear()
        
        if (packageName == "com.example.bro" || packageName.endsWith(".bro")) {
            return "SCREEN: BRO_MAIN"
        }
        
        if (root == null) return "SCREEN: EMPTY"

        val semanticNodes = mutableListOf<SemanticNode>()
        traverseAndDetectRoles(root, semanticNodes)

        val screenState = detectScreenState(semanticNodes)
        val availableActions = when (screenState) {
            ScreenState.WHATSAPP_CHAT -> "TYPE_MESSAGE, SEND_MESSAGE, BACK"
            ScreenState.WHATSAPP_MAIN -> "SEARCH_CONTACT, OPEN_CHAT"
            ScreenState.WHATSAPP_SEARCH_RESULTS -> "CLICK_RESULT, CLEAR_SEARCH, BACK"
            ScreenState.WHATSAPP_PROFILE -> "BACK"
            ScreenState.WHATSAPP_DIALOG -> "DISMISS, CLICK_BUTTON"
            ScreenState.UNKNOWN -> "BACK, HOME" // Restrict from ANY to safe fallbacks
        }

        // Assign IDs and build output
        val sb = StringBuilder()
        sb.append("SCREEN: $packageName\n")
        sb.append("SCREEN_STATE: ${screenState.name}\n")
        sb.append("AVAILABLE_ACTIONS: $availableActions\n\n")

        var idCounter = 0
        for (semNode in semanticNodes) {
            val id = idCounter++
            NodeRepository.put(id, semNode.targetNode, semNode.role)
            
            sb.append("[$id] ${semNode.role.name}")
            
            if (semNode.fields.isNotEmpty()) {
                sb.append("\n")
                for ((k, v) in semNode.fields) {
                    val safeVal = v.replace("\n", " ").replace("\"", "\\\"")
                    if (v.isNotBlank()) {
                        sb.append("    $k=\"$safeVal\"\n")
                    }
                }
            } else {
                sb.append("\n")
            }
            sb.append("\n")
        }

        return sb.toString().trimEnd()
    }

    private fun traverseAndDetectRoles(node: AccessibilityNodeInfo?, results: MutableList<SemanticNode>) {
        if (node == null || !node.isVisibleToUser) return
        
        // 1. Try to detect a complex Role
        val role = detectRole(node)
        if (role != null) {
            val fields = extractFields(node, role)
            val target = resolveTarget(node, role)
            results.add(SemanticNode(role, node, target, fields))
            return // Stop recursion for this subtree
        }
        
        // 2. Generic fallbacks for interactive containers
        val isGenericInteractive = node.isClickable || node.isEditable
        if (isGenericInteractive) {
            // Avoid consuming the whole screen if a massive layout is clickable
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() < 1000 || bounds.height() < 1000 || node.childCount == 0) {
                val genericRole = if (node.isEditable) Role.INPUT else Role.CLICKABLE
                val fields = mutableMapOf<String, String>()
                val allText = extractAllText(node)
                if (allText.isNotBlank()) fields["label"] = allText
                
                val hint = node.hintText?.toString()?.trim()
                if (!hint.isNullOrBlank()) fields["hint"] = hint
                
                val target = resolveTarget(node, genericRole)
                results.add(SemanticNode(genericRole, node, target, fields))
                return // Stop recursion
            }
        }
        
        // 3. Recurse into children
        var hasUsefulChildren = false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null && child.isVisibleToUser) {
                hasUsefulChildren = true
                traverseAndDetectRoles(child, results)
            }
        }
        
        // 4. Generic fallback for un-interactive text leaf nodes
        if (!hasUsefulChildren) {
            val text = (node.text?.toString() ?: node.contentDescription?.toString())?.trim()
            if (!text.isNullOrBlank()) {
                val target = resolveTarget(node, Role.TEXT)
                results.add(SemanticNode(Role.TEXT, node, target, mapOf("text" to text)))
            }
        }
    }

    private fun detectRole(node: AccessibilityNodeInfo): Role? {
        val res = node.viewIdResourceName?.substringAfterLast("/") ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val cls = node.className?.toString()?.substringAfterLast(".") ?: ""
        val hint = node.hintText?.toString()?.trim() ?: ""
        
        // SEARCH
        if (res == "search_bar_inner_layout" && node.isClickable) return Role.SEARCH
        if (desc.contains("Search", ignoreCase = true) && node.isClickable) return Role.SEARCH
        if (res == "search_src_text" || hint.contains("Search", ignoreCase = true)) return Role.SEARCH
        
        // CHAT_ROW
        if (res == "contact_row_container" && node.isClickable) return Role.CHAT_ROW
        
        // NEW_CHAT
        if (res == "fab" && desc.contains("New chat", ignoreCase = true) && node.isClickable) return Role.NEW_CHAT
        
        // FILTER
        if (cls == "RadioButton" && desc.contains("filter", ignoreCase = true)) return Role.FILTER
        
        // MESSAGE_INPUT
        if (node.isEditable && (res == "entry" || hint.contains("Message", ignoreCase = true))) return Role.MESSAGE_INPUT
        
        // SEND
        if (res == "send" && node.isClickable) return Role.SEND
        
        // CHAT SCREEN ACTIONS
        if (desc.contains("Attach", ignoreCase = true) || res == "input_attach_button") return Role.ATTACH
        if (desc.contains("Payment", ignoreCase = true) || res == "payment_attach_button") return Role.PAYMENT
        if (desc.contains("Camera", ignoreCase = true) || res == "camera_btn") return Role.CAMERA
        if (desc.contains("Voice message", ignoreCase = true) || res == "voice_note_btn") return Role.VOICE
        
        // BACK
        if (desc.contains("Back", ignoreCase = true) || res.contains("back")) return Role.BACK
        
        return null
    }

    private fun extractFields(node: AccessibilityNodeInfo, role: Role): Map<String, String> {
        val map = mutableMapOf<String, String>()
        when (role) {
            Role.SEARCH -> {
                map["purpose"] = "search chats, contacts, or messages"
            }
            Role.CHAT_ROW -> {
                val contact = findDescendantByRes(node, "conversations_row_contact_name")?.text?.toString()
                val previewNode = findDescendantByRes(node, "single_msg_tv") ?: findDescendantByRes(node, "msg_from_tv")
                val preview = previewNode?.text?.toString()?.replace("\n", " ")
                val time = findDescendantByRes(node, "conversations_row_date")?.text?.toString()
                
                var unread = findDescendantByRes(node, "conversations_row_message_count")?.text?.toString() 
                if (unread == null) {
                    unread = findDescendantByRes(node, "conversations_row_message_count")?.contentDescription?.toString()
                }
                
                if (contact != null) map["contact"] = contact
                if (preview != null) map["preview"] = preview
                if (time != null) map["time"] = time
                if (unread != null) map["unread"] = unread.replace(Regex("[^0-9]"), "")

                if (map.isEmpty()) {
                    val desc = node.contentDescription?.toString()?.trim()
                    if (!desc.isNullOrBlank()) {
                        map["label"] = desc
                    } else {
                        val allText = extractAllText(node)
                        if (allText.isNotBlank()) {
                            map["label"] = allText
                        }
                    }
                }
            }
            Role.FILTER -> {
                val desc = node.contentDescription?.toString() ?: ""
                map["label"] = desc.substringBefore(" filter").trim()
                map["selected"] = if (node.isChecked || node.isSelected) "true" else "false"
                if (desc.contains("items")) {
                    map["count"] = desc.substringAfter(", ").substringBefore(" items").trim()
                }
            }
            Role.MESSAGE_INPUT -> {
                val text = node.text?.toString()
                if (!text.isNullOrBlank()) map["text"] = text
            }
            else -> {}
        }
        return map
    }

    private fun resolveTarget(node: AccessibilityNodeInfo, role: Role): AccessibilityNodeInfo {
        // Rule 1: Direct
        if (node.isClickable || node.isEditable) {
            Log.d(TAG, "CANONICAL: ROLE=${role.name} RESOLUTION=rule1_direct CONFIDENCE=HIGH")
            return node
        }

        // Rule 2: Clickable descendant
        val clickableDescendants = mutableListOf<AccessibilityNodeInfo>()
        findClickableDescendants(node, clickableDescendants)
        if (clickableDescendants.size == 1) {
            Log.d(TAG, "CANONICAL: ROLE=${role.name} RESOLUTION=rule2_descendant CONFIDENCE=HIGH")
            return clickableDescendants.first()
        } else if (clickableDescendants.isNotEmpty()) {
            val best = clickableDescendants.maxByOrNull { 
                val bounds = Rect(); it.getBoundsInScreen(bounds); bounds.width() * bounds.height() 
            } ?: clickableDescendants.first()
            Log.d(TAG, "CANONICAL: ROLE=${role.name} RESOLUTION=rule2_descendant CONFIDENCE=MEDIUM")
            return best
        }

        // Rule 3: Clickable ancestor
        var ancestor = node.parent
        var levels = 0
        while (ancestor != null && levels < 3) {
            if (ancestor.isClickable) {
                Log.d(TAG, "CANONICAL: ROLE=${role.name} RESOLUTION=rule3_ancestor CONFIDENCE=HIGH")
                return ancestor
            }
            ancestor = ancestor.parent
            levels++
        }

        Log.d(TAG, "CANONICAL: ROLE=${role.name} RESOLUTION=fallback CONFIDENCE=LOW")
        return node
    }

    // --- Helpers ---

    private fun findDescendantByRes(node: AccessibilityNodeInfo, resName: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName?.endsWith("/$resName") == true) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findDescendantByRes(child, resName)
            if (found != null) return found
        }
        return null
    }

    private fun findClickableDescendants(node: AccessibilityNodeInfo, results: MutableList<AccessibilityNodeInfo>) {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isClickable || child.isEditable) {
                results.add(child)
            }
            findClickableDescendants(child, results)
        }
    }

    private fun extractAllText(node: AccessibilityNodeInfo): String {
        val texts = mutableListOf<String>()
        extractTextRecursive(node, texts)
        return texts.joinToString(" ").trim()
    }
    
    private fun extractTextRecursive(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrBlank()) texts.add(text)
        else if (!desc.isNullOrBlank()) texts.add(desc)
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null && child.isVisibleToUser) {
                extractTextRecursive(child, texts)
            }
        }
    }
}