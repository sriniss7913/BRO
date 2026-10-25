package com.example.bro.executor

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.bro.AppLauncher
import com.example.bro.Message
import com.example.bro.accessibility.BroAccessibilityService
import com.example.bro.returnToBroApp
import kotlinx.coroutines.delay

object TaskPipelineExecutor {

    // Toggle for Fast Path Direct Pipeline (Set to false to route 100% of requests through LLM Planner)
    var ENABLE_FAST_PATH = false

    suspend fun executePipeline(
        context: Context,
        goalStr: String,
        messages: MutableList<Message>
    ): Boolean {
        if (!ENABLE_FAST_PATH) return false

        val lowerGoal = goalStr.lowercase().trim()

        val targetApp = when {
            "youtube" in lowerGoal -> "YouTube"
            "chrome" in lowerGoal -> "Chrome"
            "instagram" in lowerGoal -> "Instagram"
            "zomato" in lowerGoal -> "Zomato"
            "swiggy" in lowerGoal -> "Swiggy"
            "calculator" in lowerGoal -> "Calculator"
            "settings" in lowerGoal -> "Settings"
            else -> null
        }

        val isWhatsAppGoal = lowerGoal.contains("whatsapp") ||
            (lowerGoal.contains("message") || lowerGoal.contains("chat"))

        val handled = when {
            isWhatsAppGoal && (lowerGoal.contains("send") || lowerGoal.contains("text") || lowerGoal.contains("saying")) ->
                executeWhatsAppMessaging(context, goalStr, messages)
            isWhatsAppGoal && (lowerGoal.contains("check") || lowerGoal.contains("read") || lowerGoal.contains("recent") || lowerGoal.contains("chats") || lowerGoal.contains("from ")) ->
                executeWhatsAppRead(context, goalStr, messages)
            targetApp != null && (lowerGoal.startsWith("open") || lowerGoal.startsWith("launch")) ->
                executeAppOpen(context, targetApp, messages)
            else -> false
        }

        if (handled) {
            com.example.bro.util.ExecutionLogger.log(
                taskGoal = goalStr,
                stepNumber = 1,
                appPackage = targetApp ?: "com.whatsapp",
                screenPrompt = "[Direct Pipeline Execution - Standard Automation Pattern]",
                userPromptSent = goalStr,
                rawLlmResponse = "[Bypassed LLM - Handled by Deterministic Fast-Path Pipeline]",
                parsedAction = "PIPELINE_EXECUTE (Fast Path)",
                tokenUsage = "0 tokens (Local Execution)",
                androidCommandExecuted = if (isWhatsAppGoal) {
                    "launch WhatsApp; ACTION_CLICK(search); ACTION_SET_TEXT(contact); ACTION_CLICK(contact result); ACTION_SET_TEXT(message); ACTION_CLICK(Send)"
                } else {
                    "launch app '$targetApp' with Android launch intent"
                },
                executionResult = "Deterministic pipeline completed successfully."
            )
        }

        return handled
    }

    private suspend fun executeWhatsAppMessaging(
        context: Context,
        goalStr: String,
        messages: MutableList<Message>
    ): Boolean {
        val service = BroAccessibilityService.instance ?: return false

        val (contactName, messageText) = parseWhatsAppParams(goalStr)
        messages.add(Message("⚡ B.R.O.: Opening WhatsApp for $contactName...", false))

        // Launch WhatsApp to its main screen (clear any sub-screen)
        launchApp(context, "WhatsApp")
        delay(2500)

        val root = service.getRoot() ?: return false

        // Always search for the contact via the search bar — never rely on main screen visibility
        messages.add(Message("🔍 Searching for '$contactName'...", false))
        val searchNode = findSearchNode(root)

        if (searchNode != null) {
            val target = if (searchNode.isClickable) searchNode else searchNode.parent
            target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(1000)

            val searchRoot = service.getRoot() ?: return false
            val inputNode = findFirstEditableOrClickableInput(searchRoot) ?: searchNode
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, contactName)
            }
            inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            delay(1800)

