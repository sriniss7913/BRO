package com.example.bro.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.bro.executor.ActionExecutor
import com.example.bro.accessibility.ScreenReader
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class BroAccessibilityService : AccessibilityService() {

    companion object {
        var instance: BroAccessibilityService? = null
            private set
        @Volatile
        var screenshotOnlyModeEnabled: Boolean = false

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
    private val screenshotExecutor: ExecutorService = Executors.newSingleThreadExecutor()

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

        if (!screenshotOnlyModeEnabled) {
            Log.d(
                "BRO_SCREEN",
                "Event on ${root.packageName}, nodes=${root.childCount}"
            )
        }
    }

    override fun onInterrupt() {
        Log.d("BRO", "Accessibility Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()

        screenshotExecutor.shutdown()
        instance = null
    }

    /**
     * Returns the current root node.
     */
    fun getRoot(): AccessibilityNodeInfo? {
        return rootInActiveWindow
    }

    suspend fun captureScreenshotJpeg(): ByteArray? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null

        return suspendCancellableCoroutine { continuation ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val buffer = screenshot.hardwareBuffer
                            try {
                                val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                if (bitmap == null) {
                                    Log.w("BRO_VISION", "Screenshot could not be converted to a bitmap")
                                    if (continuation.isActive) continuation.resume(null)
                                    return
                                }

                                val jpeg = ByteArrayOutputStream()
                                val encoded = bitmap.compress(Bitmap.CompressFormat.JPEG, 75, jpeg)
                                bitmap.recycle()
                                if (continuation.isActive) {
                                    continuation.resume(if (encoded) jpeg.toByteArray() else null)
                                }
                            } catch (e: Exception) {
                                Log.e("BRO_VISION", "Screenshot encoding failed: ${e.message}", e)
                                if (continuation.isActive) continuation.resume(null)
                            } finally {
                                buffer.close()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w("BRO_VISION", "Screenshot capture failed with code=$errorCode")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("BRO_VISION", "Could not request screenshot: ${e.message}", e)
                if (continuation.isActive) continuation.resume(null)
            }
        }
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

    suspend fun executeAction(action: com.example.bro.model.Action): Boolean {

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
