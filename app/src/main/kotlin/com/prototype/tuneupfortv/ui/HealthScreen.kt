package com.prototype.tuneupfortv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prototype.tuneupfortv.R
import com.prototype.tuneupfortv.device.HealthReading
import com.prototype.tuneupfortv.session.SessionState

private const val KB_PER_MB: Long = 1024L

/** Every metric card, and every skeleton standing in for one, has this height: the grid never shifts when the numbers arrive. */
private val METRIC_CARD_HEIGHT = 320.vp

/** How the TV is doing now, next to how it was when the app connected. A value that could not be read says so. */
@Composable
fun HealthScreen(connected: SessionState.Connected, actions: AppActions) {
    val health = connected.health
    InnerScreen(
        connected,
        stringResource(R.string.health_title),
        stringResource(R.string.health_subtitle),
        trailing = {
            FocusButton(stringResource(R.string.health_read_again), onClick = actions::readHealth, enabled = !health.reading, requestInitialFocus = true)
        },
    ) {
        // The padding gives the scale and halo of a focused card room.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 30.vp, vertical = 20.vp), verticalArrangement = Arrangement.spacedBy(24.vp)) {
            val start = health.atStart
            val now = health.now
            if (health.reading) {
                // Reading takes a moment: the four cards show up as skeletons, in the place the numbers will take.
                repeat(2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.vp)) {
                        MetricSkeleton(Modifier.weight(1f))
                        MetricSkeleton(Modifier.weight(1f))
                    }
                }
            } else if (now == null) {
                Text(stringResource(R.string.health_unknown), style = MaterialTheme.typography.bodyLarge, color = TuneupTheme.colors.muted)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(24.vp)) {
                    MetricCard(
                        name = stringResource(R.string.health_memory),
                        nowText = megabytes(now.memoryAvailableKb),
                        startText = megabytes(start?.memoryAvailableKb),
                        endLabel = stringResource(R.string.health_col_now),
                        change = change(signedMegabytes(start?.memoryAvailableKb, now.memoryAvailableKb), delta(start?.memoryAvailableKb, now.memoryAvailableKb), higherIsBetter = true),
                        fraction = fraction(now.memoryAvailableKb, now.memoryTotalKb),
                        modifier = Modifier.weight(1f),
                    )
                    MetricCard(
                        name = stringResource(R.string.health_swap),
                        nowText = megabytes(now.swapUsedKb),
                        startText = megabytes(start?.swapUsedKb),
                        endLabel = stringResource(R.string.health_col_now),
                        change = change(signedMegabytes(start?.swapUsedKb, now.swapUsedKb), delta(start?.swapUsedKb, now.swapUsedKb), higherIsBetter = false, neutral = true),
                        fraction = null,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.vp)) {
                    MetricCard(
                        name = stringResource(R.string.health_cpu),
                        nowText = percent(now.cpuBusyPercent),
                        startText = percent(start?.cpuBusyPercent),
                        endLabel = stringResource(R.string.health_col_now),
                        change = change(signedPercent(start?.cpuBusyPercent, now.cpuBusyPercent), delta(start?.cpuBusyPercent?.toLong(), now.cpuBusyPercent?.toLong()), higherIsBetter = false),
                        fraction = now.cpuBusyPercent?.let { it / 100f },
                        modifier = Modifier.weight(1f),
                    )
                    MetricCard(
                        name = stringResource(R.string.health_crashes),
                        nowText = count(now.crashes),
                        startText = count(start?.crashes),
                        endLabel = stringResource(R.string.health_col_now),
                        change = crashChange(now.crashes),
                        fraction = null,
                        modifier = Modifier.weight(1f),
                    )
                }
                CrashSample(now)
            }
        }
    }
}

/** The text of a change pill and the tone it takes. A change that cannot be computed has no pill. */
private data class Change(val text: String, val tone: Tone)

/** Positive and negative are not good or bad by themselves: it depends on the measure, so each one says which way is better. */
private fun delta(before: Long?, after: Long?): Long? = if (before == null || after == null) null else after - before

