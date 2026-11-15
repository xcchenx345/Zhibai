package com.zhibai.app.i18n

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Bilingual system-language switch prompt. The question is shown twice — once in
 * the app's current language (from the locale table) and once in the language
 * the system just switched to — then the user picks keep or switch.
 */
@Composable
fun LanguageSwitchDialog() {
    val req by LangManager.switchRequest.collectAsState()
    val r = req ?: return
    val context = LocalContext.current
    val targetName = LangManager.languageName(r.toCode)

    AlertDialog(
        onDismissRequest = { LangManager.dismissSwitch() },
        title = {
            Text(
                text = LangManager.t("lang.switch.title"),
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                // Line 1 — the current (old) app language
                Text(
                    text = LangManager.t("lang.switch.body").replace("{lang}", targetName),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Line 2 — the new system language (bilingual half)
                Text(
                    text = LangManager.promptLineIn(r.toCode),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!r.toAvailable) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = LangManager.t("lang.unsupported.note"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { LangManager.switchTo(context, r.toCode) }) {
                Text("${LangManager.t("lang.switch.do")} · $targetName")
            }
        },
        dismissButton = {
            TextButton(onClick = { LangManager.dismissSwitch() }) {
                Text(LangManager.t("lang.switch.keep"))
            }
        }
    )
}
