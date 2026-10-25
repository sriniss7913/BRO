package com.example.bro.agent

import com.example.bro.model.Action

enum class TaskCheckpointState {
    RUNNING,
    WAITING_FOR_USER,
    COMPLETED,
    CANCELLED
}

enum class TaskCheckpointPhase {
    OBSERVING,
    PLANNING,
    DISPATCHING,
    VERIFYING
}

data class TaskCheckpoint(
    val goal: String,
    val originalPrompt: String,
    val executionMode: String,
    val state: TaskCheckpointState = TaskCheckpointState.RUNNING,
    val phase: TaskCheckpointPhase = TaskCheckpointPhase.OBSERVING,
    val step: Int = 0,
    val recentStepHistory: List<String> = emptyList(),
    val previousActions: List<Action> = emptyList(),
    val recoveryFeedback: String? = null,
    val consecutiveRecoveryFailures: Int = 0,
    val recoveryAttempts: Int = 0,
    val sendClickedThisTask: Boolean = false,
    val lastActionType: String? = null,
    val lastTargetId: Int? = null,
    val lastScreenFingerprint: String? = null,
    val pendingAction: Action? = null,
    val blacklistedActions: List<BlacklistedAction> = emptyList(),
    val visitedStateActions: Map<String, Int> = emptyMap(),
    val strategyFailureCounts: Map<String, Int> = emptyMap(),
    val recentActionSignatures: List<String> = emptyList(),
    val repeatActionCount: Int = 0,
    val pendingQuestion: String? = null,
    val questionChoices: List<String> = emptyList(),
    val userGuidance: String? = null,
    val targetPackage: String? = null,
    val subgoal: String? = null,
    val remainingPlan: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis()
) 

data class BlacklistedAction(
    val screenHash: String,
    val actionType: com.example.bro.model.ActionType,
    val nodeId: Int?
) {
    fun promptLabel(): String = "$actionType${nodeId?.let { " node $it" }.orEmpty()}"
}

class TaskCheckpointSession(
    private val context: android.content.Context,
    private val goal: String,
    private val originalPrompt: String,
    private val executionMode: String,
    private val memory: AgentMemory,
    initial: TaskCheckpoint?,
    private val onUpdated: ((TaskCheckpoint) -> Unit)?,
    private val userGuidance: String?
) {
    var step = initial?.step ?: 0
    var repeatActionCount = initial?.repeatActionCount ?: 0
    var lastTargetId: Int? = initial?.lastTargetId
    var lastActionType: com.example.bro.model.ActionType? = initial?.lastActionType?.let {
        runCatching { com.example.bro.model.ActionType.valueOf(it) }.getOrNull()
    }
    var recoveryFeedback: String? = initial?.recoveryFeedback
    var consecutiveRecoveryFailures = initial?.consecutiveRecoveryFailures ?: 0
    var recoveryAttempts = initial?.recoveryAttempts ?: 0
    var sendClickedThisTask = initial?.sendClickedThisTask == true ||
        (initial?.phase == TaskCheckpointPhase.DISPATCHING &&
            initial.pendingAction?.let { pending ->
                pending.type == com.example.bro.model.ActionType.SEND_MESSAGE ||
                    (pending.type == com.example.bro.model.ActionType.CLICK &&
                        (pending.target?.contains("send", ignoreCase = true) == true ||
                            pending.summary?.contains("send", ignoreCase = true) == true))
            } == true)
    val recentStepHistory = initial?.recentStepHistory?.toMutableList() ?: mutableListOf()
    val blacklistedActions = initial?.blacklistedActions?.toMutableSet() ?: linkedSetOf()
    val visitedStateActions = initial?.visitedStateActions?.toMutableMap() ?: mutableMapOf()
    val strategyFailureCounts = initial?.strategyFailureCounts?.toMutableMap() ?: mutableMapOf()
    val recentActionSigs = initial?.recentActionSignatures?.toMutableList() ?: mutableListOf()
    private var screenFingerprint: String? = initial?.lastScreenFingerprint
    var targetPackage: String? = initial?.targetPackage
    var subgoal: String? = initial?.subgoal
    var remainingPlan: String? = initial?.remainingPlan

    fun recordStrategyFailure(action: com.example.bro.model.Action, screenHash: String): Int {
        val strategy =
            "$screenHash|${action.type}|${action.targetId}|${action.text?.trim()?.lowercase()?.take(60).orEmpty()}"
        val attempts = (strategyFailureCounts[strategy] ?: 0) + 1
        strategyFailureCounts[strategy] = attempts
        if (attempts >= 2) {
            recoveryFeedback =
                "This exact strategy (${action.type} on ${action.targetId?.let { "node $it" } ?: "the current target"}) failed $attempts times on this screen. Choose a different target or action."
        }
        return attempts
    }

    suspend fun persist(
        phase: TaskCheckpointPhase,
        pendingAction: com.example.bro.model.Action? = null,
        state: TaskCheckpointState = TaskCheckpointState.RUNNING,
        pendingQuestion: String? = null,
        questionChoices: List<String> = emptyList(),
        currentScreenFingerprint: String? = null
    ) {
        val fingerprint = currentScreenFingerprint ?: screenFingerprint
        screenFingerprint = fingerprint
        val pendingSendMayHaveBeenDispatched =
            phase == TaskCheckpointPhase.DISPATCHING &&
                pendingAction?.let { pending ->
                    pending.type == com.example.bro.model.ActionType.SEND_MESSAGE ||
                        (pending.type == com.example.bro.model.ActionType.CLICK &&
                            (pending.target?.contains("send", true) == true ||
                                pending.summary?.contains("send", true) == true))
                } == true
        val checkpoint = TaskCheckpoint(
            goal = goal,
            originalPrompt = originalPrompt,
            executionMode = executionMode,
            state = state,
            phase = phase,
            step = step,
            recentStepHistory = recentStepHistory.takeLast(5),
            previousActions = memory.previousActions.toList(),
            recoveryFeedback = recoveryFeedback,
            consecutiveRecoveryFailures = consecutiveRecoveryFailures,
            recoveryAttempts = recoveryAttempts,
            sendClickedThisTask = sendClickedThisTask || pendingSendMayHaveBeenDispatched,
            lastActionType = lastActionType?.name,
            lastTargetId = lastTargetId,
            lastScreenFingerprint = fingerprint,
            pendingAction = pendingAction,
            blacklistedActions = blacklistedActions.toList(),
            visitedStateActions = visitedStateActions.toMap(),
            strategyFailureCounts = strategyFailureCounts.toMap(),
            recentActionSignatures = recentActionSigs.takeLast(6),
            repeatActionCount = repeatActionCount,
            pendingQuestion = pendingQuestion,
            questionChoices = questionChoices,
            userGuidance = userGuidance ?: initialGuidance,
            targetPackage = targetPackage,
            subgoal = subgoal,
            remainingPlan = remainingPlan
        )
        TaskCheckpointStore.save(context, checkpoint)
        onUpdated?.invoke(checkpoint)
    }

    private val initialGuidance: String? = initial?.userGuidance
}
