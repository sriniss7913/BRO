package com.example.bro.util

import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import com.example.bro.accessibility.NodeRepository
import com.example.bro.model.Action
import com.example.bro.model.ActionType
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LogEntry(
    val timestamp: String,
    val taskGoal: String,
    val stepNumber: Int,
    val appPackage: String,
    val screenPrompt: String,
    val userPromptSent: String,
    val rawLlmResponse: String,
    val parsedAction: String,
    val tokenUsage: String,
    val visionDebugImagePath: String?,
    val androidCommandExecuted: String = "",
    val executionResult: String? = null
)

object ExecutionLogger {
    val logs = mutableStateListOf<LogEntry>()
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun log(
        context: Context? = null,
        taskGoal: String,
        stepNumber: Int,
        appPackage: String,
        screenPrompt: String,
        userPromptSent: String,
        rawLlmResponse: String,
        parsedAction: String,
        tokenUsage: String = "",
        visionDebugImagePath: String? = null,
        androidCommandExecuted: String = "",
        executionResult: String? = null
    ) {
        val targetContext = context?.applicationContext ?: appContext
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = LogEntry(
            timestamp = time,
            taskGoal = taskGoal,
            stepNumber = stepNumber,
            appPackage = appPackage,
            screenPrompt = screenPrompt,
            userPromptSent = userPromptSent,
            rawLlmResponse = rawLlmResponse,
            parsedAction = parsedAction,
            tokenUsage = tokenUsage,
            visionDebugImagePath = visionDebugImagePath,
            androidCommandExecuted = androidCommandExecuted,
            executionResult = executionResult
        )

        logs.add(0, entry) // Newest on top

        // Keep last 100 log entries in memory
        if (logs.size > 100) {
            logs.removeAt(logs.size - 1)
        }

        if (targetContext != null) {
            appendLogToFile(targetContext, entry)
        }
    }

    fun recordAndroidCommand(
        context: Context?,
        taskGoal: String,
        stepNumber: Int,
        appPackage: String,
        action: Action,
        result: String,
        targetLabel: String? = null
    ) {
        val command = describeAndroidCommand(action, appPackage == "vision", targetLabel)
        val targetContext = context?.applicationContext ?: appContext
        val index = logs.indexOfFirst {
            it.taskGoal == taskGoal && it.appPackage == appPackage
        }.takeIf { it >= 0 } ?: logs.indexOfFirst { it.taskGoal == taskGoal }
        val recordedStep = logs.getOrNull(index)?.stepNumber ?: stepNumber
        val recordedPackage = logs.getOrNull(index)?.appPackage ?: appPackage

        if (index >= 0) {
            val previous = logs[index]
            val updated = previous.copy(
                androidCommandExecuted = listOf(
                    previous.androidCommandExecuted.takeIf(String::isNotBlank),
                    command
                ).filterNotNull().joinToString("\n"),
                executionResult = listOfNotNull(previous.executionResult, result)
                    .joinToString("\n")
                    .ifBlank { null }
            )
            logs[index] = updated
        }

        if (targetContext != null) {
            appendExecutionUpdate(targetContext, taskGoal, recordedStep, recordedPackage, command, result)
        }
    }

    fun recordNoAndroidCommand(
        context: Context?,
        taskGoal: String,
        stepNumber: Int,
        appPackage: String,
        reason: String
    ) {
        val targetContext = context?.applicationContext ?: appContext
        val index = logs.indexOfFirst {
            it.taskGoal == taskGoal && it.appPackage == appPackage
        }.takeIf { it >= 0 } ?: logs.indexOfFirst { it.taskGoal == taskGoal }
        val recordedStep = logs.getOrNull(index)?.stepNumber ?: stepNumber
        val recordedPackage = logs.getOrNull(index)?.appPackage ?: appPackage
        if (index >= 0) {
            val previous = logs[index]
            logs[index] = previous.copy(
                androidCommandExecuted = listOf(
                    previous.androidCommandExecuted.takeIf(String::isNotBlank),
                    "No Android command dispatched"
                ).filterNotNull().joinToString("\n"),
                executionResult = listOfNotNull(previous.executionResult, reason)
                    .joinToString("\n")
                    .ifBlank { null }
            )
        }
        if (targetContext != null) {
            appendExecutionUpdate(
                targetContext, taskGoal, recordedStep, recordedPackage,
                "No Android command dispatched", reason
            )
        }
    }

