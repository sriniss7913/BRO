package com.example.bro.executor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.bro.MainActivity
import com.example.bro.planner.QuickSettingRequest
import kotlinx.coroutines.delay

object DirectSettingsExecutor {

    private const val TAG = "DirectSettings"

    fun canHandleDirectIntent(tileLabel: String): Boolean {
        return when (tileLabel) {
            "Location", "Wi-Fi", "Mobile data", "Bluetooth", "Hotspot",
            "Airplane mode", "Do Not Disturb", "Dark mode" -> true
            else -> false
        }
    }

    suspend fun execute(
        context: Context,
        service: AccessibilityService,
        request: QuickSettingRequest
    ): Boolean {
        val intentAction = getIntentAction(request.tileLabel) ?: run {
            Log.w(TAG, "No direct intent action for ${request.tileLabel}")
            return false
        }

        Log.d(TAG, "Launching direct intent for ${request.tileLabel}: $intentAction")
        val intent = Intent(intentAction).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start settings activity for $intentAction: ${e.message}")
            return false
        }

        // Wait for Settings activity to load
        var root: AccessibilityNodeInfo? = null
        val deadline = System.currentTimeMillis() + 2500L
        while (System.currentTimeMillis() < deadline) {
            delay(250)
            val current = service.rootInActiveWindow
            if (current != null && current.packageName?.contains("settings", ignoreCase = true) == true) {
                root = current
                break
            }
        }
        if (root == null) {
            root = service.rootInActiveWindow ?: return false
        }

        // Give screen a moment to populate items
        delay(400)
        root = service.rootInActiveWindow ?: root

        val switchNode = findSettingSwitch(root, request.tileLabel)
        if (switchNode == null) {
            Log.w(TAG, "Could not find switch node for ${request.tileLabel}")
            return false
        }

        val currentChecked = switchNode.isChecked
        val targetChecked = request.enabled ?: !currentChecked

        if (currentChecked == targetChecked && request.enabled != null) {
            Log.i(TAG, "${request.tileLabel} is already in the requested state: $currentChecked")
            closeSettingsAndReturnToApp(context, service)
            return true
        }

        Log.d(TAG, "Toggling switch for ${request.tileLabel} from $currentChecked to $targetChecked")
        val toggleSuccess = clickSwitchNode(service, switchNode)
        if (!toggleSuccess) {
            Log.w(TAG, "Failed to toggle switch for ${request.tileLabel}")
            return false
        }

        // Handle any confirmation popup (e.g. "Turn off mobile data?")
        delay(500)
        val dialogRoot = service.rootInActiveWindow
        if (dialogRoot != null) {
            dismissConfirmationDialogIfPresent(service, dialogRoot)
        }

