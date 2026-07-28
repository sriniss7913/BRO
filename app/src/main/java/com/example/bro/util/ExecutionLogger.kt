package com.example.bro.util

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: String,
    val taskGoal: String,
    val stepNumber: Int,
    val appPackage: String,
    val screenPrompt: String,
    val userPromptSent: String,
    val rawLlmResponse: String,
    val parsedAction: String,
    val tokenUsage: String
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
        tokenUsage: String = ""
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
            tokenUsage = tokenUsage
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
