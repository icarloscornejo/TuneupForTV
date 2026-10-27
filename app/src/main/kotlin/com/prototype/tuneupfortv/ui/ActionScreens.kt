package com.prototype.tuneupfortv.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.remember
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prototype.tuneupfortv.R
import com.prototype.tuneupfortv.device.DeviceFailure
import com.prototype.tuneupfortv.device.PackageState
import com.prototype.tuneupfortv.safety.ActionKind
import com.prototype.tuneupfortv.safety.ActionOutcome
import com.prototype.tuneupfortv.safety.EntryStatus
import com.prototype.tuneupfortv.safety.JournalEntry
import com.prototype.tuneupfortv.safety.Operation
import com.prototype.tuneupfortv.safety.RejectReason
import com.prototype.tuneupfortv.safety.AcceptResult
import com.prototype.tuneupfortv.safety.ActionPlanner
import com.prototype.tuneupfortv.safety.BatchPlanner
import com.prototype.tuneupfortv.safety.ForceCloseAll
import com.prototype.tuneupfortv.safety.LivePackage
import com.prototype.tuneupfortv.safety.PlanResult
import com.prototype.tuneupfortv.session.SessionState
import com.prototype.tuneupfortv.safety.ActionExecutor
import com.prototype.tuneupfortv.safety.WatchState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// region labels

@Composable
fun actionLabel(kind: ActionKind): String =
    stringResource(
        when (kind) {
            ActionKind.FORCE_STOP -> R.string.action_force_stop
            ActionKind.DISABLE -> R.string.action_disable
            ActionKind.RESTORE -> R.string.action_restore
            ActionKind.UNINSTALL -> R.string.action_uninstall
        },
    )

@Composable
fun rejectReasonLabel(reason: RejectReason): String =
    stringResource(
        when (reason) {
            RejectReason.UNREADABLE -> R.string.reject_unreadable
            RejectReason.THIS_APP -> R.string.reject_this_app
            RejectReason.ESSENTIAL -> R.string.reject_essential
            RejectReason.NOT_INSTALLED -> R.string.reject_not_installed
            RejectReason.ALREADY_INSTALLED -> R.string.reject_already_installed
            RejectReason.ALREADY_DISABLED -> R.string.reject_already_disabled
            RejectReason.NOTHING_TO_RESTORE -> R.string.reject_nothing_to_restore
            RejectReason.NOT_RUNNING -> R.string.reject_not_running
            RejectReason.NOT_CHANGEABLE_FROM_SHELL -> R.string.reject_not_changeable
            RejectReason.UNRELIABLE_DEVICE -> R.string.reject_unreliable_device
            RejectReason.WOULD_CRASH_LOOP -> R.string.reject_would_crash_loop
            RejectReason.LAUNCHER_SWAP_ACTIVE -> R.string.reject_launcher_swap_active
            RejectReason.LAUNCHER_NOT_READY -> R.string.reject_launcher_not_ready
        },
    )

@Composable
private fun statusLabel(status: EntryStatus): String =
    stringResource(
        when (status) {
            EntryStatus.PENDING -> R.string.status_pending
            EntryStatus.DONE -> R.string.status_done
            EntryStatus.FAILED -> R.string.status_failed
            EntryStatus.UNDONE -> R.string.status_undone
            EntryStatus.NEEDS_REVIEW -> R.string.status_needs_review
            EntryStatus.ACCEPTED -> R.string.status_accepted
            EntryStatus.UNVERIFIED -> R.string.status_unverified
        },
    )

@Composable
private fun watchLabel(state: WatchState): String =
    stringResource(
        when (state) {
            WatchState.RUNNING -> R.string.watch_running
            WatchState.OK -> R.string.watch_ok
            WatchState.ANOMALY -> R.string.watch_anomaly
            WatchState.INCONCLUSIVE -> R.string.watch_inconclusive
        },
    )

@Composable
private fun stateLabel(state: PackageState): String =
    stringResource(R.string.state_fmt, stringResource(if (state.installed) R.string.state_installed else R.string.state_not_installed), state.enabled)

