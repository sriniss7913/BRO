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

        // Auto-recovery: If we landed in WhatsApp Contact Profile / Photo screen accidentally, press BACK and skip this action
        // Only trigger on keywords that EXCLUSIVELY appear on the Profile Info page
        // (NOT "business account" which appears in normal chat headers for business contacts)
        val activeRoot = service.rootInActiveWindow
        if (activeRoot?.packageName?.toString() == "com.whatsapp") {
            val fullText = mutableListOf<String>()
            collectText(activeRoot, fullText)
            val joined = fullText.joinToString(" ").lowercase()
            val isProfileScreen = (joined.contains("disappearing messages") || joined.contains("chat lock") ||
                joined.contains("media visibility")) &&
                !joined.contains("type a message")
            val isMetaAiScreen = (joined.contains("send message to meta ai") || joined.contains("back meta ai")) &&
                !joined.contains("type a message")
            if (isProfileScreen || isMetaAiScreen) {
                Log.d("BRO_EXECUTOR", "Auto-escaping WhatsApp Profile / Meta AI screen — pressing BACK")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                try { Thread.sleep(1000) } catch (e: Exception) {}
                return true // Skip this bad action, let next LLM step see the chat screen
            }
        }

        val result = when (action.type) {
            ActionType.CLICK -> click(action)
            ActionType.TYPE -> type(action)
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

    /** Walk up from ImageView / tiny nodes to the actual clickable row. */
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
        // Guard: reject taps with negative, zero, or off-screen coordinates
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

    /** Tap the right side of wide rows to avoid WhatsApp profile-picture avatars on the left. */
    private fun clickContactRowAvoidingAvatar(node: AccessibilityNodeInfo): Boolean {
        var targetNode = node
        val rect = Rect()
        targetNode.getBoundsInScreen(rect)

        // Walk up parents if targetNode is narrow (e.g. avatar image or child text node)
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

    private fun type(action: Action): Boolean {
        val targetId = action.targetId
        var node = if (targetId != null) NodeRepository.get(targetId) else null

        if (node == null || !node.isEditable) {
            val root = service.rootInActiveWindow
            node = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFirstEditableNode(root)
        }

        if (node == null) return false

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
            if (typedText.length >= 2 && activePkg != null) {
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
        if ((desc.contains("send") || desc.contains("sms")) && (node.isClickable || node.parent?.isClickable == true)) {
            out.add(if (node.isClickable) node else node.parent!!)
            return
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
        if (!text.contains(searchText, ignoreCase = true)) return -1

        var score = 0
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.width() < 120 && rect.height() < 120) score -= 30
        if (rect.width() > 500) score += 15
        if (rect.height() in 80..300) score += 10
        if (node.isClickable) score += 10

        when {
            text.equals(searchText, ignoreCase = true) -> score += 25
            text.startsWith(searchText, ignoreCase = true) &&
                text.length <= searchText.length + 20 -> score += 18
        }

        if (cls.contains("RelativeLayout") || cls.contains("LinearLayout")) score += 5

        return score
    }

    private fun extractAggregateText(node: AccessibilityNodeInfo): String? {
        val directText = node.text?.toString()?.trim()
        if (!directText.isNullOrBlank()) return directText

        val directDesc = node.contentDescription?.toString()?.trim()
        if (!directDesc.isNullOrBlank()) return directDesc

        val builder = StringBuilder()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childText = child.text?.toString()?.trim()
                ?: child.contentDescription?.toString()?.trim()
            if (!childText.isNullOrBlank()) {
                if (builder.isNotEmpty()) builder.append(" ")
                builder.append(childText)
            }
        }
        return if (builder.isNotEmpty()) builder.toString() else null
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 100)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, null, null)
        }
        return false
    }

    private fun scroll(direction: String?): Boolean {
        // Guard: If we're on the notification shade / system UI, press BACK to dismiss it and abort scroll
        val activePkgNow = service.rootInActiveWindow?.packageName?.toString()
        if (activePkgNow == "com.android.systemui") {
            Log.w("BRO_EXECUTOR", "Detected notification shade open! Pressing BACK to close it.")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            try { Thread.sleep(800) } catch (e: Exception) {}
            return true
        }

        val isDown = direction?.equals("DOWN", ignoreCase = true) ?: true
        val root = service.rootInActiveWindow ?: return false

        val scrollableNode = findScrollableNode(root)
        if (scrollableNode != null) {
            val scrollAction = if (isDown) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (scrollableNode.performAction(scrollAction)) return true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val displayMetrics = service.resources.displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels

            val startX = (width / 2).toFloat()
            // Safe bounds: keep swipe gesture away from the very top of screen
            // to avoid accidentally triggering the notification shade pull-down
            val safeTopBound = height * 0.35f
            val startY = if (isDown) (height * 0.70f) else safeTopBound
            val endY = if (isDown) safeTopBound else (height * 0.70f)

            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(startX, endY)
            }

            val stroke = GestureDescription.StrokeDescription(path, 0, 350)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, null, null)
        }

        return false
    }


    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val found = findScrollableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun back(): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private fun home(): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        val t = node.text?.toString()?.trim() ?: node.contentDescription?.toString()?.trim()
        if (!t.isNullOrEmpty()) out.add(t)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out)
        }
    }
}
