package com.zhibai.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Canvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhibai.app.ui.theme.MiMoColors
import com.zhibai.app.viewmodel.ChatViewModel
import com.zhibai.app.localai.LocalAiSupport
import com.zhibai.app.localai.LocalModel
import com.zhibai.app.localai.ModelRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/** Steps of the first-run onboarding flow. */
private enum class ObStep {
    WELCOME, API_KEY, LOCAL_MODEL, AGENT_TOGGLE, AGENT_API,
    FONT_SIZE, TTS_VOICE, INPUT_STYLE, AGENT_PROFILE, DONE
}

@Composable
fun OnboardingScreen(
    viewModel: ChatViewModel,
    onFinished: () -> Unit
) {
    var step by remember { mutableStateOf(ObStep.WELCOME) }
    var direction by remember { mutableIntStateOf(1) }
    var agentEnabled by remember { mutableStateOf(false) }

    val totalSteps = ObStep.entries.size - 1
    val stepIndex = ObStep.entries.indexOf(step)
    val progress = if (step == ObStep.DONE) 1f
        else stepIndex.toFloat() / (totalSteps - 1).coerceAtLeast(1)

    fun goTo(target: ObStep) {
        direction = if (ObStep.entries.indexOf(target) > stepIndex) 1 else -1
        step = target
    }

    fun advanceFrom(current: ObStep) {
        val next = when (current) {
            ObStep.WELCOME -> ObStep.API_KEY
            ObStep.API_KEY -> ObStep.LOCAL_MODEL
            ObStep.LOCAL_MODEL -> ObStep.AGENT_TOGGLE
            ObStep.AGENT_TOGGLE -> if (agentEnabled) ObStep.AGENT_API else ObStep.FONT_SIZE
            ObStep.AGENT_API -> ObStep.FONT_SIZE
            ObStep.FONT_SIZE -> ObStep.TTS_VOICE
            ObStep.TTS_VOICE -> ObStep.INPUT_STYLE
            ObStep.INPUT_STYLE -> if (agentEnabled) ObStep.AGENT_PROFILE else ObStep.DONE
            ObStep.AGENT_PROFILE -> ObStep.DONE
            ObStep.DONE -> ObStep.DONE
        }
        goTo(next)
    }

    fun retreatFrom(current: ObStep) {
        val prev = when (current) {
            ObStep.WELCOME -> ObStep.WELCOME
            ObStep.API_KEY -> ObStep.WELCOME
            ObStep.LOCAL_MODEL -> ObStep.API_KEY
            ObStep.AGENT_TOGGLE -> ObStep.LOCAL_MODEL
            ObStep.AGENT_API -> ObStep.AGENT_TOGGLE
            ObStep.FONT_SIZE -> if (agentEnabled) ObStep.AGENT_API else ObStep.AGENT_TOGGLE
            ObStep.TTS_VOICE -> ObStep.FONT_SIZE
            ObStep.INPUT_STYLE -> ObStep.TTS_VOICE
            ObStep.AGENT_PROFILE -> ObStep.INPUT_STYLE
            ObStep.DONE -> ObStep.TTS_VOICE
        }
        goTo(prev)
    }

    BackHandler(enabled = step != ObStep.WELCOME && step != ObStep.DONE) {
        retreatFrom(step)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (step != ObStep.WELCOME && step != ObStep.DONE) {
                ObTopBar(
                    progress = progress,
                    stepLabel = "${stepIndex + 1} / $totalSteps",
                    onBack = { retreatFrom(step) }
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        val dir = direction
                        val slide = 48.dp
                        (fadeIn(tween(320, easing = FastOutSlowInEasing)) +
                            slideInHorizontally(
                                tween(320, easing = FastOutSlowInEasing),
                                initialOffsetX = { (slide * dir).value.toInt() }
                            ))
                            .togetherWith(
                                fadeOut(tween(200, easing = LinearOutSlowInEasing)) +
                                    slideOutHorizontally(
                                        tween(200, easing = LinearOutSlowInEasing),
                                        targetOffsetX = { (-slide * dir).value.toInt() }
                                    )
                            )
                    },
                    label = "onboarding_step"
                ) { current ->
                    when (current) {
                        ObStep.WELCOME -> WelcomeStep(onStart = { advanceFrom(ObStep.WELCOME) })
                        ObStep.API_KEY -> ApiKeyStep(
                            viewModel = viewModel,
                            onNext = { advanceFrom(ObStep.API_KEY) }
                        )
                        ObStep.LOCAL_MODEL -> LocalModelStep(
                            viewModel = viewModel,
                            onNext = { advanceFrom(ObStep.LOCAL_MODEL) }
                        )
                        ObStep.AGENT_TOGGLE -> AgentToggleStep(
                            enabled = agentEnabled,
                            onToggle = { agentEnabled = it },
                            onNext = {
                                viewModel.setAgentEnabled(agentEnabled)
                                advanceFrom(ObStep.AGENT_TOGGLE)
                            }
                        )
                        ObStep.AGENT_API -> AgentApiStep(
                            viewModel = viewModel,
                            onNext = { advanceFrom(ObStep.AGENT_API) }
                        )
                        ObStep.FONT_SIZE -> FontSizeStep(
                            viewModel = viewModel,
                            onNext = { advanceFrom(ObStep.FONT_SIZE) }
                        )
                        ObStep.TTS_VOICE -> TtsVoiceStep(
                            viewModel = viewModel,
                            onNext = { advanceFrom(ObStep.TTS_VOICE) }
                        )
                        ObStep.INPUT_STYLE -> InputStyleStep(
                            viewModel = viewModel,
                            onNext = { advanceFrom(ObStep.INPUT_STYLE) }
                        )
                        ObStep.AGENT_PROFILE -> AgentProfileStep(
                            viewModel = viewModel,
                            onNext = { advanceFrom(ObStep.AGENT_PROFILE) }
                        )
                        ObStep.DONE -> DoneStep(
                            onEnter = {
                                viewModel.completeOnboarding()
                                onFinished()
                            }
                        )
                    }
                }
            }
        }
    }
}

// ── shared building blocks ──────────────────────────────────────────

@Composable
private fun ObTopBar(
    progress: Float,
    stepLabel: String,
    onBack: () -> Unit
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(
            durationMillis = 600,
            easing = FastOutSlowInEasing
        ),
        label = "obProgress"
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                stepLabel,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.size(48.dp))
        }
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
            strokeCap = StrokeCap.Round,
        )
    }
}