/** In the language the app is showing, which can differ from the system default when the user picked one. */
@Composable
private fun formatTime(millis: Long): String {
    val locale: Locale = LocalConfiguration.current.locales[0]
    return remember(millis, locale) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, locale).format(Date(millis)) }
}

// endregion

/** Shown over the whole app while an action is being asked, made, or reported. */
@Composable
fun ActionOverlay(state: AppUiState, connected: SessionState.Connected, actions: AppActions) {
    when (val ui = state.action) {
        ActionUi.None -> Unit
        is ActionUi.Confirm -> ConfirmAction(connected, actions, ui)
        ActionUi.Running -> WorkingScreen(stringResource(R.string.running_title), stringResource(R.string.running_body), fraction = null)
        is ActionUi.Result -> ResultView(state, actions, ui.outcome)
        ActionUi.Review -> ReviewView(connected, actions)
        is ActionUi.ConfirmCloseAll -> ConfirmCloseAll(actions, ui)
        is ActionUi.ConfirmBatch -> ConfirmBatch(state, connected, actions, ui)
        is ActionUi.RunningBatch -> WorkingScreen(
            stringResource(R.string.batch_title),
            stringResource(R.string.batch_progress, actionLabel(ui.kind), ui.done, ui.total),
            fraction = ui.done.toFloat() / ui.total.coerceAtLeast(1),
        )
        is ActionUi.BatchDone -> BatchDoneView(state, actions, ui.result)
        // No count: it only says that it is working, and the same centered layout as the report that follows.
        is ActionUi.ClosingAll -> WorkingScreen(
            stringResource(R.string.closeall_title),
            stringResource(R.string.closeall_working),
            fraction = null,
        )
        is ActionUi.ClosedAll -> MessageView(
            R.string.closeall_done_title,
            stringResource(R.string.closeall_done, ui.stopped, ui.failed),
            actions::dismissAction,
            tone = if (ui.failed == 0) Tone.SUCCESS else Tone.WARNING,
            glyph = if (ui.failed == 0) "✓" else "!",
            centered = true,
        )
        is ActionUi.AcceptRefused -> {
            val message = when (ui.result) {
                AcceptResult.JOURNAL_FAILED -> R.string.accept_refused_journal
                AcceptResult.NOT_FOUND -> R.string.accept_refused_missing
                AcceptResult.DEVICE_UNREADABLE, AcceptResult.ACCEPTED -> R.string.accept_refused_device
            }
            MessageView(R.string.result_nothing_changed, stringResource(message), actions::dismissAction, tone = Tone.INFO, glyph = "i")
        }
    }
}

/** The report of a long action: a card with a spinner and, when the length is known, how far it got. Nothing here takes focus. */
@Composable
private fun WorkingScreen(title: String, body: String, fraction: Float?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 1060.vp), padding = 54.vp, radius = 34.vp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spinner(size = 84.vp)
                Spacer(Modifier.height(28.vp))
                Text(title, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(13.vp))
                Text(body, style = MaterialTheme.typography.bodyLarge, color = TuneupTheme.colors.muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 820.vp))
                // The spinner already says it is working: a bar only appears when there is a real fraction to show.
                if (fraction != null) {
                    Spacer(Modifier.height(28.vp))
                    TvProgressBar(fraction, Modifier.fillMaxWidth(0.72f))
                }
            }
        }
    }
}

/**
 * A result or a question, centered with its symbol. Inside a batch report ([embedded]) the same content
 * shows under the batch summary, without a second symbol and a second screen.
 */
@Composable
private fun ResultFrame(
    tone: Tone,
    glyph: String,
    title: String,
    embedded: Boolean,
    kicker: String? = null,
    centered: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (embedded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.vp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    } else {
        CenterScreen(tone, glyph, title, kicker = kicker, centered = centered, content = content)
    }
}

@Composable
private fun BodyText(text: String, centered: Boolean = false) {
    Text(
        text,
        // The centered report is a line of figures, not a paragraph: smaller than the explanations.
        style = if (centered) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
        color = TuneupTheme.colors.muted,
        textAlign = if (centered) TextAlign.Center else null,
        modifier = Modifier.padding(bottom = 13.vp),
    )
}

