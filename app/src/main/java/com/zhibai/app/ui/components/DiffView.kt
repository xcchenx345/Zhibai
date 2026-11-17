package com.zhibai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhibai.app.i18n.LangManager

/** One aligned row of the side-by-side diff: nullable on either side means padding. */
internal data class DiffRow(
    val leftNo: Int?,      // 1-based line number in old text, null = no line on left
    val leftText: String?,
    val leftRemoved: Boolean,
    val rightNo: Int?,
    val rightText: String?,
    val rightAdded: Boolean,
    val unchanged: Boolean
)

/**
 * Line-level diff by trimming the common prefix and common suffix; the middle
 * block is the hunk. Snippets from edit_file are small, so this is enough —
 * no LCS needed.
 */
internal fun computeDiffRows(oldText: String, newText: String): List<DiffRow> {
    val oldLines = oldText.split('\n')
    val newLines = newText.split('\n')

    var prefix = 0
    while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) prefix++
    var suffix = 0
    while (suffix < oldLines.size - prefix && suffix < newLines.size - prefix &&
        oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]) suffix++
    val oldMid = oldLines.subList(prefix, oldLines.size - suffix)
    val newMid = newLines.subList(prefix, newLines.size - suffix)

    val rows = mutableListOf<DiffRow>()
    for (i in 0 until prefix) {
        rows.add(DiffRow(i + 1, oldLines[i], false, i + 1, newLines[i], false, true))
    }
    val maxMid = maxOf(oldMid.size, newMid.size)
    for (i in 0 until maxMid) {
        val o = oldMid.getOrNull(i)
        val n = newMid.getOrNull(i)
        rows.add(DiffRow(
            leftNo = if (o != null) prefix + i + 1 else null,
            leftText = o,
            leftRemoved = o != null && n == null || (o != null && n != null && o != n),
            rightNo = if (n != null) prefix + i + 1 else null,
            rightText = n,
            rightAdded = n != null && o == null || (o != null && n != null && o != n),
            unchanged = false
        ))
    }
    for (i in 0 until suffix) {
        val no = oldLines.size - suffix + i + 1
        rows.add(DiffRow(no, oldLines[oldLines.size - suffix + i], false,
            no, newLines[newLines.size - suffix + i], false, true))
    }
    return rows
}

/**
 * MiMoCode-style side-by-side edit diff: left = before, right = after.
 * Line numbers, +/- markers, red-tint on removed lines, green-tint on added lines.
 */
@Composable
fun DiffView(
    oldText: String,
    newText: String,
    modifier: Modifier = Modifier
) {
    val rows = remember(oldText, newText) { computeDiffRows(oldText, newText) }

    val removedBg = Color(0xFFFFEBEE)
    val addedBg = Color(0xFFE8F5E9)
    val removedBgDark = Color(0xFF3A1518)
    val addedBgDark = Color(0xFF16301A)
    val lineNoColor = Color(0xFF9E9E9E)
    val dimTextColor = Color(0xFF616161)
    val dimTextColorDark = Color(0xFF8A8A8A)
    val hunkBar = Color(0xFFBDBDBD)

    val isDark = MaterialTheme.colorScheme.background.luminanceSafe()
    val bgRemoved = if (isDark) removedBgDark else removedBg
    val bgAdded = if (isDark) addedBgDark else addedBg
    val textColor = if (isDark) Color(0xFFE0E0E0) else Color(0xFF212121)
    val dimmed = if (isDark) dimTextColorDark else dimTextColor

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
    ) {
        // Header
        Text(
            text = LangManager.t("diff.header", "改动对比"),
            fontSize = 11.sp,
            color = dimmed,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            DiffColumn(
                title = LangManager.t("diff.before", "改动前"),
                side = DiffSide.LEFT,
                rows = rows,
                bgRemoved = bgRemoved,
                bgAdded = bgAdded,
                lineNoColor = lineNoColor,
                textColor = textColor,
                dimmed = dimmed,
                hunkBar = hunkBar
            )
            DiffColumn(
                title = LangManager.t("diff.after", "改动后"),
                side = DiffSide.RIGHT,
                rows = rows,
                bgRemoved = bgRemoved,
                bgAdded = bgAdded,
                lineNoColor = lineNoColor,
                textColor = textColor,
                dimmed = dimmed,
                hunkBar = hunkBar
            )
        }
    }
}

private enum class DiffSide { LEFT, RIGHT }

@Composable
private fun DiffColumn(
    title: String,
    side: DiffSide,
    rows: List<DiffRow>,
    bgRemoved: Color,
    bgAdded: Color,
    lineNoColor: Color,
    textColor: Color,
    dimmed: Color,
    hunkBar: Color
) {
    Column(modifier = Modifier.width(170.dp)) {
        Text(
            text = title,
            fontSize = 10.sp,
            color = dimmed,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 2.dp)
        )
        rows.forEach { row ->
            val no = if (side == DiffSide.LEFT) row.leftNo else row.rightNo
            val text = if (side == DiffSide.LEFT) row.leftText else row.rightText
            val marked = if (side == DiffSide.LEFT) row.leftRemoved else row.rightAdded
            val bg = when {
                text == null -> Color.Transparent
                row.unchanged -> Color.Transparent
                side == DiffSide.LEFT && row.leftRemoved -> bgRemoved
                side == DiffSide.RIGHT && row.rightAdded -> bgAdded
                else -> Color.Transparent
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = no?.toString() ?: "",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = lineNoColor,
                    modifier = Modifier.width(26.dp)
                )
                Text(
                    text = when {
                        text == null -> " "
                        row.unchanged -> " "
                        side == DiffSide.LEFT && row.leftRemoved -> "-"
                        side == DiffSide.RIGHT && row.rightAdded -> "+"
                        else -> " "
                    },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (marked && !row.unchanged) {
                        if (side == DiffSide.LEFT) Color(0xFFD32F2F) else Color(0xFF2E7D32)
                    } else hunkBar,
                    modifier = Modifier.width(12.dp)
                )
                Text(
                    text = text ?: "",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (row.unchanged) dimmed else textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 6.dp))
    }
}

/** Cheap luminance check without importing Color.luminance (keep deps minimal). */
private fun Color.luminanceSafe(): Boolean {
    // Material background in dark theme is near-black (#0C0C0C in this app)
    return (red + green + blue) / 3f < 0.5f
}
