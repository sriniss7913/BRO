package com.example.bro.executor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.util.Log
import com.example.bro.model.Action
import com.example.bro.model.ActionType
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Executes Vision actions using screenshot coordinates and system gestures.
 * It deliberately does not inspect accessibility nodes or read the UI tree.
 */
class VisionActionExecutor(
    private val context: Context,
    private val service: AccessibilityService
) {
    suspend fun execute(action: Action): Boolean {
        Log.d("BRO_VISION", "Executing screenshot-based ${action.type}")
        return when (action.type) {
            ActionType.CLICK -> withCoordinates(action) { x, y ->
                tap(x, y)
            }
            ActionType.TYPE -> typeAt(action)
            ActionType.SCROLL, ActionType.SWIPE -> scroll(action.text)
            ActionType.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            ActionType.HOME -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            else -> false
        }
    }

    private suspend fun typeAt(action: Action): Boolean {
        val text = action.text?.takeIf(String::isNotEmpty) ?: return false
        return withCoordinates(action) { x, y ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("BRO Vision input", text))
            if (!tap(x, y)) {
                false
            } else {
                kotlinx.coroutines.delay(250)
                longPress(x, y)
            }
        }
    }

    private suspend fun withCoordinates(action: Action, block: suspend (Float, Float) -> Boolean): Boolean {
        val x = action.x ?: return false
        val y = action.y ?: return false
        val metrics = context.resources.displayMetrics
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 ||
            x >= metrics.widthPixels || y >= metrics.heightPixels
        ) {
            Log.w("BRO_VISION", "Ignoring out-of-bounds screenshot coordinates ($x, $y)")
            return false
        }
        return block(x, y)
    }

    private suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        return dispatch(path, 100L)
    }

    private suspend fun longPress(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        return dispatch(path, 850L)
    }

    private suspend fun scroll(direction: String?): Boolean {
        val metrics = context.resources.displayMetrics
        val isUp = direction.equals("UP", ignoreCase = true)
        val startY = if (isUp) metrics.heightPixels * 0.3f else metrics.heightPixels * 0.7f
        val endY = if (isUp) metrics.heightPixels * 0.7f else metrics.heightPixels * 0.3f
        val path = Path().apply {
            moveTo(metrics.widthPixels * 0.5f, startY)
            lineTo(metrics.widthPixels * 0.5f, endY)
        }
        return dispatch(path, 300L)
    }

    private suspend fun dispatch(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { continuation ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val accepted = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null
            )
            if (!accepted && continuation.isActive) continuation.resume(false)
        }
}