@Composable
private fun change(text: String, difference: Long?, higherIsBetter: Boolean, neutral: Boolean = false): Change? {
    if (text.isEmpty() || difference == null) return null
    val tone = when {
        neutral || difference == 0L -> Tone.NEUTRAL
        (difference > 0) == higherIsBetter -> Tone.SUCCESS
        else -> Tone.WARNING
    }
    return Change(text, tone)
}

/** Crashes have no "before" to compare with: zero in the last minute is stable, anything else deserves a look. */
@Composable
private fun crashChange(crashes: Int?): Change? =
    when {
        crashes == null -> null
        crashes == 0 -> Change(stringResource(R.string.overview_health_stable), Tone.SUCCESS)
        else -> Change(stringResource(R.string.overview_health_crashes, crashes), Tone.WARNING)
    }

private fun fraction(part: Long?, total: Long?): Float? = if (part == null || total == null || total <= 0L) null else (part.toFloat() / total).coerceIn(0f, 1f)

@Composable
private fun MetricSkeleton(modifier: Modifier) {
    Panel(modifier.height(METRIC_CARD_HEIGHT), padding = 28.vp) {
        Skeleton(Modifier.width(160.vp).height(18.dp))
        Skeleton(Modifier.padding(top = 12.dp).width(240.vp).height(40.dp))
        Skeleton(Modifier.padding(top = 20.dp).fillMaxWidth(0.7f).height(16.dp))
    }
}

/** One measure. Focusable without an action, so the D-pad can walk down the page however tall it gets. */
@Composable
private fun MetricCard(
    name: String,
    nowText: String,
    startText: String,
    endLabel: String,
    change: Change?,
    fraction: Float?,
    modifier: Modifier,
) {
    FocusRow(modifier.height(METRIC_CARD_HEIGHT), idleBackground = TuneupTheme.colors.surface, shape = RoundedCornerShape(32.vp), contentPadding = 28.vp, minHeight = METRIC_CARD_HEIGHT) {
        val palette = TuneupTheme.colors
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = palette.muted)
                Text(nowText, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 4.vp))
            }
            if (change != null) {
                Badge(
                    change.text,
                    when (change.tone) {
                        Tone.SUCCESS -> BadgeKind.SUCCESS
                        Tone.WARNING -> BadgeKind.WARNING
                        else -> BadgeKind.NEUTRAL
                    },
                )
            }
        }
        Text(
            stringResource(R.string.health_compare, startText, endLabel, nowText),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.muted,
            modifier = Modifier.padding(top = 16.vp),
        )
        if (fraction != null) {
            TvProgressBar(fraction, Modifier.padding(top = 16.vp))
        }
    }
}

/** What crashed, as a focusable block so the D-pad can reach it however long it is. */
@Composable
private fun CrashSample(reading: HealthReading) {
    if (reading.crashSample.isEmpty()) return
    FocusRow(shape = RoundedCornerShape(32.vp), idleBackground = TuneupTheme.colors.surface, contentPadding = 28.vp) {
        Text(stringResource(R.string.health_crash_sample), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        reading.crashSample.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = TuneupTheme.colors.muted) }
    }
}

@Composable
private fun unknown(): String = stringResource(R.string.health_unknown_short)

@Composable
private fun megabytes(kilobytes: Long?): String = if (kilobytes == null) unknown() else stringResource(R.string.health_mb, kilobytes / KB_PER_MB)

@Composable
private fun signedMegabytes(before: Long?, after: Long?): String =
    if (before == null || after == null) "" else stringResource(R.string.health_mb_signed, (after - before) / KB_PER_MB)

@Composable
private fun percent(value: Int?): String = if (value == null) unknown() else stringResource(R.string.health_percent, value)

@Composable
private fun signedPercent(before: Int?, after: Int?): String =
    if (before == null || after == null) "" else stringResource(R.string.health_percent_signed, after - before)

@Composable
private fun count(value: Int?): String = if (value == null) unknown() else value.toString()
