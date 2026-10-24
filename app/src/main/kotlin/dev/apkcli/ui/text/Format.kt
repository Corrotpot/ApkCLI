package dev.apkcli.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.apkcli.R
import java.text.NumberFormat

/*
 * Numbers as the design writes them, in the user's own notation: "2 мин
 * назад", "1,2 МБ", "34k". The same rules as the prototype's ago(), mb() and
 * tokens(), so the screens read the same.
 */

/** How long ago [millis] was: "just now", "5 min ago", "yesterday", "3 wk ago". */
@Composable
@ReadOnlyComposable
fun agoText(millis: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = ((now - millis).coerceAtLeast(0) / 60_000).toInt()
    if (minutes < 1) return stringResource(R.string.ago_now)
    if (minutes < 60) return stringResource(R.string.ago_minutes, minutes)
    val hours = minutes / 60
    if (hours < 24) return pluralStringResource(R.plurals.ago_hours, hours, hours)
    val days = hours / 24
    if (days == 1) return stringResource(R.string.ago_yesterday)
    if (days < 7) return pluralStringResource(R.plurals.ago_days, days, days)
    return stringResource(R.string.ago_weeks, days / 7)
}

/** "812 B", "4,2 KB", "1,3 MB". */
@Composable
@ReadOnlyComposable
fun bytesText(bytes: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val oneDecimal = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    return when {
        bytes < 1024 -> stringResource(R.string.size_bytes, bytes)
        bytes < 1024 * 1024 -> stringResource(R.string.size_kb, oneDecimal.format(bytes / 1024.0))
        else -> stringResource(R.string.size_mb, oneDecimal.format(bytes / 1_048_576.0))
    }
}

/** Token counts, decimal: "980", "4,1k", "34k", "1M". */
@Composable
@ReadOnlyComposable
fun tokensText(count: Int): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
    return when {
        count >= 1_000_000 -> format.format(Math.round(count / 100_000.0) / 10.0) + "M"
        count >= 10_000 -> "${Math.round(count / 1000.0)}k"
        count >= 1_000 -> format.apply { minimumFractionDigits = 1 }.format(count / 1000.0) + "k"
        else -> count.toString()
    }
}
