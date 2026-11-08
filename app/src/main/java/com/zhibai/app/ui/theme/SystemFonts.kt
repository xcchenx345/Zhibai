package com.zhibai.app.ui.theme

import android.graphics.Typeface
import java.io.File

/**
 * Preset font definitions for the app.
 * Each preset maps to an Android Typeface loaded from system or constructed.
 */
object SystemFonts {

    data class FontEntry(
        val name: String,
        val path: String // unique ID, not necessarily a file path
    )

    /** All available preset fonts. */
    fun listFonts(): List<FontEntry> = listOf(
        FontEntry("系统默认", "default"),
        FontEntry("Xiaomi Sans（小米特供）", "xiaomi_sans"),
        FontEntry("Note 字体", "note"),
        FontEntry("细体", "thin"),
        FontEntry("粗体", "bold"),
        FontEntry("终端风格", "terminal"),
        FontEntry("方块占位符", "blocks")
    )

    /** Load a Typeface from a preset ID. Returns null for "default". */
    fun loadTypeface(id: String): Typeface? {
        return try {
            when (id) {
                "default" -> null
                "xiaomi_sans" -> loadFromSystemFonts(
                    "MiSans", "MiLanTaFang", "XiaomiSans",
                    "misans", "MiSans-Regular"
                ) ?: Typeface.create("sans-serif", Typeface.NORMAL)
                "note" -> Typeface.SERIF
                "thin" -> Typeface.create("sans-serif", Typeface.NORMAL)
                    .let { Typeface.create(it, 200, false) } // weight 200 = thin
                "bold" -> Typeface.create("sans-serif", Typeface.BOLD)
                "terminal" -> Typeface.MONOSPACE
                "blocks" -> loadBlockFont() ?: Typeface.MONOSPACE
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Try loading a font by name from /system/fonts. */
    private fun loadFromSystemFonts(vararg names: String): Typeface? {
        val dir = File("/system/fonts")
        if (!dir.isDirectory) return null
        val files = dir.listFiles() ?: return null
        for (name in names) {
            val match = files.firstOrNull {
                it.nameWithoutExtension.equals(name, ignoreCase = true) ||
                    it.nameWithoutExtension.startsWith(name, ignoreCase = true)
            }
            if (match != null) {
                try { return Typeface.createFromFile(match) } catch (_: Exception) {}
            }
        }
        return null
    }

    /**
     * Load a "blocks" font — every glyph is a solid square (□ / ■).
     * Uses Noto Sans Symbols or any font with block elements.
     */
    private fun loadBlockFont(): Typeface? {
        val dir = File("/system/fonts")
        if (!dir.isDirectory) return null
        val files = dir.listFiles() ?: return null
        // Try fonts known to have block/box glyphs
        val blockFonts = listOf("NotoSansSymbols", "NotoSansSymbols2", "DroidSansFallback")
        for (name in blockFonts) {
            val match = files.firstOrNull { it.nameWithoutExtension.startsWith(name, ignoreCase = true) }
            if (match != null) {
                try { return Typeface.createFromFile(match) } catch (_: Exception) {}
            }
        }
        return null
    }
}