@Composable
private fun ConfirmAction(connected: SessionState.Connected, actions: AppActions, ui: ActionUi.Confirm) {
    val classification = connected.classifications[ui.packageId]
    val record = connected.snapshot.packages.firstOrNull { it.id == ui.packageId }
    // The same planner the executor uses: what this screen promises is what will happen.
    val plan = record?.let { (ActionPlanner.plan(ui.kind, LivePackage(it, classification?.essentialReason)) as? PlanResult.Ok)?.plan }
    val installs = plan?.steps?.contains(Operation.INSTALL_EXISTING) ?: (record?.state?.installed == false)
    val what = when {
        ui.kind == ActionKind.DISABLE -> R.string.explain_what_disable
        ui.kind == ActionKind.FORCE_STOP -> R.string.explain_what_force_stop
        ui.kind == ActionKind.UNINSTALL -> R.string.explain_what_uninstall
        installs -> R.string.explain_what_restore_install
        else -> R.string.explain_what_restore_enable
    }
    val risk = when (ui.kind) {
        ActionKind.DISABLE -> R.string.explain_risk_disable
        ActionKind.FORCE_STOP -> R.string.explain_risk_force_stop
        ActionKind.RESTORE -> R.string.explain_risk_restore
        // A system app can be brought back from the image; one the user installed cannot.
        ActionKind.UNINSTALL -> if (record?.isSystem == true) R.string.explain_risk_uninstall_system else R.string.explain_risk_uninstall_user
    }
    val visual = rememberAppVisual(ui.packageId)
    val restoring = ui.kind == ActionKind.RESTORE
    CenterScreen(
        tone = if (restoring) Tone.INFO else Tone.WARNING,
        glyph = if (restoring) "↺" else "!",
        title = actionLabel(ui.kind),
        kicker = stringResource(R.string.kicker_confirm_package),
    ) {
        Text(displayName(visual, ui.packageId), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TuneupTheme.colors.accent, modifier = Modifier.padding(bottom = 22.vp))
        BodyText(stringResource(what))
        BodyText(stringResource(risk))
        // An uninstall is not watched afterwards, so it must not say it is.
        if (ui.kind != ActionKind.UNINSTALL) {
            val notes = buildString {
                if (ui.kind == ActionKind.DISABLE) append(stringResource(R.string.explain_wait_after_disable)).append(' ')
                append(stringResource(R.string.explain_watch, ActionExecutor.DEFAULT_WATCH_SECONDS))
            }
            MutedNote(notes)
        }
        Spacer(Modifier.height(30.vp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.vp)) {
            // The safe choice has the focus: a stray center press cancels instead of changing the TV.
            FocusButton(stringResource(R.string.cancel), onClick = actions::dismissAction, requestInitialFocus = true)
            FocusButton(
                stringResource(R.string.confirm_do),
                onClick = actions::confirmAction,
                enabled = plan != null,
                style = if (restoring) ButtonStyle.PRIMARY else ButtonStyle.DANGER,
            )
        }
    }
}

/**
 * The report of one change. [onDismiss] is what Close and Later do, [liveEntry] is the journal's current copy of a
 * finished change (the outcome keeps the one from when it was made), and [requestInitialFocus] lets a screen that
 * has a better first button keep the focus for itself.
 */