@Composable
private fun ObBottomBar(
    onNext: () -> Unit,
    nextLabel: String = "继续",
    nextEnabled: Boolean = true,
    onSkip: (() -> Unit)? = null,
    skipLabel: String = "跳过"
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onSkip != null) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier.weight(0.4f)
            ) {
                Text(skipLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(
            onClick = onNext,
            enabled = nextEnabled,
            modifier = Modifier.weight(1f).height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text(nextLabel, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Scrollable content area — caller must pass Modifier.weight(1f) when inside a Column. */
@Composable
private fun ObStepColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Top,
        content = content
    )
}

/** Wraps a composable with a fade-in + slide-up entrance animation. */
@Composable
private fun ObAnimatedItem(
    delayMs: Long = 0,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(500, delayMillis = delayMs.toInt(), easing = FastOutSlowInEasing),
        label = "obItemAlpha"
    )
    val offsetY by animateDpAsState(
        targetValue = if (visible) 0.dp else 16.dp,
        animationSpec = tween(500, delayMillis = delayMs.toInt(), easing = FastOutSlowInEasing),
        label = "obItemOffset"
    )
    Box(modifier = Modifier.offset(y = offsetY).alpha(alpha)) {
        content()
    }
}

@Composable
private fun ObHeading(title: String, subtitle: String) {
    ObAnimatedItem(delayMs = 0) {
        Column {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                title,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                subtitle,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 22.sp
            )
        }
    }
    Spacer(modifier = Modifier.height(32.dp))
}

// ── Step 0: Welcome ─────────────────────────────────────────────────

@Composable
private fun WelcomeStep(onStart: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val logoScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.85f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "logoScale"
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "contentAlpha"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Falling snowflakes background
        SnowfallCanvas(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Snowflake icon above logo
            Image(
                imageVector = Icons.Default.AcUnit,
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .alpha(contentAlpha * 0.6f)
            )
            Spacer(modifier = Modifier.height(16.dp))

            Image(
                painter = painterResource(id = com.zhibai.app.R.drawable.mimo_logo_foreground),
                contentDescription = null,
                modifier = Modifier
                    .size(120.dp)
                    .scale(logoScale)
                    .alpha(contentAlpha)
            )
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "知白",
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 10.sp,
                modifier = Modifier.alpha(contentAlpha)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "雪",
                fontSize = 20.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alpha(contentAlpha)
            )
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                "v2.6 · 纯净如雪",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.outline,
                letterSpacing = 2.sp,
                modifier = Modifier.alpha(contentAlpha)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "MiMo AI 非官方客户端",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                modifier = Modifier.alpha(contentAlpha)
            )
            Spacer(modifier = Modifier.height(56.dp))

            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .alpha(contentAlpha),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(26.dp)
            ) {
                Text("踏雪而行", fontSize = 17.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp)
            }
        }
    }
}

// ── Snowfall animation ──────────────────────────────────────────────

@Composable
private fun SnowfallCanvas(modifier: Modifier = Modifier) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    // Three depth layers: far (small, faint), mid, near (large, clearer)
    val farColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.05f)
    val midColor = if (isDark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.08f)
    val nearColor = if (isDark) Color.White.copy(alpha = 0.38f) else Color.Black.copy(alpha = 0.14f)

    val infiniteTransition = rememberInfiniteTransition(label = "snow")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "snowProgress"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Helper: draw one snowflake with rotation
        fun drawFlake(
            cx: Float, cy: Float, radius: Float,
            color: Color, rotation: Float, isDetailed: Boolean
        ) {
            if (isDetailed && radius > 2f) {
                // Six-arm snowflake with rotation
                val armLen = radius * 1.6f
                val strokeW = radius * 0.35f
                for (arm in 0 until 6) {
                    val angle = rotation + arm * 60f * (kotlin.math.PI.toFloat() / 180f)
                    val dx = kotlin.math.cos(angle.toDouble()).toFloat() * armLen
                    val dy = kotlin.math.sin(angle.toDouble()).toFloat() * armLen
                    drawLine(
                        color = color,
                        start = Offset(cx - dx * 0.25f, cy - dy * 0.25f),
                        end = Offset(cx + dx, cy + dy),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                    // Small branch at 1/3 point
                    val bx = cx + dx * 0.55f
                    val by = cy + dy * 0.55f
                    val branchAngle = angle + 0.6f
                    val branchLen = armLen * 0.35f
                    drawLine(
                        color = color,
                        start = Offset(bx, by),
                        end = Offset(
                            bx + kotlin.math.cos(branchAngle.toDouble()).toFloat() * branchLen,
                            by + kotlin.math.sin(branchAngle.toDouble()).toFloat() * branchLen
                        ),
                        strokeWidth = strokeW * 0.6f,
                        cap = StrokeCap.Round
                    )
                }
            } else {
                drawCircle(color = color, radius = radius, center = Offset(cx, cy))
            }
        }

        // ── Far layer: 25 tiny, slow, faint flakes ──
        for (i in 0 until 25) {
            val seed = i * 71.3f
            val xBase = ((kotlin.math.sin(seed.toDouble()).toFloat() * 0.5f + 0.5f)) * w
            val speed = 0.15f + (i % 3) * 0.06f
            val y = ((progress * speed + i * 0.04f) % 1f) * (h + 30f) - 15f
            val sway = kotlin.math.sin(seed * 0.5f).toFloat() * 20f
            val x = xBase + sway * kotlin.math.sin(progress * 6.28f * 1.2f + seed)
            val edgeFade = when {
                y < 0f || y > h -> 0f
                y < h * 0.08f -> (y / (h * 0.08f)).coerceIn(0f, 1f)
                y > h * 0.92f -> ((h - y) / (h * 0.08f)).coerceIn(0f, 1f)
                else -> 1f
            }
            drawFlake(
                cx = x, cy = y,
                radius = 1f + (i % 3) * 0.5f,
                color = farColor.copy(alpha = farColor.alpha * edgeFade),
                rotation = progress * 120f + seed,
                isDetailed = false
            )
        }

        // ── Mid layer: 18 medium flakes with slight rotation ──
        for (i in 0 until 18) {
            val seed = i * 113.7f + 42f
            val xBase = ((kotlin.math.sin(seed.toDouble()).toFloat() * 0.5f + 0.5f)) * w
            val speed = 0.25f + (i % 4) * 0.08f
            val y = ((progress * speed + i * 0.055f) % 1f) * (h + 40f) - 20f
            val sway = kotlin.math.sin(seed * 0.7f).toFloat() * 35f
            val x = xBase + sway * kotlin.math.sin(progress * 6.28f * 1.5f + seed)
            val edgeFade = when {
                y < 0f || y > h -> 0f
                y < h * 0.06f -> (y / (h * 0.06f)).coerceIn(0f, 1f)
                y > h * 0.94f -> ((h - y) / (h * 0.06f)).coerceIn(0f, 1f)
                else -> 1f
            }
            drawFlake(
                cx = x, cy = y,
                radius = 1.8f + (i % 4) * 0.7f,
                color = midColor.copy(alpha = midColor.alpha * edgeFade),
                rotation = progress * 180f + seed,
                isDetailed = i % 2 == 0
            )
        }

        // ── Near layer: 10 large flakes, detailed, gentle rotation ──
        for (i in 0 until 10) {
            val seed = i * 197.3f + 88f
            val xBase = ((kotlin.math.sin(seed.toDouble()).toFloat() * 0.5f + 0.5f)) * w
            val speed = 0.35f + (i % 3) * 0.1f
            val y = ((progress * speed + i * 0.1f) % 1f) * (h + 60f) - 30f
            val sway = kotlin.math.sin(seed * 0.9f).toFloat() * 50f
            val x = xBase + sway * kotlin.math.sin(progress * 6.28f * 1.8f + seed)
            val edgeFade = when {
                y < 0f || y > h -> 0f
                y < h * 0.04f -> (y / (h * 0.04f)).coerceIn(0f, 1f)
                y > h * 0.96f -> ((h - y) / (h * 0.04f)).coerceIn(0f, 1f)
                else -> 1f
            }
            drawFlake(
                cx = x, cy = y,
                radius = 3f + (i % 3) * 1.5f,
                color = nearColor.copy(alpha = nearColor.alpha * edgeFade),
                rotation = progress * 90f + seed,
                isDetailed = true
            )
        }
    }
}

