package com.prototype.tuneupfortv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prototype.tuneupfortv.R
import com.prototype.tuneupfortv.safety.LauncherPlan
import com.prototype.tuneupfortv.safety.LauncherSwapRunner
import com.prototype.tuneupfortv.safety.ScopeProblem
import com.prototype.tuneupfortv.safety.StopCause
import com.prototype.tuneupfortv.safety.SwapOutcome
import com.prototype.tuneupfortv.safety.SwapPreview
import com.prototype.tuneupfortv.safety.SwapRefusal
import com.prototype.tuneupfortv.safety.SwapStep
import com.prototype.tuneupfortv.safety.SwapStepStatus
import com.prototype.tuneupfortv.session.SessionState

/**
 * The last, optional step of the debloat: the lightweight launcher, downloaded from its latest release. One button to install
 * it and one to go back; while it works there is a single bar with a sentence, and the technical steps only show up
 * when something stops.
 */
@Composable
internal fun ColumnScope.LauncherPanel(state: AppUiState, connected: SessionState.Connected, actions: AppActions) {
    val ui = state.launcher
    if (ui.running) {
        RunningPanel(ui)
        return
    }
    val padding = Modifier.padding(horizontal = 6.vp)
    if (!ui.loaded) {
        Skeleton(padding.fillMaxWidth().height(96.dp), RoundedCornerShape(24.vp))
        return
    }
    val preview = ui.preview
    val plan = (preview as? SwapPreview.Ready)?.plan
    val swapRecord = (ui.record as? LauncherSwapRunner.RecordState.Present)?.record?.takeIf { !it.updateOnly }
    val outcome = ui.outcome
    val failed = outcome as? SwapOutcome.Stopped
    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.vp)) {
        if (ui.availableVersion != null) {
            Text(
                stringResource(R.string.home_fact_launcher_available, ui.availableVersion),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TuneupTheme.colors.text,
                modifier = padding,
            )
        }
        if (swapRecord == null && plan?.isUpToDate != true) {
            Text(stringResource(R.string.launcher_panel_intro), style = subtitleStyle(), color = TuneupTheme.colors.muted, modifier = padding)
            Text(stringResource(R.string.launcher_optional), style = subtitleStyle(), color = TuneupTheme.colors.muted, modifier = padding)
        }
        if (outcome != null) OutcomeBanners(outcome, ui.undoing, padding) else IdleBanners(preview, ui, swapRecord != null, plan?.wifiWarning == true && ui.confirming, actions, padding)
        Row(padding.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.vp), verticalAlignment = Alignment.Top) {
            Buttons(ui, plan?.takeIf { !it.isUpToDate }, swapRecord != null, connected.openEntries.isNotEmpty(), actions)
        }
        // The technical steps are for when something stopped: otherwise one sentence and one bar said it all.
        if (failed != null) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(10.vp), verticalArrangement = Arrangement.spacedBy(10.vp)) {
                items(ui.steps, key = { it.name }) { step -> StepRow(step, ui.statuses[step]) }
            }
        }
    }
}

@Composable
private fun ColumnScope.RunningPanel(ui: LauncherUi) {
    val palette = TuneupTheme.colors
    val finished = ui.statuses.values.count { it != SwapStepStatus.WORKING }
    val current = ui.statuses.entries.lastOrNull { it.value == SwapStepStatus.WORKING }?.key ?: ui.steps.firstOrNull { it !in ui.statuses }
    Column(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.vp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (current != null) {
            Text(stepPhrase(current, ui.undoing), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = palette.text)
        }
        TvProgressBar(if (ui.steps.isEmpty()) null else finished.toFloat() / ui.steps.size, Modifier.fillMaxWidth(0.72f).padding(top = 28.vp))
        Text(
            stringResource(R.string.launcher_progress, (finished + 1).coerceAtMost(ui.steps.size.coerceAtLeast(1)), ui.steps.size),
            style = subtitleStyle(),
            color = palette.muted,
            modifier = Modifier.padding(top = 16.vp),
        )
    }
}

@Composable
private fun stepPhrase(step: SwapStep, undoing: Boolean): String =
    stringResource(
        if (undoing) {
            when (step) {
                SwapStep.DREAMX -> R.string.launcher_undo_dreamx
                SwapStep.STOCK -> R.string.launcher_undo_stock
                SwapStep.SETUPWRAITH -> R.string.launcher_undo_setupwraith
                SwapStep.HOME -> R.string.launcher_undo_home
                SwapStep.SCREENSAVER -> R.string.launcher_undo_screensaver
                else -> R.string.launcher_undo_restore_light
            }
        } else {
            when (step) {
                SwapStep.INSTALL -> R.string.launcher_do_install
                SwapStep.ENABLE -> R.string.launcher_do_enable
                SwapStep.PERMISSIONS -> R.string.launcher_do_permissions
                SwapStep.COMPILE -> R.string.launcher_do_compile
                SwapStep.SCREENSAVER -> R.string.launcher_do_screensaver
                SwapStep.SETUPWRAITH -> R.string.launcher_do_setupwraith
                SwapStep.STOCK -> R.string.launcher_do_stock
                SwapStep.HOME -> R.string.launcher_do_home
                SwapStep.DREAMX -> R.string.launcher_do_dreamx
                SwapStep.ACCESSIBILITY, SwapStep.RESTORE_LIGHT -> R.string.launcher_do_accessibility
            }
        },
    )