@Composable
internal fun ResultView(
    state: AppUiState,
    actions: AppActions,
    outcome: ActionOutcome,
    embedded: Boolean = false,
    onDismiss: () -> Unit = actions::dismissAction,
    liveEntry: JournalEntry? = null,
    requestInitialFocus: Boolean = true,
) {
    when (outcome) {
        is ActionOutcome.Finished -> FinishedView(actions, liveEntry ?: outcome.entry, embedded, onDismiss, requestInitialFocus)
        ActionOutcome.ForceStopped -> MessageView(R.string.result_stopped_title, stringResource(R.string.result_stopped), onDismiss, Tone.SUCCESS, "✓", embedded, requestInitialFocus = requestInitialFocus)
        is ActionOutcome.ForceStopFailed -> MessageView(R.string.result_nothing_changed, stringResource(R.string.result_stop_failed), onDismiss, Tone.DANGER, "×", embedded, requestInitialFocus = requestInitialFocus)
        is ActionOutcome.Rejected -> MessageView(R.string.result_rejected_title, rejectReasonLabel(outcome.reason), onDismiss, Tone.INFO, "i", embedded, requestInitialFocus = requestInitialFocus)
        is ActionOutcome.Blocked -> {
            MessageView(R.string.result_blocked_title, stringResource(R.string.result_blocked), onDismiss, Tone.WARNING, "!", embedded, requestInitialFocus = requestInitialFocus, extra = {
                FocusButton(stringResource(R.string.review_resolve), onClick = actions::openReview)
            })
        }
        is ActionOutcome.WatchUnavailable -> MessageView(R.string.result_nothing_changed, stringResource(R.string.result_watch_unavailable, outcome.detail), onDismiss, Tone.WARNING, "!", embedded, requestInitialFocus = requestInitialFocus)
        is ActionOutcome.JournalFailed -> MessageView(R.string.result_nothing_changed, stringResource(R.string.result_journal_failed), onDismiss, Tone.INFO, "i", embedded, requestInitialFocus = requestInitialFocus)
        is ActionOutcome.DeviceError -> MessageView(R.string.result_nothing_changed, deviceErrorLabel(outcome.failure), onDismiss, Tone.DANGER, "×", embedded, requestInitialFocus = requestInitialFocus)
    }
}

@Composable
private fun deviceErrorLabel(failure: DeviceFailure): String =
    when (failure) {
        DeviceFailure.Unreachable -> stringResource(R.string.failure_unreachable)
        DeviceFailure.Refused -> stringResource(R.string.failure_refused)
        DeviceFailure.Unauthorized -> stringResource(R.string.failure_unauthorized)
        DeviceFailure.Timeout -> stringResource(R.string.failure_timeout)
        DeviceFailure.AwaitingAuthorization -> stringResource(R.string.auth_body)
        is DeviceFailure.IdentityMismatch -> stringResource(R.string.failure_identity)
        is DeviceFailure.Other -> stringResource(R.string.failure_other, failure.message)
    }

@Composable
private fun MessageView(
    title: Int,
    message: String,
    onDismiss: () -> Unit,
    tone: Tone = Tone.INFO,
    glyph: String = "i",
    embedded: Boolean = false,
    centered: Boolean = false,
    requestInitialFocus: Boolean = true,
    extra: @Composable () -> Unit = {},
) {
    ResultFrame(tone, glyph, stringResource(title), embedded, centered = centered) {
        BodyText(message, centered)
        Spacer(Modifier.height(if (embedded) 4.vp else 21.vp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.vp)) {
            FocusButton(stringResource(R.string.close), onClick = onDismiss, requestInitialFocus = requestInitialFocus, style = ButtonStyle.PRIMARY)
            extra()
        }
    }
}

