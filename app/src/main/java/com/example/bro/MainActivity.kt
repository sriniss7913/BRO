package com.example.bro

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bro.accessibility.BroAccessibilityService
import com.example.bro.accessibility.ScreenReader
import com.example.bro.agent.AgentMemory
import com.example.bro.agent.BlacklistedAction
import com.example.bro.executor.ActionExecutor
import com.example.bro.model.Action
import com.example.bro.model.ActionType
import com.example.bro.model.Goal
import com.example.bro.model.ScreenState
import com.example.bro.network.GroqRepository
import com.example.bro.planner.GroqPlanner
import com.example.bro.model.PlannerResponse
import com.example.bro.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

typealias OnQuestionCallback = (
    question: String,
    originalGoal: String,
    choices: List<String>,
    targetPackage: String,
    subgoal: String,
    remainingPlan: String
) -> Unit

private fun OnQuestionCallback?.invoke(
    question: String,
    originalGoal: String,
    choices: List<String>
) {
    this?.invoke(question, originalGoal, choices, "", "", "")
}

private fun buildQuestionChoices(
    suggested: List<String>,
    screen: ScreenState? = null
): List<String> {
    return com.example.bro.planner.ScreenChoiceExtractor.extractChoices(suggested, screen)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = getSharedPreferences("bro_prefs", Context.MODE_PRIVATE)
        com.example.bro.util.ExecutionLogger.init(applicationContext)
        val savedKey = prefs.getString("groq_api_key", "") ?: ""
        if (savedKey.isNotBlank()) {
            GroqRepository.apiKey = savedKey
        }
        val savedOpenRouterKey = prefs.getString("openrouter_api_key", "") ?: ""
        if (savedOpenRouterKey.isNotBlank()) {
            GroqRepository.openRouterApiKey = savedOpenRouterKey
        }
        val savedOpenRouterModel = prefs.getString("selected_openrouter_model", "") ?: ""
        if (savedOpenRouterModel.isNotBlank()) {
            GroqRepository.selectedOpenRouterModel = savedOpenRouterModel
        }

        setContent {
            BroTheme {
                BroApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var input by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var isExportingLog by remember { mutableStateOf(false) }

    val prefs = remember { context.getSharedPreferences("bro_prefs", Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(prefs.getString("groq_api_key", GroqRepository.apiKey) ?: "") }
    var systemPromptInput by remember { mutableStateOf(com.example.bro.planner.SystemPromptManager.getSystemPrompt(context)) }
    var visionPromptInput by remember { mutableStateOf(com.example.bro.planner.SystemPromptManager.getVisionSystemPrompt(context)) }
    var executionMode by remember {
        mutableStateOf(if (prefs.getString("execution_mode", "ACCESSIBILITY") == "VISION") "VISION" else "ACCESSIBILITY")
    }
    LaunchedEffect(executionMode) {
        BroAccessibilityService.screenshotOnlyModeEnabled = executionMode == "VISION"
    }

    var isAccessibilityActive by remember { mutableStateOf(false) }
    var isOverlayActive by remember { mutableStateOf(false) }

    var agentJob by remember { mutableStateOf<Job?>(null) }
    var isRunningTask by remember { mutableStateOf(false) }

    LaunchedEffect(isRunningTask) {
        if (isRunningTask) {
            com.example.bro.ui.EdgeGlowOverlayManager.show(context)
        } else {
            com.example.bro.ui.EdgeGlowOverlayManager.hide()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            com.example.bro.ui.EdgeGlowOverlayManager.hide()
        }
    }

    var isWebSearchEnabled by remember {
        mutableStateOf(prefs.getBoolean("web_search_enabled", true))
    }
    var tavilyApiKeyInput by remember {
        mutableStateOf(prefs.getString("tavily_api_key", "") ?: "")
    }

    LaunchedEffect(isWebSearchEnabled, tavilyApiKeyInput) {
        com.example.bro.search.WebSearchEngine.enabled = isWebSearchEnabled
        com.example.bro.search.WebSearchEngine.tavilyApiKey = tavilyApiKeyInput.trim()
    }

    val messages = remember {
        mutableStateListOf(
            Message("⚡ B.R.O. Personal AI Assistant Online. Autonomous phone control ready.", false)
        )
    }
    var activeTaskCheckpoint by remember {
        mutableStateOf<com.example.bro.agent.TaskCheckpoint?>(null)
    }
    var checkpointLoaded by remember { mutableStateOf(false) }
    var startupReady by remember { mutableStateOf(false) }

    var selectedGroqModel by remember {
        mutableStateOf(prefs.getString("selected_groq_model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile")
    }

    LaunchedEffect(selectedGroqModel) {
        GroqRepository.selectedGroqModel = selectedGroqModel
    }

    var openRouterApiKey by remember {
        mutableStateOf(prefs.getString("openrouter_api_key", GroqRepository.openRouterApiKey) ?: "")
    }
    var selectedOpenRouterModel by remember {
        mutableStateOf(prefs.getString("selected_openrouter_model", GroqRepository.selectedOpenRouterModel) ?: "meta-llama/llama-3.3-70b-instruct")
    }

    LaunchedEffect(openRouterApiKey) {
        GroqRepository.openRouterApiKey = openRouterApiKey
    }
    LaunchedEffect(selectedOpenRouterModel) {
        GroqRepository.selectedOpenRouterModel = selectedOpenRouterModel
    }

    var providerMode by remember {
        mutableStateOf(prefs.getString("provider_mode", "GROQ") ?: "GROQ")
    }
    var localPcUrl by remember {
        mutableStateOf(prefs.getString("local_pc_url", "http://192.168.1.35:1234/v1/") ?: "http://192.168.1.35:1234/v1/")
    }
    var localModelName by remember {
        mutableStateOf(prefs.getString("local_model_name", "local-model") ?: "local-model")
    }
    var customModelDirPath by remember {
        mutableStateOf(prefs.getString("on_device_model_dir", "/sdcard/Download/") ?: "/sdcard/Download/")
    }

    // Auto-scroll chat list to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(Unit) {
        AppLauncher.loadInstalledApps(context)
        isAccessibilityActive = BroAccessibilityService.isAccessibilityEnabled(context)
        isOverlayActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
        try {
            activeTaskCheckpoint = com.example.bro.agent.TaskCheckpointStore.load(context)
        } catch (error: Exception) {
            Log.e("BRO_CHECKPOINT", "Could not load saved task checkpoint.", error)
            messages.add(
                Message("⚠️ The saved task checkpoint could not be restored: ${error.message}", false)
            )
        } finally {
            checkpointLoaded = true
        }
        try {
            // Init on-device model ONCE on startup - NOT in Composable body to avoid recompose loops
            com.example.bro.agent.OnDeviceLlmManager.customModelDirPath = customModelDirPath
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.example.bro.agent.OnDeviceLlmManager.init(context)
            }
        } catch (error: Exception) {
            Log.e("BRO_MAIN", "Could not initialize the on-device model.", error)
        } finally {
            startupReady = true
        }
    }

    com.example.bro.network.GroqRepository.providerMode = providerMode
    com.example.bro.network.GroqRepository.localPcUrl = localPcUrl
    com.example.bro.network.GroqRepository.localModelName = localModelName
    com.example.bro.network.GroqRepository.openRouterApiKey = openRouterApiKey
    com.example.bro.network.GroqRepository.selectedOpenRouterModel = selectedOpenRouterModel

    // Pulsing Animation for BRO Core Orb
    val infiniteTransition = rememberInfiniteTransition(label = "broGlow")
    val orbScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbScale"
    )

    val orbAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbAlpha"
    )


    var showFeedbackDialog by remember { mutableStateOf(false) }
    var feedbackGoal by remember { mutableStateOf("") }
    var feedbackActions by remember { mutableStateOf<List<com.example.bro.model.Action>>(emptyList()) }
    var feedbackScreen by remember { mutableStateOf<ScreenState?>(null) }
    var isTrainingActive by remember { mutableStateOf(false) }

    // Tracks the original goal that was paused when AI asked a question
    var pendingQuestionGoal by remember { mutableStateOf("") }
    var pendingTorchGoal by remember { mutableStateOf<String?>(null) }
    var pendingTorchMode by remember { mutableStateOf(executionMode) }
    var cameraPermissionGrantedGoal by remember { mutableStateOf<String?>(null) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraPermissionGrantedGoal = pendingTorchGoal
        } else {
            messages.add(Message("⚠️ Camera permission is needed to control the flashlight.", false))
        }
        pendingTorchGoal = null
    }

    fun startTask(
        commandText: String,
        resumedGoal: String? = null,
        requestedMode: String = executionMode,
        userGuidance: String? = null,
        checkpoint: com.example.bro.agent.TaskCheckpoint? = null
    ) {
        if (!checkpointLoaded) {
            scope.launch {
                while (!checkpointLoaded) delay(50)
                startTask(commandText, resumedGoal, requestedMode, userGuidance, checkpoint)
            }
            return
        }
        if (commandText.isBlank() && resumedGoal == null) return

        // Fact Registration Check (e.g. "my name is srini")
        val factReply = com.example.bro.agent.UserConversationMemory.processFactRegistration(context, commandText)
        if (factReply != null) {
            messages.add(Message(commandText, true))
            messages.add(Message(factReply, false))
            com.example.bro.agent.UserConversationMemory.addTurn(context, commandText, commandText, factReply)
            return
        }

        // If resuming after a Yes answer, don't re-add the user message bubble
        if (resumedGoal == null) {
            messages.add(Message(commandText, true))
        }

        // Contextual Goal Resolution
        val resolvedGoal = resumedGoal ?: com.example.bro.agent.UserConversationMemory.resolveGoalWithContext(context, commandText)
        if (resumedGoal == null && resolvedGoal.lowercase() != commandText.lowercase()) {
            messages.add(Message("💡 Context linked: Target task → \"$resolvedGoal\"", false))
        }

        val quickSetting = com.example.bro.planner.QuickSettingRequestParser.parse(resolvedGoal)
        if (quickSetting?.tileLabel == "Flashlight" &&
            context.checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingTorchGoal = resolvedGoal
            pendingTorchMode = requestedMode
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        pendingQuestionGoal = ""
        agentJob?.cancel()
        var taskCompleted = false
        agentJob = scope.launch {
            isRunningTask = true
            var tempScreen: ScreenState? = null
            var tempActions: List<com.example.bro.model.Action> = emptyList()
            var currentCheckpoint = checkpoint?.copy(
                state = com.example.bro.agent.TaskCheckpointState.RUNNING,
                pendingQuestion = null,
                questionChoices = emptyList(),
                userGuidance = userGuidance ?: checkpoint.userGuidance,
                step = if (userGuidance != null) 0 else checkpoint.step,
                consecutiveRecoveryFailures = if (userGuidance != null) 0 else checkpoint.consecutiveRecoveryFailures,
                blacklistedActions = if (userGuidance != null) emptyList() else checkpoint.blacklistedActions
            ) ?: com.example.bro.agent.TaskCheckpoint(
                goal = resolvedGoal,
                originalPrompt = commandText.ifBlank { resolvedGoal },
                executionMode = requestedMode,
                userGuidance = userGuidance
            )
            var waitingQuestion: Pair<String, List<String>>? = null
            var waitingTargetPkg: String? = null
            var waitingSubgoal: String? = null
            var waitingRemainingPlan: String? = null
            var taskFailed = false
            var coroutineCancelled = false
            try {
                if (checkpoint == null) {
                    com.example.bro.agent.TaskCheckpointStore.clear(context)
                }
                com.example.bro.agent.TaskCheckpointStore.save(context, currentCheckpoint)
                activeTaskCheckpoint = currentCheckpoint
                executeGoalWithPlanner(
                    context = context,
                    goalStr = resolvedGoal,
                    userOriginalPrompt = commandText.ifBlank { resolvedGoal },
                    executionMode = requestedMode,
                    userGuidance = userGuidance,
                    checkpoint = checkpoint,
                    onCheckpointUpdated = { saved ->
                        currentCheckpoint = saved
                        activeTaskCheckpoint = saved
                    },
                    messages = messages,
                    onComplete = { goal, screen, actions ->
                        tempScreen = screen
                        tempActions = actions
                        taskCompleted = true
                    },
                    onQuestion = { question, originalGoal, choices, targetPkg, subgoal, remainingPlan ->
                        waitingQuestion = question to choices.distinct().take(4)
                        waitingTargetPkg = targetPkg
                        waitingSubgoal = subgoal
                        waitingRemainingPlan = remainingPlan
                        pendingQuestionGoal = originalGoal
                        val idx = messages.indexOfLast { !it.isUser }
                        if (idx >= 0) {
                            messages[idx] = messages[idx].copy(
                                isQuestion = true,
                                pendingGoal = originalGoal,
                                choices = choices.distinct().take(4),
                                targetPackage = targetPkg,
                                subgoal = subgoal,
                                remainingPlan = remainingPlan
                            )
                        }
                    }
                )
            } catch (e: CancellationException) {
                coroutineCancelled = true
                Log.d("BRO_MAIN", "Goal cancelled by user")
            } catch (e: Throwable) {
                taskFailed = true
                Log.e("BRO_MAIN", "Task execution error: ${e.message}", e)
                messages.add(Message("❌ Error: ${e.message ?: "Task execution failed"}", false))
            } finally {
                if (!coroutineCancelled) {
                    when {
                        taskCompleted -> {
                            com.example.bro.agent.TaskCheckpointStore.clear(context)
                            activeTaskCheckpoint = null
                        }
                        waitingQuestion != null || taskFailed -> {
                            val question = waitingQuestion?.first
                                ?: "The task was interrupted. Resume the saved task from its current screen?"
                            val choices = waitingQuestion?.second
                                ?: listOf("Resume task", "Cancel task")
                            currentCheckpoint = currentCheckpoint.copy(
                                state = com.example.bro.agent.TaskCheckpointState.WAITING_FOR_USER,
                                pendingQuestion = question,
                                questionChoices = choices,
                                targetPackage = waitingTargetPkg ?: currentCheckpoint.targetPackage,
                                subgoal = waitingSubgoal ?: currentCheckpoint.subgoal,
                                remainingPlan = waitingRemainingPlan ?: currentCheckpoint.remainingPlan,
                                phase = com.example.bro.agent.TaskCheckpointPhase.OBSERVING,
                                pendingAction = null
                            )
                            com.example.bro.agent.TaskCheckpointStore.save(context, currentCheckpoint)
                            activeTaskCheckpoint = currentCheckpoint
                            if (taskFailed && waitingQuestion == null) {
                                messages.add(
                                    Message(
                                        question,
                                        false,
                                        isQuestion = true,
                                        pendingGoal = currentCheckpoint.goal,
                                        choices = choices
                                    )
                                )
                            }
                        }
                        else -> {
                            com.example.bro.agent.TaskCheckpointStore.clear(context)
                            activeTaskCheckpoint = null
                        }
                    }
                }
                isRunningTask = false
                feedbackGoal = resolvedGoal
                feedbackScreen = tempScreen
                feedbackActions = tempActions
                showFeedbackDialog = taskCompleted
            }
        }
    }

    LaunchedEffect(checkpointLoaded, startupReady) {
        if (!checkpointLoaded || !startupReady) return@LaunchedEffect
        val saved = activeTaskCheckpoint ?: return@LaunchedEffect
        when (saved.state) {
            com.example.bro.agent.TaskCheckpointState.WAITING_FOR_USER -> {
                pendingQuestionGoal = saved.goal
                val question = saved.pendingQuestion
                    ?: "The task is paused. Choose how B.R.O. should continue."
                messages.add(
                    Message(
                        question,
                        false,
                        isQuestion = true,
                        pendingGoal = saved.goal,
                        choices = saved.questionChoices.ifEmpty { listOf("Resume task", "Cancel task") }
                    )
                )
            }
            com.example.bro.agent.TaskCheckpointState.RUNNING -> {
                messages.add(Message("↻ Resuming the saved task: ${saved.goal}", false))
                for (attempt in 0 until 30) {
                    if (BroAccessibilityService.instance != null) break
                    delay(500)
                }
                if (BroAccessibilityService.instance == null) {
                    val question = "The saved task is ready, but B.R.O.'s Accessibility service is not connected."
                    val paused = saved.copy(
                        state = com.example.bro.agent.TaskCheckpointState.WAITING_FOR_USER,
                        pendingQuestion = question,
                        questionChoices = listOf("Resume task", "Cancel task")
                    )
                    activeTaskCheckpoint = paused
                    com.example.bro.agent.TaskCheckpointStore.save(context, paused)
                    messages.add(
                        Message(
                            question,
                            false,
                            isQuestion = true,
                            pendingGoal = saved.goal,
                            choices = paused.questionChoices
                        )
                    )
                } else {
                    startTask(
                        "",
                        resumedGoal = saved.goal,
                        requestedMode = saved.executionMode,
                        userGuidance = saved.userGuidance,
                        checkpoint = saved
                    )
                }
            }
            else -> {
                com.example.bro.agent.TaskCheckpointStore.clear(context)
                activeTaskCheckpoint = null
            }
        }
    }

    LaunchedEffect(cameraPermissionGrantedGoal) {
        val goal = cameraPermissionGrantedGoal ?: return@LaunchedEffect
        cameraPermissionGrantedGoal = null
        startTask("", resumedGoal = goal, requestedMode = pendingTorchMode)
    }

    fun submitQuestionGuidance(message: Message, guidance: String) {
        val answer = guidance.trim()
        if (answer.isBlank()) return
        val idx = messages.indexOf(message)
        if (idx >= 0) messages[idx] = message.copy(isQuestion = false)
        messages.add(Message(answer, true))
        if (answer.equals("Cancel task", ignoreCase = true) || answer.equals("cancel", ignoreCase = true)) {
            messages.add(Message("🚫 Task cancelled.", false))
            pendingQuestionGoal = ""
            activeTaskCheckpoint = null
            scope.launch { com.example.bro.agent.TaskCheckpointStore.clear(context) }
            return
        }
        val nextMode = if (answer.contains("switch to accessibility", ignoreCase = true)) {
            "ACCESSIBILITY"
        } else {
            executionMode
        }

        // Reframe the goal cleanly using the base goal and the user's specific guidance/hint
        val baseGoal = message.pendingGoal
            .ifBlank { activeTaskCheckpoint?.originalPrompt.orEmpty() }
            .ifBlank { activeTaskCheckpoint?.goal.orEmpty() }

        val isStandardControlOption = answer.equals("Resume task", ignoreCase = true) ||
            answer.equals("Retry task", ignoreCase = true) ||
            answer.equals("Try another approach", ignoreCase = true) ||
            answer.equals("Yes", ignoreCase = true) ||
            answer.equals("No", ignoreCase = true)

        val compoundGoal = if (baseGoal.isNotBlank() && !isStandardControlOption) {
            com.example.bro.agent.GoalReframer.reframe(baseGoal, answer)
        } else {
            baseGoal
        }

        val enrichedGuidance = buildString {
            append("User selected/instructed: \"$answer\".")
            if (message.targetPackage.isNotBlank()) {
                append(" Active target app: ${message.targetPackage}.")
            }
            if (message.subgoal.isNotBlank()) {
                append(" Immediate subgoal: ${message.subgoal} -> choose \"$answer\".")
            }
            if (message.remainingPlan.isNotBlank()) {
                append(" Remaining plan: ${message.remainingPlan}.")
            }
        }

        val updatedCheckpoint = (activeTaskCheckpoint ?: com.example.bro.agent.TaskCheckpoint(
            goal = compoundGoal,
            originalPrompt = com.example.bro.agent.GoalReframer.cleanBaseGoal(baseGoal).ifBlank { compoundGoal },
            executionMode = nextMode
        )).copy(
            goal = compoundGoal,
            targetPackage = message.targetPackage.ifBlank { activeTaskCheckpoint?.targetPackage },
            subgoal = message.subgoal.ifBlank { activeTaskCheckpoint?.subgoal },
            remainingPlan = message.remainingPlan.ifBlank { activeTaskCheckpoint?.remainingPlan },
            userGuidance = enrichedGuidance,
            step = 0,
            consecutiveRecoveryFailures = 0,
            recoveryAttempts = 0,
            repeatActionCount = 0,
            blacklistedActions = emptyList(),
            recoveryFeedback = null
        )

        pendingQuestionGoal = ""

        startTask(
            "",
            resumedGoal = compoundGoal,
            requestedMode = nextMode,
            userGuidance = enrichedGuidance,
            checkpoint = updatedCheckpoint
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        JarvisBackground,
                        Color(0xFF091120),
                        Color(0xFF04060B)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // BRO AI Assistant Top HUD Bar
            Surface(
                color = Color(0xCC0D1526),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        BorderStroke(0.5.dp, JarvisCyanGlow)
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Glowing BRO Avatar
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(42.dp)
                                .scale(if (isRunningTask) orbScale else 1.0f)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            if (isRunningTask) JarvisCyan else JarvisBlue,
                                            Color.Transparent
                                        )
                                    )
                                )
                                .border(
                                    BorderStroke(
                                        1.5.dp,
                                        if (isRunningTask) JarvisCyan else JarvisBlue.copy(alpha = orbAlpha)
                                    ),
                                    CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "BRO Core",
                                tint = if (isRunningTask) Color.White else JarvisCyan,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "B.R.O. ASSISTANT",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                    color = JarvisCyan,
                                    letterSpacing = 1.5.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (isRunningTask) JarvisCyan else JarvisGreen)
                                )
                            }
                            val activeModelDisplay = when (providerMode) {
                                "GROQ" -> selectedGroqModel
                                "OPENROUTER" -> selectedOpenRouterModel
                                "ON_DEVICE" -> com.example.bro.agent.OnDeviceLlmManager.loadedModelName.ifBlank { "ON-DEVICE" }
                                "LOCAL_PC" -> localModelName
                                else -> "GROQ"
                            }
                            Text(
                                text = if (isRunningTask) "STATUS: EXECUTING AUTOMATION..." else "MODEL: $activeModelDisplay",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 10.sp,
                                color = if (isRunningTask) JarvisCyan else JarvisCyan.copy(alpha = 0.8f),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Row {
                        // Clear Chat History Button
                        IconButton(
                            onClick = {
                                messages.clear()
                                messages.add(Message("⚡ B.R.O. Personal AI Assistant Online. Autonomous phone control ready.", false))
                                Toast.makeText(context, "🧹 Chat Cleared!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0x2200E5FF))
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Clear History",
                                tint = JarvisTextSecondary
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Settings Button
                        IconButton(
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0x3300E5FF))
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = JarvisCyan
                            )
                        }
                    }
                }
            }

            // System Status HUD Glass Panel
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0x990D1526)),
                border = BorderStroke(1.dp, Color(0x2200E5FF))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isAccessibilityActive) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isAccessibilityActive) JarvisGreen else JarvisRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isAccessibilityActive) "Accessibility Core: Active" else "Accessibility Core: Offline",
                                fontSize = 12.sp,
                                color = JarvisTextPrimary,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        if (!isAccessibilityActive) {
                            Button(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    context.startActivity(intent)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = JarvisRed),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Enable", fontSize = 11.sp, color = Color.White)
                            }
                        } else if (isOverlayActive) {
                            OutlinedButton(
                                onClick = {
                                    context.startService(Intent(context, OverlayService::class.java))
                                    Toast.makeText(context, "Holographic Bubble Activated!", Toast.LENGTH_SHORT).show()
                                },
                                border = BorderStroke(1.dp, JarvisCyan),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Floating HUD", fontSize = 11.sp, color = JarvisCyan)
                            }
                        }
                    }
                }
            }


            // Chat Messages List with Glassmorphism Bubbles & Quick Copy
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                items(messages) { message ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            shape = RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = if (message.isUser) 16.dp else 4.dp,
                                bottomEnd = if (message.isUser) 4.dp else 16.dp
                            ),
                            color = if (message.isUser) Color(0xDD0055B3)
                                    else if (message.isQuestion) Color(0xDD1A1000)
                                    else Color(0xDD0D182E),
                            border = BorderStroke(
                                1.dp,
                                if (message.isUser) Color(0x6600E5FF)
                                else if (message.isQuestion) Color(0xAAF59E0B)
                                else Color(0x3300E5FF)
                            ),
                            modifier = Modifier.widthIn(max = 300.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = message.text,
                                    color = JarvisTextPrimary,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                if (message.isQuestion && message.choices.isNotEmpty()) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        message.choices.forEach { choice ->
                                            Button(
                                                onClick = {
                                                    submitQuestionGuidance(message, choice)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8)),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(choice, color = Color.White, fontSize = 12.sp)
                                            }
                                        }
                                        Text(
                                            "Or tell BRO what to do:",
                                            color = JarvisTextSecondary,
                                            fontSize = 11.sp
                                        )
                                        OutlinedTextField(
                                            value = message.customReply,
                                            onValueChange = { value ->
                                                val idx = messages.indexOf(message)
                                                if (idx >= 0) messages[idx] = message.copy(customReply = value)
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            placeholder = { Text("Type your own instruction", fontSize = 12.sp) },
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = JarvisCyan,
                                                unfocusedBorderColor = Color(0x4400E5FF),
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White
                                            )
                                        )
                                        Button(
                                            onClick = { submitQuestionGuidance(message, message.customReply) },
                                            enabled = message.customReply.isNotBlank(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Send custom instruction", color = Color.White, fontSize = 12.sp)
                                        }
                                    }
                                } else if (message.isQuestion) {
                                    // Yes / No quick-reply buttons for approval questions
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Button(
                                            onClick = {
                                                // Replace question bubble with user's "Yes" reply
                                                val idx = messages.indexOf(message)
                                                if (idx >= 0) messages[idx] = message.copy(isQuestion = false)
                                                messages.add(Message("✅ Yes, proceed", true))
                                                // Resume the original task with approval context
                                                startTask(
                                                    "",
                                                    resumedGoal = message.pendingGoal,
                                                    userGuidance = "User confirmed: Yes",
                                                    checkpoint = activeTaskCheckpoint
                                                )
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("✅ Yes", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Button(
                                            onClick = {
                                                val idx = messages.indexOf(message)
                                                if (idx >= 0) messages[idx] = message.copy(isQuestion = false)
                                                messages.add(Message("❌ No, cancel", true))
                                                messages.add(Message("🚫 Task cancelled.", false))
                                                agentJob?.cancel()
                                                isRunningTask = false
                                                pendingQuestionGoal = ""
                                                activeTaskCheckpoint = null
                                                scope.launch {
                                                    com.example.bro.agent.TaskCheckpointStore.clear(context)
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("❌ No", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                } else {
                                    // Quick Copy Button
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0x3300E5FF))
                                                .clickable {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    val clip = ClipData.newPlainText("BRO_Message", message.text)
                                                    clipboard.setPrimaryClip(clip)
                                                    Toast.makeText(context, "📋 Copied message to clipboard!", Toast.LENGTH_SHORT).show()
                                                }
                                                .padding(horizontal = 6.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Share,
                                                contentDescription = "Copy Text",
                                                tint = JarvisCyan,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "COPY",
                                                fontSize = 9.sp,
                                                color = JarvisCyan,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isRunningTask) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = JarvisCyan,
                    trackColor = Color(0x3300E5FF)
                )
            }

            // 🎓 INTERACTIVE TASK FEEDBACK & DEMO TRAINING CARD
            if (showFeedbackDialog) {
                Surface(
                    color = Color(0xEE0B1626),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .border(BorderStroke(1.dp, JarvisCyan), RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🎯 Task Feedback & Dataset Training", color = JarvisCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Was the task \"$feedbackGoal\" performed correctly?", color = Color.White, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    com.example.bro.agent.BroTrainingManager.saveSuccessfulTrajectory(
                                        context, feedbackGoal, feedbackScreen ?: ScreenState("", emptyList()), feedbackActions
                                    )
                                    showFeedbackDialog = false
                                    Toast.makeText(context, "✅ Workflow saved to bro_training_dataset.jsonl!", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                            ) {
                                Text("👍 Yes (Correct)", color = Color.White, fontSize = 11.sp)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    com.example.bro.agent.BroTrainingManager.startDemonstrationRecording(feedbackGoal, feedbackScreen)
                                    isTrainingActive = true
                                    showFeedbackDialog = false
                                    Toast.makeText(context, "🎓 Demonstration Mode Active! Perform steps manually.", Toast.LENGTH_LONG).show()
                                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                        addCategory(Intent.CATEGORY_HOME)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(homeIntent)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
                            ) {
                                Text("🎓 No, Train BRO", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            var showFinishDemoNotesDialog by remember { mutableStateOf(false) }
            var demoNotesInput by remember { mutableStateOf("") }

            if (showFinishDemoNotesDialog) {
                AlertDialog(
                    onDismissRequest = { showFinishDemoNotesDialog = false },
                    title = { Text("🎓 Demo Result / Notes (Optional)", color = JarvisCyan, fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            Text("Type the expected result or answer for this task (e.g., 'Last message was: See you tomorrow'):", fontSize = 12.sp, color = Color.White)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = demoNotesInput,
                                onValueChange = { demoNotesInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Type result notes here...", fontSize = 12.sp, color = JarvisTextSecondary) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = JarvisCyan,
                                    unfocusedBorderColor = Color(0x4400E5FF),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val count = com.example.bro.agent.BroTrainingManager.finishDemonstrationRecording(context, demoNotesInput.trim())
                                isTrainingActive = false
                                showFinishDemoNotesDialog = false
                                demoNotesInput = ""
                                Toast.makeText(context, "🎓 Saved $count demonstrated actions + result notes to dataset!", Toast.LENGTH_LONG).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                        ) {
                            Text("Save Demo", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                val count = com.example.bro.agent.BroTrainingManager.finishDemonstrationRecording(context, "")
                                isTrainingActive = false
                                showFinishDemoNotesDialog = false
                                demoNotesInput = ""
                                Toast.makeText(context, "🎓 Saved $count demonstrated actions to dataset!", Toast.LENGTH_LONG).show()
                            }
                        ) {
                            Text("Skip Notes", color = JarvisRed)
                        }
                    }
                )
            }

            if (isTrainingActive || com.example.bro.agent.BroTrainingManager.isRecordingDemonstration) {
                Surface(
                    color = Color(0xEE2A1000),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .border(BorderStroke(1.5.dp, Color(0xFFF59E0B)), RoundedCornerShape(10.dp)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("🔴 Training Demo Recording...", color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("Goal: ${com.example.bro.agent.BroTrainingManager.currentTrainingGoal}", color = Color.White, fontSize = 10.sp, maxLines = 1)
                        }
                        Button(
                            onClick = {
                                showFinishDemoNotesDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                        ) {
                            Text("Finish Demo", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Futuristic Controller Bar with Dual-State Send/Stop Button & Voice Shortcut
            Surface(
                color = Color(0xEE0B1220),
                border = BorderStroke(0.5.dp, Color(0x4400E5FF)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Mode", color = JarvisTextSecondary, fontSize = 11.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        FilterChip(
                            selected = executionMode == "ACCESSIBILITY",
                            onClick = {
                                executionMode = "ACCESSIBILITY"
                                prefs.edit().putString("execution_mode", executionMode).apply()
                            },
                            label = { Text("Accessibility", fontSize = 11.sp) }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        FilterChip(
                            selected = executionMode == "VISION",
                            onClick = {
                                executionMode = "VISION"
                                prefs.edit().putString("execution_mode", executionMode).apply()
                            },
                            label = { Text("Vision", fontSize = 11.sp) }
                        )
                    }
                    if (executionMode == "VISION") {
                        Text(
                            "Screenshot-only reading; separate from Accessibility mode. TYPE uses the visible Paste menu.",
                            fontSize = 10.sp,
                            color = JarvisTextSecondary
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    if (executionMode == "VISION") "Ask BRO Vision to control your phone..."
                                    else "Ask BRO to control your phone...",
                                    fontSize = 13.sp,
                                    color = JarvisTextSecondary
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = JarvisCyan,
                                unfocusedBorderColor = Color(0x4400E5FF),
                                focusedContainerColor = Color(0xDD0D182E),
                                unfocusedContainerColor = Color(0x990D182E),
                                focusedTextColor = JarvisTextPrimary,
                                unfocusedTextColor = JarvisTextPrimary
                            )
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        // DUAL-STATE SEND / STOP BUTTON
                        IconButton(
                            onClick = {
                                if (isRunningTask) {
                                    agentJob?.cancel()
                                    isRunningTask = false
                                    messages.add(Message("⏹️ Automation stopped by user.", false))
                                    returnToBroApp(context)
                                    Toast.makeText(context, "⏹️ Task Stopped", Toast.LENGTH_SHORT).show()
                                } else if (input.isNotBlank()) {
                                    val userMessage = input
                                    input = ""
                                    val pendingQuestionMsg = messages.lastOrNull { it.isQuestion }
                                    if (pendingQuestionMsg != null || pendingQuestionGoal.isNotBlank() ||
                                        activeTaskCheckpoint?.state == com.example.bro.agent.TaskCheckpointState.WAITING_FOR_USER
                                    ) {
                                        val targetMsg = pendingQuestionMsg ?: Message(
                                            text = pendingQuestionGoal.ifBlank { activeTaskCheckpoint?.goal.orEmpty() },
                                            isUser = false,
                                            isQuestion = true,
                                            pendingGoal = pendingQuestionGoal.ifBlank { activeTaskCheckpoint?.goal.orEmpty() },
                                            choices = activeTaskCheckpoint?.questionChoices ?: emptyList(),
                                            targetPackage = activeTaskCheckpoint?.targetPackage.orEmpty(),
                                            subgoal = activeTaskCheckpoint?.subgoal.orEmpty(),
                                            remainingPlan = activeTaskCheckpoint?.remainingPlan.orEmpty()
                                        )
                                        submitQuestionGuidance(targetMsg, userMessage)
                                    } else {
                                        startTask(userMessage)
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(if (isRunningTask) JarvisRed else JarvisCyan)
                        ) {
                            Icon(
                                imageVector = if (isRunningTask) Icons.Default.Close else Icons.Default.Send,
                                contentDescription = if (isRunningTask) "Stop Execution" else "Send Command",
                                tint = Color.Black,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    var activeSettingsTab by remember { mutableStateOf(0) } // 0 = Config, 1 = Live Inspector

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            containerColor = Color(0xFF0F182C),
            title = {
                Column {
                    Text("B.R.O. System Configuration", color = JarvisCyan, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    TabRow(
                        selectedTabIndex = activeSettingsTab,
                        containerColor = Color(0xFF0D1526),
                        contentColor = JarvisCyan
                    ) {
                        Tab(
                            selected = activeSettingsTab == 0,
                            onClick = { activeSettingsTab = 0 },
                            text = { Text("Settings", fontSize = 12.sp) }
                        )
                        Tab(
                            selected = activeSettingsTab == 1,
                            onClick = { activeSettingsTab = 1 },
                            text = { Text("Inspector (${com.example.bro.util.ExecutionLogger.logs.size})", fontSize = 12.sp) }
                        )
                    }
                }
            },
            text = {
                if (activeSettingsTab == 0) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                    ) {
                        item {
                            Text("AI Provider Mode:", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = JarvisCyan)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                FilterChip(
                                    selected = providerMode == "GROQ",
                                    onClick = { providerMode = "GROQ" },
                                    label = { Text("🌐 Groq", fontSize = 10.sp) }
                                )
                                FilterChip(
                                    selected = providerMode == "OPENROUTER",
                                    onClick = { providerMode = "OPENROUTER" },
                                    label = { Text("⚡ OpenRouter", fontSize = 10.sp) }
                                )
                                FilterChip(
                                    selected = providerMode == "LOCAL_PC",
                                    onClick = { providerMode = "LOCAL_PC" },
                                    label = { Text("💻 PC", fontSize = 10.sp) }
                                )
                                FilterChip(
                                    selected = providerMode == "ON_DEVICE",
                                    onClick = { providerMode = "ON_DEVICE" },
                                    label = { Text("📱 On-Device", fontSize = 10.sp) }
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))

                            if (providerMode == "GROQ") {
                                Text("Groq API Key:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = JarvisTextPrimary)
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = apiKey,
                                    onValueChange = { apiKey = it },
                                    placeholder = { Text("gsk_...", color = JarvisTextSecondary) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = JarvisCyan,
                                        unfocusedBorderColor = Color(0x4400E5FF),
                                        focusedTextColor = JarvisTextPrimary,
                                        unfocusedTextColor = JarvisTextPrimary
                                    )
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Get a free API key at console.groq.com",
                                    fontSize = 11.sp,
                                    color = JarvisTextSecondary
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text("Groq Model Selection:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = JarvisCyan)
                                Spacer(modifier = Modifier.height(4.dp))

                                var modelDropdownExpanded by remember { mutableStateOf(false) }
                                val groqModels = listOf(
                                    "llama-3.3-70b-versatile" to "Llama 3.3 70B (High Performance)",
                                    "llama-3.1-8b-instant" to "Llama 3.1 8B (Ultra Fast)",
                                    "deepseek-r1-distill-llama-70b" to "DeepSeek R1 70B (Reasoning)",
                                    "gemma2-9b-it" to "Gemma 2 9B (Google)",
                                    "mixtral-8x7b-32768" to "Mixtral 8x7B (MoE)",
                                    "llama3-70b-8192" to "Llama 3 70B",
                                    "llama3-8b-8192" to "Llama 3 8B"
                                )
                                val currentModelLabel = groqModels.firstOrNull { it.first == selectedGroqModel }?.second ?: selectedGroqModel

                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF0D182E),
                                        border = BorderStroke(1.dp, JarvisCyan),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { modelDropdownExpanded = true }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(currentModelLabel, color = JarvisCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                Text("Selected ID: $selectedGroqModel", color = JarvisTextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                            }
                                            Icon(
                                                imageVector = Icons.Default.ArrowDropDown,
                                                contentDescription = "Select Model",
                                                tint = JarvisCyan
                                            )
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = modelDropdownExpanded,
                                        onDismissRequest = { modelDropdownExpanded = false },
                                        modifier = Modifier
                                            .fillMaxWidth(0.85f)
                                            .background(Color(0xFF0B1626))
                                            .border(BorderStroke(1.dp, JarvisCyan), RoundedCornerShape(8.dp))
                                    ) {
                                        groqModels.forEach { (modelId, label) ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text(
                                                            label,
                                                            color = if (modelId == selectedGroqModel) JarvisCyan else Color.White,
                                                            fontWeight = if (modelId == selectedGroqModel) FontWeight.Bold else FontWeight.Normal,
                                                            fontSize = 12.sp
                                                        )
                                                        Text(modelId, color = JarvisTextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                                    }
                                                },
                                                onClick = {
                                                    selectedGroqModel = modelId
                                                    GroqRepository.selectedGroqModel = modelId
                                                    prefs.edit().putString("selected_groq_model", modelId).apply()
                                                    modelDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            } else if (providerMode == "OPENROUTER") {
                                Text("OpenRouter API Key:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = JarvisTextPrimary)
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = openRouterApiKey,
                                    onValueChange = { openRouterApiKey = it },
                                    placeholder = { Text("sk-or-v1-...", color = JarvisTextSecondary) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = JarvisCyan,
                                        unfocusedBorderColor = Color(0x4400E5FF),
                                        focusedTextColor = JarvisTextPrimary,
                                        unfocusedTextColor = JarvisTextPrimary
                                    )
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Get an API key at openrouter.ai/keys (Access 100+ models)",
                                    fontSize = 11.sp,
                                    color = JarvisTextSecondary
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text("OpenRouter Model Selection:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = JarvisCyan)
                                Spacer(modifier = Modifier.height(4.dp))

                                var openRouterDropdownExpanded by remember { mutableStateOf(false) }
                                val openRouterModels = GroqRepository.OPENROUTER_MODELS
                                val currentOpenRouterLabel = openRouterModels.firstOrNull { it.first == selectedOpenRouterModel }?.second ?: selectedOpenRouterModel

                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF0D182E),
                                        border = BorderStroke(1.dp, JarvisCyan),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { openRouterDropdownExpanded = true }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(currentOpenRouterLabel, color = JarvisCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                Text("Selected ID: $selectedOpenRouterModel", color = JarvisTextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                            }
                                            Icon(
                                                imageVector = Icons.Default.ArrowDropDown,
                                                contentDescription = "Select Model",
                                                tint = JarvisCyan
                                            )
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = openRouterDropdownExpanded,
                                        onDismissRequest = { openRouterDropdownExpanded = false },
                                        modifier = Modifier
                                            .fillMaxWidth(0.85f)
                                            .background(Color(0xFF0B1626))
                                            .border(BorderStroke(1.dp, JarvisCyan), RoundedCornerShape(8.dp))
                                    ) {
                                        openRouterModels.forEach { (modelId, label) ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text(
                                                            label,
                                                            color = if (modelId == selectedOpenRouterModel) JarvisCyan else Color.White,
                                                            fontWeight = if (modelId == selectedOpenRouterModel) FontWeight.Bold else FontWeight.Normal,
                                                            fontSize = 12.sp
                                                        )
                                                        Text(modelId, color = JarvisTextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                                    }
                                                },
                                                onClick = {
                                                    selectedOpenRouterModel = modelId
                                                    GroqRepository.selectedOpenRouterModel = modelId
                                                    prefs.edit().putString("selected_openrouter_model", modelId).apply()
                                                    openRouterDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Custom OpenRouter Model ID:", fontSize = 11.sp, color = JarvisTextPrimary)
                                OutlinedTextField(
                                    value = selectedOpenRouterModel,
                                    onValueChange = {
                                        selectedOpenRouterModel = it.trim()
                                        GroqRepository.selectedOpenRouterModel = it.trim()
                                        prefs.edit().putString("selected_openrouter_model", it.trim()).apply()
                                    },
                                    placeholder = { Text("e.g. meta-llama/llama-3.2-3b-instruct:free", color = JarvisTextSecondary) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = JarvisCyan,
                                        unfocusedBorderColor = Color(0x4400E5FF),
                                        focusedTextColor = JarvisTextPrimary,
                                        unfocusedTextColor = JarvisTextPrimary
                                    )
                                )
                            } else if (providerMode == "LOCAL_PC") {
                                Text("Local PC Server Endpoint (OpenAI API):", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = JarvisCyan)
                                Text("Host on PC via LM Studio / Ollama (e.g. http://192.168.1.35:1234/v1/):", fontSize = 10.sp, color = JarvisTextSecondary)
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = localPcUrl,
                                    onValueChange = { localPcUrl = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = JarvisCyan,
                                        unfocusedBorderColor = Color(0x4400E5FF),
                                        focusedTextColor = JarvisTextPrimary,
                                        unfocusedTextColor = JarvisTextPrimary
                                    )
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("Model Name:", fontSize = 11.sp, color = JarvisTextPrimary)
                                OutlinedTextField(
                                    value = localModelName,
                                    onValueChange = { localModelName = it },
                                    placeholder = { Text("local-model", color = JarvisTextSecondary) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = JarvisCyan,
                                        unfocusedBorderColor = Color(0x4400E5FF),
                                        focusedTextColor = JarvisTextPrimary,
                                        unfocusedTextColor = JarvisTextPrimary
                                    )
                                )
                            } else if (providerMode == "ON_DEVICE") {
                                var onDeviceStatus by remember { mutableStateOf(
                                    if (com.example.bro.agent.OnDeviceLlmManager.isModelLoaded)
                                        "✅ Loaded: ${com.example.bro.agent.OnDeviceLlmManager.loadedModelName}\nBackend: ${com.example.bro.agent.OnDeviceLlmManager.loadedModelBackend.ifBlank { "N/A" }}\nPath: ${com.example.bro.agent.OnDeviceLlmManager.loadedModelPath}"
                                    else "⚪ No model loaded"
                                ) }
                                var isLoadingModel by remember { mutableStateOf(false) }
                                var directFilePathInput by remember { mutableStateOf("") }

                                Text("📱 On-Device Model (Zero Network)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = JarvisCyan)
                                Spacer(modifier = Modifier.height(4.dp))

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                                    OutlinedButton(
                                        onClick = {
                                            try {
                                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                                    data = Uri.parse("package:" + context.packageName)
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                                context.startActivity(intent)
                                            }
                                        },
                                        border = BorderStroke(1.dp, Color(0xFFF59E0B)),
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                                    ) {
                                        Text("📂 Grant Storage Permission (All Files Access)", fontSize = 10.sp, color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold)
                                    }
                                }

                                Text("Model Directory or Direct File Path:", fontSize = 11.sp, color = JarvisTextPrimary)
                                OutlinedTextField(
                                    value = customModelDirPath,
                                    onValueChange = {
                                        customModelDirPath = it
                                        com.example.bro.agent.OnDeviceLlmManager.customModelDirPath = it
                                        prefs.edit().putString("on_device_model_dir", it).apply()
                                    },
                                    placeholder = { Text("/sdcard/Download/ or /sdcard/BRO/gemma4-e2b-it-gpu.litertlm", color = JarvisTextSecondary) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = JarvisCyan,
                                        unfocusedBorderColor = Color(0x4400E5FF),
                                        focusedTextColor = JarvisTextPrimary,
                                        unfocusedTextColor = JarvisTextPrimary
                                    )
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                // Direct File Path Load Action
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = directFilePathInput,
                                        onValueChange = { directFilePathInput = it },
                                        placeholder = { Text("Paste exact file path (e.g. /sdcard/Download/gemma4-e2b-it-gpu.litertlm)", fontSize = 10.sp, color = JarvisTextSecondary) },
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = JarvisCyan,
                                            unfocusedBorderColor = Color(0x4400E5FF),
                                            focusedTextColor = JarvisTextPrimary,
                                            unfocusedTextColor = JarvisTextPrimary
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Button(
                                        onClick = {
                                            if (directFilePathInput.isNotBlank()) {
                                                isLoadingModel = true
                                                onDeviceStatus = "⏳ Loading ${directFilePathInput.trim()}..."
                                                scope.launch {
                                                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                        com.example.bro.agent.OnDeviceLlmManager.loadModel(context, directFilePathInput.trim())
                                                    }
                                                    isLoadingModel = false
                                                    result.onSuccess { msg ->
                                                        onDeviceStatus = "✅ $msg\nPath: ${com.example.bro.agent.OnDeviceLlmManager.loadedModelPath}"
                                                    }
                                                    result.onFailure { err ->
                                                        onDeviceStatus = "❌ ${err.message}"
                                                    }
                                                }
                                            }
                                        },
                                        enabled = !isLoadingModel && directFilePathInput.isNotBlank(),
                                        colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(38.dp)
                                    ) {
                                        Text("Load File", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Text(
                                    "LiteRT-LM files named with -gpu or _gpu load with the GPU backend; other LiteRT-LM models use CPU. Vision encoding remains on CPU.",
                                    fontSize = 10.sp,
                                    color = JarvisTextSecondary
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                // Status Box
                                Surface(
                                    color = if (com.example.bro.agent.OnDeviceLlmManager.isModelLoaded) Color(0xFF0A2E1A) else Color(0xFF1A1A2E),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        onDeviceStatus,
                                        modifier = Modifier.padding(8.dp),
                                        fontSize = 11.sp,
                                        color = if (com.example.bro.agent.OnDeviceLlmManager.isModelLoaded) Color(0xFF10B981) else JarvisTextSecondary
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))

                                // Available models list
                                val availableModels = com.example.bro.agent.OnDeviceLlmManager.listAvailableModels(context)
                                if (availableModels.isEmpty()) {
                                    Text("⚠️ No model files found in search paths. Enter a direct file path above or copy a model (.litertlm, .onnx, .task, .gguf, .bin) to /sdcard/Download/ or /sdcard/BRO/.", fontSize = 10.sp, color = Color(0xFFF59E0B))
                                } else {
                                    Text("Discovered Model Files (${availableModels.size}):", fontSize = 11.sp, color = JarvisTextPrimary)
                                    availableModels.forEach { modelInfo ->
                                        val isLoaded = modelInfo.absolutePath == com.example.bro.agent.OnDeviceLlmManager.loadedModelPath ||
                                                modelInfo.fileName == com.example.bro.agent.OnDeviceLlmManager.loadedModelName
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    modelInfo.fileName,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isLoaded) Color(0xFF10B981) else Color.White
                                                )
                                                Text(
                                                    "${modelInfo.absolutePath} (${modelInfo.sizeBytes / 1024} KB)",
                                                    fontSize = 9.sp,
                                                    color = JarvisTextSecondary,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Button(
                                                onClick = {
                                                    isLoadingModel = true
                                                    onDeviceStatus = "⏳ Loading ${modelInfo.fileName}..."
                                                    scope.launch {
                                                        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                            com.example.bro.agent.OnDeviceLlmManager.loadModel(context, modelInfo.absolutePath)
                                                        }
                                                        isLoadingModel = false
                                                        result.onSuccess { msg ->
                                                            onDeviceStatus = "✅ $msg\nPath: ${com.example.bro.agent.OnDeviceLlmManager.loadedModelPath}"
                                                        }
                                                        result.onFailure { err ->
                                                            onDeviceStatus = "❌ ${err.message}"
                                                        }
                                                    }
                                                },
                                                enabled = !isLoadingModel,
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (isLoaded) Color(0xFF10B981) else JarvisCyan
                                                ),
                                                modifier = Modifier.height(28.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                            ) {
                                                Text(
                                                    if (isLoaded) "Loaded" else "Load",
                                                    fontSize = 9.sp,
                                                    color = Color.Black,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text("Primary Accessibility System Prompt:", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = JarvisCyan)
                            Text("This prompt is sent as the system message and takes priority over built-in planner wording. Edits save immediately and apply to the next planner request.", fontSize = 11.sp, color = JarvisTextSecondary)
                            Spacer(modifier = Modifier.height(6.dp))

                            OutlinedTextField(
                                value = systemPromptInput,
                                onValueChange = {
                                    systemPromptInput = it
                                    com.example.bro.planner.SystemPromptManager.saveSystemPrompt(context, it)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp),
                                textStyle = LocalTextStyle.current.copy(
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = JarvisCyan,
                                    unfocusedBorderColor = Color(0x4400E5FF),
                                    focusedTextColor = JarvisTextPrimary,
                                    unfocusedTextColor = JarvisTextPrimary
                                )
                            )

                            Spacer(modifier = Modifier.height(14.dp))
                            Text("Primary Vision Mode System Prompt:", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = JarvisCyan)
                            Text("Used as the system message only in screenshot-based Vision mode. It is separate from Accessibility and saves immediately.", fontSize = 11.sp, color = JarvisTextSecondary)
                            Spacer(modifier = Modifier.height(6.dp))

                            OutlinedTextField(
                                value = visionPromptInput,
                                onValueChange = {
                                    visionPromptInput = it
                                    com.example.bro.planner.SystemPromptManager.saveVisionSystemPrompt(context, it)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp),
                                textStyle = LocalTextStyle.current.copy(
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = JarvisCyan,
                                    unfocusedBorderColor = Color(0x4400E5FF),
                                    focusedTextColor = JarvisTextPrimary,
                                    unfocusedTextColor = JarvisTextPrimary
                                )
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            HorizontalDivider(color = Color(0x3300E5FF), modifier = Modifier.padding(vertical = 8.dp))
                            Text("🌐 WEB SEARCH ENGINE", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = JarvisCyan)
                            Text("Enables real-time web grounding for live facts & autonomous app struggle recovery.", fontSize = 10.sp, color = JarvisTextSecondary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Enable Web Search (Free Engine)", fontSize = 11.sp, color = JarvisTextPrimary)
                                Switch(
                                    checked = isWebSearchEnabled,
                                    onCheckedChange = {
                                        isWebSearchEnabled = it
                                        com.example.bro.search.WebSearchEngine.enabled = it
                                        prefs.edit().putBoolean("web_search_enabled", it).apply()
                                    }
                                )
                            }
                            if (isWebSearchEnabled) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Optional Tavily API Key (leave empty for DuckDuckGo):", fontSize = 10.sp, color = JarvisTextSecondary)
                                OutlinedTextField(
                                    value = tavilyApiKeyInput,
                                    onValueChange = {
                                        tavilyApiKeyInput = it
                                        com.example.bro.search.WebSearchEngine.tavilyApiKey = it.trim()
                                        prefs.edit().putString("tavily_api_key", it.trim()).apply()
                                    },
                                    placeholder = { Text("tvly-...", color = JarvisTextSecondary) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = JarvisCyan,
                                        unfocusedBorderColor = Color(0x4400E5FF),
                                        focusedTextColor = JarvisTextPrimary,
                                        unfocusedTextColor = JarvisTextPrimary
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        com.example.bro.planner.SystemPromptManager.resetSystemPrompt(context)
                                        systemPromptInput = com.example.bro.planner.SystemPromptManager.getSystemPrompt(context)
                                        Toast.makeText(context, "🔄 System Rules Reset to Default!", Toast.LENGTH_SHORT).show()
                                    },
                                    border = BorderStroke(1.dp, JarvisRed)
                                ) {
                                    Text("Reset Access", fontSize = 10.sp, color = JarvisRed)
                                }

                                OutlinedButton(
                                    onClick = {
                                        com.example.bro.planner.SystemPromptManager.resetVisionSystemPrompt(context)
                                        visionPromptInput = com.example.bro.planner.SystemPromptManager.getVisionSystemPrompt(context)
                                        Toast.makeText(context, "Vision prompt reset to default.", Toast.LENGTH_SHORT).show()
                                    },
                                    border = BorderStroke(1.dp, JarvisRed)
                                ) {
                                    Text("Reset Vision", fontSize = 10.sp, color = JarvisRed)
                                }

                                Button(
                                    onClick = {
                                        GroqRepository.apiKey = apiKey.trim()
                                        GroqRepository.providerMode = providerMode
                                        GroqRepository.localPcUrl = localPcUrl.trim()
                                        GroqRepository.localModelName = localModelName.trim()
                                        GroqRepository.selectedGroqModel = selectedGroqModel
                                        GroqRepository.openRouterApiKey = openRouterApiKey.trim()
                                        GroqRepository.selectedOpenRouterModel = selectedOpenRouterModel.trim()

                                        prefs.edit()
                                            .putString("groq_api_key", apiKey.trim())
                                            .putString("provider_mode", providerMode)
                                            .putString("local_pc_url", localPcUrl.trim())
                                            .putString("local_model_name", localModelName.trim())
                                            .putString("selected_groq_model", selectedGroqModel)
                                            .putString("openrouter_api_key", openRouterApiKey.trim())
                                            .putString("selected_openrouter_model", selectedOpenRouterModel.trim())
                                            .putBoolean("web_search_enabled", isWebSearchEnabled)
                                            .putString("tavily_api_key", tavilyApiKeyInput.trim())
                                            .apply()

                                        com.example.bro.planner.SystemPromptManager.saveSystemPrompt(context, systemPromptInput)
                                        com.example.bro.planner.SystemPromptManager.saveVisionSystemPrompt(context, visionPromptInput)
                                        Toast.makeText(context, "💾 Saved provider and both mode prompts.", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan)
                                ) {
                                    Text("Save Settings", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    val logs = com.example.bro.util.ExecutionLogger.logs
                    Column {
                        // File Export Header Status Box
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF091120)),
                            border = BorderStroke(0.5.dp, Color(0x4400E5FF))
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text("📄 TASK LOG FILE", fontSize = 11.sp, color = JarvisCyan, fontWeight = FontWeight.Bold)
                                val logPath = com.example.bro.util.ExecutionLogger.getLogFilePath(context)
                                Text(
                                    text = logPath,
                                    fontSize = 9.sp,
                                    color = JarvisTextSecondary,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Button(
                                    onClick = {
                                        isExportingLog = true
                                        scope.launch {
                                            try {
                                                val fileName = com.example.bro.util.ExecutionLogger
                                                    .exportLogFileToDownloads(context)
                                                Toast.makeText(
                                                    context,
                                                    "Log exported to Downloads/$fileName",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                Log.e("BRO_LOGGER", "Failed to export task log", e)
                                                Toast.makeText(
                                                    context,
                                                    "Could not export log: ${e.message ?: "unknown error"}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            } finally {
                                                isExportingLog = false
                                            }
                                        }
                                    },
                                    enabled = !isExportingLog,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan)
                                ) {
                                    Text(
                                        if (isExportingLog) "Exporting log..." else "Download log to Downloads",
                                        fontSize = 11.sp,
                                        color = Color.Black,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("BRO_Log_Path", logPath)
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(context, "📋 Log path copied to clipboard!", Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Text("Copy Path", fontSize = 10.sp, color = JarvisCyan)
                                    }
                                    TextButton(
                                        onClick = {
                                            com.example.bro.util.ExecutionLogger.clearLogFile(context)
                                            Toast.makeText(context, "🗑️ Log text file cleared!", Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Text("Clear File", fontSize = 10.sp, color = JarvisRed)
                                    }
                                }
                            }
                        }

                        if (logs.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No execution logs recorded yet.\nRun a task to inspect LLM exchanges!", color = JarvisTextSecondary, fontSize = 13.sp)
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Recent In-App Logs (${logs.size})", fontSize = 11.sp, color = JarvisCyan, fontFamily = FontFamily.Monospace)
                                TextButton(onClick = { com.example.bro.util.ExecutionLogger.clear() }) {
                                    Text("Clear UI", fontSize = 11.sp, color = JarvisRed)
                                }
                            }
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(240.dp)
                            ) {
                                items(logs) { entry ->
                                    InspectorCardItem(entry)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        GroqRepository.apiKey = apiKey.trim()
                        GroqRepository.openRouterApiKey = openRouterApiKey.trim()
                        GroqRepository.selectedOpenRouterModel = selectedOpenRouterModel.trim()
                        prefs.edit()
                            .putString("groq_api_key", apiKey.trim())
                            .putString("openrouter_api_key", openRouterApiKey.trim())
                            .putString("selected_openrouter_model", selectedOpenRouterModel.trim())
                            .putBoolean("web_search_enabled", isWebSearchEnabled)
                            .putString("tavily_api_key", tavilyApiKeyInput.trim())
                            .apply()
                        com.example.bro.planner.SystemPromptManager.saveSystemPrompt(context, systemPromptInput)
                        com.example.bro.planner.SystemPromptManager.saveVisionSystemPrompt(context, visionPromptInput)
                        showSettingsDialog = false
                        Toast.makeText(context, "Settings Saved!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan)
                ) {
                    Text("Close", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

fun openApp(context: Context, appName: String?, goal: String? = null): Boolean {
    if (appName == null) return false
    val directIntent = context.packageManager.getLaunchIntentForPackage(appName)
    val packageName = when {
        directIntent != null -> appName
        else -> AppLauncher.findPackage(appName) ?: if (appName.contains('.')) appName else null
    }

    if (packageName == null) {
        Toast.makeText(context, "App '$appName' not found", Toast.LENGTH_SHORT).show()
        return false
    }
    if (packageName.contains("dialer", ignoreCase = true) ||
        packageName.contains("phone", ignoreCase = true)
    ) {
        val explicitCallGoal = goal?.let {
            Regex(
                """\b(call|dial)\s+[\p{L}0-9+]|\b(?:make|place)\s+(?:a\s+)?call\s+to\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(it)
        } == true
        if (!explicitCallGoal) {
            Log.w("BRO_MAIN", "Blocked dialer launch because the task does not explicitly request a call.")
            return false
        }
    }

    val intent = directIntent ?: context.packageManager.getLaunchIntentForPackage(packageName)
    if (intent == null) {
        Toast.makeText(context, "Cannot launch '$appName'", Toast.LENGTH_SHORT).show()
        return false
    }

    return try {
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.e("BRO_MAIN", "Could not launch '$appName': ${e.message}", e)
        Toast.makeText(context, "Cannot launch '$appName'", Toast.LENGTH_SHORT).show()
        false
    }
}

private fun isLockScreenGoal(goal: String): Boolean =
    Regex(
        """\block\s+(?:(?:my|the)\s+)?(?:phone|screen|device)\b""",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(goal)

fun returnToBroApp(context: Context) {
    try {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.e("BRO_MAIN", "Error returning to BRO app: ${e.message}")
    }
}

private fun actionSignature(action: com.example.bro.model.Action): String = buildString {
    append(action.type)
    action.targetId?.let { append(":id=$it") }
    action.target?.let { append(":target=$it") }
    if (action.x != null && action.y != null) append(":xy=${action.x},${action.y}")
    action.text?.let { append(":text=$it") }
}

private fun screenFingerprint(structure: String): String =
    Integer.toHexString(structure.hashCode())

private fun recordRecentStep(
    history: MutableList<String>,
    step: Int,
    screen: ScreenState,
    canonicalOutput: String,
    action: com.example.bro.model.Action,
    targetLabel: String?,
    result: String
) {
    val screenState = Regex("""(?m)^SCREEN_STATE:\s*(.+)$""")
        .find(canonicalOutput)?.groupValues?.get(1)?.trim()
        ?: "UNKNOWN"
    val target = targetLabel?.takeIf(String::isNotBlank)
        ?: action.target
        ?: action.packageName
        ?: action.text
        ?: action.targetId?.let { "node $it" }
        ?: action.type.name
    history.add("Step $step: [${screen.packageName} / $screenState] -> ${action.type}($target) -> $result")
    while (history.size > 5) history.removeAt(0)
}

private fun actionTargetLabel(action: com.example.bro.model.Action): String? =
    action.targetId?.let { id ->
        val node = com.example.bro.accessibility.NodeRepository.get(id) ?: return@let null
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString(), node.hintText?.toString())
            .firstOrNull(String::isNotBlank)
            ?.trim()
    }

private fun screenSignature(screen: ScreenState): String {
    val nodes = screen.nodes
        .take(80)
        .map { node ->
            val label = (node.text?.trim() ?: node.contentDescription?.trim().orEmpty())
                .replace(Regex("\\s+"), " ")
                .take(60)
            "${node.id}:$label:${node.className}:${node.clickable}:${node.enabled}:${node.editable}:${node.checkable}:${node.checked}"
        }
        .joinToString("|")

    return "${screen.packageName}#${screen.nodes.size}#$nodes"
}

private data class VisionFallbackOutcome(
    val action: com.example.bro.model.Action?,
    val executed: Boolean,
    val screenChanged: Boolean,
    val detail: String
)

private class AccessibilityVisionFallback(
    private val context: Context,
    private val service: BroAccessibilityService,
    private val goal: String,
    private val memory: AgentMemory,
    private val recentStepHistory: MutableList<String>,
    private val screenReader: ScreenReader
) {
    private val attemptedScreenHashes = mutableSetOf<String>()

    suspend fun attempt(
        screen: ScreenState,
        screenHash: String,
        step: Int,
        canonicalOutput: String,
        previousSignature: String
    ): VisionFallbackOutcome? {
        // Accessibility-only mode: disable vision fallback so no vision model interferes
        return null
    }
}

private fun isVisionFallbackUncertainty(text: String): Boolean =
    Regex(
        """\b(not sure|unclear|can't tell|cannot tell|can't determine|cannot determine|unable to determine|unable to identify|which option should i|which one should i)\b""",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(text)

private fun visibleScreenText(screen: ScreenState): List<String> =
    screen.nodes.mapNotNull {
        it.text?.trim() ?: it.contentDescription?.trim()
    }.filter { text ->
        text.length > 2 &&
            !text.contains("com.") &&
            !text.equals("Search", ignoreCase = true) &&
            !text.equals("Chats", ignoreCase = true) &&
            !text.equals("Calls", ignoreCase = true) &&
            !text.equals("Status", ignoreCase = true) &&
            !text.equals("Communities", ignoreCase = true)
    }.distinct()

private suspend fun waitForScreenChange(
    service: BroAccessibilityService,
    screenReader: ScreenReader,
    previousSignature: String,
    timeoutMs: Long = 5000L,
    pollMs: Long = 250L
): Boolean {

    val start = System.currentTimeMillis()

    while (System.currentTimeMillis() - start < timeoutMs) {

        delay(pollMs)

        val root = service.getRoot() ?: continue
        val currentScreen = screenReader.read(root)
        val currentSignature = screenSignature(currentScreen)

        if (currentSignature != previousSignature) {

            Log.d(
                "BRO_STATE",
                "UI changed after ${System.currentTimeMillis() - start}ms"
            )

            return true
        }
    }

    Log.w(
        "BRO_STATE",
        "UI did not change within ${timeoutMs}ms"
    )

    return false
}

private suspend fun recoverStuckTask(
    service: BroAccessibilityService
): String? {
    return if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
        delay(600)
        "Pressed BACK to leave the stuck screen"
    } else {
        null
    }
}

suspend fun executeGoalWithVisionMode(
    context: Context,
    goalStr: String,
    userOriginalPrompt: String,
    userGuidance: String?,
    messages: MutableList<Message>,
    onComplete: ((goal: String, screen: ScreenState?, actions: List<com.example.bro.model.Action>) -> Unit)?,
    onQuestion: OnQuestionCallback? = null
) {
    val service = BroAccessibilityService.instance
    if (service == null) {
        val question = "Vision mode needs the Accessibility service for screenshots and actions. You can enable it, switch modes, or cancel."
        messages.add(Message("⚠️ $question", false))
        onQuestion?.invoke(question, goalStr, listOf("Switch to Accessibility mode", "Cancel task"), "", "", "")
        return
    }

    val manager = com.example.bro.agent.OnDeviceLlmManager
    if (!manager.isModelLoaded || !manager.loadedModelName.endsWith(".litertlm", ignoreCase = true)) {
        val question = "Vision mode needs a loaded on-device LiteRT-LM model. Screenshots are never sent to remote providers."
        messages.add(Message("⚠️ $question", false))
        onQuestion?.invoke(question, goalStr, listOf("Switch to Accessibility mode", "Cancel task"), "", "", "")
        return
    }
    try {
        manager.prepareModelForVision(context)?.let { notice ->
            messages.add(Message("ℹ️ $notice", false))
        }
    } catch (e: Exception) {
        Log.e("BRO_VISION", "Could not prepare the selected model for Vision", e)
        val question = e.message ?: "The selected model does not support Vision input."
        messages.add(Message("⚠️ $question", false))
        onQuestion?.invoke(question, goalStr, listOf("Switch to Accessibility mode", "Cancel task"), "", "", "")
        return
    }

    val planner = GroqPlanner()
    val memory = AgentMemory()
    val executor = com.example.bro.executor.VisionActionExecutor(context, service)
    var previousActionSignature = ""
    var repeatedActionCount = 0
    var recoveryFeedback: String? = null

    for (step in 1..15) {
        var screenshot: ByteArray? = null
        for (attempt in 0..2) {
            screenshot = service.captureScreenshotJpeg()
            if (screenshot != null) break
            if (attempt < 2) delay(500)
        }
        val currentScreenshot = screenshot
        if (currentScreenshot == null) {
            val question = "I couldn't capture the current screen after several attempts. You can retry, use Accessibility mode, or cancel."
            messages.add(Message("⚠️ $question", false))
            onQuestion?.invoke(
                question,
                goalStr,
                listOf("Retry screenshot", "Switch to Accessibility mode", "Cancel task")
            )
            returnToBroApp(context)
            return
        }

        var decision: com.example.bro.model.PlannerResponse? = null
        var actionError = ""
        for (attempt in 0..2) {
            val candidate = try {
                planner.planVision(
                    goal = Goal(goalStr),
                    screenshot = currentScreenshot,
                    memory = memory,
                    context = context,
                    recoveryFeedback = recoveryFeedback,
                    userGuidance = userGuidance
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BRO_VISION", "Vision planning failed for model ${manager.loadedModelName}", e)
                val question = "Vision planning failed: ${e.message ?: "the model could not produce a response"}"
                messages.add(Message("⚠️ $question", false))
                onQuestion?.invoke(
                    question,
                    goalStr,
                    listOf("Retry task", "Switch to Accessibility mode", "Cancel task")
                )
                returnToBroApp(context)
                return
            }
            val candidateAction = candidate.action
            actionError = when {
                candidateAction.targetId != null -> "Do not use node IDs in Vision mode."
                candidateAction.type == ActionType.CLICK &&
                    (candidateAction.x == null || candidateAction.y == null) ->
                    "CLICK needs numeric x and y coordinates from the screenshot."
                candidateAction.type == ActionType.TYPE &&
                    (candidateAction.x == null || candidateAction.y == null || candidateAction.text.isNullOrBlank()) ->
                    "TYPE needs numeric x and y coordinates and text."
                candidateAction.type == ActionType.SEARCH || candidateAction.type == ActionType.SWIPE ->
                    "That action is not supported in Vision mode; choose a supported screenshot-based action."
                candidateAction.type == ActionType.TOGGLE_QUICK_SETTING ->
                    "TOGGLE_QUICK_SETTING is available only in Accessibility mode."
                candidateAction.type == ActionType.SEND_MESSAGE ->
                    "SEND_MESSAGE is available only in Accessibility mode."
                candidateAction.type == ActionType.LOCK_SCREEN ->
                    "LOCK_SCREEN is available only in Accessibility mode."
                else -> ""
            }
            if (actionError.isBlank()) {
                decision = candidate
                recoveryFeedback = null
                break
            }
            recoveryFeedback = actionError
        }
        if (decision == null) {
            com.example.bro.util.ExecutionLogger.recordNoAndroidCommand(
                context, goalStr, step, "vision",
                "Vision action was rejected before dispatch: $actionError"
            )
            val question = "I couldn't find a usable screenshot-based action after retrying. $actionError"
            messages.add(Message("⚠️ $question", false))
            onQuestion?.invoke(
                question,
                goalStr,
                listOf("Try another approach", "Switch to Accessibility mode", "Cancel task")
            )
            returnToBroApp(context)
            return
        }
        val resolvedDecision = decision
        val action = resolvedDecision.action

        if (resolvedDecision.thought.isNotBlank()) {
            messages.add(Message("💡 [Vision step $step] ${action.summary ?: resolvedDecision.thought}", false))
        }

        when (action.type) {
            ActionType.FINISH -> {
                com.example.bro.util.ExecutionLogger.recordNoAndroidCommand(
                    context, goalStr, step, "vision", "LLM selected FINISH."
                )
                val result = action.summary.orEmpty().ifBlank { "Task completed." }
                messages.add(Message("✅ $result", false))
                com.example.bro.agent.UserConversationMemory.addTurn(context, userOriginalPrompt, goalStr, result)
                onComplete?.invoke(goalStr, null, memory.previousActions)
                returnToBroApp(context)
                return
            }
            ActionType.READ_SCREEN -> {
                com.example.bro.util.ExecutionLogger.recordNoAndroidCommand(
                    context, goalStr, step, "vision", "LLM selected READ_SCREEN."
                )
                val result = action.summary.orEmpty().ifBlank { resolvedDecision.thought }
                val displayText = "📱 Screen content:\n${result.ifBlank { "The vision model could not read visible text." }}"
                messages.add(Message(displayText, false))
                com.example.bro.agent.UserConversationMemory.addTurn(context, userOriginalPrompt, goalStr, displayText)
                onComplete?.invoke(goalStr, null, memory.previousActions)
                returnToBroApp(context)
                return
            }
            ActionType.ASK_USER,
            ActionType.CHAT -> {
                com.example.bro.util.ExecutionLogger.recordNoAndroidCommand(
                    context, goalStr, step, "vision", "LLM selected ${action.type}; no Android action dispatched."
                )
                val text = action.question ?: action.summary.orEmpty().ifBlank { resolvedDecision.thought }.ifBlank { "Vision mode could not determine a safe next action." }
                messages.add(Message("❓ $text", false))
                onQuestion?.invoke(
                    text,
                    goalStr,
                    buildQuestionChoices(
                        action.options ?: resolvedDecision.choices,
                        screen = null
                    ).ifEmpty { listOf("Try another approach", "Switch to Accessibility mode") }
                )
                returnToBroApp(context)
                return
            }
            else -> Unit
        }

        val signature = "${action.type}:${action.x}:${action.y}:${action.text}:${action.packageName}"
        if (signature == previousActionSignature) repeatedActionCount++ else repeatedActionCount = 1
        previousActionSignature = signature
        if (repeatedActionCount >= 3) {
            com.example.bro.util.ExecutionLogger.recordNoAndroidCommand(
                context, goalStr, step, "vision",
                "Repeated-action guard stopped dispatch."
            )
            val question = "The same action has not moved the task forward. What should I try next?"
            messages.add(Message("⚠️ $question", false))
            onQuestion?.invoke(
                question,
                goalStr,
                listOf("Try another approach", "Switch to Accessibility mode", "Cancel task")
            )
            returnToBroApp(context)
            return
        }

        if (action.type == ActionType.FIND_APP) {
            val query = action.text.orEmpty().ifBlank { action.app.orEmpty() }.ifBlank { action.packageName.orEmpty() }
            val matches = AppLauncher.searchApps(query)
            val resultMessage = if (matches.isNotEmpty()) {
                "Found installed apps for '$query': " + matches.joinToString(", ") { "${it.label} (${it.packageName})" }
            } else {
                "No installed apps found matching '$query'."
            }
            messages.add(Message("🔍 $resultMessage", false))
            recoveryFeedback = resultMessage
            continue
        }

        val success = when (action.type) {
            ActionType.OPEN_APP ->
                openApp(context, action.app ?: action.packageName ?: action.target, goalStr)
            ActionType.WAIT -> {
                val waitMs = (action.text?.toLongOrNull() ?: 800L).coerceIn(100L, 8000L)
                delay(waitMs)
                true
            }
            ActionType.CLICK,
            ActionType.TYPE,
            ActionType.SCROLL,
            ActionType.BACK,
            ActionType.HOME,
            ActionType.LOCK_SCREEN -> executor.execute(action)
            else -> false
        }

        com.example.bro.util.ExecutionLogger.recordAndroidCommand(
            context, goalStr, step, "vision", action,
            if (success) "Vision executor accepted the action."
            else "Vision executor failed to dispatch or complete the action."
        )
        if (!success) {
            recoveryFeedback = "The previous ${action.type} action failed. Choose a different valid action based on the current screenshot."
            continue
        }

        recoveryFeedback = if (action.type == ActionType.TYPE) {
            "The text is on the clipboard and the target field was long-pressed. Use the new screenshot to locate and click the visible Paste control; do not repeat TYPE."
        } else {
            null
        }
        memory.remember(action, true, "Executed ${action.type} from screenshot")
        val settleDelayMs = when (action.type) {
            ActionType.OPEN_APP -> 2200L
            ActionType.WAIT -> 0L
            else -> 900L
        }
        if (settleDelayMs > 0) delay(settleDelayMs)
    }

    val question = "I reached the Vision action limit without confirming the task is complete. What should I try next?"
    messages.add(Message("⚠️ $question", false))
    onQuestion?.invoke(
        question,
        goalStr,
        listOf("Try another approach", "Switch to Accessibility mode", "Cancel task"),
        "",
        "",
        ""
    )
    returnToBroApp(context)
}

private enum class StepFlowResult {
    CONTINUE_BATCH,
    CONTINUE_LOOP,
    TERMINATE,
    BREAK_BATCH
}

private fun handleAskUserAction(
    context: Context,
    goalStr: String,
    step: Int,
    screen: ScreenState,
    canonicalOutput: String,
    act: com.example.bro.model.Action,
    actTargetLabel: String?,
    thought: String,
    decision: com.example.bro.model.PlannerResponse,
    recentStepHistory: MutableList<String>,
    messages: MutableList<Message>,
    onQuestion: OnQuestionCallback?
) {
    val questionText = act.question.orEmpty().ifBlank { act.summary.orEmpty() }.ifBlank { thought }
    val choices = buildQuestionChoices(act.options ?: decision.choices, screen)
    com.example.bro.util.ExecutionLogger.recordNoAndroidCommand(
        context, goalStr, step, screen.packageName,
        "LLM requested user decision via ASK_USER: $questionText"
    )
    recordRecentStep(
        recentStepHistory, step, screen, canonicalOutput, act,
        actTargetLabel, "Awaiting user decision: $questionText"
    )
    messages.add(Message("❓ $questionText", false))
    onQuestion?.invoke(
        questionText,
        goalStr,
        choices,
        screen.packageName,
        act.subgoal.orEmpty(),
        act.remainingPlan.orEmpty()
    )
    returnToBroApp(context)
}

private suspend fun handleChatAction(
    context: Context,
    goalStr: String,
    userOriginalPrompt: String,
    step: Int,
    screen: ScreenState,
    canonicalOutput: String,
    act: com.example.bro.model.Action,
    actTargetLabel: String?,
    thought: String,
    decision: com.example.bro.model.PlannerResponse,
    executor: ActionExecutor,
    visionFallback: AccessibilityVisionFallback,
    currentScreenHash: String,
    recentStepHistory: MutableList<String>,
    messages: MutableList<Message>,
    onQuestion: OnQuestionCallback?,
    onComplete: ((goal: String, screen: ScreenState?, actions: List<com.example.bro.model.Action>) -> Unit)?,
    memory: AgentMemory,
    checkpointSession: com.example.bro.agent.TaskCheckpointSession
): StepFlowResult {
    val chatText = act.summary.orEmpty().ifBlank { thought }
    com.example.bro.util.ExecutionLogger.recordNoAndroidCommand(
        context, goalStr, step, screen.packageName,
        "LLM returned CHAT; no Android command dispatched."
    )
    if (screen.packageName == context.packageName && chatText.isNotBlank()) {
        recordRecentStep(
            recentStepHistory, step, screen, canonicalOutput, act,
            actTargetLabel, "No-op: awaiting user input"
        )
        messages.add(Message("❓ $chatText", false))
        onQuestion?.invoke(
            chatText,
            goalStr,
            buildQuestionChoices(decision.choices, screen),
            screen.packageName,
            act.subgoal.orEmpty(),
            act.remainingPlan.orEmpty()
        )
        returnToBroApp(context)
        return StepFlowResult.TERMINATE
    } else if (chatText.isNotBlank()) {
        // AUTO-RECOVERY FOR WHATSAPP: If on WhatsApp main screen and model produced CHAT, auto-click Search
        if (screen.packageName == "com.whatsapp") {
            val searchNode = screen.nodes.firstOrNull {
                val txt = (it.text ?: it.contentDescription ?: "").lowercase()
                txt.contains("search") || txt.contains("ask meta")
            }
            if (searchNode != null) {
                messages.add(Message("⚡ Auto-Recovering: Clicking Search bar in WhatsApp...", false))
                val recoveryAction = com.example.bro.model.Action(
                    type = ActionType.CLICK,
                    targetId = searchNode.id
                )
                val recovered = executor.execute(recoveryAction)
                com.example.bro.util.ExecutionLogger.recordAndroidCommand(
                    context, goalStr, step, screen.packageName, recoveryAction,
                    if (recovered) "WhatsApp search recovery click succeeded."
                    else "WhatsApp search recovery click failed.",
                    actionTargetLabel(recoveryAction)
                )
                delay(1200)
                return StepFlowResult.BREAK_BATCH
            }
        }

        val isRealQuestion = chatText.contains("?") ||
                chatText.lowercase().contains("which app") ||
                chatText.lowercase().contains("which contact")

        if (screen.packageName != context.packageName &&
            isVisionFallbackUncertainty(chatText)
        ) {
            val fallback = visionFallback.attempt(
                screen,
                currentScreenHash,
                step,
                canonicalOutput,
                screenSignature(screen)
            )
            if (fallback?.executed == true) {
                checkpointSession.recoveryFeedback = fallback.detail
                checkpointSession.consecutiveRecoveryFailures = 0
                delay(400)
                return StepFlowResult.CONTINUE_LOOP
            }
            if (fallback != null) checkpointSession.recoveryFeedback = fallback.detail
        }

        if (isRealQuestion) {
            recordRecentStep(
                recentStepHistory, step, screen, canonicalOutput, act,
                actTargetLabel, "No-op: awaiting user input"
            )
            messages.add(Message("❓ $chatText", false))
            onQuestion?.invoke(
                chatText,
                goalStr,
                buildQuestionChoices(decision.choices, screen),
                screen.packageName,
                act.subgoal.orEmpty(),
                act.remainingPlan.orEmpty()
            )
            returnToBroApp(context)
            return StepFlowResult.TERMINATE
        } else {
            recordRecentStep(
                recentStepHistory, step, screen, canonicalOutput, act,
                actTargetLabel, "No-op: model response had no actionable command"
            )
            checkpointSession.consecutiveRecoveryFailures++
            checkpointSession.recoveryFeedback = "You returned conversational text instead of an actionable command. Output strictly a single JSON object. If the requested information or email is already visible on this screen, output {\"type\":\"FINISH\",\"reply\":\"<your summary>\"}. Otherwise choose an available action: CLICK, SCROLL, BACK."
            if (checkpointSession.consecutiveRecoveryFailures >= 3) {
                val summaryContent = chatText.ifBlank { thought }
                val displayText = "📋 Summary:\n$summaryContent"
                messages.add(Message(displayText, false))
                com.example.bro.agent.UserConversationMemory.addTurn(context, userOriginalPrompt, goalStr, displayText)
                onComplete?.invoke(goalStr, screen, memory.previousActions)
                returnToBroApp(context)
                return StepFlowResult.TERMINATE
            }
            messages.add(Message("⚠️ Model chatter: ${chatText.take(100)}...", false))
        }
    }
    delay(1000)
    return StepFlowResult.CONTINUE_BATCH
}

private suspend fun handleFinishAction(
    context: Context,
    planner: GroqPlanner,
    goalStr: String,
    normalizedGoal: String,
    userOriginalPrompt: String,
    step: Int,
    screen: ScreenState,
    act: com.example.bro.model.Action,
    memory: AgentMemory,
    messages: MutableList<Message>,
    onComplete: ((goal: String, screen: ScreenState?, actions: List<com.example.bro.model.Action>) -> Unit)?
) {
    com.example.bro.util.ExecutionLogger.recordNoAndroidCommand(
        context, goalStr, step, screen.packageName, "LLM selected ${act.type}."
    )
    val displayText = com.example.bro.executor.PlannerExecutionHelper.buildScreenSummaryDisplayText(
        planner, goalStr, normalizedGoal, screen, act, messages
    )
    messages.add(Message(displayText, false))
    com.example.bro.agent.UserConversationMemory.addTurn(context, userOriginalPrompt, goalStr, displayText)
    onComplete?.invoke(goalStr, screen, memory.previousActions)
    returnToBroApp(context)
}

private suspend fun handleWebSearchAction(
    context: Context,
    goalStr: String,
    step: Int,
    screen: ScreenState,
    canonicalOutput: String,
    act: com.example.bro.model.Action,
    thought: String,
    recentStepHistory: MutableList<String>,
    messages: MutableList<Message>,
    checkpointSession: com.example.bro.agent.TaskCheckpointSession
): StepFlowResult {
    val query = act.text.orEmpty().ifBlank { act.target.orEmpty() }.ifBlank { thought }
    messages.add(Message("🌐 Searching Web: '$query'...", false))
    val searchSummary = com.example.bro.search.WebSearchEngine.searchSummary(query)
    val resultDetail = if (searchSummary.isNotBlank()) {
        "Web Search Guidance for '$query':\n$searchSummary"
    } else {
        "Web Search returned no specific snippets for '$query'."
    }
    checkpointSession.recoveryFeedback = resultDetail
    recordRecentStep(recentStepHistory, step, screen, canonicalOutput, act, "WEB_SEARCH: $query", resultDetail)
    com.example.bro.util.ExecutionLogger.recordNoAndroidCommand(
        context, goalStr, step, screen.packageName,
        "Executed WEB_SEARCH for '$query'; injected guidance into planner context."
    )

    // If currently on BRO home screen, summarize the search answer directly and complete the task
    if (screen.packageName == context.packageName) {
        val augmentedPrompt = "User Request: $goalStr\n\nLive Web Search Results:\n$searchSummary\n\nPlease answer the user's question directly and concisely using the verified live web search results above."
        val reply = try {
            val useOnDevice = com.example.bro.agent.OnDeviceLlmManager.isModelLoaded &&
                (com.example.bro.network.GroqRepository.providerMode == "ON_DEVICE" || !com.example.bro.network.GroqRepository.isCloudConfigured())
            if (useOnDevice) {
                com.example.bro.agent.OnDeviceLlmManager.generateResponse(
                    systemPrompt = com.example.bro.planner.SystemPromptManager.DEFAULT_CHAT_SYSTEM_PROMPT,
                    userPrompt = augmentedPrompt
                )
            } else {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.bro.network.GroqRepository().sendPrompt(
                        systemPrompt = com.example.bro.planner.SystemPromptManager.DEFAULT_CHAT_SYSTEM_PROMPT,
                        userPrompt = augmentedPrompt
                    )
                }
            }
        } catch (e: Exception) {
            searchSummary
        }
        val cleanReply = reply.ifBlank { resultDetail }
        messages.add(Message(cleanReply, false))
        com.example.bro.agent.UserConversationMemory.addTurn(context, goalStr, goalStr, cleanReply)
        return StepFlowResult.TERMINATE
    }

    return StepFlowResult.CONTINUE_LOOP
}

private fun handleFindAppAction(
    context: Context,
    goalStr: String,
    step: Int,
    screen: ScreenState,
    canonicalOutput: String,
    act: com.example.bro.model.Action,
    recentStepHistory: MutableList<String>,
    messages: MutableList<Message>,
    checkpointSession: com.example.bro.agent.TaskCheckpointSession
): StepFlowResult {
    val query = act.text.orEmpty().ifBlank { act.app.orEmpty() }.ifBlank { act.packageName.orEmpty() }
    val matches = AppLauncher.searchApps(query)
    val resultMessage = if (matches.isNotEmpty()) {
        "Found installed apps for '$query': " + matches.joinToString(", ") { "${it.label} (${it.packageName})" }
    } else {
        "No installed apps found matching '$query'."
    }
    messages.add(Message("🔍 $resultMessage", false))
    checkpointSession.recoveryFeedback = resultMessage
    recordRecentStep(recentStepHistory, step, screen, canonicalOutput, act, "FIND_APP: $query", resultMessage)
    return StepFlowResult.CONTINUE_LOOP
}

private suspend fun handleOpenAppAction(
    context: Context,
    goalStr: String,
    step: Int,
    screen: ScreenState,
    canonicalOutput: String,
    act: com.example.bro.model.Action,
    actTargetLabel: String?,
    currentScreenHash: String,
    recentStepHistory: MutableList<String>,
    messages: MutableList<Message>,
    memory: AgentMemory,
    checkpointSession: com.example.bro.agent.TaskCheckpointSession,
    onQuestion: OnQuestionCallback?
): StepFlowResult {
    val appName = act.app ?: act.packageName ?: act.target
    val targetPkg = appName?.let { target ->
        AppLauncher.findPackage(target)
            ?: if (target.contains('.')) target else null
    }
    if (targetPkg != null && screen.packageName == targetPkg) {
        com.example.bro.util.ExecutionLogger.recordNoAndroidCommand(
            context, goalStr, step, screen.packageName,
            "OPEN_APP skipped because $targetPkg is already foreground."
        )
        Log.d("BRO_MAIN", "Target app $appName ($targetPkg) is already open on screen. Skipping re-launch.")
        delay(1000)
        return StepFlowResult.CONTINUE_BATCH
    } else {
        checkpointSession.persist(
            com.example.bro.agent.TaskCheckpointPhase.DISPATCHING,
            act,
            currentScreenFingerprint = currentScreenHash
        )
        if (!openApp(context, appName, goalStr)) {
            com.example.bro.util.ExecutionLogger.recordAndroidCommand(
                context, goalStr, step, screen.packageName, act,
                "Launch failed: could not resolve or start '$appName'."
            )
            memory.remember(act, false, "Could not resolve or launch app '$appName'")
            recordRecentStep(
                recentStepHistory, step, screen, canonicalOutput, act,
                actTargetLabel, "Failed: app launch did not succeed"
            )
            checkpointSession.consecutiveRecoveryFailures++
            if (checkpointSession.consecutiveRecoveryFailures <= 2) {
                checkpointSession.recoveryFeedback = "Could not open '$appName'. Choose a different reasonable way to continue the goal."
                messages.add(Message("⚠️ Could not open '$appName'. Trying another approach.", false))
                return StepFlowResult.CONTINUE_LOOP
            }
            val question = "I couldn't open '$appName'. Would you like to try another approach or cancel?"
            messages.add(Message("⚠️ $question", false))
            onQuestion?.invoke(question, goalStr, listOf("Try another approach", "Retry task", "Cancel task"))
            returnToBroApp(context)
            return StepFlowResult.TERMINATE
        }
        com.example.bro.util.ExecutionLogger.recordAndroidCommand(
            context, goalStr, step, screen.packageName, act,
            "Launch intent dispatched for '$appName'."
        )
        memory.remember(act, true, "Opened $appName")
        recordRecentStep(
            recentStepHistory, step, screen, canonicalOutput, act,
            actTargetLabel, "Opened app"
        )
        checkpointSession.lastActionType = act.type
        checkpointSession.lastTargetId = act.targetId
        delay(3000)
        return StepFlowResult.CONTINUE_BATCH
    }
}

private suspend fun handleLockScreenAction(
    context: Context,
    goalStr: String,
    userOriginalPrompt: String,
    step: Int,
    screen: ScreenState,
    canonicalOutput: String,
    act: com.example.bro.model.Action,
    actTargetLabel: String?,
    currentScreenHash: String,
    executor: ActionExecutor,
    recentStepHistory: MutableList<String>,
    messages: MutableList<Message>,
    memory: AgentMemory,
    checkpointSession: com.example.bro.agent.TaskCheckpointSession,
    onComplete: ((goal: String, screen: ScreenState?, actions: List<com.example.bro.model.Action>) -> Unit)?
) {
    checkpointSession.persist(
        com.example.bro.agent.TaskCheckpointPhase.DISPATCHING,
        act,
        currentScreenFingerprint = currentScreenHash
    )
    val locked = executor.execute(act)
    com.example.bro.util.ExecutionLogger.recordAndroidCommand(
        context, goalStr, step, screen.packageName, act,
        if (locked) "Lock-screen command accepted." else "Lock-screen command failed."
    )
    if (locked) {
        checkpointSession.lastActionType = act.type
        recordRecentStep(
            recentStepHistory, step, screen, canonicalOutput, act,
            actTargetLabel, "Lock action accepted"
        )
        val result = "✅ Screen locked."
        messages.add(Message(result, false))
        com.example.bro.agent.UserConversationMemory.addTurn(
            context, userOriginalPrompt, goalStr, result
        )
        onComplete?.invoke(goalStr, screen, memory.previousActions)
    } else {
        recordRecentStep(
            recentStepHistory, step, screen, canonicalOutput, act,
            actTargetLabel, "Failed: lock action failed"
        )
        messages.add(Message("⚠️ Android could not lock the screen.", false))
    }
}

private suspend fun handleToggleQuickSettingAction(
    context: Context,
    goalStr: String,
    step: Int,
    screen: ScreenState,
    canonicalOutput: String,
    act: com.example.bro.model.Action,
    currentScreenHash: String,
    executor: ActionExecutor,
    recentStepHistory: MutableList<String>,
    messages: MutableList<Message>,
    memory: AgentMemory,
    checkpointSession: com.example.bro.agent.TaskCheckpointSession,
    onQuestion: OnQuestionCallback?
): StepFlowResult {
    val tileLabel = act.target ?: act.text.orEmpty()
    checkpointSession.persist(
        com.example.bro.agent.TaskCheckpointPhase.DISPATCHING,
        act,
        currentScreenFingerprint = currentScreenHash
    )
    val success = executor.execute(act)
    com.example.bro.util.ExecutionLogger.recordAndroidCommand(
        context, goalStr, step, screen.packageName, act,
        if (success) "Quick-setting action succeeded." else "Quick-setting action failed."
    )
    if (success) {
        memory.remember(act, true, "Toggled Quick Settings tile '${act.target ?: act.text.orEmpty()}'")
        recordRecentStep(
            recentStepHistory,
            step,
            screen,
            canonicalOutput,
            act,
            tileLabel,
            "Quick Setting toggled"
        )
        checkpointSession.lastActionType = act.type
        checkpointSession.lastTargetId = act.targetId
        messages.add(
            Message(
                "✅ Toggled Quick Settings tile '$tileLabel'.",
                false
            )
        )
        returnToBroApp(context)
        return StepFlowResult.TERMINATE
    }

    memory.remember(act, false, "Quick Settings tile was unavailable or could not be clicked")
    recordRecentStep(
        recentStepHistory, step, screen, canonicalOutput, act,
        tileLabel, "Failed: Quick Settings tile unavailable"
    )
    checkpointSession.recoveryFeedback =
        "The requested Quick Settings tile could not be found. Choose a tile that is visible in the current Quick Settings screen or use another approach."
    checkpointSession.consecutiveRecoveryFailures++
    if (checkpointSession.consecutiveRecoveryFailures <= 2) {
        return StepFlowResult.CONTINUE_LOOP
    }
    val question = "I couldn't locate that Quick Settings tile. What should I try?"
    messages.add(Message("⚠️ $question", false))
    onQuestion?.invoke(question, goalStr, listOf("Try another approach", "Cancel task"))
    returnToBroApp(context)
    return StepFlowResult.TERMINATE
}

private suspend fun handleWaitAction(
    context: Context,
    goalStr: String,
    step: Int,
    screen: ScreenState,
    canonicalOutput: String,
    act: com.example.bro.model.Action,
    recentStepHistory: MutableList<String>,
    memory: AgentMemory
) {
    val waitMs = act.text?.toLongOrNull() ?: 2000L
    delay(waitMs)
    com.example.bro.util.ExecutionLogger.recordAndroidCommand(
        context, goalStr, step, screen.packageName, act,
        "Wait completed after ${waitMs}ms."
    )
    memory.remember(act, true, "Waited $waitMs ms")
    recordRecentStep(
        recentStepHistory,
        step,
        screen,
        canonicalOutput,
        act,
        "$waitMs ms",
        "Waited"
    )
}

private suspend fun handleStandardAccessibilityAction(
    context: Context,
    goalStr: String,
    step: Int,
    screen: ScreenState,
    canonicalOutput: String,
    act: com.example.bro.model.Action,
    actTargetLabel: String?,
    currentScreenHash: String,
    isExplicitSendClick: Boolean,
    service: BroAccessibilityService,
    executor: ActionExecutor,
    screenReader: ScreenReader,
    visionFallback: AccessibilityVisionFallback,
    recentStepHistory: MutableList<String>,
    messages: MutableList<Message>,
    memory: AgentMemory,
    checkpointSession: com.example.bro.agent.TaskCheckpointSession,
    onQuestion: OnQuestionCallback?,
    onIncident: (com.example.bro.model.ExecutionIncident?) -> Unit
): StepFlowResult {
    val beforeSignature = screenSignature(screen)
    val beforeHash = screenFingerprint(beforeSignature)

    Log.d(
        "BRO_STATE",
        "Executing ${act.type}, state=$beforeSignature"
    )

    checkpointSession.persist(
        com.example.bro.agent.TaskCheckpointPhase.DISPATCHING,
        act,
        currentScreenFingerprint = currentScreenHash
    )
    val success = executor.execute(act)

    if (!success) {
        com.example.bro.util.ExecutionLogger.recordAndroidCommand(
            context, goalStr, step, screen.packageName, act,
            "Executor reported failure; the command was not accepted or completed.",
            actTargetLabel
        )
        memory.remember(
            act,
            false,
            "Execution failed"
        )
        Log.w(
            "BRO_STATE",
            "${act.type} execution failed"
        )

        val currentRoot = service.getRoot()
        val stateUnchanged = currentRoot != null &&
            screenSignature(screenReader.read(currentRoot)) == beforeSignature
        val strategyFailures =
            checkpointSession.recordStrategyFailure(act, beforeHash)
        if (stateUnchanged && act.type in setOf(
                ActionType.CLICK, ActionType.TYPE, ActionType.SCROLL
            )
        ) {
            checkpointSession.blacklistedActions.add(
                BlacklistedAction(beforeHash, act.type, act.targetId)
            )
            checkpointSession.recoveryFeedback = if (act.targetId != null) {
                "Feedback: Your previous action on node ${act.targetId} failed to change the screen state. Node ${act.targetId} is now blacklisted. You must choose a DIFFERENT nodeId or use the BACK action to escape this screen."
            } else {
                "${act.type} did not change the screen state. This action is blacklisted on this screen; choose a different action or use BACK."
            }
        } else {
            checkpointSession.recoveryFeedback = "The previous ${act.type} action failed. Choose a different available action from the current screen."
        }
        if (strategyFailures >= 2) {
            checkpointSession.recoveryFeedback = checkpointSession.recoveryFeedback
        }

        recordRecentStep(
            recentStepHistory,
            step,
            screen,
            canonicalOutput,
            act,
            actTargetLabel,
            "Failed: ${if (stateUnchanged) "Screen unchanged" else "execution failed"}"
        )
        if (stateUnchanged) checkpointSession.consecutiveRecoveryFailures++
        else checkpointSession.consecutiveRecoveryFailures = 0
        if (checkpointSession.consecutiveRecoveryFailures <= 2) {
            messages.add(Message("⚠️ Accessibility ${act.type} failed. Trying a different action.", false))
            return StepFlowResult.CONTINUE_LOOP
        }
        val fallback = visionFallback.attempt(
            screen,
            beforeHash,
            step,
            canonicalOutput,
            beforeSignature
        )
        if (fallback?.executed == true) {
            checkpointSession.consecutiveRecoveryFailures = 0
            checkpointSession.recoveryAttempts = 0
            checkpointSession.recoveryFeedback = fallback.detail
            delay(400)
            return StepFlowResult.CONTINUE_LOOP
        }
        if (fallback != null) checkpointSession.recoveryFeedback = fallback.detail
        val recovery = if (checkpointSession.recoveryAttempts < 2) {
            recoverStuckTask(service)
        } else null
        if (recovery != null) {
            checkpointSession.recoveryAttempts++
            recentStepHistory.add("Step $step: [${screen.packageName} / recovery] -> $recovery -> Recovery executed")
            while (recentStepHistory.size > 5) recentStepHistory.removeAt(0)
            checkpointSession.consecutiveRecoveryFailures = 0
            delay(800)
            return StepFlowResult.CONTINUE_LOOP
        }
        val question = "The action failed repeatedly and automatic recovery could not proceed. What should I try next?"
        messages.add(Message("⚠️ $question", false))
        onQuestion?.invoke(question, goalStr, listOf("Try another approach", "Retry task", "Cancel task"))
        returnToBroApp(context)
        return StepFlowResult.TERMINATE
    }

    if (act.type == ActionType.SEND_MESSAGE ||
        (act.type == ActionType.CLICK && isExplicitSendClick)
    ) {
        checkpointSession.sendClickedThisTask = true
    }
    checkpointSession.lastActionType = act.type
    checkpointSession.lastTargetId = act.targetId

    memory.remember(
        act,
        true,
        "Executed ${act.type}"
    )

    val changed = waitForScreenChange(
        service = service,
        screenReader = screenReader,
        previousSignature = beforeSignature
    )

    com.example.bro.util.ExecutionLogger.recordAndroidCommand(
        context, goalStr, step, screen.packageName, act,
        if (changed) "Command accepted; screen changed."
        else "Command accepted; screen did not change.",
        actTargetLabel
    )

    memory.remember(
        act,
        changed,
        if (changed)
            "Action executed and UI changed"
        else
            "Action executed but UI remained unchanged. ERROR: No progress made. DO NOT REPEAT this action."
    )

    if (!changed) {
        val incident = com.example.bro.model.ExecutionIncident(
            step = step,
            attemptedAction = "${act.type}(targetId=${act.targetId ?: ""}, text=${act.text ?: ""})",
            failureKind = com.example.bro.model.IncidentKind.UNCHANGED_SCREEN,
            diagnostic = "The action dispatched successfully but the screen state remained identical.",
            suggestedDirective = "Do not re-attempt the identical action on this element. Try another element, scroll, or ask user."
        )
        onIncident(incident)
        val latestRoot = service.getRoot()
        val stateUnchanged = latestRoot != null &&
            screenSignature(screenReader.read(latestRoot)) == beforeSignature
        if (stateUnchanged && act.type in setOf(
                ActionType.CLICK, ActionType.TYPE, ActionType.SCROLL
            )
        ) {
            checkpointSession.blacklistedActions.add(
                BlacklistedAction(beforeHash, act.type, act.targetId)
            )
        }
        val attemptFeedback = if (stateUnchanged && act.targetId != null) {
            "Feedback: Your previous action on node ${act.targetId} failed to change the screen state. Node ${act.targetId} is now blacklisted. You must choose a DIFFERENT nodeId or use the BACK action to escape this screen."
        } else if (stateUnchanged) {
            "${act.type} did not change the screen. Choose a different valid action; do not repeat it."
        } else {
            "The current screen could not be verified after ${act.type}. Re-read the current screen before deciding what to do next."
        }
        val strategyFailures =
            checkpointSession.recordStrategyFailure(act, beforeHash)
        checkpointSession.recoveryFeedback = if (strategyFailures >= 2) {
            checkpointSession.recoveryFeedback
        } else {
            attemptFeedback
        }
        recordRecentStep(
            recentStepHistory,
            step,
            screen,
            canonicalOutput,
            act,
            actTargetLabel,
            if (stateUnchanged) "Failed: Screen unchanged" else "Failed: screen state could not be verified"
        )
        if (stateUnchanged) checkpointSession.consecutiveRecoveryFailures++
        else checkpointSession.consecutiveRecoveryFailures = 0
        if (checkpointSession.consecutiveRecoveryFailures <= 2) {
            messages.add(Message("⚠️ ${act.type} made no visible progress. Trying another action.", false))
            return StepFlowResult.CONTINUE_LOOP
        }
        val fallback = visionFallback.attempt(
            screen,
            beforeHash,
            step,
            canonicalOutput,
            beforeSignature
        )
        if (fallback?.executed == true) {
            checkpointSession.consecutiveRecoveryFailures = 0
            checkpointSession.recoveryAttempts = 0
            checkpointSession.recoveryFeedback = fallback.detail
            delay(400)
            return StepFlowResult.CONTINUE_LOOP
        }
        if (fallback != null) checkpointSession.recoveryFeedback = fallback.detail
        val recovery = if (checkpointSession.recoveryAttempts < 2) {
            recoverStuckTask(service)
        } else null
        if (recovery != null) {
            checkpointSession.recoveryAttempts++
            recentStepHistory.add("Step $step: [${screen.packageName} / recovery] -> $recovery -> Recovery executed")
            while (recentStepHistory.size > 5) recentStepHistory.removeAt(0)
            checkpointSession.consecutiveRecoveryFailures = 0
            delay(800)
            return StepFlowResult.CONTINUE_LOOP
        }
        if (com.example.bro.search.WebSearchEngine.enabled && checkpointSession.recoveryAttempts == 2) {
            checkpointSession.recoveryAttempts++
            messages.add(Message("🌐 Searching web for help navigating ${screen.packageName}...", false))
            val hint = com.example.bro.search.WebSearchEngine.searchSummary("how to ${goalStr} in ${screen.packageName} on Android")
            if (hint.isNotBlank()) {
                checkpointSession.recoveryFeedback = (checkpointSession.recoveryFeedback ?: "") +
                    "\n[Live Web Search Navigation Guidance:\n$hint\nFollow this guidance to proceed.]"
                checkpointSession.consecutiveRecoveryFailures = 0
                return StepFlowResult.CONTINUE_LOOP
            }
        }
        val question = "The screen stayed unchanged after retries and automatic recovery. What should I try next?"
        messages.add(Message("⚠️ $question", false))
        onQuestion?.invoke(question, goalStr, listOf("Try another approach", "Retry task", "Cancel task"))
        returnToBroApp(context)
        return StepFlowResult.TERMINATE
    }
    onIncident(null)
    checkpointSession.consecutiveRecoveryFailures = 0
    checkpointSession.recoveryAttempts = 0
    recordRecentStep(
        recentStepHistory,
        step,
        screen,
        canonicalOutput,
        act,
        actTargetLabel,
        "Screen changed"
    )
    Log.d(
        "BRO_STATE",
        "Action=${act.type}, UI_CHANGED=$changed"
    )

    if (changed) {
        delay(300)
    }
    return StepFlowResult.CONTINUE_BATCH
}

private suspend fun handleActionValidationError(
    context: Context,
    goalStr: String,
    step: Int,
    screen: ScreenState,
    canonicalOutput: String,
    act: com.example.bro.model.Action,
    actTargetLabel: String?,
    validationError: String,
    currentScreenHash: String,
    didWebSearchThisTask: Boolean,
    onWebSearchUsed: () -> Unit,
    visionFallback: AccessibilityVisionFallback,
    recentStepHistory: MutableList<String>,
    messages: MutableList<Message>,
    memory: AgentMemory,
    checkpointSession: com.example.bro.agent.TaskCheckpointSession,
    onQuestion: OnQuestionCallback?,
    onIncident: (com.example.bro.model.ExecutionIncident?) -> Unit
): StepFlowResult {
    val incident = com.example.bro.model.ExecutionIncident(
        step = step,
        attemptedAction = "${act.type}(targetId=${act.targetId ?: ""}, text=${act.text ?: ""})",
        failureKind = com.example.bro.model.IncidentKind.REJECTED_BY_SAFETY_NET,
        diagnostic = validationError,
        suggestedDirective = "Safety Net rejected this action. Address the validation failure and pick a different node or appropriate action."
    )
    onIncident(incident)
    com.example.bro.util.ExecutionLogger.recordNoAndroidCommand(
        context, goalStr, step, screen.packageName,
        "Action rejected before dispatch: $validationError"
    )
    recordRecentStep(
        recentStepHistory, step, screen, canonicalOutput, act,
        actTargetLabel, "Failed: rejected before dispatch ($validationError)"
    )
    memory.remember(act, false, validationError)
    val strategyFailures =
        checkpointSession.recordStrategyFailure(act, currentScreenHash)
    checkpointSession.consecutiveRecoveryFailures++
    if (checkpointSession.consecutiveRecoveryFailures <= 2) {
        checkpointSession.recoveryFeedback = if (strategyFailures >= 2) {
            checkpointSession.recoveryFeedback
        } else {
            validationError
        }
        if (checkpointSession.consecutiveRecoveryFailures == 2 && com.example.bro.search.WebSearchEngine.enabled && !didWebSearchThisTask) {
            onWebSearchUsed()
            val searchQuery = "how to $goalStr in ${screen.packageName}"
            val searchHint = com.example.bro.search.WebSearchEngine.searchSummary(searchQuery)
            if (searchHint.isNotBlank()) {
                checkpointSession.recoveryFeedback = (checkpointSession.recoveryFeedback ?: "") + "\n[Web Search Guidance: $searchHint]"
                messages.add(Message("🌐 Retrieved web navigation guidance for ${screen.packageName}...", false))
            }
        }
        messages.add(Message("⚠️ $validationError Trying another action from the current screen.", false))
        return StepFlowResult.CONTINUE_LOOP
    }
    val fallback = visionFallback.attempt(
        screen,
        currentScreenHash,
        step,
        canonicalOutput,
        screenSignature(screen)
    )
    if (fallback?.executed == true) {
        checkpointSession.consecutiveRecoveryFailures = 0
        checkpointSession.recoveryAttempts = 0
        checkpointSession.recoveryFeedback = fallback.detail
        delay(400)
        return StepFlowResult.CONTINUE_LOOP
    }
    if (fallback != null) checkpointSession.recoveryFeedback = fallback.detail
    val question = "I couldn't find a valid action after trying alternatives. $validationError"
    messages.add(Message("⚠️ $question", false))
    onQuestion?.invoke(
        question,
        goalStr,
        buildQuestionChoices(emptyList(), screen)
    )
    returnToBroApp(context)
    return StepFlowResult.TERMINATE
}

suspend fun executeGoalWithPlanner(
    context: Context,
    goalStr: String,
    userOriginalPrompt: String = goalStr,
    executionMode: String = "ACCESSIBILITY",
    userGuidance: String? = null,
    checkpoint: com.example.bro.agent.TaskCheckpoint? = null,
    onCheckpointUpdated: ((com.example.bro.agent.TaskCheckpoint) -> Unit)? = null,
    messages: MutableList<Message>,
    onComplete: ((goal: String, screen: ScreenState?, actions: List<com.example.bro.model.Action>) -> Unit)? = null,
    onQuestion: OnQuestionCallback? = null
) {
    var goalStr = goalStr
    var normalizedGoal = goalStr.lowercase()
    val planner = GroqPlanner()
    if (checkpoint == null) {
        val userDecision = planner.evaluateUserRequest(
            prompt = goalStr,
            context = context,
            visionMode = executionMode == "VISION"
        )

        when (userDecision) {
            is com.example.bro.planner.UserRequestDecision.WebSearch -> {
                val searchQuery = userDecision.query.ifBlank { userOriginalPrompt }
                messages.add(Message("🔍 Searching the web for: \"$searchQuery\"...", false))
                val webSummary = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.bro.search.WebSearchEngine.searchSummary(searchQuery)
                }
                var reply = ""
                if (webSummary.isNotBlank()) {
                    val augmentedPrompt = "User Question: $userOriginalPrompt\n\nLive Web Search Results:\n$webSummary\n\nPlease answer the user's question directly and concisely using the verified live web search results above."
                    try {
                        val useOnDevice = com.example.bro.agent.OnDeviceLlmManager.isModelLoaded &&
                            (executionMode == "VISION" || com.example.bro.network.GroqRepository.providerMode == "ON_DEVICE" || !com.example.bro.network.GroqRepository.isCloudConfigured())
                        val groundedAnswer = if (useOnDevice) {
                            com.example.bro.agent.OnDeviceLlmManager.generateResponse(
                                systemPrompt = com.example.bro.planner.SystemPromptManager.DEFAULT_CHAT_SYSTEM_PROMPT,
                                userPrompt = augmentedPrompt
                            )
                        } else {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.example.bro.network.GroqRepository().sendPrompt(
                                    systemPrompt = com.example.bro.planner.SystemPromptManager.DEFAULT_CHAT_SYSTEM_PROMPT,
                                    userPrompt = augmentedPrompt
                                )
                            }
                        }
                        if (groundedAnswer.isNotBlank()) {
                            reply = groundedAnswer.trim()
                        }
                    } catch (e: Exception) {
                        Log.w("BRO_SEARCH", "Failed to generate grounded search reply", e)
                        reply = webSummary
                    }
                }
                if (reply.isBlank()) {
                    reply = "I searched the web for \"$searchQuery\" but could not find relevant results."
                }
                messages.add(Message(reply, false))
                com.example.bro.agent.UserConversationMemory.addTurn(
                    context,
                    userOriginalPrompt,
                    goalStr,
                    reply
                )
                return
            }
            is com.example.bro.planner.UserRequestDecision.Chat -> {
                var reply = userDecision.reply
                val modelMentionsSearching = reply.contains("I can search", ignoreCase = true) ||
                    reply.contains("I will search", ignoreCase = true) ||
                    reply.contains("search for information", ignoreCase = true) ||
                    reply.contains("look that up", ignoreCase = true) ||
                    reply.contains("let me search", ignoreCase = true)

                val needsWebSearch = com.example.bro.search.WebSearchEngine.enabled &&
                    (com.example.bro.planner.TaskIntentClassifier.requiresWebSearch(userOriginalPrompt) ||
                     com.example.bro.planner.TaskIntentClassifier.isExplicitWebSearch(userOriginalPrompt) ||
                     modelMentionsSearching)
                if (needsWebSearch) {
                    messages.add(Message("🔍 Searching the web for live information...", false))
                    val webSummary = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.example.bro.search.WebSearchEngine.searchSummary(userOriginalPrompt)
                    }
                    if (webSummary.isNotBlank()) {
                        val augmentedPrompt = "User Question: $userOriginalPrompt\n\nLive Web Search Results:\n$webSummary\n\nPlease answer the user's question directly and concisely using the verified live web search results above."
                        try {
                            val useOnDevice = com.example.bro.agent.OnDeviceLlmManager.isModelLoaded &&
                                (executionMode == "VISION" || com.example.bro.network.GroqRepository.providerMode == "ON_DEVICE" || !com.example.bro.network.GroqRepository.isCloudConfigured())
                            val groundedAnswer = if (useOnDevice) {
                                com.example.bro.agent.OnDeviceLlmManager.generateResponse(
                                    systemPrompt = com.example.bro.planner.SystemPromptManager.DEFAULT_CHAT_SYSTEM_PROMPT,
                                    userPrompt = augmentedPrompt
                                )
                            } else {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.example.bro.network.GroqRepository().sendPrompt(
                                        systemPrompt = com.example.bro.planner.SystemPromptManager.DEFAULT_CHAT_SYSTEM_PROMPT,
                                        userPrompt = augmentedPrompt
                                    )
                                }
                            }
                            if (groundedAnswer.isNotBlank()) {
                                reply = groundedAnswer.trim()
                            }
                        } catch (e: Exception) {
                            Log.w("BRO_SEARCH", "Failed to generate grounded search reply", e)
                        }
                    } else if (reply.isBlank()) {
                        reply = "I searched the web for \"$userOriginalPrompt\" but could not find relevant results."
                    }
                }
                if (reply.isNotBlank()) {
                    messages.add(Message(reply, false))
                }
                com.example.bro.agent.UserConversationMemory.addTurn(
                    context,
                    userOriginalPrompt,
                    goalStr,
                    reply
                )
                return
            }
            is com.example.bro.planner.UserRequestDecision.Action -> {
                if (!userDecision.conversationalReply.isNullOrBlank()) {
                    messages.add(Message(userDecision.conversationalReply, false))
                }
                if (userDecision.taskGoal.isNotBlank()) {
                    goalStr = userDecision.taskGoal
                    normalizedGoal = goalStr.lowercase()
                }
                com.example.bro.agent.UserConversationMemory.rememberTaskGoal(context, goalStr)
            }
        }
    }

    if (com.example.bro.executor.PlannerExecutionHelper.handlePreExecutionSpecialGoals(context, goalStr, userOriginalPrompt, messages, onComplete)) {
        return
    }

    val sensitiveKeywords = listOf("pay", "send money", "transfer", "delete account", "buy", "order now")
    if (sensitiveKeywords.any { normalizedGoal.contains(it) }) {
        messages.add(
            Message("⚠️ Safety Warning: This request involves a sensitive action (payment/deletion). Please confirm explicitly before proceeding.", false)
        )
        return
    }

    if (executionMode == "VISION") {
        executeGoalWithVisionMode(
            context = context,
            goalStr = goalStr,
            userOriginalPrompt = userOriginalPrompt,
            userGuidance = userGuidance,
            messages = messages,
            onComplete = onComplete,
            onQuestion = onQuestion
        )
        return
    }

    val service = BroAccessibilityService.instance
    if (service == null) {
        messages.add(
            Message("⚠️ Accessibility service is disabled. Please enable 'Bro' in Accessibility Settings first.", false)
        )
        return
    }

    val quickSettingRequest =
        com.example.bro.planner.QuickSettingRequestParser.parse(goalStr)
    if (quickSettingRequest != null) {
        val enabledText = when (quickSettingRequest.enabled) {
            true -> "on"
            false -> "off"
            null -> "toggle"
        }
        val action = com.example.bro.model.Action(
            type = ActionType.TOGGLE_QUICK_SETTING,
            target = quickSettingRequest.tileLabel,
            desiredState = quickSettingRequest.enabled,
            summary = "$enabledText ${quickSettingRequest.tileLabel}"
        )
        val completed = ActionExecutor(service).execute(action)
        if (completed) {
            val stateText = when (quickSettingRequest.enabled) {
                true -> "enabled"
                false -> "disabled"
                null -> "toggled"
            }
            messages.add(Message("✅ ${quickSettingRequest.tileLabel} is $stateText.", false))
        } else {
            val question = if (quickSettingRequest.tileLabel == "Flashlight") {
                "I couldn't toggle the flashlight. Check that BRO has Camera permission, then retry."
            } else {
                "I couldn't find or change the ${quickSettingRequest.tileLabel} tile in Quick Settings."
            }
            messages.add(Message("⚠️ $question", false))
            onQuestion?.invoke(
                question,
                goalStr,
                listOf("Open Settings manually", "Cancel task"),
                "",
                "",
                ""
            )
        }
        returnToBroApp(context)
        return
    }

    val taskIntent = com.example.bro.planner.TaskIntentClassifier.classify(goalStr)
    Log.d("BRO_INTENT", "Goal: '$goalStr' | Classified Intent: $taskIntent")

    val memory = AgentMemory()
    checkpoint?.let {
        memory.restore(it.previousActions, it.step)
    }
    val screenReader = ScreenReader()
    val executor = ActionExecutor(service)

    val checkpointSession = com.example.bro.agent.TaskCheckpointSession(
        context,
        goalStr,
        userOriginalPrompt,
        executionMode,
        memory,
        checkpoint,
        onCheckpointUpdated,
        userGuidance
    )
    var step by checkpointSession::step
    val maxSteps = 15
    var repeatActionCount by checkpointSession::repeatActionCount
    var lastTargetId by checkpointSession::lastTargetId
    var lastActionType by checkpointSession::lastActionType
    var recoveryFeedback by checkpointSession::recoveryFeedback
    var consecutiveRecoveryFailures by checkpointSession::consecutiveRecoveryFailures
    var recoveryAttempts by checkpointSession::recoveryAttempts
    var sendClickedThisTask by checkpointSession::sendClickedThisTask
    var didWebSearchThisTask = false
    val recentStepHistory = checkpointSession.recentStepHistory
    val blacklistedActions = checkpointSession.blacklistedActions
    val visitedStateActions = checkpointSession.visitedStateActions
    val recentActionSigs = checkpointSession.recentActionSigs
    var lastAutoSaveState: String? = null
    var activeUserGuidance: String? = userGuidance ?: checkpoint?.userGuidance
    var directActionConsumed = false
    var activeIncident: com.example.bro.model.ExecutionIncident? = null
    val visionFallback = AccessibilityVisionFallback(
        context = context,
        service = service,
        goal = goalStr,
        memory = memory,
        recentStepHistory = recentStepHistory,
        screenReader = screenReader
    )

    suspend fun persistCheckpoint(
        phase: com.example.bro.agent.TaskCheckpointPhase,
        pendingAction: com.example.bro.model.Action? = null,
        state: com.example.bro.agent.TaskCheckpointState = com.example.bro.agent.TaskCheckpointState.RUNNING,
        pendingQuestion: String? = null,
        questionChoices: List<String> = emptyList(),
        currentScreenFingerprint: String? = null
    ) = checkpointSession.persist(
        phase,
        pendingAction,
        state,
        pendingQuestion,
        questionChoices,
        currentScreenFingerprint
    )

    if (checkpoint?.phase == com.example.bro.agent.TaskCheckpointPhase.DISPATCHING &&
        checkpoint.pendingAction != null
    ) {
        recoveryFeedback = if (sendClickedThisTask) {
            "The previous SEND command may have been dispatched before the app restarted. Verify delivery first; do not send again."
        } else {
            "The previous action may have been dispatched before the app restarted. Re-read the current screen and verify its result before repeating anything."
        }
    }

    taskLoop@ while (step < maxSteps) {
        step++

        var root = service.getRoot()
        var screen = if (root != null) screenReader.read(root) else ScreenState("", emptyList())

        // If resuming task and currently on BRO home screen, restore foreground app from checkpoint
        val targetAppPkg = checkpoint?.targetPackage
        if (screen.packageName == context.packageName && !targetAppPkg.isNullOrBlank()) {
            Log.d("BRO_MAIN", "Resuming task; bringing target app $targetAppPkg back to foreground...")
            openApp(context, targetAppPkg, goalStr)
            delay(1500)
            root = service.getRoot()
            screen = if (root != null) screenReader.read(root) else screen
        }

        // Guard: If notification shade was accidentally opened, close it immediately and retry
        val isSystemUiExpected = normalizedGoal.contains("quick") ||
            normalizedGoal.contains("tile") ||
            normalizedGoal.contains("notification") ||
            normalizedGoal.contains("setting")
        if (screen.packageName == "com.android.systemui" && !isSystemUiExpected) {
            Log.w("BRO_MAIN", "Notification shade detected! Pressing BACK to close it.")
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            delay(800)
            step-- // Don't count this as a step
            continue
        }

        // Goal Completion Check
        val canonicalOutput = screenReader.toPrompt(screen)
        val currentScreenHash = screenFingerprint(screenSignature(screen))
        persistCheckpoint(
            com.example.bro.agent.TaskCheckpointPhase.PLANNING,
            currentScreenFingerprint = currentScreenHash
        )
        val isComplete = com.example.bro.planner.GoalCompletionChecker.isComplete(
            goal = Goal(goalStr),
            screen = screen,
            lastActionType = lastActionType,
            canonicalOutput = canonicalOutput,
            sendWasClicked = sendClickedThisTask
        )

        if (isComplete) {
            messages.add(Message("✅ Goal completed successfully!", false))
            returnToBroApp(context)
            return
        }

        val currentStateSignature = screenSignature(screen)
        val alarmSaveAction = com.example.bro.executor.ActionValidator.findAlarmSaveAction(
            Goal(goalStr),
            canonicalOutput
        )
        if (alarmSaveAction != null && lastAutoSaveState != currentStateSignature) {
            lastAutoSaveState = currentStateSignature
            val validationError = com.example.bro.executor.ActionValidator.validate(
                alarmSaveAction,
                Goal(goalStr),
                canonicalOutput
            )
            if (validationError == null) {
                Log.i("BRO_MAIN", "Requested alarm time/date is visible; selecting the current Save control.")
                val success = executor.execute(alarmSaveAction)
                if (success) {
                    memory.remember(alarmSaveAction, true, "Saved alarm after verifying requested time and date")
                    lastActionType = ActionType.CLICK
                    lastTargetId = alarmSaveAction.targetId
                    val changed = waitForScreenChange(
                        service = service,
                        screenReader = screenReader,
                        previousSignature = currentStateSignature
                    )
                    if (changed) {
                        consecutiveRecoveryFailures = 0
                        delay(300)
                        continue@taskLoop
                    }
                    recoveryFeedback = "The Save control was tapped but the screen did not change. Verify the result before retrying."
                } else {
                    recoveryFeedback = "The visible Save control could not be clicked. Choose another valid action from the current screen."
                }
            } else {
                Log.w("BRO_MAIN", "Alarm auto-save rejected: $validationError")
                recoveryFeedback = validationError
            }
        }

        val plannerFeedback = buildString {
            recoveryFeedback?.takeIf(String::isNotBlank)?.let(::append)
        }.ifBlank { null }

        var directUserAction: Action? = null
        val cleanGuidance = activeUserGuidance?.trim().orEmpty()
        if (!directActionConsumed && cleanGuidance.isNotBlank()) {
            when {
                cleanGuidance.equals("Go back", ignoreCase = true) ||
                cleanGuidance.equals("Back", ignoreCase = true) ||
                cleanGuidance.equals("Go back (BACK)", ignoreCase = true) -> {
                    directUserAction = Action(type = ActionType.BACK, summary = "Direct user action: Go back")
                    directActionConsumed = true
                }
                cleanGuidance.equals("Use search", ignoreCase = true) || cleanGuidance.equals("Search", ignoreCase = true) -> {
                    val searchNode = screen.nodes.firstOrNull { (it.text ?: it.contentDescription).orEmpty().contains("search", ignoreCase = true) }
                    if (searchNode != null) {
                        directUserAction = Action(type = ActionType.CLICK, targetId = searchNode.id, summary = "Direct user action: Open search")
                        directActionConsumed = true
                    }
                }
                else -> {
                    val targetText = cleanGuidance.removePrefix("Click ").removePrefix("Select ").trim()
                    val matchingNode = screen.nodes.firstOrNull { node ->
                        node.enabled && node.clickable && (
                            node.text?.equals(targetText, ignoreCase = true) == true ||
                            node.contentDescription?.equals(targetText, ignoreCase = true) == true ||
                            (targetText.length >= 3 && (node.text?.contains(targetText, ignoreCase = true) == true || node.contentDescription?.contains(targetText, ignoreCase = true) == true))
                        )
                    }
                    if (matchingNode != null) {
                        val nodeLabel = (matchingNode.text ?: matchingNode.contentDescription).orEmpty()
                        directUserAction = Action(type = ActionType.CLICK, targetId = matchingNode.id, summary = "Direct user action: Select $nodeLabel")
                        directActionConsumed = true
                    }
                }
            }
        }

        val decision = if (directUserAction != null) {
            PlannerResponse(
                thought = "Executing user direct guidance: $cleanGuidance",
                action = directUserAction,
                actions = listOf(directUserAction)
            )
        } else {
            planner.plan(
                goal = Goal(goalStr),
                screen = screen,
                memory = memory,
                context = context,
                recoveryFeedback = plannerFeedback,
                userGuidance = activeUserGuidance,
                recentStepHistory = recentStepHistory,
                blacklistedActions = blacklistedActions
                    .filter { it.screenHash == currentScreenHash }
                    .map(BlacklistedAction::promptLabel),
                activeIncident = activeIncident
            )
        }
        recoveryFeedback = null

        val rawActions = if (!decision.actions.isNullOrEmpty()) decision.actions else listOf(decision.action)
        val appLaunchIndex = rawActions.indexOfFirst { it.type == ActionType.OPEN_APP || it.type == ActionType.FIND_APP }
        val orderedActions = if (appLaunchIndex >= 0) {
            rawActions.take(appLaunchIndex + 1)
        } else {
            rawActions
        }
        val actionsToExecute = orderedActions.map {
            com.example.bro.executor.ActionValidator.autoHealAction(it, canonicalOutput)
        }
        var action = actionsToExecute.firstOrNull() ?: decision.action
        val thought = decision.thought

        val blacklistedAction = actionsToExecute.firstOrNull {
            BlacklistedAction(currentScreenHash, it.type, it.targetId) in blacklistedActions
        }
        if (blacklistedAction != null) {
            recoveryFeedback = "The model selected a blacklisted action on this unchanged screen. Choose a different node or action."
            val rejectedLabel = BlacklistedAction(
                currentScreenHash,
                blacklistedAction.type,
                blacklistedAction.targetId
            ).promptLabel()
            Log.w("BRO_STATE", "Rejected planner action $rejectedLabel on unchanged screen.")
            consecutiveRecoveryFailures++
            if (consecutiveRecoveryFailures >= 3) {
                val fallback = visionFallback.attempt(
                    screen,
                    currentScreenHash,
                    step,
                    canonicalOutput,
                    screenSignature(screen)
                )
                if (fallback?.executed == true) {
                    consecutiveRecoveryFailures = 0
                    recoveryAttempts = 0
                    recoveryFeedback = fallback.detail
                    delay(400)
                    continue@taskLoop
                }
                if (fallback != null) recoveryFeedback = fallback.detail
                val recovery = if (recoveryAttempts < 2) {
                    recoverStuckTask(service)
                } else null
                if (recovery != null) {
                    recoveryAttempts++
                    recentStepHistory.add("Step $step: [${screen.packageName} / recovery] -> $recovery -> Recovery executed")
                    while (recentStepHistory.size > 5) recentStepHistory.removeAt(0)
                    consecutiveRecoveryFailures = 0
                    delay(800)
                } else {
                    val question = "I could not recover from the repeated stuck actions. What should I try next?"
                    messages.add(Message("⚠️ $question", false))
                    onQuestion?.invoke(question, goalStr, buildQuestionChoices(emptyList(), screen))
                    returnToBroApp(context)
                    return
                }
            }
            continue@taskLoop
        }

        if (thought.isNotBlank()) {
            messages.add(Message("💡 [Step $step] ${action.summary ?: thought}", false))
        }

        // Loop detection — identical actions, state+action repeats, alternating patterns
        val actionSig = actionSignature(action)
        val stateKey = "${screenSignature(screen)}::$actionSig"
        visitedStateActions[stateKey] = (visitedStateActions[stateKey] ?: 0) + 1

        recentActionSigs.add(actionSig)
        if (recentActionSigs.size > 6) recentActionSigs.removeAt(0)

        if (visitedStateActions[stateKey]!! >= 4) {
            recordRecentStep(
                recentStepHistory, step, screen, canonicalOutput, action,
                actionTargetLabel(action), "Failed: repeated strategy guard stopped dispatch"
            )
            val question = "The same action keeps appearing without progress. What should I try next?"
            messages.add(Message("⚠️ $question", false))
            onQuestion?.invoke(question, goalStr, buildQuestionChoices(emptyList(), screen))
            returnToBroApp(context)
            return
        }

        if (recentActionSigs.size >= 4) {
            val last4 = recentActionSigs.takeLast(4)
            if (last4[0] == last4[2] && last4[1] == last4[3] && last4[0] != last4[1]) {
                recordRecentStep(
                    recentStepHistory, step, screen, canonicalOutput, action,
                    actionTargetLabel(action), "Failed: alternating-action guard stopped dispatch"
                )
                com.example.bro.util.ExecutionLogger.recordNoAndroidCommand(
                    context, goalStr, step, screen.packageName,
                    "Alternating-action guard stopped dispatch."
                )
                val question = "The task is alternating between actions without progress. What should I try next?"
                messages.add(Message("⚠️ $question", false))
                onQuestion?.invoke(question, goalStr, buildQuestionChoices(emptyList(), screen))
                returnToBroApp(context)
                return
            }
        }

        if (action.type == lastActionType && action.targetId != null && action.targetId == lastTargetId) {
            repeatActionCount++
            if (repeatActionCount >= 3) {
                recordRecentStep(
                    recentStepHistory, step, screen, canonicalOutput, action,
                    actionTargetLabel(action), "Failed: repeated-target guard stopped dispatch"
                )
                com.example.bro.util.ExecutionLogger.recordNoAndroidCommand(
                    context, goalStr, step, screen.packageName,
                    "Repeated-target guard stopped dispatch."
                )
                val question = "The same target keeps being selected without progress. What should I try next?"
                messages.add(Message("⚠️ $question", false))
                onQuestion?.invoke(question, goalStr, buildQuestionChoices(emptyList(), screen))
                returnToBroApp(context)
                return
            }
        } else {
            repeatActionCount = 0
        }
        lastActionType = action.type
        lastTargetId = action.targetId

        for ((actionIdx, act) in actionsToExecute.withIndex()) {
            val isNodeAction = act.type == ActionType.CLICK ||
                act.type == ActionType.TYPE ||
                act.type == ActionType.SEARCH
            val actTargetLabel = actionTargetLabel(act)
            val validationError = if (screen.packageName == context.packageName && isNodeAction) {
                "ACTION REJECTED: BRO's screen has no target UI nodes. Open the requested app before interacting (e.g. {\"type\":\"OPEN_APP\",\"app\":\"<TargetApp>\"} or {\"type\":\"FIND_APP\",\"query\":\"<keyword>\"})."
            } else if (act.type == ActionType.OPEN_APP &&
                (act.app?.contains("bro", ignoreCase = true) == true ||
                 act.packageName?.contains(context.packageName, ignoreCase = true) == true ||
                 act.target?.contains("bro", ignoreCase = true) == true)
            ) {
                "ACTION REJECTED: You are already inside the BRO application. OPEN_APP must specify the target third-party app (e.g. WhatsApp, Messages, Spotify, Swiggy) or use FIND_APP to search for it."
            } else {
                com.example.bro.executor.ActionValidator.validate(
                    act,
                    Goal(goalStr),
                    canonicalOutput,
                    currentPackage = screen.packageName,
                    semanticCritic = { g, candidate ->
                        com.example.bro.executor.SemanticEntityCritic.isPlausibleMatch(g, candidate)
                    }
                )
            }
            if (act.type == ActionType.CLICK &&
                act.targetId != null &&
                com.example.bro.accessibility.NodeRepository.getRole(act.targetId) ==
                    com.example.bro.accessibility.UiCanonicalizer.Role.TEXT &&
                com.example.bro.accessibility.NodeRepository.get(act.targetId)?.let { node ->
                    !node.isClickable && node.parent?.isClickable != true
                } == true
            ) {
                blacklistedActions.add(BlacklistedAction(currentScreenHash, act.type, act.targetId))
            }
            val isExplicitSendClick = act.type == ActionType.SEND_MESSAGE ||
                (act.type == ActionType.CLICK &&
                    act.targetId?.let { id ->
                        com.example.bro.accessibility.NodeRepository.getRole(id) ==
                            com.example.bro.accessibility.UiCanonicalizer.Role.SEND ||
                            actTargetLabel?.contains("send", ignoreCase = true) == true
                    } == true)
            if (isExplicitSendClick && sendClickedThisTask) {
                com.example.bro.util.ExecutionLogger.recordNoAndroidCommand(
                    context, goalStr, step, screen.packageName,
                    "Duplicate SEND suppressed; no command dispatched."
                )
                recordRecentStep(
                    recentStepHistory, step, screen, canonicalOutput, act,
                    actTargetLabel, "Failed: duplicate SEND suppressed"
                )
                recoveryFeedback =
                    "SEND was already clicked during this task. Do not send the same message again; verify delivery or finish."
                continue@taskLoop
            }
            if (act.type == ActionType.TYPE &&
                com.example.bro.executor.ActionValidator.isTypeValueAlreadyPresent(act)
            ) {
                val detail = "Input node ${act.targetId} already contains the requested value. Do not type it again; continue with the next required action."
                com.example.bro.util.ExecutionLogger.recordNoAndroidCommand(
                    context, goalStr, step, screen.packageName,
                    "TYPE suppressed: $detail"
                )
                recordRecentStep(
                    recentStepHistory, step, screen, canonicalOutput, act,
                    actTargetLabel, "No-op: requested text already present"
                )
                memory.remember(act, true, detail)
                recoveryFeedback = detail
                messages.add(Message("ℹ️ The requested value is already entered. Moving to the next step.", false))
                continue
            }
            if (validationError != null) {
                val flow = handleActionValidationError(
                    context = context,
                    goalStr = goalStr,
                    step = step,
                    screen = screen,
                    canonicalOutput = canonicalOutput,
                    act = act,
                    actTargetLabel = actTargetLabel,
                    validationError = validationError,
                    currentScreenHash = currentScreenHash,
                    didWebSearchThisTask = didWebSearchThisTask,
                    onWebSearchUsed = { didWebSearchThisTask = true },
                    visionFallback = visionFallback,
                    recentStepHistory = recentStepHistory,
                    messages = messages,
                    memory = memory,
                    checkpointSession = checkpointSession,
                    onQuestion = onQuestion,
                    onIncident = { activeIncident = it }
                )
                if (flow == StepFlowResult.CONTINUE_LOOP) continue@taskLoop
                if (flow == StepFlowResult.TERMINATE) return
                continue
            }

            if (thought.isNotBlank() && actionIdx == 0) {
                messages.add(Message("💡 [Step $step] ${act.summary ?: thought}", false))
            } else if (!act.summary.isNullOrBlank() && actionsToExecute.size > 1) {
                messages.add(Message("⚡ Action ${actionIdx + 1}/${actionsToExecute.size}: ${act.summary}", false))
            }

            when (act.type) {
                ActionType.ASK_USER -> {
                    handleAskUserAction(
                        context, goalStr, step, screen, canonicalOutput,
                        act, actTargetLabel, thought, decision,
                        recentStepHistory, messages, onQuestion
                    )
                    return
                }

                ActionType.CHAT -> {
                    val flow = handleChatAction(
                        context, goalStr, userOriginalPrompt, step, screen, canonicalOutput,
                        act, actTargetLabel, thought, decision, executor, visionFallback,
                        currentScreenHash, recentStepHistory, messages, onQuestion, onComplete,
                        memory, checkpointSession
                    )
                    if (flow == StepFlowResult.TERMINATE) return
                    if (flow == StepFlowResult.CONTINUE_LOOP) continue@taskLoop
                    if (flow == StepFlowResult.BREAK_BATCH) break
                }

                ActionType.FINISH, ActionType.READ_SCREEN -> {
                    handleFinishAction(
                        context, planner, goalStr, normalizedGoal, userOriginalPrompt,
                        step, screen, act, memory, messages, onComplete
                    )
                    return
                }

                ActionType.WEB_SEARCH -> {
                    val flow = handleWebSearchAction(
                        context, goalStr, step, screen, canonicalOutput,
                        act, thought, recentStepHistory, messages, checkpointSession
                    )
                    if (flow == StepFlowResult.CONTINUE_LOOP) continue@taskLoop
                }

                ActionType.FIND_APP -> {
                    val flow = handleFindAppAction(
                        context, goalStr, step, screen, canonicalOutput,
                        act, recentStepHistory, messages, checkpointSession
                    )
                    if (flow == StepFlowResult.CONTINUE_LOOP) continue@taskLoop
                }

                ActionType.OPEN_APP -> {
                    val flow = handleOpenAppAction(
                        context, goalStr, step, screen, canonicalOutput,
                        act, actTargetLabel, currentScreenHash, recentStepHistory,
                        messages, memory, checkpointSession, onQuestion
                    )
                    if (flow == StepFlowResult.TERMINATE) return
                    if (flow == StepFlowResult.CONTINUE_LOOP) continue@taskLoop
                }

                ActionType.LOCK_SCREEN -> {
                    handleLockScreenAction(
                        context, goalStr, userOriginalPrompt, step, screen, canonicalOutput,
                        act, actTargetLabel, currentScreenHash, executor, recentStepHistory,
                        messages, memory, checkpointSession, onComplete
                    )
                    return
                }

                ActionType.TOGGLE_QUICK_SETTING -> {
                    val flow = handleToggleQuickSettingAction(
                        context, goalStr, step, screen, canonicalOutput,
                        act, currentScreenHash, executor, recentStepHistory,
                        messages, memory, checkpointSession, onQuestion
                    )
                    if (flow == StepFlowResult.TERMINATE) return
                    if (flow == StepFlowResult.CONTINUE_LOOP) continue@taskLoop
                }

                ActionType.WAIT -> {
                    handleWaitAction(
                        context, goalStr, step, screen, canonicalOutput,
                        act, recentStepHistory, memory
                    )
                }

                ActionType.CLICK,
                ActionType.TYPE,
                ActionType.SEARCH,
                ActionType.SCROLL,
                ActionType.SWIPE,
                ActionType.BACK,
                ActionType.HOME,
                ActionType.SEND_MESSAGE -> {
                    val flow = handleStandardAccessibilityAction(
                        context, goalStr, step, screen, canonicalOutput,
                        act, actTargetLabel, currentScreenHash, isExplicitSendClick,
                        service, executor, screenReader, visionFallback,
                        recentStepHistory, messages, memory, checkpointSession,
                        onQuestion, onIncident = { activeIncident = it }
                    )
                    if (flow == StepFlowResult.TERMINATE) return
                    if (flow == StepFlowResult.CONTINUE_LOOP) continue@taskLoop
                }

                else -> {
                    delay(1000)
                }
            }
        }
    }

    // Return to BRO app if max steps reached
    val question = "I reached the action limit without confirming the task is complete. What should I try next?"
    messages.add(Message("⚠️ $question", false))
    onQuestion?.invoke(question, goalStr, listOf("Try another approach", "Retry task", "Cancel task"))
    returnToBroApp(context)
}

@Composable
fun InspectorCardItem(entry: com.example.bro.util.LogEntry) {
    var showScreenRead by remember { mutableStateOf(false) }
    var showPrompt by remember { mutableStateOf(false) }
    var showVisionPreview by remember { mutableStateOf(false) }
    var visionPreview by remember(entry.visionDebugImagePath) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    var visionPreviewLoadError by remember(entry.visionDebugImagePath) {
        mutableStateOf<String?>(null)
    }

    LaunchedEffect(showVisionPreview, entry.visionDebugImagePath) {
        val imagePath = entry.visionDebugImagePath
        if (showVisionPreview && !imagePath.isNullOrBlank() && visionPreview == null) {
            val (result, loadError) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val decoded = BitmapFactory.decodeFile(imagePath)
                        ?: throw IllegalArgumentException("Image file could not be read")
                    Pair(decoded.asImageBitmap(), null)
                } catch (error: Exception) {
                    Pair(null, error.message ?: "Image could not be loaded")
                }
            }
            visionPreview = result
            visionPreviewLoadError = loadError
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF091120)),
        border = BorderStroke(0.5.dp, Color(0x3300E5FF))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("[${entry.timestamp}] Step ${entry.stepNumber}", fontSize = 11.sp, color = JarvisCyan, fontWeight = FontWeight.Bold)
                Text(entry.appPackage.substringAfterLast('.'), fontSize = 10.sp, color = JarvisTextSecondary)
            }
            Text("GOAL: ${entry.taskGoal}", fontSize = 12.sp, color = JarvisTextPrimary, fontWeight = FontWeight.Bold)
            Text("ACTION: ${entry.parsedAction}", fontSize = 11.sp, color = JarvisGreen)
            Text(
                "ANDROID COMMAND EXECUTED: ${
                    entry.androidCommandExecuted.ifBlank { "No Android command recorded yet" }
                }",
                fontSize = 10.sp,
                color = JarvisCyan,
                fontFamily = FontFamily.Monospace
            )
            entry.executionResult?.takeIf(String::isNotBlank)?.let { result ->
                Text("EXECUTION RESULT: $result", fontSize = 10.sp, color = JarvisTextSecondary)
            }
            if (entry.tokenUsage.isNotBlank()) {
                Text("TOKENS: ${entry.tokenUsage}", fontSize = 10.sp, color = JarvisCyan)
            }

            if (entry.appPackage == "vision") {
                Spacer(modifier = Modifier.height(6.dp))
                val imagePath = entry.visionDebugImagePath
                if (imagePath.isNullOrBlank()) {
                    Text(
                        "🎯 VISION SCREEN PREVIEW NOT SAVED FOR THIS STEP",
                        fontSize = 10.sp,
                        color = Color(0xFFF59E0B),
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showVisionPreview = !showVisionPreview },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "🎯 VISION SCREEN + SELECTED COORDINATE",
                            fontSize = 10.sp,
                            color = JarvisCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (showVisionPreview) "▼ Hide" else "▶ Preview",
                            fontSize = 10.sp,
                            color = JarvisCyan
                        )
                    }
                    if (showVisionPreview) {
                        val preview = visionPreview
                        if (preview != null) {
                            Image(
                                bitmap = preview,
                                contentDescription = "Vision screenshot with selected coordinate marker",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp)
                                    .padding(top = 6.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Text(
                                text = visionPreviewLoadError?.let { "Preview unavailable: $it" }
                                    ?: "Loading Vision preview...",
                                fontSize = 10.sp,
                                color = JarvisTextSecondary,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        Text(
                            text = imagePath,
                            fontSize = 9.sp,
                            color = JarvisTextSecondary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 1. PHONE SCREEN DOM READ BY ACCESSIBILITY
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showScreenRead = !showScreenRead },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📱 PHONE SCREEN READ", fontSize = 10.sp, color = JarvisCyan, fontWeight = FontWeight.Bold)
                Text(if (showScreenRead) "▼ Hide" else "▶ View DOM Elements", fontSize = 10.sp, color = JarvisCyan)
            }
            if (showScreenRead) {
                Text(
                    text = entry.screenPrompt.ifBlank { "(No DOM text)" },
                    fontSize = 10.sp,
                    color = JarvisTextSecondary,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 2. FULL PROMPT SENT TO LLM
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showPrompt = !showPrompt },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📤 PROMPT SENT TO LLM", fontSize = 10.sp, color = JarvisCyan, fontWeight = FontWeight.Bold)
                Text(if (showPrompt) "▼ Hide" else "▶ View Payload", fontSize = 10.sp, color = JarvisCyan)
            }
            if (showPrompt) {
                Text(
                    text = entry.userPromptSent,
                    fontSize = 10.sp,
                    color = JarvisTextSecondary,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 3. RAW LLM RESPONSE
            Text("📥 RAW RESPONSE FROM LLM:", fontSize = 10.sp, color = JarvisGreen, fontWeight = FontWeight.Bold)
            Text(
                text = entry.rawLlmResponse,
                fontSize = 10.sp,
                color = JarvisTextSecondary,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}