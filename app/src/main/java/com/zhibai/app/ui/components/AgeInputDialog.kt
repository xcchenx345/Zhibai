package com.zhibai.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhibai.app.i18n.LangManager

/**
 * Mandatory age prompt (voluntary number, no real-name / ID check).
 * Under 18 → shorter usage reminders + youth wellbeing content.
 */
@Composable
fun AgeInputDialog(
    onConfirm: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    val parsed = text.trim().toIntOrNull()
    val valid = parsed != null && parsed in 1..120
    val isMinor = valid && parsed!! in 1..17

    AlertDialog(
        onDismissRequest = { /* must fill to continue */ },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onConfirm(parsed!!) },
                enabled = valid
            ) { Text(LangManager.t("common.ok", "确定")) }
        },
        title = { Text(LangManager.t("youth.age.title", "请填写你的年龄"), fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    LangManager.t("youth.age.desc", "用于合理安排休息提醒（不会做实名认证，仅保存在本机）。"),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        // soft limit: digits only, max 3 chars — no strict ID rules
                        text = input.filter { it.isDigit() }.take(3)
                    },
                    singleLine = true,
                    label = { Text(LangManager.t("youth.age.label", "年龄")) },
                    placeholder = { Text(LangManager.t("youth.age.placeholder", "例如 16")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                if (valid) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        if (isMinor)
                            LangManager.t("youth.age.minorNote", "未满 18 岁：连续使用 30 分钟会温馨提醒，并提供青少年求助热线。")
                        else
                            LangManager.t("youth.age.adultNote", "已满 18 岁：连续使用 60 分钟会提醒你休息一下。"),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
    )
}