// ── Step 1: API Key ─────────────────────────────────────────────────

@Composable
private fun ApiKeyStep(
    viewModel: ChatViewModel,
    onNext: () -> Unit
) {
    val savedKey by viewModel.apiKey.collectAsState()
    var input by rememberSaveable { mutableStateOf(savedKey) }
    var visible by rememberSaveable { mutableStateOf(false) }
    var hint by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        ObStepColumn(modifier = Modifier.weight(1f)) {
            ObHeading("配置 API Key", "连接 MiMo 服务后才能开始对话。填好后点「继续」。")

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("MiMo API Key") },
                placeholder = { Text("sk-xxxxx") },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                onClick = { hint = !hint },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Lightbulb,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "如何获取 API Key",
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            if (hint) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AnimatedVisibility(visible = hint) {
                        Text(
                            "1. 访问 platform.xiaomimimo.com\n2. 登录或注册账号\n3. 进入 API Keys 区域\n4. 创建并复制 Key",
                            fontSize = 13.sp,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        ObBottomBar(
            onNext = {
                if (input.isNotBlank()) viewModel.setApiKey(input.trim())
                onNext()
            },
            nextLabel = if (savedKey.isNotBlank()) "保存并继续" else "继续",
            nextEnabled = input.isNotBlank()
        )
    }
}

// ── Step 1b: local model selection (built-in MNN) ───────────────────

private enum class ObLocalAiDialog { NONE, WARNING, BATTERY, INSTALL_CONFIRM, ERROR, UNSUPPORTED }

@Composable
private fun LocalModelStep(
    viewModel: ChatViewModel,
    onNext: () -> Unit
) {
    val installing by viewModel.localAiInstalling.collectAsState()
    val enabled by viewModel.localAiEnabled.collectAsState()
    val activeModel by viewModel.localAiModel.collectAsState()
    val scope = rememberCoroutineScope()

    var support by remember { mutableStateOf<LocalAiSupport.Support?>(null) }
    var models by remember { mutableStateOf<List<LocalModel>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<LocalModel?>(null) }
    var dialog by remember { mutableStateOf(ObLocalAiDialog.NONE) }
    var statusMsg by remember { mutableStateOf("") }

    LaunchedEffect(reloadKey) {
        loading = true
        loadError = null
        support = viewModel.localAiSupport()
        try {
            val list = withContext(Dispatchers.IO) { ModelRegistry.fetch() }
            models = list
            if (selected == null) {
                selected = ModelRegistry.defaultModel(
                    list, support?.tier ?: LocalAiSupport.Tier.HIGH
                )
            }
        } catch (e: Exception) {
            loadError = e.message ?: "网络错误"
        } finally {
            loading = false
        }
    }

    fun confirmOrInstall() {
        val m = selected ?: return
        if (viewModel.isLocalAiInstalled(m)) {
            dialog = ObLocalAiDialog.NONE
            viewModel.setLocalAiEnabled(true, m.displayName)
        } else {
            dialog = ObLocalAiDialog.INSTALL_CONFIRM
        }
    }

    fun beginEnable() {
        val s = support ?: viewModel.localAiSupport().also { support = it }
        if (!s.supported) {
            dialog = ObLocalAiDialog.UNSUPPORTED
        } else if (!viewModel.localAiBatteryOk()) {
            statusMsg = ""
            dialog = ObLocalAiDialog.BATTERY
        } else {
            confirmOrInstall()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ObStepColumn(modifier = Modifier.weight(1f)) {
            ObHeading(
                "本地模型（可选）",
                "内置 MNN 推理引擎，不依赖任何第三方应用。启用后可离线对话；也可以跳过继续使用云端。"
            )

            support?.let { s ->
                Text(
                    "本机档位：${LocalAiSupport.tierLabel(s.tier)} · " +
                        "${"%.1f".format(s.totalRamGb)}GB 内存",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!s.supported) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(s.reason, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("正在获取模型列表…", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                loadError != null -> Column {
                    Text(
                        "获取模型列表失败：$loadError",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { reloadKey++ }) { Text("重试") }
                }

                else -> {
                    val tier = support?.tier ?: LocalAiSupport.Tier.HIGH
                    models.forEach { m ->
                        val allowed = ModelRegistry.allowedForTier(tier, m)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .then(
                                    if (selected?.id == m.id && allowed)
                                        Modifier.background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                                        )
                                    else Modifier
                                )
                                .clickable { if (allowed && !installing) selected = m }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            RadioButton(
                                selected = selected?.id == m.id,
                                enabled = allowed && !installing,
                                onClick = { if (allowed && !installing) selected = m }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(m.displayName, fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium)
                                Text(
                                    buildString {
                                        append("%.1fB".format(m.paramsB))
                                        append(" · ")
                                        append(
                                            when {
                                                !allowed -> "本机档位不足"
                                                m.displayName.contains("MiMo-7B-RL") -> "推荐 · 默认"
                                                else -> "可安装"
                                            }
                                        )
                                    },
                                    fontSize = 12.sp,
                                    color = when {
                                        !allowed -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        m.displayName.contains("MiMo-7B-RL") ->
                                            MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (installing) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "下载中 — 进度与完成提醒见灵动岛",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary
                )
            }
            if (enabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "已启用 · $activeModel",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (statusMsg.isNotBlank() && dialog == ObLocalAiDialog.NONE) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(statusMsg, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        }

        ObBottomBar(
            onNext = {
                if (enabled || installing) onNext()
                else dialog = ObLocalAiDialog.WARNING
            },
            nextLabel = when {
                installing -> "安装中…"
                enabled -> "完成"
                else -> "启用本地AI"
            },
            nextEnabled = !installing && (enabled || selected != null),
            onSkip = { onNext() },
            skipLabel = "跳过"
        )
    }

    // ── dialogs ──
    when (dialog) {
        ObLocalAiDialog.NONE -> {}

        ObLocalAiDialog.WARNING -> AlertDialog(
            onDismissRequest = { dialog = ObLocalAiDialog.NONE },
            title = { Text("启用本地AI？") },
            text = {
                Text(buildAnnotatedString {
                    append("本地AI可能更慢，甚至导致发热卡顿\n")
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append("严重的会导致死机")
                    pop()
                    append("\n是否继续？")
                }, lineHeight = 22.sp)
            },
            confirmButton = { TextButton(onClick = { beginEnable() }) { Text("继续") } },
            dismissButton = { TextButton(onClick = { dialog = ObLocalAiDialog.NONE }) { Text("取消") } }
        )

        ObLocalAiDialog.UNSUPPORTED -> AlertDialog(
            onDismissRequest = { dialog = ObLocalAiDialog.NONE },
            title = { Text("机型不在支持名单") },
            text = {
                Text((support?.reason ?: "该设备不满足本地AI要求") +
                    "\n\n支持名单按内存分档：低档 4-6GB、中档 6-8GB、高档 ≥8GB（需 arm64）。")
            },
            confirmButton = { TextButton(onClick = { dialog = ObLocalAiDialog.NONE }) { Text("关闭") } }
        )

        ObLocalAiDialog.BATTERY -> AlertDialog(
            onDismissRequest = { dialog = ObLocalAiDialog.NONE },
            title = { Text("先设置省电策略") },
            text = {
                Text("准备安装本地模型前，需要把本应用的省电策略设为「无限制」，" +
                    "否则下载和推理过程可能被系统杀掉。\n\n$statusMsg")
            },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.localAiBatteryOk()) {
                        dialog = ObLocalAiDialog.NONE
                        confirmOrInstall()
                    } else {
                        statusMsg = "仍未设为「无限制」，请修改后重试"
                    }
                }) { Text("我已设置") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.openLocalAiBatterySettings() }) { Text("去设置") }
            }
        )

        ObLocalAiDialog.INSTALL_CONFIRM -> AlertDialog(
            onDismissRequest = { dialog = ObLocalAiDialog.NONE },
            title = { Text("准备安装") },
            text = {
                val m = selected
                Text(buildString {
                    append("将下载并安装本地模型：\n")
                    append("· ").append(m?.displayName ?: "—").append('\n')
                    if (m != null && m.paramsB > 0) {
                        append("· 约 ").append("%.1f".format(m.paramsB * 0.74)).append(" GB（4bit）\n")
                    }
                    append("\n下载期间会通过灵动岛显示进度，完成后提醒你。")
                }, lineHeight = 22.sp)
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = ObLocalAiDialog.NONE
                    val m = selected ?: return@TextButton
                    viewModel.installLocalModel(m) { ok, msg ->
                        if (ok) viewModel.setLocalAiEnabled(true, m.displayName)
                        else scope.launch {
                            statusMsg = "安装失败: $msg"
                            dialog = ObLocalAiDialog.ERROR
                        }
                    }
                }) { Text("开始安装") }
            },
            dismissButton = {
                TextButton(onClick = { dialog = ObLocalAiDialog.NONE }) { Text("取消") }
            }
        )

        ObLocalAiDialog.ERROR -> AlertDialog(
            onDismissRequest = { dialog = ObLocalAiDialog.NONE },
            title = { Text("本地AI出错了") },
            text = { Text(statusMsg) },
            confirmButton = { TextButton(onClick = { dialog = ObLocalAiDialog.NONE }) { Text("关闭") } }
        )
    }
}

// ── Step 2: Agent toggle ────────────────────────────────────────────

@Composable
private fun AgentToggleStep(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onNext: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ObStepColumn(modifier = Modifier.weight(1f)) {
            ObHeading("智能体模式", "开启后 AI 可以读写文件、执行命令，在你的设备上自主工作。")

            ObFeatureCard(
                icon = Icons.Default.FolderOpen,
                title = "本地文件工作区",
                desc = "在 /sdcard/知白/ 读写文档、代码和笔记",
                highlight = enabled
            )
            Spacer(modifier = Modifier.height(10.dp))
            ObFeatureCard(
                icon = Icons.Default.SmartToy,
                title = "自主工具调用",
                desc = "搜索、编辑、下载，敏感操作仍会先问你",
                highlight = enabled
            )
            Spacer(modifier = Modifier.height(10.dp))
            ObFeatureCard(
                icon = Icons.Default.PhoneAndroid,
                title = "手机控制",
                desc = "打开应用、获取设备信息（需额外授权）",
                highlight = enabled
            )
            Spacer(modifier = Modifier.height(28.dp))

            Card(
                onClick = { onToggle(!enabled) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (enabled)
                        MiMoColors.Primary.copy(alpha = 0.08f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (enabled) "智能体已开启" else "暂不开启",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (enabled) "后续可在此页重新配置接入"
                                else "仅使用聊天功能，随时可在设置里开启",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = enabled,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedThumbColor = MaterialTheme.colorScheme.background
                        )
                    )
                }
            }
        }

        ObBottomBar(onNext = onNext, nextLabel = "继续")
    }
}