        delay(400)
        closeSettingsAndReturnToApp(context, service)
        return true
    }

    private fun getIntentAction(tileLabel: String): String? = when (tileLabel) {
        "Location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
        "Wi-Fi" -> Settings.ACTION_WIFI_SETTINGS
        "Mobile data" -> Settings.ACTION_DATA_ROAMING_SETTINGS
        "Bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
        "Hotspot" -> "android.settings.TETHER_SETTINGS"
        "Airplane mode" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
        "Do Not Disturb" -> Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS
        "Dark mode" -> Settings.ACTION_DISPLAY_SETTINGS
        else -> null
    }

    private fun getTargetKeywords(tileLabel: String): List<String> = when (tileLabel) {
        "Location" -> listOf("location", "use location", "location access", "access location")
        "Wi-Fi" -> listOf("wi-fi", "use wi-fi", "wifi", "wireless")
        "Mobile data" -> listOf("mobile data", "cellular data", "mobile network")
        "Bluetooth" -> listOf("bluetooth", "use bluetooth")
        "Hotspot" -> listOf("mobile hotspot", "portable hotspot", "personal hotspot", "hotspot", "tethering")
        "Airplane mode" -> listOf("airplane mode", "flight mode")
        "Do Not Disturb" -> listOf("do not disturb", "turn on now")
        "Dark mode" -> listOf("dark mode", "dark theme", "night mode")
        else -> listOf(tileLabel.lowercase())
    }

    private fun findSettingSwitch(root: AccessibilityNodeInfo, tileLabel: String): AccessibilityNodeInfo? {
        val checkableNodes = mutableListOf<AccessibilityNodeInfo>()
        collectCheckableNodes(root, checkableNodes)

        if (checkableNodes.isEmpty()) {
            Log.w(TAG, "No checkable switch nodes found on settings page")
            return null
        }

        if (checkableNodes.size == 1) {
            Log.d(TAG, "Single master switch found on settings page: ${checkableNodes[0]}")
            return checkableNodes[0]
        }

        val keywords = getTargetKeywords(tileLabel)

        // Find the checkable switch whose row/parent/sibling contains target keywords
        for (node in checkableNodes) {
            if (rowContainsKeywords(node, keywords)) {
                return node
            }
        }

        // Fallback: check switch with text or content description
        for (node in checkableNodes) {
            val combined = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .joinToString(" ").lowercase()
            if (keywords.any { combined.contains(it) }) {
                return node
            }
        }

        // Default to the first switch
        return checkableNodes.firstOrNull()
    }

    private fun collectCheckableNodes(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (!node.isVisibleToUser) return

        val cls = node.className?.toString().orEmpty()
        val isSwitch = node.isCheckable || cls.contains("Switch", ignoreCase = true)
        if (isSwitch) {
            out.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectCheckableNodes(child, out)
        }
    }

    private fun rowContainsKeywords(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(4) {
            val p = current ?: return false
            val textList = mutableListOf<String>()
            collectNodeSubtreeText(p, textList)
            val rowText = textList.joinToString(" ").lowercase()
            if (keywords.any { rowText.contains(it) }) {
                return true
            }
            current = p.parent
        }
        return false
    }

    private fun collectNodeSubtreeText(node: AccessibilityNodeInfo, out: MutableList<String>) {
        node.text?.toString()?.takeIf(String::isNotBlank)?.let(out::add)
        node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(out::add)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodeSubtreeText(child, out)
        }
    }

    private fun clickSwitchNode(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        var parent = node.parent
        repeat(3) {
            val p = parent ?: return@repeat
            if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = p.parent
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            return tapAt(service, rect.centerX().toFloat(), rect.centerY().toFloat())
        }

        return false
    }

    private fun dismissConfirmationDialogIfPresent(
        service: AccessibilityService,
        dialogRoot: AccessibilityNodeInfo
    ): Boolean {
        val confirmPhrases = listOf("turn off", "turn on", "ok", "confirm", "agree", "yes")

        // First look for standard AlertDialog button1
        val button1List = dialogRoot.findAccessibilityNodeInfosByViewId("android:id/button1")
        if (button1List.isNotEmpty() && button1List[0].isVisibleToUser) {
            val btn = button1List[0]
            Log.d(TAG, "Dismissing confirmation dialog via android:id/button1 (${btn.text})")
            if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }

        // Search for node matching confirmation text
        val matchingNodes = mutableListOf<AccessibilityNodeInfo>()
        collectClickableMatchingText(dialogRoot, confirmPhrases, matchingNodes)
        val best = matchingNodes.firstOrNull()
        if (best != null) {
            Log.d(TAG, "Dismissing confirmation dialog via matched text button '${best.text}'")
            if (best.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            val rect = Rect()
            best.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                return tapAt(service, rect.centerX().toFloat(), rect.centerY().toFloat())
            }
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

    private fun closeSettingsAndReturnToApp(context: Context, service: AccessibilityService) {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        try {
            val appIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            context.startActivity(appIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not bring MainActivity back to front: ${e.message}")
        }
    }

    private fun tapAt(service: AccessibilityService, x: Float, y: Float): Boolean {
        val metrics = service.resources.displayMetrics
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 ||
            x >= metrics.widthPixels || y >= metrics.heightPixels
        ) {
            return false
        }
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }
}