@Composable
private fun Buttons(ui: LauncherUi, plan: LauncherPlan?, canUndo: Boolean, hasOpenEntries: Boolean, actions: AppActions) {
    when {
        // The safe choice has the focus: a stray center press cancels instead of changing the TV.
        ui.confirming -> {
            FocusButton(stringResource(R.string.cancel), onClick = actions::cancelLauncher, requestInitialFocus = true)
            FocusButton(stringResource(R.string.launcher_button_confirm), onClick = actions::startLauncher, style = ButtonStyle.DANGER)
        }
        else -> {
            if (plan != null) {
                if (hasOpenEntries) {
                    FocusButton(stringResource(R.string.review_resolve), onClick = actions::openReview)
                } else {
                    FocusButton(
                        stringResource(if (plan.updateOnly) R.string.launcher_button_update else R.string.launcher_button_swap),
                        onClick = actions::askLauncher,
                        style = ButtonStyle.PRIMARY,
                    )
                }
            }
            if (canUndo) FocusButton(stringResource(R.string.launcher_button_undo), onClick = actions::undoLauncher)
            // The last step of the assistant is a choice: "Skip" while there is something to install, "Finish" when it is done or not needed.
            val offering = plan != null && ui.outcome == null
            FocusButton(
                stringResource(if (offering) R.string.debloat_skip else R.string.debloat_finish),
                onClick = actions::finishDebloat,
                style = if (offering) ButtonStyle.SECONDARY else ButtonStyle.PRIMARY,
            )
        }
    }
}

@Composable
private fun IdleBanners(preview: SwapPreview?, ui: LauncherUi, hasSwap: Boolean, showWifiWarning: Boolean, actions: AppActions, modifier: Modifier) {
    when {
        // No internet, no repo or no release all look the same from here: there is nothing to download.
        ui.availableVersion == null ->
            Banner(stringResource(R.string.launcher_unavailable), Tone.WARNING, modifier, stringResource(R.string.launcher_try_again), actions::retryLauncher)
        ui.record is LauncherSwapRunner.RecordState.Unreadable -> Banner(stringResource(R.string.launcher_refusal_record_unreadable), Tone.DANGER, modifier)
        preview is SwapPreview.Refused -> Banner(refusalText(preview.refusal), Tone.WARNING, modifier)
        preview is SwapPreview.Ready && preview.plan.isUpToDate ->
            Banner(stringResource(if (hasSwap) R.string.launcher_active_banner else R.string.launcher_up_to_date, ui.availableVersion), Tone.SUCCESS, modifier)
        preview is SwapPreview.Ready && preview.plan.updateOnly -> Banner(stringResource(R.string.launcher_update_intro), Tone.INFO, modifier)
    }
    if (showWifiWarning) Banner(stringResource(R.string.launcher_wifi_warning), Tone.WARNING, modifier)
}

@Composable
private fun OutcomeBanners(outcome: SwapOutcome, undoing: Boolean, modifier: Modifier) {
    when (outcome) {
        is SwapOutcome.Refused -> Banner(refusalText(outcome.refusal), Tone.WARNING, modifier)
        is SwapOutcome.Done -> {
            Banner(stringResource(if (undoing) R.string.launcher_undone else R.string.launcher_done), Tone.SUCCESS, modifier)
            if (outcome.unconfirmed.isNotEmpty()) {
                Banner(stringResource(R.string.launcher_unconfirmed, outcome.unconfirmed.map { stepLabel(it) }.joinToString()), Tone.WARNING, modifier)
            }
            if (outcome.wifiReminder) Banner(stringResource(R.string.launcher_wifi_reminder), Tone.WARNING, modifier)
        }
        is SwapOutcome.Stopped -> {
            Banner(stringResource(R.string.launcher_stopped, stepLabel(outcome.step), causeText(outcome.cause)), Tone.DANGER, modifier)
            if (outcome.rolledBack) Banner(stringResource(R.string.launcher_rolled_back), Tone.INFO, modifier)
            if (outcome.rollbackIncomplete) Banner(stringResource(R.string.launcher_rollback_incomplete), Tone.WARNING, modifier)
        }
    }
}