/** What happened to a change that was made (or tried): clean, doubtful, or needing a decision. */
@Composable
private fun FinishedView(actions: AppActions, entry: JournalEntry, embedded: Boolean, onDismiss: () -> Unit, requestInitialFocus: Boolean) {
    val watch = entry.watch
    val needsDecision = entry.isBlocking && entry.status != EntryStatus.PENDING
    val (title, message) = when {
        entry.status == EntryStatus.FAILED -> R.string.result_failed_title to stringResource(R.string.result_failed)
        entry.status == EntryStatus.PENDING -> R.string.result_pending_title to stringResource(R.string.result_pending)
        entry.status == EntryStatus.NEEDS_REVIEW -> R.string.result_review_title to stringResource(R.string.result_review)
        watch?.state == WatchState.ANOMALY -> R.string.result_anomaly_title to stringResource(R.string.result_anomaly, watch.detail.orEmpty())
        watch?.state == WatchState.INCONCLUSIVE -> R.string.result_inconclusive_title to stringResource(R.string.result_inconclusive, watch.detail.orEmpty())
        else -> R.string.result_ok_title to stringResource(R.string.result_ok)
    }
    // Only a clean finish is a success: a doubt or a pending answer is never shown as one.
    val (tone, glyph) = when {
        entry.status == EntryStatus.FAILED -> Tone.DANGER to "×"
        title == R.string.result_ok_title -> Tone.SUCCESS to "✓"
        else -> Tone.WARNING to "!"
    }
    ResultFrame(tone, glyph, stringResource(title), embedded) {
        Text(entry.packageId, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TuneupTheme.colors.accent)
        BodyText(message)
        Spacer(Modifier.height(if (embedded) 4.vp else 8.vp))
        if (needsDecision) {
            // The app never changes anything by itself after a change: what happens next is the user's choice.
            MutedNote(stringResource(R.string.result_decide))
            Spacer(Modifier.height(20.vp))
            FlowRowButtons {
                FocusButton(stringResource(R.string.result_later), onClick = onDismiss, requestInitialFocus = requestInitialFocus)
                FocusButton(stringResource(R.string.result_keep), onClick = { actions.acceptEntry(entry.id) }, style = ButtonStyle.PRIMARY)
            }
        } else {
            FocusButton(stringResource(R.string.close), onClick = onDismiss, requestInitialFocus = requestInitialFocus, style = ButtonStyle.PRIMARY)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowButtons(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.vp), verticalArrangement = Arrangement.spacedBy(8.vp)) { content() }
}

// region review

/** The changes that still need a decision. Nothing is shown here that does not: this is not a history. */
@Composable
private fun ReviewView(connected: SessionState.Connected, actions: AppActions) {
    val palette = TuneupTheme.colors
    val open = connected.openEntries.sortedByDescending { it.timestampMillis }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.vp)) {
        ScreenHeader(stringResource(R.string.review_title), stringResource(R.string.review_subtitle))
        if (open.isEmpty()) {
            Text(stringResource(R.string.review_empty), style = MaterialTheme.typography.bodyLarge, color = palette.muted, modifier = Modifier.weight(1f).padding(horizontal = 6.vp))
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(10.vp), verticalArrangement = Arrangement.spacedBy(14.vp)) {
                items(open, key = { it.id }) { entry -> ReviewCard(entry, actions) }
            }
        }
        // Outside the list so it exists from the first frame and can take the focus however many changes wait.
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.vp), horizontalArrangement = Arrangement.End) {
            FocusButton(stringResource(R.string.close), onClick = actions::dismissAction, requestInitialFocus = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewCard(entry: JournalEntry, actions: AppActions) {
    val palette = TuneupTheme.colors
    Panel(Modifier.fillMaxWidth(), padding = 22.vp, radius = 24.vp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.vp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.vp)) {
                Text(entry.packageId, style = MaterialTheme.typography.titleLarge)
                entry.watch?.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.muted) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.vp), verticalArrangement = Arrangement.spacedBy(8.vp)) {
                    Badge(statusLabel(entry.status), BadgeKind.WARNING)
                    Badge(actionLabel(entry.kind))
                    entry.watch?.let { Badge(watchLabel(it.state)) }
                    Badge(formatTime(entry.timestampMillis))
                }
            }
            FocusButton(stringResource(R.string.result_keep), onClick = { actions.acceptEntry(entry.id) }, style = ButtonStyle.PRIMARY)
        }
    }
}

// endregion

/** Force stop, Disable and Restore, only the ones the planner allows right now, from what the last read shows. */
@Composable
fun ActionButtons(packageId: String, allowed: List<ActionKind>, blockedReason: RejectReason?, actions: AppActions) {
    if (allowed.isEmpty()) {
        if (blockedReason != null) MutedNote(rejectReasonLabel(blockedReason))
        return
    }
    FlowRowButtons {
        // The first button takes the focus, so after an action screen closes the remote has somewhere to start.
        allowed.forEachIndexed { index, kind ->
            val style = when (kind) {
                ActionKind.DISABLE, ActionKind.UNINSTALL -> ButtonStyle.DANGER
                ActionKind.RESTORE -> ButtonStyle.PRIMARY
                ActionKind.FORCE_STOP -> ButtonStyle.SECONDARY
            }
            FocusButton(actionLabel(kind), onClick = { actions.askAction(packageId, kind) }, requestInitialFocus = index == 0, style = style)
        }
    }
}

