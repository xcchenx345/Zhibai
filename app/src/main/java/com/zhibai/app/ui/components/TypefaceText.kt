package com.zhibai.app.ui.components

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Text composable that supports custom Android Typeface via native TextView.
 * Only used when a custom font is selected; otherwise normal Compose Text is used.
 */
@Composable
fun TypefaceText(
    text: AnnotatedString,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    typeface: Typeface,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textColor = color.toArgb()
    val sizePx = with(density) { fontSize.toPx().toInt() }
    val linePx = with(density) { lineHeight.toPx().toInt() }

    // Convert AnnotatedString spans to Android spans
    val spannable = remember(text, typeface, textColor, sizePx) {
        val builder = SpannableStringBuilder(text.text)
        for (span in text.spanStyles) {
            val start = span.start.coerceIn(0, text.text.length)
            val end = span.end.coerceIn(0, text.text.length)
            if (start >= end) continue
            if (span.item.color != Color.Unspecified) {
                builder.setSpan(
                    ForegroundColorSpan(span.item.color.toArgb()),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (span.item.fontSize.value > 0) {
                builder.setSpan(
                    AbsoluteSizeSpan(with(density) { span.item.fontSize.toPx().toInt() }),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            span.item.fontWeight?.let { fw ->
                val style = if (fw >= FontWeight.Bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                builder.setSpan(StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        builder
    }

    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                this.typeface = typeface
                setTextColor(textColor)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx.toFloat())
                setLineSpacing(0f, lineHeight.value / fontSize.value)
                includeFontPadding = false
            }
        },
        update = { tv ->
            tv.typeface = typeface
            tv.setTextColor(textColor)
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx.toFloat())
            tv.text = spannable
        },
        modifier = modifier.fillMaxWidth()
    )
}
