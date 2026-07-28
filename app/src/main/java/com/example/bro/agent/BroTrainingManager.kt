package com.example.bro.agent

import android.content.Context
import android.util.Log
import com.example.bro.model.Action
import com.example.bro.model.ScreenState
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

data class TrainingSample(
    val timestamp: String,
    val goal: String,
    val initialScreenPackage: String,
    val screenElements: List<String>,
    val actionsPerformed: List<ActionSummary>,
    val userDemonstrationNotes: String? = null,
    val isUserDemonstration: Boolean,
    val status: String = "SUCCESS"
)

data class ActionSummary(
    val type: String,
    val targetId: Int? = null,
    val targetText: String? = null,
    val textInput: String? = null,
    val summary: String? = null
)

object BroTrainingManager {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private const val TRAINING_FILE_NAME = "bro_training_dataset.jsonl"

    var isRecordingDemonstration: Boolean = false
        private set
    var currentTrainingGoal: String = ""
        private set
    private val recordedDemonstrationActions = mutableListOf<ActionSummary>()
    private var initialTrainingScreen: ScreenState? = null
    private var lastRecordedText: String = ""

    private fun getDatasetFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "logs")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, TRAINING_FILE_NAME)
    }

    fun saveSuccessfulTrajectory(
        context: Context,
        goal: String,
        screen: ScreenState,
        actions: List<Action>
    ) {
        val sample = TrainingSample(
            timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()),
            goal = goal,
            initialScreenPackage = screen.packageName,
            screenElements = screen.nodes.take(25).map { node ->
                "[${node.id}] ${(node.text ?: node.contentDescription ?: node.className ?: "").toString().take(40)}"
            },
            actionsPerformed = actions.map { act ->
                ActionSummary(
                    type = act.type.name,
                    targetId = act.targetId,
                    targetText = act.target,
                    textInput = act.text,
                    summary = act.summary
                )
            },
            isUserDemonstration = false,
            status = "SUCCESS"
        )
        appendSampleToFile(context, sample)
    }

    fun startDemonstrationRecording(goal: String, currentScreen: ScreenState?) {
        isRecordingDemonstration = true
        currentTrainingGoal = goal
        initialTrainingScreen = currentScreen
        recordedDemonstrationActions.clear()
        lastRecordedText = ""
        Log.d("BRO_TRAINING", "Started demonstration recording for goal: '$goal'")
    }

    fun recordManualAction(actionType: String, targetText: String?, inputContent: String? = null) {
        if (!isRecordingDemonstration) return

        val cleanText = targetText?.trim() ?: ""
        if (cleanText.isEmpty() || cleanText == lastRecordedText) return
        lastRecordedText = cleanText

        val summary = ActionSummary(
            type = actionType,
            targetText = cleanText.take(50),
            textInput = inputContent,
            summary = "User demonstrated: $actionType '$cleanText'"
        )
        recordedDemonstrationActions.add(summary)
        Log.d("BRO_TRAINING", "Recorded demonstration action: $summary")
    }

    fun finishDemonstrationRecording(context: Context, userNotes: String = ""): Int {
        if (!isRecordingDemonstration) return 0
        isRecordingDemonstration = false

        val sample = TrainingSample(
            timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()),
            goal = currentTrainingGoal,
            initialScreenPackage = initialTrainingScreen?.packageName ?: "unknown",
            screenElements = initialTrainingScreen?.nodes?.take(25)?.map { node ->
                "[${node.id}] ${(node.text ?: node.contentDescription ?: node.className ?: "").toString().take(40)}"
            } ?: emptyList(),
            actionsPerformed = ArrayList(recordedDemonstrationActions),
            userDemonstrationNotes = userNotes.ifBlank { null },
            isUserDemonstration = true,
            status = "HUMAN_DEMONSTRATION"
        )

        appendSampleToFile(context, sample)
        val actionCount = recordedDemonstrationActions.size
        recordedDemonstrationActions.clear()
        initialTrainingScreen = null
        currentTrainingGoal = ""
        return actionCount
    }

    private fun appendSampleToFile(context: Context, sample: TrainingSample) {
        try {
            val file = getDatasetFile(context)
            val jsonLine = gson.toJson(sample).replace("\n", " ") + "\n"
            file.appendText(jsonLine)
            Log.d("BRO_TRAINING", "Appended training sample to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("BRO_TRAINING", "Error appending training sample: ${e.message}")
        }
    }

    fun getDatasetFilePath(context: Context): String {
        return getDatasetFile(context).absolutePath
    }
}