@Composable
private fun causeText(cause: StopCause): String =
    stringResource(
        when (cause) {
            StopCause.UNCLEAN -> R.string.launcher_cause_unclean
            StopCause.REJECTED -> R.string.launcher_cause_rejected
            StopCause.FAILED -> R.string.launcher_cause_failed
            StopCause.UNCERTAIN -> R.string.launcher_cause_uncertain
            StopCause.NOT_CONFIRMED -> R.string.launcher_cause_not_confirmed
            StopCause.DEVICE -> R.string.launcher_cause_device
            StopCause.RECORD -> R.string.launcher_cause_record
        },
    )

@Composable
private fun refusalText(refusal: SwapRefusal): String =
    when (refusal) {
        SwapRefusal.UnreliableDevice -> stringResource(R.string.reject_unreliable_device)
        SwapRefusal.RecordUnreadable -> stringResource(R.string.launcher_refusal_record_unreadable)
        SwapRefusal.RecordNotWritten -> stringResource(R.string.launcher_refusal_record_not_written)
        SwapRefusal.OpenEntries -> stringResource(R.string.debloat_pending_review)
        SwapRefusal.Unreadable -> stringResource(R.string.launcher_refusal_unreadable)
        SwapRefusal.WriteStillPending -> stringResource(R.string.launcher_refusal_write_pending)
        SwapRefusal.DownloadFailed -> stringResource(R.string.launcher_refusal_download)
        SwapRefusal.NothingToUndo -> stringResource(R.string.launcher_refusal_nothing_to_undo)
        is SwapRefusal.Device -> stringResource(R.string.launcher_refusal_device)
        is SwapRefusal.Guarded -> stringResource(R.string.launcher_refusal_guarded, refusal.packageId, rejectReasonLabel(refusal.reason))
        is SwapRefusal.Scope -> when (refusal.problem) {
            ScopeProblem.UNREADABLE, ScopeProblem.HOME_NOT_SUPPORTED -> stringResource(R.string.launcher_scope_unreadable)
            ScopeProblem.RECORD_UNREADABLE -> stringResource(R.string.launcher_refusal_record_unreadable)
            ScopeProblem.NO_STOCK -> stringResource(R.string.launcher_scope_no_stock)
            ScopeProblem.SEVERAL_STOCK -> stringResource(R.string.launcher_scope_several_stock)
            ScopeProblem.UNKNOWN_HOME_CANDIDATE -> stringResource(R.string.launcher_scope_unknown_candidate, refusal.detail.orEmpty())
            ScopeProblem.ANOTHER_STOCK_ENABLED -> stringResource(R.string.launcher_scope_another_stock, refusal.detail.orEmpty())
            ScopeProblem.SCREENSAVER_NOT_SUPPORTED -> stringResource(R.string.launcher_scope_screensaver)
            ScopeProblem.HOME_REMOVER -> stringResource(R.string.launcher_scope_home_remover, refusal.detail.orEmpty())
        }
    }

@Composable
private fun stepLabel(step: SwapStep): String =
    stringResource(
        when (step) {
            SwapStep.INSTALL -> R.string.launcher_step_install
            SwapStep.ENABLE -> R.string.launcher_step_enable
            SwapStep.PERMISSIONS -> R.string.launcher_step_permissions
            SwapStep.COMPILE -> R.string.launcher_step_compile
            SwapStep.SCREENSAVER -> R.string.launcher_step_screensaver
            SwapStep.SETUPWRAITH -> R.string.launcher_step_setupwraith
            SwapStep.STOCK -> R.string.launcher_step_stock
            SwapStep.HOME -> R.string.launcher_step_home
            SwapStep.DREAMX -> R.string.launcher_step_dreamx
            SwapStep.ACCESSIBILITY -> R.string.launcher_step_accessibility
            SwapStep.RESTORE_LIGHT -> R.string.launcher_step_restore_light
        },
    )

@Composable
private fun StepRow(step: SwapStep, status: SwapStepStatus?) {
    val palette = TuneupTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.vp, vertical = 8.vp),
        horizontalArrangement = Arrangement.spacedBy(16.vp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stepLabel(step), style = MaterialTheme.typography.titleMedium, color = palette.text, modifier = Modifier.weight(1f))
        val (text, kind) = when (status) {
            null -> stringResource(R.string.debloat_pill_untried) to BadgeKind.NEUTRAL
            SwapStepStatus.WORKING -> stringResource(R.string.debloat_pill_working) to BadgeKind.RUNNING
            SwapStepStatus.DONE -> stringResource(R.string.launcher_pill_done) to BadgeKind.SUCCESS
            SwapStepStatus.SKIPPED -> stringResource(R.string.debloat_pill_skipped) to BadgeKind.NEUTRAL
            SwapStepStatus.UNCONFIRMED -> stringResource(R.string.launcher_pill_unconfirmed) to BadgeKind.WARNING
            SwapStepStatus.FAILED -> stringResource(R.string.debloat_pill_failed) to BadgeKind.DANGER
        }
        Badge(text, kind, compact = true)
    }
}
