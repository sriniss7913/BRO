package com.example.bro.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.bro.executor.ActionExecutor
import com.example.bro.accessibility.ScreenReader

class BroAccessibilityService : AccessibilityService() {

    companion object {
        var instance: BroAccessibilityService? = null
            private set

        fun isAccessibilityEnabled(context: android.content.Context): Boolean {
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(context.packageName)
        }
    }

    private lateinit var actionExecutor: ActionExecutor
    private val screenReader = ScreenReader()

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this

        actionExecutor = ActionExecutor(this)

        Log.d("BRO", "Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        if (event == null) return

        val root = rootInActiveWindow ?: return

        Log.d(
            "BRO_EVENT",
            "Event=${event.eventType}, Package=${root.packageName}"
        )

        // Ignore our own app
        if (root.packageName == packageName)
            return

        // Record user manual actions during Demonstration Mode
        if (com.example.bro.agent.BroTrainingManager.isRecordingDemonstration) {
            val node = event.source
            val nodeText = (
                node?.text?.toString() ?:
                node?.contentDescription?.toString() ?:
                event.text?.joinToString(" ") ?:
                event.contentDescription?.toString() ?: ""
            ).trim()

            if (nodeText.isNotBlank()) {
                when (event.eventType) {
                    AccessibilityEvent.TYPE_VIEW_CLICKED,
                    AccessibilityEvent.TYPE_VIEW_SELECTED,
                    AccessibilityEvent.TYPE_VIEW_FOCUSED,
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                        com.example.bro.agent.BroTrainingManager.recordManualAction("CLICK", nodeText)
                    }
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                        com.example.bro.agent.BroTrainingManager.recordManualAction("TYPE", "Input Field", nodeText)
                    }
                }
            }
        }

        val screen = screenReader.read(root)

        Log.d(
            "BRO_SCREEN",
            screenReader.toPrompt(screen)
        )
    }

    override fun onInterrupt() {
        Log.d("BRO", "Accessibility Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()

        instance = null
    }

    /**
     * Returns the current root node.
     */
    fun getRoot(): AccessibilityNodeInfo? {
        return rootInActiveWindow
    }

    /**
     * Returns the current screen formatted for the AI planner.
     */
    fun getCurrentScreenText(): String {

        val root = rootInActiveWindow
            ?: return "No active screen"

        val screen = screenReader.read(root)

        return screenReader.toPrompt(screen)
    }

    fun executeAction(action: com.example.bro.model.Action): Boolean {

        if (!::actionExecutor.isInitialized) {
            Log.d(
                "BRO_EXECUTOR",
                "ActionExecutor not initialized"
            )
            return false
        }

        Log.d(
            "BRO_EXECUTOR",
            "Executing ${action.type} targetId=${action.targetId}"
        )

        return actionExecutor.execute(action)
    }

} // <-- BroAccessibilityService ends HERE
