package com.example.bro.executor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.bro.accessibility.NodeRepository
import com.example.bro.model.Action
import com.example.bro.model.ActionType
import kotlinx.coroutines.delay

class ActionExecutor(
    private val service: AccessibilityService
) {

    suspend fun execute(action: Action): Boolean {
        Log.d(
            "BRO_EXECUTOR",
            "Executing type=${action.type}, targetId=${action.targetId}, text=${action.text}, target=${action.target}"
        )

        val result = when (action.type) {
            ActionType.CLICK -> click(action)
            ActionType.TYPE -> type(action)
            ActionType.SEARCH -> search(action)
            ActionType.SEND_MESSAGE -> sendMessage()
            ActionType.TOGGLE_QUICK_SETTING -> toggleQuickSetting(
                action.target ?: action.text,
                action.desiredState
            )
            ActionType.SCROLL, ActionType.SWIPE -> scroll(action.text)
            ActionType.BACK -> back()
            ActionType.HOME -> home()
            ActionType.LOCK_SCREEN -> lockScreen()
            else -> false
        }

        Log.d("BRO_EXECUTOR", "Result=$result for ${action.type}")
        return result
    }

    private fun click(action: Action): Boolean {
        if (action.targetId == null && action.x != null && action.y != null) {
            return tapAt(action.x, action.y)
        }

        action.targetId?.let { id ->
            val node = NodeRepository.get(id)
            if (node != null) {
                if (NodeRepository.getRole(id) == com.example.bro.accessibility.UiCanonicalizer.Role.TEXT &&
                    !node.isClickable && node.parent?.isClickable != true
                ) {
                    Log.w("BRO_EXECUTOR", "Rejecting CLICK on non-clickable TEXT nodeId=$id")
                    return false
                }
                Log.d("BRO_EXECUTOR", "CLICK by ID=$id text=${node.text}")
                val resolved = resolveClickTarget(node)
                if (NodeRepository.getRole(id) == com.example.bro.accessibility.UiCanonicalizer.Role.CHAT_ROW &&
                    clickContactRowAvoidingAvatar(resolved)) return true
                if (clickNodeOrParent(resolved)) return true
            }
        }

        if (action.x != null && action.y != null && tapAt(action.x, action.y)) {
            return true
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

    private fun sendMessage(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val sendButton = findSendButton(root)
        if (sendButton == null) {
            Log.w("BRO_EXECUTOR", "SEND_MESSAGE: No Send button was exposed on the current screen.")
            return false
        }
        return clickNodeOrParent(sendButton)
    }

    private suspend fun toggleQuickSetting(
        requestedTile: String?,
        desiredState: Boolean?
    ): Boolean {
        val target = requestedTile?.trim()?.takeIf(String::isNotEmpty) ?: run {
            Log.w("BRO_EXECUTOR", "Quick setting action requires a tile label.")
            return false
        }
        if (target.contains("flash", ignoreCase = true) ||
            target.contains("torch", ignoreCase = true)
        ) {
            return toggleTorch(desiredState)
        }
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) {
            Log.w("BRO_EXECUTOR", "Could not open Quick Settings for '$target'.")
            return false
        }
        delay(800)

        var tile = awaitQuickSettingTile(target, timeoutMs = 2000L)
        if (tile == null) {
            Log.d("BRO_EXECUTOR", "Tile '$target' not found on page 1, swiping to page 2...")
            swipeHorizontal(fromRightToLeft = true)
            delay(600)
            tile = awaitQuickSettingTile(target, timeoutMs = 2000L)
        }

        if (tile != null && selectQuickSettingState(tile, target, desiredState)) {
            delay(500)
            dismissConfirmationDialogIfPresent()
            delay(400)
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return true
        }

        if (target.contains("mobile data", ignoreCase = true) || target.contains("wi-fi", ignoreCase = true)) {
            val internetTile = awaitQuickSettingTile("Internet", timeoutMs = 1000L)
            if (internetTile != null && (tapAtNode(internetTile) || clickNodeOrParent(internetTile))) {
                delay(800)
                val subTile = awaitQuickSettingTile(target, timeoutMs = 2000L)
                if (subTile != null && selectQuickSettingState(subTile, target, desiredState)) {
                    delay(500)
                    dismissConfirmationDialogIfPresent()
                    delay(300)
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    delay(300)
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    return true
                }
            }
        }

        Log.w("BRO_EXECUTOR", "Quick Settings tile '$target' was not found or could not be clicked.")
        return false
    }

    private fun tapAtNode(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            return tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        return false
    }

    private fun swipeHorizontal(fromRightToLeft: Boolean): Boolean {
        val metrics = service.resources.displayMetrics
        val path = Path()
        val y = metrics.heightPixels * 0.35f
        val startX = if (fromRightToLeft) metrics.widthPixels * 0.85f else metrics.widthPixels * 0.15f
        val endX = if (fromRightToLeft) metrics.widthPixels * 0.15f else metrics.widthPixels * 0.85f
        path.moveTo(startX, y)
        path.lineTo(endX, y)
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun dismissConfirmationDialogIfPresent(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val button1List = root.findAccessibilityNodeInfosByViewId("android:id/button1")
        if (button1List.isNotEmpty() && button1List[0].isVisibleToUser) {
            val btn = button1List[0]
            Log.d("BRO_EXECUTOR", "Dismissing dialog via android:id/button1 (${btn.text})")
            if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        val confirmPhrases = listOf("turn off", "turn on", "ok", "confirm", "agree", "yes")
        val matchingNodes = mutableListOf<AccessibilityNodeInfo>()
        collectClickableMatchingText(root, confirmPhrases, matchingNodes)
        val best = matchingNodes.firstOrNull()
        if (best != null) {
            Log.d("BRO_EXECUTOR", "Dismissing dialog via text '${best.text}'")
            if (best.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            return tapAtNode(best)
        }
        return false
    }

    private fun collectClickableMatchingText(
        node: AccessibilityNodeInfo,
        phrases: List<String>,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (!node.isVisibleToUser) return
        val text = (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty().trim().lowercase()
        if (phrases.any { text.equals(it, ignoreCase = true) || (text.contains(it) && text.length <= 15) }) {
            out.add(node)
            return
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickableMatchingText(child, phrases, out)
        }
    }

    private fun toggleTorch(enable: Boolean?): Boolean {
        if (enable == null) {
            Log.w("BRO_EXECUTOR", "Flashlight action requires the requested on/off state.")
            return false
        }
        if (service.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e("BRO_EXECUTOR", "Camera permission is required to toggle the flashlight.")
            return false
        }
        return try {
            val cameraManager = service.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cameraManager.setTorchMode(cameraId, enable)
            true
        } catch (e: Exception) {
            Log.e("BRO_EXECUTOR", "Could not set flashlight state to $enable.", e)
            false
        }
    }

    private fun lockScreen(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            Log.w("BRO_EXECUTOR", "Lock screen global action requires Android 9 or newer.")
            false
        }

    private fun selectQuickSettingState(
        tile: AccessibilityNodeInfo,
        label: String,
        desiredState: Boolean?
    ): Boolean {
        if (desiredState != null) {
            val currentState = readQuickSettingState(tile, label)
            if (currentState == desiredState) {
                Log.i("BRO_EXECUTOR", "Quick Settings tile '$label' is already in the requested state.")
                return true
            }
        }
        val tapOk = tapAtNode(tile)
        if (tapOk) {
            Log.d("BRO_EXECUTOR", "Toggled quick tile '$label' via physical tap")
            return true
        }
        return clickNodeOrParent(tile)
    }

    private fun readQuickSettingState(
        tile: AccessibilityNodeInfo,
        label: String
    ): Boolean? {
        var current: AccessibilityNodeInfo? = tile
        var combinedLabel = ""
        repeat(4) {
            val node = current ?: return@repeat
            if (node.isCheckable) return node.isChecked
            combinedLabel += " " + listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString()
            ).joinToString(" ")
            current = node.parent
        }
        val stateText = combinedLabel
            .replace(label, "", ignoreCase = true)
            .lowercase()
        return when {
            Regex("""\b(on|enabled|active|connected)\b""").containsMatchIn(stateText) -> true
            Regex("""\b(off|disabled|inactive|disconnected)\b""").containsMatchIn(stateText) -> false
            else -> null
        }
    }

    private suspend fun awaitQuickSettingTile(
        label: String,
        timeoutMs: Long
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = service.rootInActiveWindow
            if (root != null) {
                findQuickSettingTile(root, label)?.let { return it }
            }
            delay(250)
        }
        return null
    }

    private fun findQuickSettingTile(
        node: AccessibilityNodeInfo,
        requestedLabel: String
    ): AccessibilityNodeInfo? {
        if (!node.isVisibleToUser) return null
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findQuickSettingTile(child, requestedLabel)?.let { return it }
        }

        val label = listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString()
        ).joinToString(" ").replace(Regex("\\s+"), " ").trim()
        if (label.length <= requestedLabel.length + 60 &&
            label.contains(requestedLabel, ignoreCase = true)
        ) {
            return node
        }
        return null
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
        // If startNode has an immediate checkable/switch child, try clicking it directly
        for (i in 0 until startNode.childCount) {
            val child = startNode.getChild(i)
            if (child != null && (child.isCheckable || child.className?.toString()?.contains("Switch", true) == true)) {
                if (child.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }

        var node: AccessibilityNodeInfo? = startNode
        repeat(5) {
            val current = node ?: return@repeat
            if (current.isClickable || current.isCheckable) {
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

    private suspend fun search(action: Action): Boolean {
        val query = action.text?.takeIf(String::isNotBlank) ?: run {
            Log.w("BRO_EXECUTOR", "SEARCH: Query is blank.")
            return false
        }
        val target = action.targetId?.let(NodeRepository::get)
            ?: NodeRepository.firstByRole(com.example.bro.accessibility.UiCanonicalizer.Role.SEARCH)
        var inputNode = target?.takeIf { it.isEditable }

        if (inputNode == null && target != null) {
            Log.d("BRO_EXECUTOR", "SEARCH: Expanding collapsed search nodeId=${action.targetId}")
            if (!clickNodeOrParent(target)) {
                Log.w("BRO_EXECUTOR", "SEARCH: Could not open the search control.")
                return false
            }
            delay(600)
        }

        val root = service.rootInActiveWindow
        inputNode = inputNode
            ?: root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: findFirstEditableNode(root)

        if (inputNode == null) {
            Log.e("BRO_EXECUTOR", "SEARCH: No editable input appeared after opening the search control.")
            return false
        }

        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val setOk = resilientSetText(inputNode, query, action.x, action.y)
        
        if (setOk) {
            Log.d("BRO_EXECUTOR", "SEARCH: Successfully typed '$query'")
            delay(1500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val imeAccepted = inputNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                Log.d("BRO_EXECUTOR", "SEARCH: IME enter dispatched=$imeAccepted")
            }
            return true
        }

        Log.e("BRO_EXECUTOR", "SEARCH: Failed to set text on input field.")
        return false
    }

    private suspend fun type(action: Action): Boolean {
        if (action.text.isNullOrBlank()) {
            Log.w("BRO_EXECUTOR", "TYPE action rejected because text is blank.")
            return false
        }
        val typedText = action.text
        val targetId = action.targetId
        var node = if (targetId != null) NodeRepository.get(targetId) else null

        if ((node == null || !node.isEditable) && action.x != null && action.y != null) {
            if (!tapAt(action.x, action.y)) return false
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            node = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: return false
        }

        if (node == null || !node.isEditable) {
            val root = service.rootInActiveWindow
            node = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: if (targetId != null) findFirstEditableNode(root) else null
        }

        if (node == null) return false

        val nodeDesc = (node.contentDescription?.toString() ?: node.text?.toString() ?: "").lowercase()
        val hintText = node.hintText?.toString()?.lowercase().orEmpty()
        val nodeRole = targetId?.let(NodeRepository::getRole)
        val isSearchInput = nodeRole == com.example.bro.accessibility.UiCanonicalizer.Role.SEARCH ||
            nodeDesc.contains("search") ||
            hintText.contains("search") ||
            service.rootInActiveWindow?.packageName?.toString()?.let {
                it.contains("maps", ignoreCase = true) ||
                    it.contains("settings", ignoreCase = true) ||
                    it.contains("google", ignoreCase = true)
            } == true
        val isChatRoomInput = nodeDesc.contains("type a message") || nodeDesc.contains("message_input") ||
                hintText.contains("type a message") || hintText.contains("message")

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        val setOk = resilientSetText(node, typedText, action.x, action.y)
        if (setOk) {
            val activePkg = service.rootInActiveWindow?.packageName?.toString()
            if (isSearchInput && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                delay(400)
                val imeAccepted = node.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                )
                Log.d("BRO_EXECUTOR", "TYPE: search IME enter dispatched=$imeAccepted")
            }
            // ONLY auto-select contact if we typed in a Search bar on Main Screen, NOT inside a chat room!
            if (typedText.length >= 2 && activePkg != null && !isChatRoomInput) {
                try {
                    delay(800)
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

        return false
    }

    /**
     * Google ARTEMIS Multi-Tier Resilient Text Entry Pipeline:
     * Tier 1: Direct ACTION_SET_TEXT
     * Tier 2: Clipboard Paste Fallback (ClipboardManager + ACTION_PASTE)
     * Tier 3: Tap-to-Focus + Delay + ACTION_PASTE / Retry
     */
    suspend fun resilientSetText(
        node: AccessibilityNodeInfo,
        text: String,
        fallbackX: Float? = null,
        fallbackY: Float? = null
    ): Boolean {
        // Prepare Tier 1 arguments
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }

        // Tier 1: Direct Accessibility ACTION_SET_TEXT
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val tier1Success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (tier1Success && verifyTextApplied(node, text)) {
            Log.d("BRO_EXECUTOR", "Text entry succeeded via Tier 1 (ACTION_SET_TEXT)")
            return true
        }
        Log.w("BRO_EXECUTOR", "Tier 1 text entry failed or unverified. Trying Tier 2 (Clipboard Paste)...")

        // Tier 2: Clipboard Paste Fallback
        val clipSuccess = copyToClipboard(text)
        if (clipSuccess) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (pasteOk && verifyTextApplied(node, text)) {
                Log.d("BRO_EXECUTOR", "Text entry succeeded via Tier 2 (ACTION_PASTE)")
                return true
            }

            // Try paste on currently focused input node
            val focused = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused != node) {
                if (focused.performAction(AccessibilityNodeInfo.ACTION_PASTE) && verifyTextApplied(focused, text)) {
                    Log.d("BRO_EXECUTOR", "Text entry succeeded via Tier 2 on focused node")
                    return true
                }
            }
        }
        Log.w("BRO_EXECUTOR", "Tier 2 paste failed. Trying Tier 3 (Physical Tap-to-Focus + Paste)...")

        // Tier 3: Physical Tap-to-Focus + Paste Fallback
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val tapX = fallbackX ?: if (bounds.width() > 0) bounds.centerX().toFloat() else null
        val tapY = fallbackY ?: if (bounds.height() > 0) bounds.centerY().toFloat() else null

        if (tapX != null && tapY != null) {
            tapAt(tapX, tapY)
            delay(250)
            val reFocused = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: node
            if (reFocused.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                Log.d("BRO_EXECUTOR", "Text entry succeeded via Tier 3 (Tap + ACTION_PASTE)")
                return true
            }
            if (reFocused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                Log.d("BRO_EXECUTOR", "Text entry succeeded via Tier 3 (Tap + ACTION_SET_TEXT)")
                return true
            }
        }

        return false
    }

    private fun copyToClipboard(text: String): Boolean {
        return try {
            val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                val clip = ClipData.newPlainText("bro_input", text)
                clipboard.setPrimaryClip(clip)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("BRO_EXECUTOR", "Failed to set clipboard: ${e.message}")
            false
        }
    }

    private fun verifyTextApplied(node: AccessibilityNodeInfo, expectedText: String): Boolean {
        val currentText = node.text?.toString().orEmpty()
        if (currentText.contains(expectedText, ignoreCase = true)) return true
        // If node text wasn't updated in-memory yet or is password/masked, check if non-blank
        return currentText.isNotBlank() || node.isFocused
    }

    private fun findFirstEditableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isVisibleToUser && root.isEditable) return root
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
        val resourceId = node.viewIdResourceName?.substringAfterLast("/")?.lowercase().orEmpty()

        if ((desc == "send" || text == "send" || desc.contains("send message") ||
                resourceId == "send" || resourceId.contains("send_button")) &&
            (node.isClickable || cls.contains("ImageButton") || resourceId.isNotBlank())
        ) {
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
        val metrics = service.resources.displayMetrics
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 ||
            x >= metrics.widthPixels || y >= metrics.heightPixels) {
            Log.w("BRO_EXECUTOR", "Skipping tap — out-of-bounds coordinates ($x, $y)")
            return false
        }
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