@Composable
private fun ObFeatureCard(
    icon: ImageVector,
    title: String,
    desc: String,
    highlight: Boolean
) {
    val bg by animateColorAsState(
        targetValue = if (highlight)
            MiMoColors.Primary.copy(alpha = 0.06f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        animationSpec = tween(300),
        label = "featureBg"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (highlight) MiMoColors.Primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    desc,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

// ── Step 2b: Agent API (skippable) ──────────────────────────────────

@Composable
private fun AgentApiStep(
    viewModel: ChatViewModel,
    onNext: () -> Unit
) {
    val savedUrl by viewModel.agentBaseUrl.collectAsState()
    val savedKey by viewModel.agentApiKey.collectAsState()
    var url by rememberSaveable {
        mutableStateOf(savedUrl.ifBlank { "https://api.xiaomimimo.com/v1" })
    }
    var key by rememberSaveable { mutableStateOf(savedKey) }
    var keyVisible by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        ObStepColumn(modifier = Modifier.weight(1f)) {
            ObHeading(
                "智能体接入",
                "为 Agent 单独指定服务地址和 Key。留空则与主聊天共用。"
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("请求地址 Base URL") },
                placeholder = { Text("https://api.xiaomimimo.com/v1") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Agent API Key（可留空）") },
                placeholder = { Text("与主 Key 相同则留空") },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "使用第三方中转服务时填写自定义地址；官方直连保持默认即可。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
                lineHeight = 18.sp
            )
        }

        ObBottomBar(
            onNext = {
                viewModel.setAgentBaseUrl(url.trim().ifBlank { "https://api.xiaomimimo.com/v1" })
                viewModel.setAgentApiKey(key.trim())
                onNext()
            },
            nextLabel = "保存并继续",
            onSkip = {
                viewModel.setAgentBaseUrl("https://api.xiaomimimo.com/v1")
                viewModel.setAgentApiKey("")
                onNext()
            },
            skipLabel = "跳过，用默认"
        )
    }
}

// ── Step 3: Font size ───────────────────────────────────────────────

@Composable
private fun FontSizeStep(
    viewModel: ChatViewModel,
    onNext: () -> Unit
) {
    val savedSize by viewModel.fontSize.collectAsState()
    var slider by remember { mutableFloatStateOf(savedSize.toFloat()) }

    Column(modifier = Modifier.fillMaxSize()) {
        ObStepColumn(modifier = Modifier.weight(1f)) {
            ObHeading("文字大小", "调整聊天消息的阅读字号。")

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        "预览",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .align(Alignment.End)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            "今天天气怎么样？",
                            fontSize = slider.toInt().sp,
                            color = MaterialTheme.colorScheme.background
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            "温州今天多云转晴，气温 22-28°C，适合出门。\nThe spring breeze brushes the willows as birds sing and call,\nUpon the rippling azure waves, a lone boat drifts along.\n春风拂柳鸟啼鸣，碧波荡漾孤舟行。",
                            fontSize = slider.toInt().sp,
                            lineHeight = (slider.toInt() + 6).sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                "${slider.toInt()} sp",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            // Slider with a dot above the midpoint (15 = default)
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "15",
                        fontSize = 10.sp,
                        color = if (slider.toInt() == 15) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = slider,
                    onValueChange = { slider = it },
                    valueRange = 10f..20f,
                    steps = 9,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(top = 36.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "小",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "标准 15",
                    fontSize = 12.sp,
                    color = if (slider.toInt() == 15) MiMoColors.Primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (slider.toInt() == 15) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    "大",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        ObBottomBar(
            onNext = {
                viewModel.setFontSize(slider.toInt())
                onNext()
            }
        )
    }
}

// ── Step 4: TTS voice ───────────────────────────────────────────────

@Composable
private fun TtsVoiceStep(
    viewModel: ChatViewModel,
    onNext: () -> Unit
) {
    val savedVoice by viewModel.ttsVoice.collectAsState()
    var selected by remember { mutableStateOf(savedVoice) }

    val voices = listOf(
        Triple("冰糖", "甜美女声", true),
        Triple("茉莉", "温柔女声", true),
        Triple("苏打", "清爽男声", true),
        Triple("白桦", "沉稳男声", true),
        Triple("Mia", "English female", false),
        Triple("Chloe", "English female", false),
        Triple("Milo", "English male", false),
        Triple("Dean", "English male", false)
    )

    Column(modifier = Modifier.fillMaxSize()) {
        ObStepColumn(modifier = Modifier.weight(1f)) {
            ObHeading("讲述人音色", "选择长按消息朗读时使用的默认声音。")

            voices.forEach { (id, desc, isCn) ->
                val isSelected = selected == id
                Card(
                    onClick = { selected = id },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected)
                            MiMoColors.Primary.copy(alpha = 0.08f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(id, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                                if (!isCn) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "EN",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                MaterialTheme.colorScheme.outlineVariant
                                                    .copy(alpha = 0.5f)
                                            )
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                desc,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        AnimatedVisibility(visible = isSelected) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        ObBottomBar(
            onNext = {
                viewModel.setTtsVoice(selected)
                onNext()
            }
        )
    }
}

// ── Step: Input bar style (glass vs classic) ────────────────────────

@Composable
private fun InputStyleStep(
    viewModel: ChatViewModel,
    onNext: () -> Unit
) {
    val savedStyle by viewModel.inputStyle.collectAsState()
    var selected by remember { mutableStateOf(savedStyle) }

    Column(modifier = Modifier.fillMaxSize()) {
        ObStepColumn(modifier = Modifier.weight(1f)) {
            ObHeading("输入框外观", "选择聊天输入框的视觉风格。")

            // Glass option
            Card(
                onClick = { selected = "glass" },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected == "glass")
                        MiMoColors.Primary.copy(alpha = 0.08f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                border = if (selected == "glass")
                    androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                else null,
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = null,
                            tint = if (selected == "glass") MiMoColors.Primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("磨砂玻璃（推荐）", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        if (selected == "glass") {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "悬浮半透明，输入时变不透明，上下滑动可透视下方消息。支持自定义磨砂颜色与阴影开关，圆角固定 32dp。",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Classic option
            Card(
                onClick = { selected = "classic" },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected == "classic")
                        MiMoColors.Primary.copy(alpha = 0.08f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                border = if (selected == "classic")
                    androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                else null,
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            tint = if (selected == "classic") MiMoColors.Primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("经典样式", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        if (selected == "classic") {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "传统不透明输入框，简洁稳定。适合喜欢简约风格的用户。",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        ObBottomBar(
            onNext = {
                viewModel.setInputStyle(selected)
                onNext()
            }
        )
    }
}

// ── Step 5: Agent workspace personalization (skippable) ─────────────

@Composable
private fun AgentProfileStep(
    viewModel: ChatViewModel,
    onNext: () -> Unit
) {
    var userName by rememberSaveable { mutableStateOf("") }
    var agentName by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("温州，浙江，中国") }
    var vibe by rememberSaveable { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        ObStepColumn(modifier = Modifier.weight(1f)) {
            ObHeading(
                "个性化智能体",
                "填写后会写入 Agent 的工作区文件（IDENTITY / USER / SOUL），让它更懂你。"
            )

            ObProfileField(
                label = "你的名字",
                placeholder = "希望 Agent 怎么称呼你",
                value = userName,
                onValueChange = { userName = it }
            )
            ObProfileField(
                label = "Agent 的名字",
                placeholder = "给你的智能体取个名字",
                value = agentName,
                onValueChange = { agentName = it }
            )
            ObProfileField(
                label = "所在城市",
                placeholder = "用于时区与本地信息",
                value = location,
                onValueChange = { location = it }
            )
            ObProfileField(
                label = "性格偏好（可选）",
                placeholder = "例如：说话简洁，别废话",
                value = vibe,
                onValueChange = { vibe = it },
                singleLine = false,
                maxLines = 3
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "以上均可留空，Agent 会使用默认人格。之后也能在工作区直接编辑这些 .md 文件。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
                lineHeight = 18.sp
            )
        }

        ObBottomBar(
            onNext = {
                val profile = buildMap {
                    val identityName = agentName.trim().ifBlank { "MiMo Agent" }
                    put(
                        "IDENTITY.md",
                        """# Identity
You are $identityName, a local AI assistant running on the user's Android device.
You operate autonomously within the user's granted permissions.
Your workspace is at /sdcard/知白/ (or the configured workspace path).""".trimIndent()
                    )
                    if (userName.isNotBlank() || location.isNotBlank() || vibe.isNotBlank()) {
                        buildString {
                            appendLine("# User Profile")
                            if (userName.isNotBlank()) appendLine("- Name: ${userName.trim()}")
                            if (location.isNotBlank()) appendLine("- Location: ${location.trim()}")
                            appendLine("- Timezone: Asia/Shanghai (UTC+8)")
                            if (vibe.isNotBlank()) appendLine("- Preferences: ${vibe.trim()}")
                        }.trim().let { put("USER.md", it) }
                    }
                    if (vibe.isNotBlank()) {
                        put(
                            "SOUL.md",
                            """# Soul
## Core Principles
- Be genuinely useful, not performatively helpful
- Have opinions, be direct, skip pleasantries
- Try to figure things out yourself before asking
- Respect user's time - concise answers, clean code

## Personality
${vibe.trim()}""".trimIndent()
                        )
                    }
                }
                if (profile.isNotEmpty()) {
                    viewModel.applyAgentWorkspaceProfile(profile)
                }
                onNext()
            },
            onSkip = onNext,
            skipLabel = "跳过"
        )
    }
}

@Composable
private fun ObProfileField(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    maxLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        label = { Text(label) },
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.outline) },
        singleLine = singleLine,
        maxLines = maxLines,
        shape = RoundedCornerShape(12.dp)
    )
}

// ── Final: Done ─────────────────────────────────────────────────────

/**
 * Morph success animation (PPT-style):
 *  phase 0  a horizontal line draws across (like the progress bar)
 *  phase 1  the line bends down and closes into a circle
 *  phase 2  the circle fills solid
 *  phase 3  a checkmark draws itself stroke-by-stroke
 */
@Composable
private fun DoneStep(onEnter: () -> Unit) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        started = true
        delay(2600) // total animation duration before advancing
        onEnter()
    }

    // Master progress 0 → 1 with a smooth curve
    val master by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 2400, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
        label = "morphMaster"
    )

    // Phase boundaries
    val lineDraw = (master / 0.22f).coerceIn(0f, 1f)              // 0 → 0.22
    val morph = ((master - 0.25f) / 0.28f).coerceIn(0f, 1f)       // 0.25 → 0.53
    val fill = ((master - 0.55f) / 0.15f).coerceIn(0f, 1f)        // 0.55 → 0.70
    val check = ((master - 0.72f) / 0.24f).coerceIn(0f, 1f)       // 0.72 → 0.96
    val textAlpha = ((master - 0.85f) / 0.12f).coerceIn(0f, 1f)

    // Circle geometry (centered)
    val circleRadius = 56.dp
    val strokeWidth = 6.dp

    // Text appears after circle completes
    val textSlide by animateFloatAsState(
        targetValue = if (textAlpha > 0f) 0f else 24f,
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "textSlide"
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Canvas(
            modifier = Modifier.size(circleRadius * 2 + strokeWidth * 2)
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = circleRadius.toPx()
            val sw = strokeWidth.toPx()

            val primaryColor = Color(0xFF000000) // pure black per user preference
            val trackColor = Color(0x33000000)

            // ── Draw the morphing line / circle ──
            if (morph < 1f) {
                // Phase 0-1: horizontal line that bends into a circle
                val path = androidx.compose.ui.graphics.Path()

                if (morph <= 0f) {
                    // Pure horizontal line
                    val halfLen = r * lineDraw
                    path.moveTo(cx - halfLen, cy)
                    path.lineTo(cx + halfLen, cy)
                } else {
                    // Morph: line endpoints curve down and around to close the circle
                    // At morph=0: straight line; at morph=1: full circle
                    val startAngle = Math.toRadians(-180.0).toFloat() // left side
                    val totalSweep = Math.toRadians((360.0 * morph)).toFloat()

                    // Blend: for small morph, keep line-like; for large, arc-like
                    // We draw an arc from -180° sweeping clockwise, but blend the
                    // radius of the "flat" part into the circle
                    val flatBlend = 1f - morph // 1 = flat line, 0 = circle

                    // Start point of arc
                    val startX = cx + r * kotlin.math.cos(startAngle)
                    val startY = cy + r * kotlin.math.sin(startAngle)

                    // For a flat line, y is always cy; for circle, y follows the arc
                    // We interpolate: at morph=0, path is line; at morph=1, path is arc
                    val steps = 64
                    for (i in 0..steps) {
                        val t = i.toFloat() / steps
                        val angle = startAngle + totalSweep * t
                        val arcX = cx + r * kotlin.math.cos(angle)
                        val arcY = cy + r * kotlin.math.sin(angle)
                        // Flat position: x moves linearly across the diameter, y = cy
                        val flatX = cx - r + (2f * r * t)
                        val flatY = cy
                        // Blend
                        val px = flatX * flatBlend + arcX * morph
                        val py = flatY * flatBlend + arcY * morph
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                }

                drawPath(
                    path = path,
                    color = if (fill > 0f) primaryColor else trackColor,
                    style = Stroke(width = sw, cap = StrokeCap.Round)
                )
            } else {
                // Full circle outline (after morph completes, before/during fill)
                drawCircle(
                    color = trackColor,
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = sw)
                )
            }

            // ── Fill the circle ──
            if (fill > 0f) {
                val fillRadius = r * fill
                drawCircle(
                    color = primaryColor,
                    radius = fillRadius,
                    center = Offset(cx, cy)
                )
            }

            // ── Draw the checkmark ──
            if (check > 0f) {
                // Checkmark path: two segments (short down-right, then long up-right)
                val checkColor = Color.White
                val checkStroke = sw * 0.9f

                // Points relative to center
                val p1x = cx - r * 0.38f
                val p1y = cy + r * 0.02f
                val p2x = cx - r * 0.08f
                val p2y = cy + r * 0.35f
                val p3x = cx + r * 0.42f
                val p3y = cy - r * 0.30f

                // Total length for proportional drawing
                val len1 = kotlin.math.hypot(p2x - p1x, p2y - p1y)
                val len2 = kotlin.math.hypot(p3x - p2x, p3y - p2y)
                val totalLen = len1 + len2

                // Draw progressively based on `check` progress
                val drawnLen = totalLen * check

                val path = androidx.compose.ui.graphics.Path()
                path.moveTo(p1x, p1y)

                if (drawnLen <= len1) {
                    // Still on first segment
                    val t = drawnLen / len1
                    path.lineTo(p1x + (p2x - p1x) * t, p1y + (p2y - p1y) * t)
                } else {
                    // First segment complete, drawing second
                    path.lineTo(p2x, p2y)
                    val t = (drawnLen - len1) / len2
                    path.lineTo(p2x + (p3x - p2x) * t, p2y + (p3y - p2y) * t)
                }

                drawPath(
                    path = path,
                    color = checkColor,
                    style = Stroke(width = checkStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            "一切就绪",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .alpha(textAlpha)
                .offset(y = textSlide.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "开始你的知白之旅",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .alpha(textAlpha)
                .offset(y = textSlide.dp)
        )
    }
}

// ══════════════════════════════════════════════════════════════════════
// Update Preview — shows v2.6 changelog as a mini walkthrough
// ══════════════════════════════════════════════════════════════════════

private enum class UpStep { WELCOME, GLASS, AGENT, THINKING, ADB, ISLAND, QUESTIONNAIRE, NEWLIST, OPTLIST, FIXLIST, FIXES, OVERVIEW, DONE }

@Composable
fun UpdatePreviewScreen(onDismiss: () -> Unit) {
    var step by remember { mutableStateOf(UpStep.WELCOME) }
    var direction by remember { mutableIntStateOf(1) }

    val totalSteps = UpStep.entries.size - 1
    val stepIndex = UpStep.entries.indexOf(step)
    val progress = if (step == UpStep.DONE) 1f
        else stepIndex.toFloat() / (totalSteps - 1).coerceAtLeast(1)

    fun goTo(target: UpStep) {
        direction = if (UpStep.entries.indexOf(target) > stepIndex) 1 else -1
        step = target
    }

    fun advanceFrom(current: UpStep) {
        val next = when (current) {
            UpStep.WELCOME -> UpStep.GLASS
            UpStep.GLASS -> UpStep.AGENT
            UpStep.AGENT -> UpStep.THINKING
            UpStep.THINKING -> UpStep.ADB
            UpStep.ADB -> UpStep.ISLAND
            UpStep.ISLAND -> UpStep.QUESTIONNAIRE
            UpStep.QUESTIONNAIRE -> UpStep.NEWLIST
            UpStep.NEWLIST -> UpStep.OPTLIST
            UpStep.OPTLIST -> UpStep.FIXLIST
            UpStep.FIXLIST -> UpStep.FIXES
            UpStep.FIXES -> UpStep.OVERVIEW
            UpStep.OVERVIEW -> UpStep.DONE
            UpStep.DONE -> UpStep.DONE
        }
        goTo(next)
    }

    fun retreatFrom(current: UpStep) {
        val prev = when (current) {
            UpStep.WELCOME -> UpStep.WELCOME
            UpStep.GLASS -> UpStep.WELCOME
            UpStep.AGENT -> UpStep.GLASS
            UpStep.THINKING -> UpStep.AGENT
            UpStep.ADB -> UpStep.THINKING
            UpStep.ISLAND -> UpStep.ADB
            UpStep.QUESTIONNAIRE -> UpStep.ISLAND
            UpStep.NEWLIST -> UpStep.QUESTIONNAIRE
            UpStep.OPTLIST -> UpStep.NEWLIST
            UpStep.FIXLIST -> UpStep.OPTLIST
            UpStep.FIXES -> UpStep.FIXLIST
            UpStep.OVERVIEW -> UpStep.FIXES
            UpStep.DONE -> UpStep.OVERVIEW
        }
        goTo(prev)
    }

    BackHandler(enabled = step != UpStep.WELCOME && step != UpStep.DONE) {
        retreatFrom(step)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (step != UpStep.WELCOME && step != UpStep.DONE) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { retreatFrom(step) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Text("${stepIndex + 1} / $totalSteps", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "skip")
                        }
                    }
                    val animatedProgress by animateFloatAsState(
                        targetValue = progress,
                        animationSpec = tween(600, easing = FastOutSlowInEasing),
                        label = "upProgress"
                    )
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                        strokeCap = StrokeCap.Round,
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        val dir = direction
                        val slide = 48.dp
                        (fadeIn(tween(320, easing = FastOutSlowInEasing)) +
                            slideInHorizontally(tween(320, easing = FastOutSlowInEasing), initialOffsetX = { (slide * dir).value.toInt() }))
                            .togetherWith(
                                fadeOut(tween(200, easing = LinearOutSlowInEasing)) +
                                    slideOutHorizontally(tween(200, easing = LinearOutSlowInEasing), targetOffsetX = { (-slide * dir).value.toInt() })
                            )
                    },
                    label = "update_step"
                ) { current ->
                    when (current) {
                        UpStep.WELCOME -> UpWelcomeStep(onStart = { advanceFrom(UpStep.WELCOME) })
                        UpStep.GLASS -> UpFeatureStep(
                            icon = Icons.Default.ClearAll,
                            title = "磨砂玻璃输入框",
                            desc = "半透明悬浮设计，输入时自动变不透明。颜色、磨砂度可调，圆角固定 32dp。",
                            bullets = listOf(
                                "设置 → 输入框外观 可自定义",
                                "6 种颜色预设 + 自定义颜色",
                                "磨砂度 0~100% 独立调节",
                                "圆角固定 32dp 胶囊形"
                            ),
                            onNext = { advanceFrom(UpStep.GLASS) }
                        )
                        UpStep.AGENT -> UpFeatureStep(
                            icon = Icons.Default.AcUnit,
                            title = "Agent 任务系统",
                            desc = "T1~T999 任务清单，四种状态追踪。执行时雪花旋转指示。",
                            bullets = listOf(
                                "任务状态：待办 / 进行中 / 已完成 / 已取消",
                                "自动判定成功或失败",
                                "输入框上方雪花动画实时反馈",
                                "完成时蓝色下雪庆祝"
                            ),
                            onNext = { advanceFrom(UpStep.AGENT) }
                        )
                        UpStep.THINKING -> UpFeatureStep(
                            icon = Icons.Default.Psychology,
                            title = "思考等级 Off ~ MAX",
                            desc = "五档推理深度可选，精确控制 token 预算。",
                            bullets = listOf(
                                "关 — 不启用思考",
                                "低 1K / 中 4K / 高 8K tokens",
                                "MAX 16K tokens 深度推理",
                                "输入框工具栏「思考」按钮切换"
                            ),
                            onNext = { advanceFrom(UpStep.THINKING) }
                        )
                        UpStep.ADB -> UpFeatureStep(
                            icon = Icons.Default.Cable,
                            title = "无线调试 ADB",
                            desc = "接入系统无线调试，Agent 的 run_adb 从沙箱假权限升级为真实 shell。",
                            bullets = listOf(
                                "Shizuku 同源协议，SPAKE2 安全配对",
                                "配对一次，之后自动连接",
                                "mDNS 自动发现配对/连接端口",
                                "设置 → AI Agent → 无线调试 ADB"
                            ),
                            onNext = { advanceFrom(UpStep.ADB) }
                        )
                        UpStep.ISLAND -> UpFeatureStep(
                            icon = Icons.Default.Notifications,
                            title = "超级岛状态上岛",
                            desc = "对话与 Agent 运行状态实时显示在超级岛胶囊，退出 App 也能看。",
                            bullets = listOf(
                                "等待响应 / 思考中 / 输出中 / 任务完成",
                                "走秒计时 + 系统动画，不闪屏",
                                "工具审批可在岛上直接允许/拒绝",
                                "输出完成停留 20 秒自动收起",
                                "设置主页可跳转「实时动态」开关"
                            ),
                            onNext = { advanceFrom(UpStep.ISLAND) }
                        )
                        UpStep.QUESTIONNAIRE -> UpFeatureStep(
                            icon = Icons.Default.Assessment,
                            title = "Agent 问卷",
                            desc = "需要你做决定时，AI 直接在聊天里发一张可填写的问卷卡片。",
                            bullets = listOf(
                                "单选 / 多选 / 填空 / 评分",
                                "答案结构化回传，AI 继续执行任务",
                                "必填项校验，跳过需明确放弃",
                                "工具 create_questionnaire"
                            ),
                            onNext = { advanceFrom(UpStep.QUESTIONNAIRE) }
                        )
                        UpStep.NEWLIST -> UpFeatureStep(
                            icon = Icons.Default.NewReleases,
                            title = "新功能",
                            desc = "本轮追加的中小功能。",
                            bullets = listOf(
                                "Markdown 表格网格渲染，长表格可横滑",
                                "代码块保存/查看直达 /sdcard/知白",
                                "设置主页「超级岛状态显示」入口",
                                "高风险回复自动换成友好中文提示"
                            ),
                            onNext = { advanceFrom(UpStep.NEWLIST) }
                        )
                        UpStep.OPTLIST -> UpFeatureStep(
                            icon = Icons.Default.AutoAwesome,
                            title = "新优化",
                            desc = "细节手感打磨。",
                            bullets = listOf(
                                "岛展示改阶段快照，输出过程不再闪屏",
                                "思考不足 1 秒按 1 秒计",
                                "思考完成后 Thinking 变 Thought",
                                "风控消息隐藏思考/复制/重答",
                                "被拒回合不写入长期记忆（含你的消息）"
                            ),
                            onNext = { advanceFrom(UpStep.OPTLIST) }
                        )
                        UpStep.FIXLIST -> UpFeatureStep(
                            icon = Icons.Default.Build,
                            title = "修复已知问题",
                            desc = "本轮修复。",
                            bullets = listOf(
                                "「输出完成」被 finally 误删，现停留 20 秒",
                                "岛权限缺失不上岛（已声明系统权限）",
                                "表格部分场景渲染为空气",
                                "包名迁移残留（com.zhibai.app）"
                            ),
                            onNext = { advanceFrom(UpStep.FIXLIST) }
                        )
                        UpStep.FIXES -> UpFeatureStep(
                            icon = Icons.Default.Build,
                            title = "优化与修复",
                            desc = "大量底层优化，使用更流畅稳定。",
                            bullets = listOf(
                                "单次对话无限制，长回复 32K 不截断",
                                "输出不再闪屏",
                                "自动滚动不再跳动",
                                "LaTeX 溢出修复、表格横滑",
                                "代码块保存/查看、SVG 保存",
                                "深色模式按钮颜色修复"
                            ),
                            onNext = { advanceFrom(UpStep.FIXES) }
                        )
                        UpStep.OVERVIEW -> UpOverviewStep(onDone = { goTo(UpStep.DONE) })
                        UpStep.DONE -> {
                            LaunchedEffect(Unit) { delay(600); onDismiss() }
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpWelcomeStep(onStart: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val contentAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "upWelcomeAlpha"
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            imageVector = Icons.Default.AcUnit,
            contentDescription = null,
            modifier = Modifier.size(48.dp).alpha(contentAlpha * 0.6f)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text("v2.6 雪", fontSize = 36.sp, fontWeight = FontWeight.Bold, modifier = Modifier.alpha(contentAlpha))
        Spacer(modifier = Modifier.height(12.dp))
        Text("这次更新了什么？", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alpha(contentAlpha))
        Spacer(modifier = Modifier.height(8.dp))
        Text("花一分钟看看新功能", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.alpha(contentAlpha))
        Spacer(modifier = Modifier.height(48.dp))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(52.dp).alpha(contentAlpha),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(26.dp)
        ) {
            Text("开始查看", fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun UpFeatureStep(
    icon: ImageVector,
    title: String,
    desc: String,
    bullets: List<String>,
    onNext: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(modifier = Modifier.height(32.dp))
        Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        Text(desc, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 24.sp)
        Spacer(modifier = Modifier.height(24.dp))
        bullets.forEachIndexed { i, bullet ->
            ObAnimatedItem(delayMs = i * 80L) {
                Row(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text("· ", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(bullet, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Spacer(modifier = Modifier.height(40.dp))
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(26.dp)
        ) {
            Text("继续", fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun UpOverviewStep(onDone: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(modifier = Modifier.height(32.dp))
        Text("总览", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text("v2.6 雪 · 全部更新内容", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(24.dp))

        val sections = listOf(
            "磨砂玻璃输入框" to "半透明悬浮，输入时变不透明，颜色/磨砂度可调，圆角 32dp",
            "Agent 任务系统" to "T1~T999 清单，雪花动画指示，自动判定成败",
            "思考等级 Off~MAX" to "五档推理深度，精确控制 token 预算",
            "无限制对话" to "单次 32K tokens，思考模式 65K，不再截断",
            "代码块增强" to "保存到 workspace、外部查看、按语言选扩展名",
            "表格与公式" to "表格左右滑动，LaTeX 溢出修复",
            "性能优化" to "输出不再闪屏，自动滚动优化，消息动画",
            "深色模式" to "按钮颜色修复，全面适配",
            "无线调试 ADB" to "SPAKE2 配对，Agent 获得真实 shell 权限",
            "超级岛上岛" to "五状态胶囊、走秒动画、岛内审批、完成停留 20 秒",
            "Agent 问卷" to "AI 发问卷卡片，单选/多选/填空/评分结构化收集",
            "表格网格渲染" to "Markdown 表格显示为网格，可横滑",
            "代码块存知白" to "保存/查看直达 /sdcard/知白",
            "风控友好提示" to "高风险英文替换为中文，隐藏思考/复制/重答",
            "完成通知修复" to "不再被 finally 秒杀，正常停留"
        )
        sections.forEachIndexed { i, (t, d) ->
            ObAnimatedItem(delayMs = i * 60L) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(t, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(d, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text(
            "想重新看一遍？\n设置 → 关于 → 长按版本号 → 隐藏设置 → 查看更新内容",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
            lineHeight = 18.sp
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(26.dp)
        ) {
            Text("开始使用", fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}