    private fun describeAndroidCommand(
        action: Action,
        vision: Boolean,
        targetLabelOverride: String?
    ): String {
        fun quoted(value: String?): String =
            value.orEmpty().replace("\\", "\\\\").replace("\"", "\\\"")

        if (vision) {
            return when (action.type) {
                ActionType.OPEN_APP ->
                    "startActivity(launchIntent, package=\"${quoted(action.packageName ?: action.target)}\")"
                ActionType.CLICK ->
                    "dispatchGesture(tap, x=${action.x}, y=${action.y})"
                ActionType.TYPE ->
                    "ClipboardManager.setPrimaryClip(text=\"${quoted(action.text)}\"); dispatchGesture(tap+longPress, x=${action.x}, y=${action.y})"
                ActionType.SCROLL, ActionType.SWIPE ->
                    "dispatchGesture(swipe, direction=\"${quoted(action.text)}\")"
                ActionType.BACK -> "performGlobalAction(GLOBAL_ACTION_BACK)"
                ActionType.HOME -> "performGlobalAction(GLOBAL_ACTION_HOME)"
                ActionType.WAIT -> "delay(${quoted(action.text)} ms)"
                else -> "No Android command dispatched (${action.type})"
            }
        }

        val node = action.targetId?.let(NodeRepository::get)
        val label = targetLabelOverride?.takeIf(String::isNotBlank) ?: node?.let {
            listOfNotNull(
                it.text?.toString(),
                it.contentDescription?.toString(),
                it.hintText?.toString(),
                it.viewIdResourceName
            ).firstOrNull(String::isNotBlank)
        }
        val nodeRef = action.targetId?.let { id ->
            "nodeId=$id${label?.let { ", label=\"${quoted(it)}\"" }.orEmpty()}"
        }
        return when (action.type) {
            ActionType.OPEN_APP ->
                "startActivity(launchIntent, package=\"${quoted(action.packageName ?: action.target)}\")"
            ActionType.CLICK ->
                "AccessibilityNodeInfo.ACTION_CLICK(${nodeRef ?: "target=${quoted(action.target)}"})"
            ActionType.TYPE ->
                "AccessibilityNodeInfo.ACTION_SET_TEXT(${nodeRef ?: "focused input"}, text=\"${quoted(action.text)}\")"
            ActionType.SEARCH ->
                "click search; ACTION_SET_TEXT(query=\"${quoted(action.text)}\"); ACTION_IME_ENTER when supported"
            ActionType.SEND_MESSAGE ->
                "AccessibilityNodeInfo.ACTION_CLICK(current Send control)"
            ActionType.TOGGLE_QUICK_SETTING -> {
                val target = action.target ?: action.text.orEmpty()
                if (target.contains("flash", true) || target.contains("torch", true)) {
                    "CameraManager.setTorchMode(enable=${action.desiredState})"
                } else {
                    "performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS); click tile=\"${quoted(target)}\""
                }
            }
            ActionType.LOCK_SCREEN ->
                "performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)"
            ActionType.SCROLL, ActionType.SWIPE ->
                "AccessibilityService.dispatchGesture(direction=\"${quoted(action.text)}\")"
            ActionType.BACK -> "performGlobalAction(GLOBAL_ACTION_BACK)"
            ActionType.HOME -> "performGlobalAction(GLOBAL_ACTION_HOME)"
            ActionType.WAIT -> "delay(${quoted(action.text)} ms)"
            ActionType.FIND_APP ->
                "AppLauncher.searchApps(query=\"${quoted(action.text ?: action.app ?: action.packageName)}\")"
            ActionType.ASK_USER ->
                "ASK_USER(question=\"${quoted(action.question ?: action.summary)}\", options=${action.options})"
            ActionType.READ_SCREEN, ActionType.FINISH, ActionType.CHAT, ActionType.WEB_SEARCH, ActionType.READ_NOTIFICATIONS ->
                "No Android command dispatched (${action.type})"
        }
    }

    private fun appendExecutionUpdate(
        context: Context,
        taskGoal: String,
        stepNumber: Int,
        appPackage: String,
        command: String,
        result: String
    ) {
        try {
            val logDir = context.getExternalFilesDir("logs") ?: context.filesDir
            if (!logDir.exists() && !logDir.mkdirs()) {
                throw IllegalStateException("Could not create log directory: ${logDir.absolutePath}")
            }
            FileWriter(File(logDir, "bro_task_logs.txt"), true).use { writer ->
                writer.appendLine("ANDROID EXECUTION UPDATE")
                writer.appendLine("TASK GOAL: $taskGoal")
                writer.appendLine("STEP: $stepNumber")
                writer.appendLine("APP PACKAGE: $appPackage")
                writer.appendLine("ANDROID COMMAND EXECUTED: $command")
                writer.appendLine("EXECUTION RESULT: $result")
                writer.appendLine("--------------------------------------------------")
            }
        } catch (e: Exception) {
            Log.e("BRO_LOGGER", "Failed to save Android execution update", e)
        }
    }

    suspend fun saveVisionDebugImage(
        context: Context,
        imageBytes: ByteArray,
        stepNumber: Int,
        action: String,
        x: Float?,
        y: Float?
    ): String? = withContext(Dispatchers.IO) {
        var bitmap: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > 1280 || bounds.outHeight / sampleSize > 2560) {
                sampleSize *= 2
            }
            bitmap = BitmapFactory.decodeByteArray(
                imageBytes,
                0,
                imageBytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inMutable = true
                }
            )
                ?: throw IllegalArgumentException("Could not decode captured Vision screenshot")

            val sourceWidth = bounds.outWidth
            val sourceHeight = bounds.outHeight
            val width = bitmap.width
            val height = bitmap.height
            val scaleX = width.toFloat() / sourceWidth.coerceAtLeast(1)
            val scaleY = height.toFloat() / sourceHeight.coerceAtLeast(1)
            val canvas = Canvas(bitmap)
            val scale = (width / 900f).coerceAtLeast(0.75f)
            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(145, 0, 220, 255)
                strokeWidth = 1.5f * scale
                style = Paint.Style.STROKE
            }
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 13f * scale
                style = Paint.Style.FILL
                setShadowLayer(3f * scale, 1f * scale, 1f * scale, Color.BLACK)
            }

            val divisions = 6
            for (i in 1 until divisions) {
                val gridX = width * i / divisions.toFloat()
                val gridY = height * i / divisions.toFloat()
                canvas.drawLine(gridX, 0f, gridX, height.toFloat(), gridPaint)
                canvas.drawLine(0f, gridY, width.toFloat(), gridY, gridPaint)
                canvas.drawText("${(gridX / scaleX).toInt()} px", gridX + 3f * scale, 18f * scale, labelPaint)
                canvas.drawText("${(gridY / scaleY).toInt()} px", 3f * scale, gridY - 3f * scale, labelPaint)
            }

            val validX = x?.takeIf { it.isFinite() && it >= 0f && it < sourceWidth }
            val validY = y?.takeIf { it.isFinite() && it >= 0f && it < sourceHeight }
            val validPoint = validX != null && validY != null
            val headerHeight = 40f * scale
            val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(210, 0, 0, 0)
                style = Paint.Style.FILL
            }
            canvas.drawRect(0f, 0f, width.toFloat(), headerHeight, headerPaint)
            val pointText = if (validPoint) "x=${validX!!.toInt()} y=${validY!!.toInt()}" else "no action coordinates"
            canvas.drawText(
                "BRO Vision | ${sourceWidth}x$sourceHeight px | $action | $pointText",
                8f * scale,
                26f * scale,
                labelPaint
            )

            if (validPoint) {
                val px = validX!! * scaleX
                val py = validY!! * scaleY
                val radius = 18f * scale
                val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.RED
                    strokeWidth = 4f * scale
                    style = Paint.Style.STROKE
                }
                val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.YELLOW
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(px, py, radius, markerPaint)
                canvas.drawLine(px - radius * 1.7f, py, px + radius * 1.7f, py, markerPaint)
                canvas.drawLine(px, py - radius * 1.7f, px, py + radius * 1.7f, markerPaint)
                canvas.drawCircle(px, py, 4f * scale, centerPaint)
                canvas.drawText(
                    "(${"%.1f".format(Locale.US, validX)}, ${"%.1f".format(Locale.US, validY)})",
                    px + radius,
                    py - radius,
                    labelPaint
                )
            }

            val logDir = File(context.getExternalFilesDir("logs") ?: context.filesDir, "vision_debug")
            if (!logDir.exists() && !logDir.mkdirs()) {
                throw IllegalStateException("Could not create Vision debug directory: ${logDir.absolutePath}")
            }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val file = File(logDir, "vision_${timestamp}_step${stepNumber}.jpg")
            file.outputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    throw IllegalStateException("Could not encode Vision debug screenshot")
                }
            }
            Log.i("BRO_LOGGER", "Saved Vision coordinate inspector image: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e("BRO_LOGGER", "Failed to save Vision coordinate inspector image", e)
            null
        } finally {
            bitmap?.recycle()
        }
    }

    private fun appendLogToFile(context: Context, entry: LogEntry) {
        try {
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val extDir = context.getExternalFilesDir("logs") ?: context.filesDir
            if (!extDir.exists()) extDir.mkdirs()

            val logFile = File(extDir, "bro_task_logs.txt")
            val writer = FileWriter(logFile, true)

            writer.appendLine("==================================================")
            writer.appendLine("TIMESTAMP: $dateStr ${entry.timestamp}")
            writer.appendLine("TASK GOAL: ${entry.taskGoal}")
            writer.appendLine("STEP: ${entry.stepNumber}")
            writer.appendLine("APP PACKAGE: ${entry.appPackage}")
            if (entry.tokenUsage.isNotBlank()) {
                writer.appendLine("TOKENS: ${entry.tokenUsage}")
            }
            writer.appendLine("--------------------------------------------------")
            writer.appendLine("1. PHONE SCREEN DOM READ:")
            writer.appendLine(entry.screenPrompt)
            writer.appendLine("--------------------------------------------------")
            writer.appendLine("2. PAYLOAD SENT TO LLM:")
            writer.appendLine(entry.userPromptSent)
            writer.appendLine("--------------------------------------------------")
            writer.appendLine("3. RAW LLM RESPONSE:")
            writer.appendLine(entry.rawLlmResponse)
            writer.appendLine("--------------------------------------------------")
            writer.appendLine("PARSED ACTION: ${entry.parsedAction}")
            if (entry.androidCommandExecuted.isNotBlank()) {
                writer.appendLine("ANDROID COMMAND EXECUTED:")
                writer.appendLine(entry.androidCommandExecuted)
                entry.executionResult?.let { writer.appendLine("EXECUTION RESULT: $it") }
            }
            entry.visionDebugImagePath?.let { writer.appendLine("VISION COORDINATE INSPECTOR: $it") }
            writer.appendLine("==================================================")
            writer.appendLine()
            writer.flush()
            writer.close()

            Log.d("BRO_LOGGER", "Saved log entry to ${logFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("BRO_LOGGER", "Failed to write log to file: ${e.message}")
        }
    }

    fun getLogFilePath(context: Context): String {
        val extDir = context.getExternalFilesDir("logs") ?: context.filesDir
        val file = File(extDir, "bro_task_logs.txt")
        return file.absolutePath
    }

    fun logFileExists(context: Context): Boolean {
        val extDir = context.getExternalFilesDir("logs") ?: context.filesDir
        return File(extDir, "bro_task_logs.txt").exists()
    }

    suspend fun exportLogFileToDownloads(context: Context): String = withContext(Dispatchers.IO) {
        val sourceFile = File(
            context.getExternalFilesDir("logs") ?: context.filesDir,
            "bro_task_logs.txt"
        )
        if (!sourceFile.isFile || !sourceFile.canRead()) {
            throw IllegalStateException("No readable task log file is available to export.")
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "BRO_task_logs_$timestamp.txt"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val destination: Uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw IllegalStateException("Android could not create the exported log in Downloads.")

        try {
            val output = resolver.openOutputStream(destination)
                ?: throw IllegalStateException("Android could not open the exported log for writing.")
            sourceFile.inputStream().use { input ->
                output.use { input.copyTo(it) }
            }

            val publishedValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (resolver.update(destination, publishedValues, null, null) != 1) {
                throw IllegalStateException("Android could not publish the exported log in Downloads.")
            }
            fileName
        } catch (e: Exception) {
            resolver.delete(destination, null, null)
            throw e
        }
    }

    fun clearLogFile(context: Context): Boolean {
        return try {
            val extDir = context.getExternalFilesDir("logs") ?: context.filesDir
            val logFile = File(extDir, "bro_task_logs.txt")
            if (logFile.exists()) {
                logFile.delete()
            } else true
        } catch (e: Exception) {
            false
        }
    }

    fun clear() {
        logs.clear()
    }
}
