package com.example.bro

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bro.accessibility.BroAccessibilityService
import com.example.bro.accessibility.ScreenReader
import com.example.bro.agent.AgentMemory
import com.example.bro.executor.ActionExecutor
import com.example.bro.model.ActionType
import com.example.bro.model.Goal
import com.example.bro.model.ScreenState
import com.example.bro.network.GroqRepository
import com.example.bro.planner.GroqPlanner
import com.example.bro.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class Message(
    val text: String,
    val isUser: Boolean
)

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

    val prefs = remember { context.getSharedPreferences("bro_prefs", Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(prefs.getString("groq_api_key", GroqRepository.apiKey) ?: "") }
    var systemPromptInput by remember { mutableStateOf(com.example.bro.planner.SystemPromptManager.getSystemPrompt(context)) }

    var isAccessibilityActive by remember { mutableStateOf(false) }
    var isOverlayActive by remember { mutableStateOf(false) }

    var agentJob by remember { mutableStateOf<Job?>(null) }
    var isRunningTask by remember { mutableStateOf(false) }

    val messages = remember {
        mutableStateListOf(
            Message("⚡ B.R.O. Personal AI Assistant Online. Autonomous phone control ready.", false)
        )
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
    }

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

    val quickActionPresets = listOf(
        "💬 Send hi to Ramesh on WhatsApp",
        "🎵 Open YouTube",
        "🌐 Open Chrome",
        "⚙️ Open Settings",
        "📱 Inspect current screen"
    )

    var showFeedbackDialog by remember { mutableStateOf(false) }
    var feedbackGoal by remember { mutableStateOf("") }
    var feedbackActions by remember { mutableStateOf<List<com.example.bro.model.Action>>(emptyList()) }
    var feedbackScreen by remember { mutableStateOf<ScreenState?>(null) }
    var isTrainingActive by remember { mutableStateOf(false) }

    fun startTask(commandText: String) {
        if (commandText.isBlank()) return

        if (commandText.equals("inspect screen", true)) {
            val service = BroAccessibilityService.instance
            val text = service?.getCurrentScreenText() ?: "Accessibility Service not running."
            messages.add(Message(commandText, true))
            messages.add(Message("📱 Current Screen:\n$text", false))
            return
        }

        messages.add(Message(commandText, true))

        // Fact Registration Check (e.g. "my name is srini")
        val factReply = com.example.bro.agent.UserConversationMemory.processFactRegistration(context, commandText)
        if (factReply != null) {
            messages.add(Message(factReply, false))
            com.example.bro.agent.UserConversationMemory.addTurn(context, commandText, commandText, factReply)
            return
        }

        // Contextual Goal Resolution (e.g. "what about pasu" -> "check recent message for pasu")
        val resolvedGoal = com.example.bro.agent.UserConversationMemory.resolveGoalWithContext(context, commandText)
        if (resolvedGoal.lowercase() != commandText.lowercase()) {
            messages.add(Message("💡 Context linked: Target task → \"$resolvedGoal\"", false))
        }

        agentJob?.cancel()
        agentJob = scope.launch {
            isRunningTask = true
            try {
                executeGoalWithPlanner(
                    context = context,
                    goalStr = resolvedGoal,
                    userOriginalPrompt = commandText,
                    messages = messages,
                    onComplete = { goal, screen, actions ->
                        feedbackGoal = goal
                        feedbackScreen = screen
                        feedbackActions = actions
                        showFeedbackDialog = true
                    }
                )
            } catch (e: CancellationException) {
                Log.d("BRO_MAIN", "Goal cancelled by user")
            } catch (e: Exception) {
                messages.add(Message("❌ Error: ${e.message}", false))
            } finally {
                isRunningTask = false
            }
        }
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
                            Text(
                                text = if (isRunningTask) "STATUS: EXECUTING AUTOMATION..." else if (GroqRepository.apiKey.isNotBlank()) "STATUS: ONLINE // READY" else "⚠️ API KEY MISSING",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = if (isRunningTask) JarvisCyan else JarvisTextSecondary,
                                fontFamily = FontFamily.Monospace
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

            // Quick Preset Command Chips Row
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(quickActionPresets) { preset ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0x770D182E),
                        border = BorderStroke(1.dp, Color(0x3300E5FF)),
                        modifier = Modifier.clickable {
                            input = preset.substringAfter(" ")
                        }
                    ) {
                        Text(
                            text = preset,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            fontSize = 11.sp,
                            color = JarvisCyan,
                            fontFamily = FontFamily.Monospace
                        )
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
                            color = if (message.isUser) Color(0xDD0055B3) else Color(0xDD0D182E),
                            border = BorderStroke(
                                1.dp,
                                if (message.isUser) Color(0x6600E5FF) else Color(0x3300E5FF)
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

                                Spacer(modifier = Modifier.height(6.dp))

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
                                val count = com.example.bro.agent.BroTrainingManager.finishDemonstrationRecording(context)
                                isTrainingActive = false
                                Toast.makeText(context, "🎓 Saved $count demonstrated actions to training dataset!", Toast.LENGTH_LONG).show()
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                "Ask BRO to control your phone...",
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
                                // STOP ACTION: Cancel running agent task immediately
                                agentJob?.cancel()
                                isRunningTask = false
                                messages.add(Message("⏹️ Automation stopped by user.", false))
                                returnToBroApp(context)
                                Toast.makeText(context, "⏹️ Task Stopped", Toast.LENGTH_SHORT).show()
                            } else {
                                // SEND ACTION: Launch agent task
                                if (input.isNotBlank()) {
                                    val userMessage = input
                                    input = ""
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
                            Text("Groq API Key:", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = JarvisTextPrimary)
                            Spacer(modifier = Modifier.height(6.dp))
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

                            Spacer(modifier = Modifier.height(16.dp))

                            Text("LLM System Rules & System Prompt:", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = JarvisCyan)
                            Text("Edit rules directly here. Changes take effect on next task:", fontSize = 11.sp, color = JarvisTextSecondary)
                            Spacer(modifier = Modifier.height(6.dp))

                            OutlinedTextField(
                                value = systemPromptInput,
                                onValueChange = { systemPromptInput = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
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

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        com.example.bro.planner.SystemPromptManager.resetSystemPrompt(context)
                                        systemPromptInput = com.example.bro.planner.SystemPromptManager.getSystemPrompt(context)
                                        Toast.makeText(context, "🔄 System Rules Reset to Default!", Toast.LENGTH_SHORT).show()
                                    },
                                    border = BorderStroke(1.dp, JarvisRed)
                                ) {
                                    Text("Reset Rules", fontSize = 11.sp, color = JarvisRed)
                                }

                                Button(
                                    onClick = {
                                        GroqRepository.apiKey = apiKey.trim()
                                        prefs.edit().putString("groq_api_key", apiKey.trim()).apply()
                                        com.example.bro.planner.SystemPromptManager.saveSystemPrompt(context, systemPromptInput)
                                        Toast.makeText(context, "💾 LLM Rules & Key Saved!", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan)
                                ) {
                                    Text("Save Rules", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
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
                                Text("📄 EXPORTED TASK LOG FILE", fontSize = 11.sp, color = JarvisCyan, fontWeight = FontWeight.Bold)
                                val logPath = com.example.bro.util.ExecutionLogger.getLogFilePath(context)
                                Text(
                                    text = logPath,
                                    fontSize = 9.sp,
                                    color = JarvisTextSecondary,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(6.dp))
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
                        prefs.edit().putString("groq_api_key", apiKey.trim()).apply()
                        com.example.bro.planner.SystemPromptManager.saveSystemPrompt(context, systemPromptInput)
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

fun openApp(context: Context, appName: String?) {
    if (appName == null) return
    val packageName = AppLauncher.findPackage(appName)

    if (packageName == null) {
        Toast.makeText(context, "App '$appName' not found", Toast.LENGTH_SHORT).show()
        return
    }

    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (intent == null) {
        Toast.makeText(context, "Cannot launch '$appName'", Toast.LENGTH_SHORT).show()
        return
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

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
    action.text?.let { append(":text=$it") }
}

private fun screenSignature(screen: ScreenState): String {
    val labels = screen.nodes.take(12).mapNotNull {
        it.text?.take(25) ?: it.contentDescription?.take(25)
    }.joinToString("|")
    return "${screen.packageName}#${screen.nodes.size}#$labels"
}

suspend fun executeGoalWithPlanner(
    context: Context,
    goalStr: String,
    userOriginalPrompt: String = goalStr,
    messages: MutableList<Message>,
    onComplete: ((goal: String, screen: ScreenState?, actions: List<com.example.bro.model.Action>) -> Unit)? = null
) {
    val normalizedGoal = goalStr.lowercase()
    val trimmedGoal = normalizedGoal.trim()

    // Fast-path conversational greetings & Q&A (0 tokens to LLM, prevents opening apps on 'hi')
    val userName = com.example.bro.agent.UserConversationMemory.getFacts(context)["userName"]
    val nameGreeting = if (!userName.isNullOrBlank()) " $userName" else ""

    val conversationalResponse = when (trimmedGoal) {
        "hi", "hello", "hey", "hi bro", "hello bro", "hey bro", "bro", "hlo", "hola" ->
            "⚡ Hey$nameGreeting! I'm B.R.O., your autonomous phone assistant. What task can I do for you?"
        "how are you", "how are you doing", "how r u" ->
            "⚡ All systems operational$nameGreeting! Ready to automate your phone."
        "who are you", "what are you", "what is bro" ->
            "⚡ I'm B.R.O. (Personal AI Assistant). Tell me what you want done and I'll operate your phone for you!"
        "what can you do", "help", "commands" ->
            "⚡ Here are some things I can do:\n• 'Send hi to Ramesh on WhatsApp'\n• 'Check my recent WhatsApp messages'\n• 'Open YouTube'\n• 'Open Chrome / Settings'"
        "thanks", "thank you", "thx", "ty" ->
            "⚡ Happy to help$nameGreeting! Let me know if you need anything else."
        "bye", "goodbye" ->
            "⚡ Goodbye$nameGreeting!"
        else -> null
    }

    if (conversationalResponse != null) {
        messages.add(Message(conversationalResponse, false))
        com.example.bro.agent.UserConversationMemory.addTurn(context, userOriginalPrompt, goalStr, conversationalResponse)
        return
    }

    val sensitiveKeywords = listOf("pay", "send money", "transfer", "delete account", "buy", "order now")
    if (sensitiveKeywords.any { normalizedGoal.contains(it) }) {
        messages.add(
            Message("⚠️ Safety Warning: This request involves a sensitive action (payment/deletion). Please confirm explicitly before proceeding.", false)
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

    // Fast-path pipeline for common WhatsApp / app-open tasks
    val isHandledByPipeline = com.example.bro.executor.TaskPipelineExecutor.executePipeline(context, goalStr, messages)
    if (isHandledByPipeline) {
        return
    }

    val taskIntent = com.example.bro.planner.TaskIntentClassifier.classify(goalStr)
    Log.d("BRO_INTENT", "Goal: '$goalStr' | Classified Intent: $taskIntent")

    val planner = GroqPlanner()
    val memory = AgentMemory()
    val screenReader = ScreenReader()
    val executor = ActionExecutor(service)

    var step = 0
    val maxSteps = 15
    var repeatActionCount = 0
    var lastTargetId: Int? = null
    var lastActionType: ActionType? = null
    val visitedStateActions = mutableMapOf<String, Int>()
    val recentActionSigs = mutableListOf<String>()

    while (step < maxSteps) {
        step++

        val root = service.getRoot()
        val screen = if (root != null) screenReader.read(root) else ScreenState("", emptyList())

        val decision = planner.plan(
            goal = Goal(goalStr),
            screen = screen,
            memory = memory,
            context = context
        )

        var action = decision.action
        val thought = decision.thought

        // Direct override: If currently on BRO's screen and user goal requires an external app, force OPEN_APP
        if (screen.packageName == context.packageName && action.type == ActionType.CHAT) {
            val targetApp = when {
                "whatsapp" in normalizedGoal -> "WhatsApp"
                "youtube" in normalizedGoal -> "YouTube"
                "chrome" in normalizedGoal -> "Chrome"
                "instagram" in normalizedGoal -> "Instagram"
                "zomato" in normalizedGoal -> "Zomato"
                "calculator" in normalizedGoal -> "Calculator"
                "settings" in normalizedGoal -> "Settings"
                else -> null
            }
            if (targetApp != null) {
                action = com.example.bro.model.Action(
                    type = ActionType.OPEN_APP,
                    packageName = targetApp,
                    summary = "Opening $targetApp..."
                )
            }
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

        if (visitedStateActions[stateKey]!! >= 2) {
            messages.add(Message("⚠️ Stopping task: detected repeated state+action loop.", false))
            returnToBroApp(context)
            return
        }

        if (recentActionSigs.size >= 4) {
            val last4 = recentActionSigs.takeLast(4)
            if (last4[0] == last4[2] && last4[1] == last4[3] && last4[0] != last4[1]) {
                messages.add(Message("⚠️ Stopping task: detected alternating action loop.", false))
                returnToBroApp(context)
                return
            }
        }

        if (action.type == lastActionType && action.targetId != null && action.targetId == lastTargetId) {
            repeatActionCount++
            if (repeatActionCount >= 3) {
                messages.add(Message("⚠️ Stopping task: Got stuck performing repeated action on target [${action.targetId}].", false))
                returnToBroApp(context)
                return
            }
        } else {
            repeatActionCount = 0
        }
        lastActionType = action.type
        lastTargetId = action.targetId

        val actionsToExecute = if (!decision.actions.isNullOrEmpty()) decision.actions else listOf(action)

        for ((actionIdx, act) in actionsToExecute.withIndex()) {
            if (thought.isNotBlank() && actionIdx == 0) {
                messages.add(Message("💡 [Step $step] ${act.summary ?: thought}", false))
            } else if (!act.summary.isNullOrBlank() && actionsToExecute.size > 1) {
                messages.add(Message("⚡ Action ${actionIdx + 1}/${actionsToExecute.size}: ${act.summary}", false))
            }

            when (act.type) {
                ActionType.CHAT -> {
                    val chatText = act.summary.orEmpty().ifBlank { thought }
                    if (screen.packageName == context.packageName && chatText.isNotBlank()) {
                        messages.add(Message("💬 $chatText", false))
                        com.example.bro.agent.UserConversationMemory.addTurn(context, userOriginalPrompt, goalStr, chatText)
                        return
                    } else if (chatText.isNotBlank()) {
                        messages.add(Message("❓ $chatText", false))
                        com.example.bro.agent.UserConversationMemory.addTurn(context, userOriginalPrompt, goalStr, chatText)
                        returnToBroApp(context)
                        return
                    }
                    delay(1000)
                }

                ActionType.FINISH -> {
                    val finishText = act.summary.orEmpty().ifBlank { "Task completed." }
                    val visibleTexts = screen.nodes.mapNotNull {
                        it.text?.trim() ?: it.contentDescription?.trim()
                    }.filter { text ->
                        text.length > 2 &&
                            !text.contains("com.") &&
                            !text.equals("Search", ignoreCase = true) &&
                            !text.equals("Chats", ignoreCase = true) &&
                            !text.equals("Calls", ignoreCase = true) &&
                            !text.equals("Status", ignoreCase = true) &&
                            !text.equals("Communities", ignoreCase = true)
                    }.distinct()  // Dedup identical visible texts

                    val isReadGoal = normalizedGoal.contains("check") ||
                        normalizedGoal.contains("read") ||
                        normalizedGoal.contains("recent") ||
                        normalizedGoal.contains("message") ||
                        normalizedGoal.contains("order") ||
                        normalizedGoal.contains("status")

                    // For read goals, prefer actual screen content over model's reply (which may hallucinate)
                    val contentDetails = if (visibleTexts.isNotEmpty() && isReadGoal) {
                        "\n\n📱 Screen Items Read:\n" + visibleTexts.take(20).joinToString("\n") { "• $it" }
                    } else ""

                    val displayText = if (isReadGoal && contentDetails.isNotBlank()) {
                        "✅ Read complete.$contentDetails"
                    } else {
                        "✅ $finishText$contentDetails"
                    }

                    messages.add(Message(displayText, false))
                    com.example.bro.agent.UserConversationMemory.addTurn(context, userOriginalPrompt, goalStr, displayText)
                    onComplete?.invoke(goalStr, screen, memory.previousActions)
                    returnToBroApp(context)
                    return
                }

                ActionType.OPEN_APP -> {
                    val appName = act.packageName
                    val targetPkg = if (appName != null) AppLauncher.findPackage(appName) else null
                    if (targetPkg != null && screen.packageName == targetPkg) {
                        Log.d("BRO_MAIN", "Target app $appName ($targetPkg) is already open on screen. Skipping re-launch.")
                        delay(1000)
                    } else {
                        openApp(context, appName)
                        memory.remember(act, true, "Opened $appName")
                        delay(3000)
                    }
                }

                ActionType.CLICK, ActionType.TYPE, ActionType.SCROLL, ActionType.SWIPE, ActionType.BACK, ActionType.HOME -> {
                    val success = executor.execute(act)
                    memory.remember(act, success, if (success) "Executed ${act.type}" else "Failed ${act.type}")

                    // Auto-finish immediately if Send button click succeeded
                    if (success && act.type == ActionType.CLICK) {
                        val summaryLower = (act.summary ?: "").lowercase()
                        if (summaryLower.contains("send") || summaryLower.contains("sent")) {
                            messages.add(Message("✅ Message sent successfully! Task completed.", false))
                            returnToBroApp(context)
                            return
                        }
                    }

                    val stepDelay = if (actionsToExecute.size > 1) 800L else 2500L
                    delay(stepDelay)
                }

                ActionType.WAIT -> {
                    val waitMs = act.text?.toLongOrNull() ?: 2000L
                    delay(waitMs)
                    memory.remember(act, true, "Waited $waitMs ms")
                }

                else -> {
                    delay(1000)
                }
            }
        }
    }

    // Return to BRO app if max steps reached
    messages.add(Message("⏹️ Reached max step limit (15 steps). Task ended.", false))
    returnToBroApp(context)
}

@Composable
fun InspectorCardItem(entry: com.example.bro.util.LogEntry) {
    var showScreenRead by remember { mutableStateOf(false) }
    var showPrompt by remember { mutableStateOf(false) }

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
            if (entry.tokenUsage.isNotBlank()) {
                Text("TOKENS: ${entry.tokenUsage}", fontSize = 10.sp, color = JarvisCyan)
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