/** One app in a preview of what an action will touch: its icon and name, focusable only so the D-pad can scroll the list. */
@Composable
private fun PreviewCell(packageId: String) {
    val palette = TuneupTheme.colors
    val visual = rememberAppVisual(packageId)
    FocusRow(
        shape = RoundedCornerShape(24.vp),
        idleBackground = palette.surface,
        contentPadding = 16.vp,
        minHeight = 96.vp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.vp)) {
            AppIconImage(visual, 64.vp)
            // Two lines of height whatever the name, so every cell is as tall as the next; a short name sits at the icon's middle.
            Box(Modifier.height(44.dp).weight(1f), contentAlignment = Alignment.CenterStart) {
                Text(visual.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Four processes that stay as they are, as a bullet line. Plain text: it takes focus without showing it, only so the
 * D-pad can walk down the list when it is longer than the screen.
 */
@Composable
private fun NotCloseableRow(names: List<String>) {
    Row(Modifier.fillMaxWidth().focusable().padding(vertical = 4.vp), horizontalArrangement = Arrangement.spacedBy(20.vp)) {
        names.forEach { name ->
            Text("•  $name", style = MaterialTheme.typography.bodySmall, color = TuneupTheme.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        // The last line may have fewer than four: the empty places keep the columns where they are.
        repeat(NOT_CLOSEABLE_COLUMNS - names.size) { Spacer(Modifier.weight(1f)) }
    }
}

private const val NOT_CLOSEABLE_COLUMNS: Int = 4

@Composable
private fun ConfirmCloseAll(actions: AppActions, ui: ActionUi.ConfirmCloseAll) {
    val targets = ui.targets
    Column(Modifier.fillMaxSize().padding(10.vp), verticalArrangement = Arrangement.spacedBy(14.vp)) {
        // The title starts at the top edge of the symbol and the subtitle hangs right under it, however tall the subtitle gets.
        Row(horizontalArrangement = Arrangement.spacedBy(24.vp), verticalAlignment = Alignment.Top) {
            StatusSymbol(Tone.WARNING, "!")
            // The text box has air above its letters (leading and ascent): pulled up so the letters, not the box, meet the symbol's edge.
            Column(Modifier.weight(1f).offset(y = (-3).dp)) {
                Text(stringResource(R.string.closeall_title), style = MaterialTheme.typography.headlineLarge)
                Text(stringResource(R.string.closeall_body, targets.size), style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp), color = TuneupTheme.colors.muted)
            }
        }
        Row(Modifier.padding(top = 24.vp), horizontalArrangement = Arrangement.spacedBy(16.vp)) {
            // The safe choice has the focus, and the buttons come before the long list so the remote reaches them at once.
            FocusButton(stringResource(R.string.cancel), onClick = actions::dismissAction, requestInitialFocus = true)
            FocusButton(stringResource(R.string.closeall_confirm, targets.size), onClick = actions::confirmCloseAll, enabled = targets.isNotEmpty(), style = ButtonStyle.DANGER)
        }
        // Everything that is about to be stopped, as the icons the person knows; then what will be left alone, and why.
        LazyVerticalGrid(
            modifier = Modifier.weight(1f),
            // Twelve columns: the apps take four each (three across), the bullet list three each (four across).
            columns = GridCells.Fixed(12),
            contentPadding = PaddingValues(20.vp),
            horizontalArrangement = Arrangement.spacedBy(20.vp),
            verticalArrangement = Arrangement.spacedBy(20.vp),
        ) {
            gridItems(targets, key = { it }, span = { GridItemSpan(4) }) { packageId -> PreviewCell(packageId) }
            if (ui.notCloseable.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "not-closeable-title") {
                    Text(
                        stringResource(R.string.closeall_not_closeable, ui.notCloseable.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = TuneupTheme.colors.muted,
                        modifier = Modifier.padding(top = 48.vp),
                    )
                }
                ui.notCloseable.chunked(NOT_CLOSEABLE_COLUMNS).forEach { names ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "daemons-${names.first()}") { NotCloseableRow(names) }
                }
            }
        }
    }
}

/** What a batch will do and what it will leave alone, before anything is touched. */
@Composable
private fun ConfirmBatch(state: AppUiState, connected: SessionState.Connected, actions: AppActions, ui: ActionUi.ConfirmBatch) {
    val palette = TuneupTheme.colors
    val plan = remember(ui.kind, state.selection, connected.snapshot, connected.classifications) {
        BatchPlanner.plan(
            ui.kind,
            state.selection,
            connected.snapshot.packages,
            connected.classifications,
            connected.snapshot.processPackages.values.filterNotNull().toSet(),
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(Modifier.widthIn(max = 1260.vp).fillMaxHeight().padding(10.vp), horizontalArrangement = Arrangement.spacedBy(26.vp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                StatusSymbol(Tone.WARNING, "!", Modifier.padding(bottom = 28.vp))
                Text(stringResource(R.string.kicker_confirm_batch), style = MaterialTheme.typography.titleSmall, color = palette.muted, modifier = Modifier.padding(bottom = 12.vp))
                Text("${actionLabel(ui.kind)}: ${stringResource(R.string.batch_title)}", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(bottom = 16.vp))
                BodyText(stringResource(R.string.batch_body, plan.runnable.size))
                Spacer(Modifier.height(17.vp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.vp)) {
                    // The safe choice has the focus, and the buttons come before the long list.
                    FocusButton(stringResource(R.string.cancel), onClick = actions::dismissAction, requestInitialFocus = true)
                    FocusButton(
                        stringResource(R.string.batch_confirm, actionLabel(ui.kind), plan.runnable.size),
                        onClick = actions::confirmBatch,
                        enabled = plan.runnable.isNotEmpty(),
                        style = if (ui.kind == ActionKind.RESTORE) ButtonStyle.PRIMARY else ButtonStyle.DANGER,
                    )
                }
            }
            Panel(Modifier.width(390.vp).fillMaxHeight(), padding = 22.vp, radius = 28.vp) {
                Text(stringResource(R.string.batch_to_change), style = MaterialTheme.typography.titleSmall, color = palette.muted, modifier = Modifier.padding(bottom = 10.vp))
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.vp), verticalArrangement = Arrangement.spacedBy(4.vp)) {
                    if (plan.runnable.isEmpty()) item { Text(stringResource(R.string.batch_nothing), style = MaterialTheme.typography.bodyMedium, color = palette.muted) }
                    items(plan.runnable, key = { it }) { packageId -> PreviewCell(packageId) }
                    if (plan.skipped.isNotEmpty()) {
                        item(key = "skipped-title") {
                            Text(stringResource(R.string.batch_skipped, plan.skipped.size), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.vp))
                        }
                        plan.skipped.forEach { (packageId, reason) ->
                            item(key = "skipped-$packageId") {
                                FocusRow(shape = RoundedCornerShape(16.vp), contentPadding = 12.vp, minHeight = 64.vp) {
                                    Text("$packageId: ${rejectReasonLabel(reason)}", style = MaterialTheme.typography.bodySmall, color = TuneupTheme.colors.muted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** How a batch ended, and, when it stopped early, the report of the change that stopped it. */
@Composable
private fun BatchDoneView(state: AppUiState, actions: AppActions, result: BatchResult) {
    val stopped = result.stoppedAt != null
    val outcome = result.stoppedBecause
    CenterScreen(
        tone = if (stopped) Tone.WARNING else Tone.SUCCESS,
        glyph = if (stopped) "!" else "✓",
        title = stringResource(if (stopped) R.string.batch_stopped_title else R.string.batch_done_title),
        kicker = stringResource(R.string.batch_title),
    ) {
        BodyText(stringResource(R.string.batch_summary, result.succeeded, result.skipped, result.untried))
        if (result.stoppedAt != null && outcome != null) {
            Text(stringResource(R.string.batch_stopped_at, result.stoppedAt), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.vp, bottom = 8.vp))
            ResultView(state, actions, outcome, embedded = true)
        } else {
            Spacer(Modifier.height(16.vp))
            FocusButton(stringResource(R.string.close), onClick = actions::dismissAction, requestInitialFocus = true, style = ButtonStyle.PRIMARY)
        }
    }
}
