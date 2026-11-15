package com.zhibai.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhibai.app.data.model.ArtifactEntry
import com.zhibai.app.data.model.SessionArtifacts

/**
 * 产物中心 panel — same accordion language as the task checklist:
 *  产物 → 中间产物 (made, often unseen) with a 更多 expander,
 *  参考 → 联网搜索 (urls) + 项目文件 (read files).
 * Rows open the in-app viewer via [onOpenFile]; URLs via [onOpenUrl].
 */
@Composable
fun ArtifactsPanel(
    artifacts: SessionArtifacts,
    onOpenFile: (String) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (artifacts.produced.isEmpty() && artifacts.referenced.isEmpty() && artifacts.searches.isEmpty()) return

    val scheme = MaterialTheme.colorScheme
    val totalRefs = artifacts.referenced.size + artifacts.searches.size

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ArtifactSection(
            title = "产物",
            count = artifacts.produced.size,
            items = artifacts.produced,
            emptyHint = "暂无中间产物",
            onOpenFile = onOpenFile,
            onOpenUrl = onOpenUrl
        ) {
            // Produced files: 中间产物 sub-group + name rows + 更多 expander
            if (artifacts.produced.isNotEmpty()) {
                SubGroupLabel("中间产物", artifacts.produced.size, Icons.Default.Description)
            }
            ProducedList(items = artifacts.produced, onOpenFile = onOpenFile)
        }
        ArtifactSection(
            title = "参考",
            count = totalRefs,
            items = artifacts.searches + artifacts.referenced,
            emptyHint = "暂无参考",
            onOpenFile = onOpenFile,
            onOpenUrl = onOpenUrl
        ) {
            if (artifacts.searches.isNotEmpty()) {
                SubGroupLabel("联网搜索", artifacts.searches.size, Icons.Default.Language)
                artifacts.searches.forEach { e ->
                    RefRow(
                        title = e.name,
                        subtitle = e.path,
                        icon = Icons.Default.Language,
                        onClick = { onOpenUrl(e.path) }
                    )
                }
            }
            if (artifacts.referenced.isNotEmpty()) {
                SubGroupLabel("项目文件", artifacts.referenced.size, Icons.Default.Folder)
                artifacts.referenced.forEach { e ->
                    RefRow(
                        title = e.name,
                        subtitle = e.path,
                        icon = Icons.Default.Description,
                        onClick = { onOpenFile(e.path) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtifactSection(
    title: String,
    count: Int,
    items: List<ArtifactEntry>,
    emptyHint: String,
    onOpenFile: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    content: @Composable () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember(title) { mutableStateOf(true) }
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220),
        label = "artifactChevron"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surface.copy(alpha = 0.96f))
            .border(1.dp, scheme.outline.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, scheme.outline.copy(alpha = 0.35f), RoundedCornerShape(50))
                    .padding(horizontal = 7.dp, vertical = 1.dp)
            ) {
                Text(
                    count.toString(),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(18.dp).rotate(chevron),
                tint = scheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(120))
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                if (items.isEmpty()) {
                    Text(
                        emptyHint,
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                } else {
                    content()
                }
            }
        }
    }
}

/** Produced list — first 8 rows, then a 更多 N expander (reference design). */
@Composable
private fun ProducedList(items: List<ArtifactEntry>, onOpenFile: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val visible = items.take(8)
    val hidden = items.drop(8)
    var showMore by remember(items.size) { mutableStateOf(false) }

    visible.forEach { e ->
        RefRow(title = e.name, subtitle = "", icon = Icons.Default.Description,
            onClick = { onOpenFile(e.path) })
    }
    if (hidden.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { showMore = !showMore }
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier
                    .size(14.dp)
                    .rotate(if (showMore) 180f else 0f),
                tint = scheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "更多",
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                hidden.size.toString(),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = scheme.onSurfaceVariant
            )
        }
        if (showMore) {
            hidden.forEach { e ->
                RefRow(title = e.name, subtitle = "", icon = Icons.Default.Description,
                    onClick = { onOpenFile(e.path) })
            }
        }
    }
}

@Composable
private fun SubGroupLabel(title: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 5.dp)
    ) {
        Icon(icon, contentDescription = null,
            modifier = Modifier.size(13.dp), tint = scheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            title,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            count.toString(),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun RefRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = scheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 12.sp,
                color = scheme.onSurface.copy(alpha = 0.88f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (icon == Icons.Default.Language) TextDecoration.Underline else null
            )
            if (subtitle.isNotBlank() && subtitle != title) {
                Text(
                    subtitle,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
