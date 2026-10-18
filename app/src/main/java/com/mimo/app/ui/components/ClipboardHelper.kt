package com.mimo.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

/**
 * Robust clipboard helper.
 *
 * Some OEM skins (notably HyperOS/MIUI) silently drop setPrimaryClip under their
 * clipboard protection. We verify by reading the clip back immediately (the
 * foreground app that just wrote it CAN read its own clip on API 29+), retry
 * once on mismatch, and report the real outcome instead of blindly saying "Copied".
 */
object ClipboardHelper {

    fun copy(context: Context, text: String, label: String = "MiMo"): Boolean {
        if (text.isEmpty()) return false
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)

            // verify write actually stuck (foreground app may read its own clip)
            if (readBack(clipboard) == text) return true

            // one retry — transient system hiccups happen on some ROMs
            clipboard.setPrimaryClip(clip)
            readBack(clipboard) == text
        } catch (e: Exception) {
            false
        }
    }

    private fun readBack(clipboard: ClipboardManager): String? {
        return try {
            clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        } catch (e: Exception) {
            null
        }
    }

    /** Copy with honest user feedback (Chinese). */
    fun copyWithToast(context: Context, text: String, label: String = "MiMo") {
        val ok = copy(context, text, label)
        Toast.makeText(
            context,
            if (ok) "已复制到剪贴板" else "复制失败，请长按文本手动复制",
            Toast.LENGTH_SHORT
        ).show()
    }
}
