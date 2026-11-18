package com.zhibai.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhibai.app.R
import com.zhibai.app.i18n.LangManager
import com.zhibai.app.ui.theme.MiMoColors
import com.zhibai.app.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

/**
 * Third-party (OpenAI-compatible) model configuration.
 *
 * Layout per design: a huge DeepSeek mark floats above the form; the warning
 * spells out what is lost when no MiMo key is configured (ASR/TTS off, the
 * chat's "MiMo" label becomes the model id, Xiaomi branding is dropped).
 * Easter egg: the floating mark is the whale; a FAILED connectivity test
 * switches it to the sleeping whale until a test succeeds again.
 */
@Composable
fun ThirdPartyModelPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val savedBase by viewModel.thirdPartyBaseUrl.collectAsState()
    val savedKey by viewModel.thirdPartyApiKey.collectAsState()
    val savedModel by viewModel.thirdPartyModelId.collectAsState()

    var baseUrl by rememberSaveable { mutableStateOf(savedBase) }
    var apiKey by rememberSaveable { mutableStateOf(savedKey) }
    var modelId by rememberSaveable { mutableStateOf(savedModel) }
    var keyVisible by rememberSaveable { mutableStateOf(false) }

    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testFailed by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // The mark drifts slowly up and down - "飘着".
    val floatY by rememberInfiniteTransition(label = "dsFloat").animateFloat(
        initialValue = -12f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse),
        label = "floatY"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = LangManager.t("common.back", "返回"))
            }
            Text(
                LangManager.t("thirdparty.title", "配置其它厂商模型"),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // Floating DeepSeek mark (only DeepSeek for now; other vendor icons orbit later)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                contentAlignment = Alignment.Center
            ) {
                // Light backdrop so the black mark reads on dark themes too
                Box(
                    modifier = Modifier
                        .size(210.dp)
                        .offset(y = floatY.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                ) {
                    Image(
                        painter = painterResource(
                            if (testFailed) R.drawable.deepseek_whale_sleep else R.drawable.deepseek_whale
                        ),
                        contentDescription = LangManager.t("thirdparty.iconDesc", "DeepSeek"),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(28.dp)
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(modifier = Modifier.padding(14.dp)) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MiMoColors.Primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        LangManager.t(
                            "thirdparty.warning",
                            "警告：如果你未配置 MiMo 模型，将会强制关闭一些功能——ASR、TTS；对话界面的「MiMo」会改成模型名；关于小米的一切会被抹去。"
                        ),
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(LangManager.t("thirdparty.baseUrl", "OpenAI 接口地址")) },
                placeholder = { Text("https://api.deepseek.com/v1") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(LangManager.t("thirdparty.apiKey", "API Key")) },
                placeholder = { Text("sk-xxxxx") },
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

            OutlinedTextField(
                value = modelId,
                onValueChange = { modelId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(LangManager.t("thirdparty.modelId", "模型 ID")) },
                placeholder = { Text("deepseek-chat") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        testing = true
                        testMessage = null
                        scope.launch {
                            val err = viewModel.testThirdPartyConnection(baseUrl, apiKey, modelId)
                            testing = false
                            testFailed = err != null
                            testMessage = if (err == null) {
                                LangManager.t("thirdparty.testOk", "连接成功，接口可用。")
                            } else {
                                LangManager.t("thirdparty.testFail", "连接失败：{error}").replace("{error}", err)
                            }
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (testing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(LangManager.t("thirdparty.testing", "测试中…"))
                    } else {
                        Text(LangManager.t("thirdparty.test", "连通性测试"))
                    }
                }

                Button(
                    onClick = {
                        viewModel.saveThirdPartyConfig(baseUrl, apiKey, modelId)
                        saved = true
                    },
                    enabled = !testing,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(LangManager.t("thirdparty.save", "保存配置"))
                }
            }

            testMessage?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    it,
                    fontSize = 13.sp,
                    color = if (testFailed) MiMoColors.Primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (saved) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    LangManager.t("thirdparty.saved", "已保存。未配置 MiMo Key 时，对话将使用该模型。"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
