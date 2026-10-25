package com.example.bro.executor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.bro.planner.GroqPlanner
import kotlinx.coroutines.delay

object NotificationReader {

    private val NOTIFICATION_INTENT_REGEX = Regex(
        """\b(notifications?|notification\s*bar|notification\s*shade|notification\s*dropdown)\b""",
        RegexOption.IGNORE_CASE
    )
    private val ACTION_KEYWORDS_REGEX = Regex(
        """\b(read|check|summarize|summary|what\s+are|show|view|get|any|list)\b""",
        RegexOption.IGNORE_CASE
    )

    fun isNotificationGoal(goal: String): Boolean {
        val lower = goal.lowercase().replace(Regex("\\s+"), " ").trim()
        return NOTIFICATION_INTENT_REGEX.containsMatchIn(lower) && ACTION_KEYWORDS_REGEX.containsMatchIn(lower)
    }

    suspend fun readAndSummarizeNotifications(
        service: AccessibilityService,
        planner: GroqPlanner?,
        goal: String
    ): Pair<Boolean, String> {
        Log.d("NotificationReader", "Opening notification shade...")
        val opened = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        if (!opened) {
            Log.w("NotificationReader", "Failed to dispatch GLOBAL_ACTION_NOTIFICATIONS")
            return Pair(false, "⚠️ Could not open the notification dropdown.")
        }

        // Wait for shade to animate open
        delay(900)

        val extracted = mutableListOf<String>()
        val seenSet = mutableSetOf<String>()

        fun harvestCurrentScreen() {
            val root = service.rootInActiveWindow ?: return
            collectNotificationTexts(root, extracted, seenSet)
        }

        harvestCurrentScreen()

        // If list is short, try a quick swipe down in the shade to reveal more notifications
        if (extracted.size < 3) {
            scrollDownShade(service)
            delay(600)
            harvestCurrentScreen()
        }

        // Close notification shade cleanly
        delay(300)
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        delay(400)

        if (extracted.isEmpty()) {
            return Pair(true, "🔔 You don't have any new notifications right now.")
        }

        Log.d("NotificationReader", "Harvested ${extracted.size} notifications: $extracted")

        // Summarize using LLM if available, else formatted bullet points
        val summary = if (planner != null) {
            try {
                val llmSummary = planner.summarizeScreenOutcome(
                    goal = goal,
                    screenTexts = extracted,
                    appName = "Notifications"
                )
                if (llmSummary.isNotBlank()) "🔔 **Notifications Summary:**\n$llmSummary" else null
            } catch (e: Exception) {
                Log.w("NotificationReader", "LLM notification summarization failed: ${e.message}")
                null
            }
        } else null

        val finalText = summary ?: run {
            "🔔 **Notifications:**\n" + extracted.take(8).joinToString("\n• ", prefix = "• ")
        }

        return Pair(true, finalText)
    }

    private fun collectNotificationTexts(
        node: AccessibilityNodeInfo,
        out: MutableList<String>,
        seen: MutableSet<String>,
        depth: Int = 0
    ) {
        if (!node.isVisibleToUser) return

        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        val clsName = node.className?.toString().orEmpty()

        // Skip Quick Settings / status bar header controls
        if (viewId.contains("quick_settings") || viewId.contains("qs_") ||
            viewId.contains("battery") || viewId.contains("clock") ||
            viewId.contains("brightness") || clsName.contains("QSTileView")
        ) {
            return
        }

        // Check if this node is a notification card / row
        val isNotificationContainer = viewId.contains("notification") ||
            viewId.contains("row") ||
            viewId.contains("expandable") ||
            clsName.contains("Notification")

        if (isNotificationContainer) {
            val cardTexts = mutableListOf<String>()
            extractTextSubtree(node, cardTexts)
            val filtered = cardTexts.filter { isValidNotificationText(it) }
            if (filtered.isNotEmpty()) {
                val combined = filtered.distinct().joinToString(" - ")
                if (combined.length > 3 && seen.add(combined)) {
                    out.add(combined)
                }
            }
            return // Don't recurse into child rows of this container
        }

        // Also extract direct texts from nodes if not encapsulated in explicit container
        val directText = node.text?.toString()?.trim()
        if (!directText.isNullOrBlank() && isValidNotificationText(directText)) {
            val subtext = node.contentDescription?.toString()?.trim()
            val entry = if (!subtext.isNullOrBlank() && subtext != directText && isValidNotificationText(subtext)) {
                "$directText: $subtext"
            } else {
                directText
            }
            if (seen.add(entry)) {
                out.add(entry)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNotificationTexts(child, out, seen, depth + 1)
        }
    }

    private fun extractTextSubtree(node: AccessibilityNodeInfo, out: MutableList<String>) {
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrBlank()) out.add(t)
        val d = node.contentDescription?.toString()?.trim()
        if (!d.isNullOrBlank() && d != t) out.add(d)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractTextSubtree(child, out)
        }
    }

    private fun isValidNotificationText(text: String): Boolean {
        if (text.length <= 1) return false
        val lower = text.lowercase()
        // Filter out system shade clutter
        val blacklist = setOf(
            "clear all", "notification settings", "manage", "silent notifications",
            "alerting notifications", "conversations", "history", "device control",
            "media output", "android system", "system ui", "done", "reply", "mark as read",
            "delete", "mute", "turn on", "turn off", "settings", "pause", "resume"
        )
        if (lower in blacklist) return false
        // Skip bare timestamps, battery percentage, signal indicators
        if (Regex("""^\d{1,3}%$""").matches(lower)) return false
        if (Regex("""^\d{1,2}:\d{2}(\s*(am|pm))?$""").matches(lower)) return false
        return true
    }

    private fun scrollDownShade(service: AccessibilityService): Boolean {
        val metrics = service.resources.displayMetrics
        val path = Path()
        val centerX = metrics.widthPixels * 0.5f
        val startY = metrics.heightPixels * 0.65f
        val endY = metrics.heightPixels * 0.35f
        path.moveTo(centerX, startY)
        path.lineTo(centerX, endY)
        val stroke = GestureDescription.StrokeDescription(path, 0, 250)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }
}
