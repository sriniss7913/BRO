package com.example.bro.executor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.bro.accessibility.NodeRepository
import com.example.bro.model.Action
import com.example.bro.model.ActionType

class ActionExecutor(
    private val service: AccessibilityService
) {

    fun execute(action: Action): Boolean {
        Log.d(
            "BRO_EXECUTOR",
            "Executing type=${action.type}, targetId=${action.targetId}, text=${action.text}, target=${action.target}"
        )

        val result = when (action.type) {
            ActionType.CLICK -> click(action)
            ActionType.TYPE -> type(action)
            ActionType.SEARCH -> search(action)
            ActionType.SCROLL, ActionType.SWIPE -> scroll(action.text)
            ActionType.BACK -> back()
            ActionType.HOME -> home()
            else -> false
        }

        Log.d("BRO_EXECUTOR", "Result=$result for ${action.type}")
        return result
    }

    private fun click(action: Action): Boolean {
        action.targetId?.let { id ->
            val node = NodeRepository.get(id)
            if (node != null) {
                Log.d("BRO_EXECUTOR", "CLICK by ID=$id text=${node.text}")
                val resolved = resolveClickTarget(node)
                if (clickContactRowAvoidingAvatar(resolved)) return true
                if (clickNodeOrParent(resolved)) return true
            }
        }

        val targetText = action.target?.trim().orEmpty()
        val summaryText = action.summary?.trim().orEmpty()

        if (targetText.length >= 2) {
            val root = service.rootInActiveWindow
            if (root != null) {
                val candidates = mutableListOf<AccessibilityNodeInfo>()
                collectContactCandidates(root, targetText, candidates)
                val best = candidates.maxByOrNull { scoreContactRow(it, targetText) }
                if (best != null && scoreContactRow(best, targetText) >= 0) {
                    Log.d("BRO_EXECUTOR", "Matched explicit target '$targetText'")
                    if (clickContactRowAvoidingAvatar(best)) return true
                }
            }
        }

        // Fallback for Send actions (SMS / WhatsApp send icon buttons)
        if (summaryText.contains("send", ignoreCase = true) || targetText.contains("send", ignoreCase = true)) {
            val root = service.rootInActiveWindow
            if (root != null) {
                val sendBtn = findSendButton(root)
                if (sendBtn != null) {
                    Log.d("BRO_EXECUTOR", "Found explicit Send button fallback")
                    return sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
        }

        return false
    }

    private fun resolveClickTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val cls = node.className?.toString().orEmpty()
        if (!cls.contains("ImageView", ignoreCase = true) &&
            !cls.contains("ImageButton", ignoreCase = true)
        ) {
            return node
        }

        var parent = node.parent
        repeat(5) {
            val p = parent ?: return node
            if (p.isClickable &&
                !p.className?.toString().orEmpty().contains("ImageView", ignoreCase = true)
            ) {
                return p
            }
            parent = p.parent
        }
        return node
    }

    private fun clickNodeOrParent(startNode: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = startNode
        repeat(5) {
            val current = node ?: return@repeat
            if (current.isClickable) {
                Log.d("BRO_EXECUTOR", "Clicking node text=${current.text} class=${current.className}")
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            node = current.parent
        }

        val rect = Rect()
        startNode.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0 && rect.left >= 0 && rect.top >= 0) {
            val centerX = rect.centerX().toFloat()
            val centerY = rect.centerY().toFloat()
            if (centerX > 0 && centerY > 0) {
                Log.d("BRO_EXECUTOR", "Fallback physical tap at ($centerX, $centerY)")
                return tapAt(centerX, centerY)
            }
        }
        Log.w("BRO_EXECUTOR", "Skipping tap — invalid bounds: $rect")
        return false
    }

    private fun clickContactRowAvoidingAvatar(node: AccessibilityNodeInfo): Boolean {
        var targetNode = node
        val rect = Rect()
        targetNode.getBoundsInScreen(rect)

        if (rect.width() <= 400) {
            var parent = node.parent
            repeat(5) {
                val p = parent ?: return@repeat
                val pRect = Rect()
                p.getBoundsInScreen(pRect)
                if (pRect.width() > 400 && pRect.height() in 60..350) {
                    targetNode = p
                    rect.set(pRect)
                    return@repeat
                }
                parent = p.parent
            }
        }

        if (rect.width() > 400 && rect.height() in 60..350 && rect.top > 250) {
            val tapX = rect.left + rect.width() * 0.70f
            val tapY = rect.centerY().toFloat()
            Log.d("BRO_EXECUTOR", "Row tap avoiding avatar at ($tapX, $tapY)")
            if (tapAt(tapX, tapY)) return true
        }
        return clickNodeOrParent(targetNode)
    }

    private fun search(action: Action): Boolean {
        // 1. Click the search node to open the search bar
        action.targetId?.let { id ->
            val node = NodeRepository.get(id)
            if (node != null && node.isClickable) {
                Log.d("BRO_EXECUTOR", "SEARCH: Clicking search icon/bar ID=$id")
                clickNodeOrParent(node)
                // Wait for the UI transition (search bar appearing)
                try { Thread.sleep(1000) } catch (e: Exception) {}
            }
        }

        // 2. Find the newly appeared editable search field
        val root = service.rootInActiveWindow
        val inputNode = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditableNode(root)

        if (inputNode == null) {
            Log.e("BRO_EXECUTOR", "SEARCH: Could not find editable input field after clicking search.")
            return false
        }

        // 3. Type the query
        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                action.text ?: ""
            )
        }
        val setOk = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        
        if (setOk) {
            Log.d("BRO_EXECUTOR", "SEARCH: Successfully typed '${action.text}'")
            // Wait for search results to populate so they appear in the next DOM read
            try { Thread.sleep(1500) } catch (e: Exception) {}
            return true
        }

        Log.e("BRO_EXECUTOR", "SEARCH: Failed to set text on input field.")
        return false
    }

    private fun type(action: Action): Boolean {
        val targetId = action.targetId
        var node = if (targetId != null) NodeRepository.get(targetId) else null

        if (node == null || !node.isEditable) {
            val root = service.rootInActiveWindow
            node = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFirstEditableNode(root)
        }

        if (node == null) return false

        val nodeDesc = (node.contentDescription?.toString() ?: node.text?.toString() ?: "").lowercase()
        val hintText = node.hintText?.toString()?.lowercase().orEmpty()
        val isChatRoomInput = nodeDesc.contains("type a message") || nodeDesc.contains("message_input") ||
                hintText.contains("type a message") || hintText.contains("message")

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                action.text ?: ""
            )
        }

        val setOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (setOk) {
            val typedText = action.text ?: ""
            val activePkg = service.rootInActiveWindow?.packageName?.toString()
            // ONLY auto-select contact if we typed in a Search bar on Main Screen, NOT inside a chat room!
            if (typedText.length >= 2 && activePkg != null && !isChatRoomInput) {
                try {
                    Thread.sleep(800)
                    if (activePkg == "com.whatsapp") {
                        selectWhatsAppContact(typedText)
                    } else if (activePkg.contains("messaging") || activePkg.contains("mms")) {
                        selectMessagingContact(typedText)
                    }
                } catch (e: Exception) {
                    Log.e("BRO_EXECUTOR", "Auto contact selection error: ${e.message}")
                }
            }
            return true
        }

        val focused = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return focused?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) ?: false
    }

    private fun findFirstEditableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isEditable) return root
        for (i in 0 until root.childCount) {
            val res = findFirstEditableNode(root.getChild(i))
            if (res != null) return res
        }
        return null
    }

    private fun selectMessagingContact(searchText: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val sendToNodes = root.findAccessibilityNodeInfosByText("Send to")
        if (sendToNodes.isNotEmpty()) {
            val target = sendToNodes.firstOrNull { it.isClickable || it.parent?.isClickable == true }
            if (target != null) {
                val clickable = if (target.isClickable) target else target.parent
                Log.d("BRO_EXECUTOR", "Clicking 'Send to' row in Messages")
                return clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            }
        }
        return selectWhatsAppContact(searchText)
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val sendNodes = root.findAccessibilityNodeInfosByText("Send")
        if (sendNodes.isNotEmpty()) {
            val btn = sendNodes.firstOrNull { it.isClickable || it.parent?.isClickable == true }
            if (btn != null) return if (btn.isClickable) btn else btn.parent
        }

        val descNodes = mutableListOf<AccessibilityNodeInfo>()
        collectSendImageButtons(root, descNodes)
        return descNodes.firstOrNull()
    }

    private fun collectSendImageButtons(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        val desc = node.contentDescription?.toString().orEmpty().lowercase()
        val text = node.text?.toString().orEmpty().lowercase()
        val cls = node.className?.toString().orEmpty()

        if ((desc == "send" || text == "send" || desc.contains("send message")) && (node.isClickable || cls.contains("ImageButton"))) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectSendImageButtons(child, out)
        }
    }

    private fun selectWhatsAppContact(searchText: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectContactCandidates(root, searchText, candidates)

        val best = candidates.maxByOrNull { scoreContactRow(it, searchText) } ?: return false
        val score = scoreContactRow(best, searchText)
        if (score < 0) return false

        Log.d(
            "BRO_EXECUTOR",
            "Auto-selecting contact row: score=$score text=${extractAggregateText(best)}"
        )
        return clickContactRowAvoidingAvatar(best)
    }

    private fun collectContactCandidates(
        node: AccessibilityNodeInfo,
        searchText: String,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        val cls = node.className?.toString().orEmpty()
        val isImage = cls.contains("ImageView", ignoreCase = true) ||
            cls.contains("ImageButton", ignoreCase = true)

        if (!node.isEditable && !isImage) {
            val nodeText = extractAggregateText(node)
            if (!nodeText.isNullOrBlank() && nodeText.contains(searchText, ignoreCase = true)) {
                val clickTarget = when {
                    node.isClickable -> node
                    node.parent?.isClickable == true -> node.parent
                    else -> null
                }
                if (clickTarget != null) out.add(clickTarget)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectContactCandidates(child, searchText, out)
        }
    }

    private fun scoreContactRow(node: AccessibilityNodeInfo, searchText: String): Int {
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("ImageView", ignoreCase = true)) return -1
        if (node.isEditable) return -1

        val text = extractAggregateText(node) ?: return -1

        if (text.contains("ask meta", ignoreCase = true) ||
            text.contains("meta ai", ignoreCase = true) ||
            text.contains("send message to meta", ignoreCase = true)) {
            return -1
        }

        if (!text.contains(searchText, ignoreCase = true)) return -1

        var score = 0
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.width() < 120 && rect.height() < 120) score -= 30
        if (rect.width() > 500) score += 15
        if (rect.height() in 80..300) score += 10
        return score
    }

    private fun extractAggregateText(node: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth > 4) return null

        val directText = node.text?.toString()?.trim()
        if (!directText.isNullOrBlank()) return directText

        val directDesc = node.contentDescription?.toString()?.trim()
        if (!directDesc.isNullOrBlank()) return directDesc

        val builder = StringBuilder()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childText = extractAggregateText(child, depth + 1)
            if (!childText.isNullOrBlank()) {
                if (builder.isNotEmpty()) builder.append(" ")
                builder.append(childText)
            }
        }
        return if (builder.isNotEmpty()) builder.toString() else null
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        val t = node.text?.toString()?.trim() ?: node.contentDescription?.toString()?.trim()
        if (!t.isNullOrBlank()) out.add(t)
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), out)
        }
    }

    private fun scroll(direction: String?): Boolean {
        val isUp = direction?.equals("UP", ignoreCase = true) == true
        val path = Path()
        val displayMetrics = service.resources.displayMetrics
        val startY = if (isUp) displayMetrics.heightPixels * 0.3f else displayMetrics.heightPixels * 0.7f
        val endY = if (isUp) displayMetrics.heightPixels * 0.7f else displayMetrics.heightPixels * 0.3f
        val centerX = displayMetrics.widthPixels * 0.5f

        path.moveTo(centerX, startY)
        path.lineTo(centerX, endY)

        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val builder = GestureDescription.Builder().addStroke(stroke)
        return service.dispatchGesture(builder.build(), null, null)
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val builder = GestureDescription.Builder().addStroke(stroke)
        return service.dispatchGesture(builder.build(), null, null)
    }

    private fun back(): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private fun home(): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }
}