            // Tap the matching contact in search results
            val resultRoot = service.getRoot() ?: searchRoot
            val clicked = clickContactRow(resultRoot, contactName)
            if (!clicked) {
                // Fallback: tap first result row
                clickFirstResultRow(resultRoot)
            }
            delay(1500)
        } else {
            messages.add(Message("⚠️ Could not find WhatsApp search bar. Trying to find contact on screen...", false))
            val mainRoot = service.getRoot() ?: return false
            val clicked = clickContactRow(mainRoot, contactName)
            if (!clicked) {
                messages.add(Message("❌ Contact '$contactName' not found on WhatsApp.", false))
                returnToBroApp(context)
                return false
            }
            delay(1500)
        }

        // Now inside the chat — type message and send
        val inChatRoot = service.getRoot() ?: root
        val chatInputNode = findChatInputNode(inChatRoot)
        if (chatInputNode != null) {
            chatInputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(500)
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, messageText)
            chatInputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            delay(800)

            // Find and click the send button
            val sendRoot = service.getRoot() ?: inChatRoot
            val sendBtn = findSendButton(sendRoot)
            if (sendBtn != null) {
                sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                delay(500)
                messages.add(Message("✅ Sent \"$messageText\" to $contactName on WhatsApp!", false))
                returnToBroApp(context)
                return true
            } else {
                messages.add(Message("❌ Could not find Send button. Message typed but not sent.", false))
                returnToBroApp(context)
                return false
            }
        } else {
            messages.add(Message("❌ Opened chat for '$contactName' but could not find message input.", false))
            returnToBroApp(context)
            return false
        }
    }


    private suspend fun executeWhatsAppRead(
        context: Context,
        goalStr: String,
        messages: MutableList<Message>
    ): Boolean {
        val service = BroAccessibilityService.instance ?: return false
        val targetContact = extractContactForRead(goalStr)

        if (targetContact != null) {
            return executeWhatsAppContactRead(context, targetContact, messages)
        }

        messages.add(Message("⚡ B.R.O. Direct Pipeline: Inspecting WhatsApp recent chats...", false))

        launchApp(context, "WhatsApp")
        delay(2000)

        val root = service.getRoot()
        val visibleTexts = mutableListOf<String>()
        if (root != null) {
            collectAllTexts(root, visibleTexts)
        }

        val chatEntries = visibleTexts
            .map { it.trim() }
            .filter { text ->
                text.length > 2 &&
                !text.contains("search", ignoreCase = true) &&
                !text.contains("ask meta", ignoreCase = true) &&
                !text.contains("chats", ignoreCase = true) &&
                !text.contains("status", ignoreCase = true) &&
                !text.contains("calls", ignoreCase = true) &&
                !text.contains("community", ignoreCase = true) &&
                !text.contains("unread", ignoreCase = true) &&
                !text.contains("favorites", ignoreCase = true)
            }

        val summary = if (chatEntries.isNotEmpty()) {
            "📱 Recent WhatsApp Messages:\n" + chatEntries.take(10).joinToString("\n") { "• $it" }
        } else {
            "📱 Checked WhatsApp. No recent unread messages found."
        }

        messages.add(Message("✅ $summary", false))
        returnToBroApp(context)
        return true
    }

    private suspend fun executeWhatsAppContactRead(
        context: Context,
        contactName: String,
        messages: MutableList<Message>
    ): Boolean {
        val service = BroAccessibilityService.instance ?: return false
        messages.add(Message("⚡ B.R.O. Direct Pipeline: Reading chat from '$contactName'...", false))

        launchApp(context, "WhatsApp")
        delay(2000)

        val root = service.getRoot() ?: return false
        val searchNode = findSearchNode(root)
        if (searchNode != null) {
            messages.add(Message("🔍 Searching for '$contactName'...", false))
            val target = if (searchNode.isClickable) searchNode else searchNode.parent
            target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(1000)

            val updatedRoot = service.getRoot() ?: return false
            val inputNode = findFirstEditableOrClickableInput(updatedRoot) ?: searchNode
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, contactName)
            }
            inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            delay(1500)

            val searchResultRoot = service.getRoot() ?: updatedRoot
            clickContactRow(searchResultRoot, contactName)
            delay(1500)
        }

        // Inside the chat thread now! Collect messages from screen. DO NOT TYPE OR SEND ANYTHING!
        val chatRoot = service.getRoot()
        val visibleTexts = mutableListOf<String>()
        if (chatRoot != null) {
            collectAllTexts(chatRoot, visibleTexts)
        }

        val messagesList = visibleTexts
            .map { it.trim() }
            .filter { text ->
                text.length > 1 &&
                !text.contains("type a message", ignoreCase = true) &&
                !text.contains("search", ignoreCase = true) &&
                !text.contains("online", ignoreCase = true) &&
                !text.contains("typing...", ignoreCase = true) &&
                !text.equals(contactName, ignoreCase = true)
            }
            .distinct()

        val summary = if (messagesList.isNotEmpty()) {
            "📱 Recent Messages in chat with $contactName:\n" + messagesList.takeLast(8).joinToString("\n") { "• $it" }
        } else {
            "📱 Opened chat with $contactName, but no recent messages were visible."
        }

        messages.add(Message("✅ $summary", false))
        returnToBroApp(context)
        return true
    }

    private suspend fun executeAppOpen(
        context: Context,
        appName: String,
        messages: MutableList<Message>
    ): Boolean {
        messages.add(Message("⚡ B.R.O. Direct Pipeline: Opening $appName...", false))
        val success = launchApp(context, appName)
        if (success) {
            messages.add(Message("✅ Successfully opened $appName.", false))
        } else {
            messages.add(Message("❌ Could not launch $appName.", false))
        }
        returnToBroApp(context)
        return true
    }

    private fun extractContactForRead(goal: String): String? {
        val lower = goal.lowercase().trim()
        val stopWords = setOf(
            "whatsapp", "app", "recent", "chats", "chat", "messages", "message",
            "check", "read", "inspect", "get", "show", "me", "my", "last", "latest",
            "the", "a", "an", "from", "for", "with", "of", "to"
        )

        // Pattern 1: Look for explicit preposition markers ("from", "of", "with", "for", "in")
        val preps = listOf("from ", "of ", "with ", "for ", "in ")
        for (prep in preps) {
            if (lower.contains(prep)) {
                val after = goal.substring(lower.indexOf(prep) + prep.length).trim()
                val clean = after
                    .substringBefore(" on whatsapp")
                    .substringBefore(" on WhatsApp")
                    .substringBefore(" app")
                    .substringBefore(" chat")
                    .substringBefore(" message")
                    .trim()
                if (clean.isNotBlank()) return clean.capitalizeWords()
            }
        }

        // Pattern 2: Extract non-stopwords if user typed e.g. "check pasu my inspiration chat"
        val words = goal.split(Regex("\\s+")).filter { it.isNotBlank() }
        val contactWords = words.filter { word ->
            val w = word.lowercase()
            w !in stopWords
        }

        if (contactWords.isNotEmpty()) {
            val contactCandidate = contactWords.joinToString(" ")
                .substringBefore(" on whatsapp")
                .substringBefore(" on WhatsApp")
                .trim()
            if (contactCandidate.length >= 2) {
                return contactCandidate.capitalizeWords()
            }
        }

        return null
    }

    private fun parseWhatsAppParams(goal: String): Pair<String, String> {
        val lower = goal.lowercase()
        var contact = "Contact"
        var message = "hi"

        if (lower.contains(" to ")) {
            val afterTo = goal.substringAfter(" to ", "").trim()
            contact = afterTo
                .substringBefore(" on whatsapp")
                .substringBefore(" on WhatsApp")
                .substringBefore(" saying")
                .substringBefore(" text")
                .substringBefore(" message")
                .ifBlank { "Contact" }
        } else if (lower.contains(" from ")) {
            val afterFrom = goal.substringAfter(" from ", "").trim()
            contact = afterFrom
                .substringBefore(" on whatsapp")
                .substringBefore(" on WhatsApp")
                .substringBefore(" saying")
                .ifBlank { "Contact" }
        }

        if (goal.contains("\"")) {
            message = goal.substringAfter("\"").substringBefore("\"")
        } else if (lower.contains(" saying ")) {
            message = goal.substringAfter(" saying ", "").trim()
        } else if (lower.contains(" text ")) {
            message = goal.substringAfter(" text ", "").trim()
        } else if (lower.contains(" message ")) {
            message = goal.substringAfter(" message ", "").trim()
        }

        return Pair(contact.capitalizeWords(), message)
    }

    private fun launchApp(context: Context, appName: String): Boolean {
        val pkg = AppLauncher.findPackage(appName) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    private fun findSearchNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val searchOrMetaNodes = root.findAccessibilityNodeInfosByText("Ask Meta or Search")
        if (searchOrMetaNodes.isNotEmpty()) return searchOrMetaNodes.firstOrNull { it.isClickable || it.isEditable || it.parent?.isClickable == true }

        val searchOrMetaAiNodes = root.findAccessibilityNodeInfosByText("Ask Meta AI or Search")
        if (searchOrMetaAiNodes.isNotEmpty()) return searchOrMetaAiNodes.firstOrNull { it.isClickable || it.isEditable || it.parent?.isClickable == true }

        val searchNodes = root.findAccessibilityNodeInfosByText("Search")
        if (searchNodes.isNotEmpty()) return searchNodes.firstOrNull { it.isClickable || it.isEditable || it.parent?.isClickable == true }

        val askMetaNodes = root.findAccessibilityNodeInfosByText("Ask Meta")
        if (askMetaNodes.isNotEmpty()) return askMetaNodes.firstOrNull { it.isClickable || it.isEditable || it.parent?.isClickable == true }

        return findFirstEditableOrClickableInput(root)
    }

    private fun findFirstEditableOrClickableInput(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable || (node.className?.toString()?.contains("EditText", true) == true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val res = findFirstEditableOrClickableInput(child)
            if (res != null) return res
        }
        return null
    }

    private fun clickContactRow(root: AccessibilityNodeInfo, contactName: String): Boolean {
        val matchingNodes = root.findAccessibilityNodeInfosByText(contactName)
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        for (node in matchingNodes) {
            val cls = node.className?.toString().orEmpty()
            if (cls.contains("ImageView", ignoreCase = true)) continue

            var parent: AccessibilityNodeInfo? = node
            repeat(5) {
                val p = parent ?: return@repeat
                if (p.isClickable && !p.className?.toString().orEmpty().contains("ImageView", ignoreCase = true)) {
                    candidates.add(p)
                    return@repeat
                }
                parent = p.parent
            }
        }

        val best = candidates.maxByOrNull { scoreContactRow(it, contactName) } ?: return false
        if (scoreContactRow(best, contactName) < 0) return false

        val rect = Rect()
        best.getBoundsInScreen(rect)
        if (rect.width() > 400 && rect.height() in 60..350) {
            return best.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return best.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun scoreContactRow(node: AccessibilityNodeInfo, contactName: String): Int {
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("ImageView", ignoreCase = true) || node.isEditable) return -1

        val text = node.text?.toString()?.trim()
            ?: node.contentDescription?.toString()?.trim()
            ?: return -1

        // CRITICAL: Reject "Ask Meta" / "Meta AI" rows so we click the actual contact name, NOT Meta AI!
        if (text.contains("ask meta", ignoreCase = true) ||
            text.contains("meta ai", ignoreCase = true) ||
            text.contains("send message to meta", ignoreCase = true)) {
            return -1
        }

        if (!text.contains(contactName, ignoreCase = true)) return -1

        var score = 0
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() < 120 && rect.height() < 120) score -= 30
        if (rect.width() > 500) score += 15
        if (rect.height() in 80..300) score += 10
        if (node.isClickable) score += 10
        if (text.equals(contactName, ignoreCase = true)) score += 25
        return score
    }

    private fun clickFirstResultRow(root: AccessibilityNodeInfo): Boolean {
        return findAndClickContactResult(root)
    }

    private fun findAndClickContactResult(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim() ?: node.contentDescription?.toString()?.trim()
        val isMetaAi = text != null && (text.contains("ask meta", ignoreCase = true) || text.contains("meta ai", ignoreCase = true))
        val isSearch = text != null && text.contains("search", ignoreCase = true)

        if (node.isClickable && !isMetaAi && !isSearch && !node.className?.toString().orEmpty().contains("ImageView", ignoreCase = true) && !node.isEditable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickContactResult(child)) return true
        }
        return false
    }

    private fun findChatInputNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val msgNodes = root.findAccessibilityNodeInfosByText("Message")
        if (msgNodes.isNotEmpty()) {
            val input = msgNodes.firstOrNull { it.isEditable || it.className?.toString()?.contains("EditText", true) == true }
            if (input != null) return input
        }
        return findFirstEditableOrClickableInput(root)
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val sendNodes = root.findAccessibilityNodeInfosByText("Send")
        if (sendNodes.isNotEmpty()) {
            val btn = sendNodes.firstOrNull { it.isClickable || it.parent?.isClickable == true }
            if (btn != null) return if (btn.isClickable) btn else btn.parent
        }

        // Check for ImageButton send icons
        return findSendImageButton(root)
    }

    private fun findSendImageButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString().orEmpty().lowercase()
        if (desc.contains("send") && node.isClickable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val res = findSendImageButton(child)
            if (res != null) return res
        }
        return null
    }

    private fun collectAllTexts(node: AccessibilityNodeInfo, list: MutableList<String>) {
        val txt = node.text?.toString()?.trim()
        if (!txt.isNullOrBlank()) list.add(txt)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllTexts(child, list)
        }
    }

    private fun String.capitalizeWords(): String = split(" ").joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
}
