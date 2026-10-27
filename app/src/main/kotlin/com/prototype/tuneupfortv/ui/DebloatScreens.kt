package com.prototype.tuneupfortv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prototype.tuneupfortv.R
import com.prototype.tuneupfortv.device.DebloatEntry
import com.prototype.tuneupfortv.safety.DebloatEntryState
import com.prototype.tuneupfortv.device.DebloatList
import com.prototype.tuneupfortv.device.DebloatLists
import com.prototype.tuneupfortv.safety.ActionKind
import com.prototype.tuneupfortv.safety.ActionOutcome
import com.prototype.tuneupfortv.safety.BatchPlanner
import com.prototype.tuneupfortv.session.SessionState

/** What the plan says about disabling [packageIds] on this TV, read from the last snapshot. */
private fun disablePlan(connected: SessionState.Connected, packageIds: Set<String>) =
    BatchPlanner.plan(
        ActionKind.DISABLE,
        packageIds,
        connected.snapshot.packages,
        connected.classifications,
        connected.snapshot.processPackages.values.filterNotNull().toSet(),
    )

/** How many suggested packages of the best list apply to this TV, or null until the lists are read. */
internal fun debloatSuggestedCount(state: AppUiState, connected: SessionState.Connected): Int? {
    val lists = state.debloat.lists ?: return null
    val best = DebloatLists.forDevice(connected.snapshot.identity, lists) ?: return 0
    return disablePlan(connected, best.entries.filter { it.suggested }.map { it.packageId }.toSet()).runnable.size
}

// region one list

/** One line of the list: a group title or a package. */
private sealed interface DebloatLine {
    val key: String

    data class Group(val name: String) : DebloatLine {
        override val key: String get() = "group-$name"
    }

    data class Package(val entry: DebloatEntry) : DebloatLine {
        override val key: String get() = entry.packageId
    }
}

/** The room a focused row needs on each side: it grows 3% of the width of the list and glows 8 more. */
private val LIST_FOCUS_ROOM = 40.vp

/** The height of the strip under the tabs: the same in every tab, with a button or without, so nothing moves. */
private val STRIP_HEIGHT = 44.dp

/** The one step the screen is in; each has one main thing to do, so nothing competes for the user's attention. */
private enum class DebloatStep { READY, RUNNING, STOPPED, DONE }

/**
 * What the screen shows. The first time it is an assistant, step by step (choose, confirm, run, result, launcher); once
 * the assistant is over, or when it was never asked for, it is the three tabs of the list.
 */
private enum class DebloatView { PICK, CONFIRM, RUN, RESULT, LAUNCHER, TABS }

/** The number of the step in the assistant, for the indicator at the top. */
private fun wizardStepNumber(view: DebloatView): Int =
    when (view) {
        DebloatView.PICK -> 1
        DebloatView.CONFIRM -> 2
        DebloatView.RUN -> 3
        DebloatView.RESULT -> 4
        DebloatView.LAUNCHER -> 5
        DebloatView.TABS -> 0
    }

private const val WIZARD_STEPS = 5

@Composable
internal fun DebloatScreen(state: AppUiState, connected: SessionState.Connected, actions: AppActions) {
    val ui = state.debloat
    val list = ui.openedList
    if (list == null) {
        // Lists are read in the background; once read, a TV without a list of its own has nothing to offer.
        InnerScreen(connected, stringResource(R.string.debloat_title), null) {
            if (ui.lists == null) {
                Skeleton(Modifier.fillMaxWidth().padding(horizontal = 6.vp).height(96.dp), RoundedCornerShape(24.vp))
            } else {
                EmptyState(stringResource(R.string.debloat_no_list), stringResource(R.string.debloat_no_list_hint), Modifier.weight(1f))
            }
        }
        return
    }
    val packageIds = remember(list) { list.entries.map { it.packageId } }
    val plan = remember(list, connected.snapshot, connected.classifications) { disablePlan(connected, packageIds.toSet()) }
    val entryStates = remember(plan, packageIds) { packageIds.associateWith { DebloatEntryState.from(plan.skipped[it]) } }

    val step = when {
        ui.running -> DebloatStep.RUNNING
        ui.stopOutcome != null -> DebloatStep.STOPPED
        ui.toRestore.isNotEmpty() -> DebloatStep.DONE
        else -> DebloatStep.READY
    }
    val view = when {
        ui.running -> DebloatView.RUN
        ui.wizard == DebloatWizard.LAUNCHER -> DebloatView.LAUNCHER
        ui.confirming -> DebloatView.CONFIRM
        step == DebloatStep.STOPPED || step == DebloatStep.DONE -> DebloatView.RESULT
        ui.wizard == DebloatWizard.PICK -> DebloatView.PICK
        else -> DebloatView.TABS
    }
    val inAssistant = ui.wizard != null
    val willDisable = entryStates.values.count { it == DebloatEntryState.WILL_DISABLE }
    val protectedCount = entryStates.values.count { it == DebloatEntryState.PROTECTED }
    val alreadyOff = packageIds.filter { entryStates[it] == DebloatEntryState.ALREADY_DISABLED }
    // Which of the disabled ones the TV lets the app switch back on (the system's own disables are not its to undo).
    val enableable = remember(alreadyOff, connected.snapshot, connected.classifications) {
        BatchPlanner.plan(ActionKind.RESTORE, alreadyOff.toSet(), connected.snapshot.packages, connected.classifications, emptySet()).runnable.toSet()
    }
    // The first step of the assistant is the "to disable" list; the tabs only exist once it is over.
    val tab = if (view == DebloatView.PICK || view == DebloatView.CONFIRM) DebloatTab.TO_DISABLE else ui.tab ?: if (willDisable > 0) DebloatTab.TO_DISABLE else DebloatTab.DISABLED
    val alreadyClean = step == DebloatStep.READY && willDisable == 0

    // Before a run: the packages of the tab being looked at. After it: what was tried.
    val lines = remember(list, entryStates, ui.statuses, step, tab) {
        buildList {
            list.groups.forEach { group ->
                val shown = group.entries.filter { entry ->
                    when {
                        step != DebloatStep.READY -> entry.packageId in ui.statuses
                        else -> when (tab) {
                            DebloatTab.TO_DISABLE -> entryStates[entry.packageId] == DebloatEntryState.WILL_DISABLE
                            DebloatTab.DISABLED -> entryStates[entry.packageId] == DebloatEntryState.ALREADY_DISABLED
                            DebloatTab.PROTECTED -> entryStates[entry.packageId] == DebloatEntryState.PROTECTED
                        }
                    }
                }
                if (shown.isNotEmpty()) {
                    if (group.name.isNotEmpty()) add(DebloatLine.Group(group.name))
                    shown.forEach { add(DebloatLine.Package(it)) }
                }
            }
        }
    }
    val tickedToRun = ui.chosen.count { entryStates[it] == DebloatEntryState.WILL_DISABLE }
    val tickedToEnable = ui.chosenEnable.count { it in enableable }
    // The tick column only exists where something in the tab can be ticked: an empty slot would be a hole.
    val tabHasTicks = step == DebloatStep.READY && when (tab) {
        DebloatTab.TO_DISABLE -> willDisable > 0
        DebloatTab.DISABLED -> alreadyOff.any { it in enableable }
        DebloatTab.PROTECTED -> false
    }
    val openEntries = connected.openEntries
    val canStart = tickedToRun > 0 && openEntries.isEmpty()

    val identity = connected.snapshot.identity
    val tvName = if (identity.model.startsWith(identity.manufacturer, ignoreCase = true)) identity.model else "${identity.manufacturer} ${identity.model}"
    // The technical id of the TV: its full product name, its Android and its firmware build.
    val tvDetail = listOf(
        connected.snapshot.productName.ifEmpty { identity.model.replace(' ', '_') },
        stringResource(R.string.debloat_android, androidName(identity.sdk), identity.sdk),
        connected.snapshot.buildId,
    ).filter { it.isNotEmpty() }.joinToString(" · ")
    val counts = when (step) {
        DebloatStep.RUNNING ->
            if (ui.phase == DebloatPhase.RESTORING) stringResource(R.string.debloat_progress_restore, ui.done, ui.total) else stringResource(R.string.debloat_progress_disable, ui.done, ui.total)
        DebloatStep.STOPPED, DebloatStep.DONE -> stringResource(
            R.string.debloat_after,
            ui.statuses.values.count { it == StepStatus.Done || it == StepStatus.Restored },
            ui.statuses.values.count { it is StepStatus.Skipped },
            ui.statuses.values.count { it is StepStatus.Failed || it == StepStatus.Untried || it == StepStatus.MayLand },
        )
        DebloatStep.READY -> stringResource(
            R.string.debloat_summary,
            willDisable,
            entryStates.values.count { it == DebloatEntryState.ALREADY_DISABLED },
            protectedCount,
        )
    }

    // Names and icons of every tab are read once, in the background, so changing tab finds them cached instead of loading them.
    PrefetchAppVisuals(remember(entryStates) { packageIds.filter { entryStates[it] != DebloatEntryState.NOT_ON_TV } })
    // Each tab (and each step) starts its list at the top: a state kept across tabs would leave the new one scrolled.
    val listState = remember(tab, step) { LazyListState() }
    // While it runs, the list follows the package being changed.
    val workingId = ui.statuses.entries.firstOrNull { it.value == StepStatus.Working }?.key
    LaunchedEffect(workingId) {
        val index = lines.indexOfFirst { it.key == workingId }
        if (workingId != null && index >= 0) listState.animateScrollToItem(index)
    }

    val stoppedAt = ui.statuses.entries.firstOrNull { it.value is StepStatus.Failed }?.key
    val stoppedEntry = (ui.stopOutcome as? ActionOutcome.Finished)?.entry?.let { entry -> connected.journalEntries.firstOrNull { it.id == entry.id } ?: entry }
    val mayLand = ui.statuses.values.any { it == StepStatus.MayLand }
    // A button asks for the focus only when the screen or the step appears: changing tab must leave the focus on the tab.
    // Down from a tab goes to the action of the tab when it has one, then on to the list.
    val actionRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    var firstLook by remember(step) { mutableStateOf(true) }
    LaunchedEffect(step) { firstLook = false }
    // Whether the list below has an action to jump to: the confirmation, the run and the launcher have their own screens.
    val hasButton = when (view) {
        DebloatView.RUN, DebloatView.CONFIRM, DebloatView.LAUNCHER -> false
        DebloatView.RESULT -> true
        DebloatView.PICK -> canStart || openEntries.isNotEmpty()
        DebloatView.TABS -> (tab == DebloatTab.TO_DISABLE && (canStart || openEntries.isNotEmpty())) || (tab == DebloatTab.DISABLED && tickedToEnable > 0)
    }
    // The buttons of the step: in the strip of the tabs, or in the bar fixed under the card of the assistant.
    val actionButtons: @Composable () -> Unit = {
        when {
            view == DebloatView.RESULT -> {
                // In the assistant the way on is "Next" (the launcher); outside it, "Finish" closes the report.
                val onward = if (inAssistant) actions::nextDebloat else actions::finishDebloat
                val onwardLabel = stringResource(if (inAssistant) R.string.debloat_next else R.string.debloat_finish)
                if (step == DebloatStep.STOPPED) {
                    if (stoppedEntry != null) FocusButton(stringResource(R.string.debloat_keep), onClick = { actions.acceptEntry(stoppedEntry.id) })
                    FocusButton(stringResource(R.string.debloat_undo, ui.toRestore.size), onClick = actions::restoreDebloat, modifier = Modifier.focusRequester(actionRequester), requestInitialFocus = true, style = ButtonStyle.PRIMARY)
                    FocusButton(onwardLabel, onClick = onward)
                } else {
                    FocusButton(stringResource(R.string.debloat_undo, ui.toRestore.size), onClick = actions::restoreDebloat)
                    FocusButton(onwardLabel, onClick = onward, modifier = Modifier.focusRequester(actionRequester), requestInitialFocus = true, style = ButtonStyle.PRIMARY)
                }
            }
            tab == DebloatTab.TO_DISABLE && openEntries.isNotEmpty() ->
                FocusButton(stringResource(R.string.review_resolve), onClick = actions::openReview, modifier = Modifier.focusRequester(actionRequester), requestInitialFocus = firstLook)
            view == DebloatView.PICK -> {
                // Nothing to clean, or not now: the assistant can go straight on to the launcher.
                FocusButton(stringResource(R.string.debloat_skip), onClick = actions::nextDebloat)
                FocusButton(
                    stringResource(R.string.debloat_next),
                    onClick = actions::askDebloat,
                    modifier = Modifier.focusRequester(actionRequester),
                    enabled = canStart,
                    requestInitialFocus = canStart && firstLook,
                    style = ButtonStyle.PRIMARY,
                )
            }
            tab == DebloatTab.TO_DISABLE ->
                FocusButton(
                    if (tickedToRun > 0) stringResource(R.string.debloat_run, tickedToRun) else stringResource(R.string.debloat_run_none),
                    onClick = actions::askDebloat,
                    modifier = Modifier.focusRequester(actionRequester),
                    enabled = canStart,
                    requestInitialFocus = canStart && firstLook,
                    style = ButtonStyle.PRIMARY,
                )
            tab == DebloatTab.DISABLED ->
                FocusButton(
                    if (tickedToEnable > 0) stringResource(R.string.debloat_enable, tickedToEnable) else stringResource(R.string.debloat_enable_none),
                    onClick = actions::enableDebloat,
                    modifier = Modifier.focusRequester(actionRequester),
                    enabled = tickedToEnable > 0,
                    requestInitialFocus = tickedToEnable > 0 && firstLook,
                    style = ButtonStyle.PRIMARY,
                )
            else -> Unit
        }
    }
    InnerScreen(
        connected,
        tvName,
        tvDetail,
        subtitleTextStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 15.sp, lineHeight = 20.sp),
        caption = if (view == DebloatView.LAUNCHER) null else counts,
    ) {
        // The assistant says where the user is; once it is over there is nothing to count.
        if (inAssistant && view != DebloatView.TABS) WizardChips(wizardStepNumber(view))
        if (view == DebloatView.TABS) {
            // For whoever forgot an app: the assistant is one card away, always in sight.
            RestartCard(actions)
            // The list can be looked at by what each package is. These are tabs, not buttons: the action is in the strip below.
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.vp), horizontalArrangement = Arrangement.spacedBy(8.vp), verticalAlignment = Alignment.CenterVertically) {
                val tabModifier = Modifier.weight(1f).focusProperties { if (hasButton) down = actionRequester }
                DebloatTabItem(stringResource(R.string.debloat_tab_disable, willDisable), tab == DebloatTab.TO_DISABLE, tabModifier) { actions.selectDebloatTab(DebloatTab.TO_DISABLE) }
                DebloatTabItem(stringResource(R.string.debloat_tab_disabled, alreadyOff.size), tab == DebloatTab.DISABLED, tabModifier) { actions.selectDebloatTab(DebloatTab.DISABLED) }
                DebloatTabItem(stringResource(R.string.debloat_tab_protected, protectedCount), tab == DebloatTab.PROTECTED, tabModifier) { actions.selectDebloatTab(DebloatTab.PROTECTED) }
            }
        }
        // What this step is about: a title and a sentence, or the result of the run.
        when (view) {
            DebloatView.PICK -> WizardTitle(stringResource(R.string.debloat_pick_title), stringResource(R.string.debloat_pick_subtitle, willDisable))
            DebloatView.RUN -> WizardTitle(stringResource(R.string.debloat_run_title), stringResource(R.string.debloat_run_subtitle))
            DebloatView.RESULT ->
                if (step == DebloatStep.STOPPED) {
                    Banner(stringResource(R.string.debloat_stopped_banner, stoppedAt ?: stoppedEntry?.packageId.orEmpty()), Tone.WARNING, Modifier.padding(horizontal = 6.vp))
                } else {
                    ResultHero(ui.toRestore.size)
                    if (mayLand) Banner(stringResource(R.string.debloat_may_land_banner), Tone.WARNING, Modifier.padding(horizontal = 6.vp))
                }
            else -> Unit
        }
        // The strip under the tabs: what this tab is, and the one action it has. At the top, so it is one step from the list.
        // In every tab it is the same height, with or without a button, so nothing moves when the tab changes.
        if (view == DebloatView.TABS) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.vp).height(STRIP_HEIGHT), horizontalArrangement = Arrangement.spacedBy(16.vp), verticalAlignment = Alignment.Top) {
                val description = when {
                    tab == DebloatTab.TO_DISABLE && openEntries.isNotEmpty() -> stringResource(R.string.debloat_pending_review)
                    tab == DebloatTab.TO_DISABLE -> stringResource(R.string.debloat_tab_disable_note)
                    tab == DebloatTab.DISABLED && alreadyClean -> stringResource(R.string.debloat_tab_disabled_clean_note)
                    tab == DebloatTab.DISABLED -> stringResource(R.string.debloat_tab_disabled_note)
                    else -> stringResource(R.string.debloat_tab_protected_note)
                }
                Text(description, style = subtitleStyle(), color = TuneupTheme.colors.muted, modifier = Modifier.weight(1f))
                actionButtons()
            }
        }
        if (view == DebloatView.LAUNCHER) {
            LauncherPanel(state, connected, actions)
        } else if (view == DebloatView.CONFIRM) {
            ConfirmPanel(tickedToRun, actions)
        } else {
            // In the assistant the list is a card; its action is a bar fixed below it, so it never scrolls out of sight.
            // The card only paints its background: clipping it would cut the focused row, which grows and glows past its edges.
            val cardModifier = if (inAssistant) Modifier.background(TuneupTheme.colors.surface.copy(alpha = 0.45f), RoundedCornerShape(28.vp)) else Modifier
            Column(Modifier.weight(1f).then(cardModifier)) {
                if (lines.isEmpty()) {
                    EmptyState(stringResource(R.string.debloat_nothing_here), null, Modifier.weight(1f))
                } else {
                    // The list is wider than its card by the room a focused row needs, and its rows keep their place inside the card.
                    LazyColumn(Modifier.weight(1f).bleed(LIST_FOCUS_ROOM), state = listState, contentPadding = PaddingValues(horizontal = LIST_FOCUS_ROOM + 10.vp, vertical = 14.vp), verticalArrangement = Arrangement.spacedBy(10.vp)) {
                items(lines, key = { it.key }) { line ->
                    when (line) {
                        is DebloatLine.Group -> Text(line.name, style = MaterialTheme.typography.titleSmall, color = TuneupTheme.colors.muted, modifier = Modifier.padding(start = 6.vp, top = 10.vp))
                        is DebloatLine.Package -> {
                            val entry = line.entry
                            val entryState = entryStates[entry.packageId] ?: DebloatEntryState.NOT_ON_TV
                            val toDisable = step == DebloatStep.READY && tab == DebloatTab.TO_DISABLE && entryState == DebloatEntryState.WILL_DISABLE
                            val toEnable = step == DebloatStep.READY && tab == DebloatTab.DISABLED && entry.packageId in enableable
                            // In the tabs, right goes to the action when there is one; up from the first row goes to it too, not to the tabs.
                            // In the assistant the action is the bar below the card: down reaches it by itself.
                            val isFirstRow = line == lines.firstOrNull { it is DebloatLine.Package }
                            DebloatRow(
                                modifier = Modifier.focusProperties {
                                    if (hasButton && view == DebloatView.TABS) {
                                        right = actionRequester
                                        if (isFirstRow) up = actionRequester
                                    }
                                },
                                entry = entry,
                                entryState = entryState,
                                status = if (step == DebloatStep.READY) null else ui.statuses[entry.packageId],
                                ticked = if (toEnable) entry.packageId in ui.chosenEnable else entry.packageId in ui.chosen,
                                tickable = toDisable || toEnable,
                                showTick = step == DebloatStep.READY,
                                tickSlot = tabHasTicks,
                                requestInitialFocus = !hasButton && lines.firstOrNull() == line,
                                onClick = when {
                                    ui.confirming -> null
                                    toDisable -> ({ actions.toggleDebloatEntry(entry.packageId) })
                                    toEnable -> ({ actions.toggleDebloatEnable(entry.packageId) })
                                    else -> null
                                },
                            )
                        }
                    }
                }
                    }
                }
            }
            // The action of the step, fixed below the card so it never scrolls out of sight (the launcher step has its own).
            if (inAssistant && (view == DebloatView.PICK || view == DebloatView.RESULT)) {
                WizardBar(if (view == DebloatView.PICK) stringResource(R.string.debloat_selected_count, tickedToRun) else null) { actionButtons() }
            }
        }
    }
}

/** Where the user is in the assistant: a chip per step with its name, the current one filled and the ones behind it ticked. */
@Composable
private fun WizardChips(current: Int) {
    val palette = TuneupTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.vp), horizontalArrangement = Arrangement.spacedBy(8.vp), verticalAlignment = Alignment.CenterVertically) {
        for (number in 1..WIZARD_STEPS) {
            val label = stringResource(
                when (number) {
                    1 -> R.string.debloat_wizard_pick
                    2 -> R.string.debloat_wizard_confirm
                    3 -> R.string.debloat_wizard_run
                    4 -> R.string.debloat_wizard_result
                    else -> R.string.debloat_wizard_launcher
                },
            )
            val isCurrent = number == current
            val isBehind = number < current
            Row(
                Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(if (isCurrent) palette.accent else palette.muted.copy(alpha = if (isBehind) 0.22f else 0.1f))
                    .padding(horizontal = 16.vp, vertical = 8.vp),
                horizontalArrangement = Arrangement.spacedBy(8.vp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isBehind) Icon(Glyphs.Check, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.vp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) palette.onAccent else if (isBehind) palette.text else palette.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The title of a step of the assistant, and one sentence under it. */
@Composable
private fun WizardTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 6.vp), verticalArrangement = Arrangement.spacedBy(4.vp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = TuneupTheme.colors.text)
        Text(subtitle, style = subtitleStyle(), color = TuneupTheme.colors.muted)
    }
}

/** What the run did, said big: a check and the number of apps that are now off. */
@Composable
private fun ResultHero(count: Int) {
    val palette = TuneupTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.vp), horizontalArrangement = Arrangement.spacedBy(20.vp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.vp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
            Icon(Glyphs.Check, contentDescription = null, tint = palette.onAccent, modifier = Modifier.size(34.vp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.vp)) {
            Text(stringResource(R.string.debloat_result_hero, count), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = palette.text)
            Text(stringResource(R.string.debloat_result_note), style = subtitleStyle(), color = palette.muted)
        }
    }
}

/** The bar fixed under the card: how many are chosen on the left, and the buttons of the step on the right. */
@Composable
private fun WizardBar(counter: String?, buttons: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.vp, vertical = 8.vp), horizontalArrangement = Arrangement.spacedBy(16.vp), verticalAlignment = Alignment.CenterVertically) {
        Text(counter.orEmpty(), style = subtitleStyle(), color = TuneupTheme.colors.muted, modifier = Modifier.weight(1f))
        buttons()
    }
}

/** Shown above the tabs once the assistant is over: a way to run it again that does not need to be looked for. */
@Composable
private fun RestartCard(actions: AppActions) {
    val palette = TuneupTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.vp).clip(RoundedCornerShape(24.vp)).background(palette.surface).padding(horizontal = 24.vp, vertical = 12.vp),
        horizontalArrangement = Arrangement.spacedBy(16.vp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.debloat_restart_title), style = MaterialTheme.typography.titleMedium, color = palette.text, modifier = Modifier.weight(1f))
        FocusButton(stringResource(R.string.debloat_restart_button), onClick = actions::restartDebloatWizard, minHeight = 56.vp)
    }
}

/** The confirmation, in a step of its own: what is about to happen, and a way back that has the focus. */
@Composable
private fun ColumnScope.ConfirmPanel(count: Int, actions: AppActions) {
    val palette = TuneupTheme.colors
    Column(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.vp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.debloat_confirm_title, count), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = palette.text)
        Text(stringResource(R.string.debloat_confirm_note), style = subtitleStyle(), color = palette.muted, modifier = Modifier.padding(top = 12.vp))
        Row(Modifier.padding(top = 32.vp), horizontalArrangement = Arrangement.spacedBy(16.vp)) {
            // The safe choice has the focus: a stray center press goes back instead of changing the TV.
            FocusButton(stringResource(R.string.debloat_back), onClick = actions::cancelDebloat, requestInitialFocus = true)
            FocusButton(stringResource(R.string.debloat_run_confirm, count), onClick = actions::startDebloat, style = ButtonStyle.DANGER)
        }
    }
}

@Composable
private fun DebloatRow(
    modifier: Modifier,
    entry: DebloatEntry,
    entryState: DebloatEntryState,
    status: StepStatus?,
    ticked: Boolean,
    tickable: Boolean,
    showTick: Boolean,
    tickSlot: Boolean,
    requestInitialFocus: Boolean,
    onClick: (() -> Unit)?,
) {
    val visual = rememberAppVisual(entry.packageId)
    val showId = visual.label != entry.packageId
    val note = if (entry.packageId in DebloatLists.NEVER_SUGGESTED) stringResource(R.string.debloat_screen_reader_note) else entry.note
    FocusRow(
        modifier,
        onClick = onClick,
        requestInitialFocus = requestInitialFocus,
        selected = showTick && tickable && ticked,
        idleBackground = TuneupTheme.colors.surface,
        contentPadding = 16.vp,
        minHeight = 88.vp,
    ) {
        val palette = TuneupTheme.colors
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.vp)) {
            if (!showTick || tickSlot) {
                Box(Modifier.size(52.vp), contentAlignment = Alignment.Center) {
                    when {
                        showTick -> if (tickable) SelectBox(ticked)
                        else -> StepMark(status)
                    }
                }
            }
            AppIconImage(visual, 56.vp)
            Column(Modifier.weight(1f)) {
                Text(visual.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showId) Text(entry.packageId, style = subtitleStyle(), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (note != null) Text(note, style = subtitleStyle(), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatePill(entryState, status)
        }
    }
}

/** The live mark of a package while a run goes: a spinner, a check, or a warning. */
@Composable
private fun StepMark(status: StepStatus?) {
    val palette = TuneupTheme.colors
    when (status) {
        StepStatus.Working -> Spinner(size = 36.vp)
        StepStatus.Done, StepStatus.Restored -> Icon(Glyphs.Check, contentDescription = null, tint = palette.accent, modifier = Modifier.size(36.vp))
        is StepStatus.Failed, StepStatus.MayLand -> Text("!", style = MaterialTheme.typography.headlineSmall, color = palette.danger)
        else -> Box(Modifier.size(14.vp).clip(RoundedCornerShape(7.vp)).background(palette.muted.copy(alpha = 0.4f)))
    }
}

@Composable
private fun StatePill(entryState: DebloatEntryState, status: StepStatus?) {
    val (text, kind) = when (status) {
        StepStatus.Pending -> stringResource(R.string.debloat_pill_waiting) to BadgeKind.NEUTRAL
        StepStatus.Working -> stringResource(R.string.debloat_pill_working) to BadgeKind.RUNNING
        StepStatus.Done -> stringResource(R.string.debloat_pill_done) to BadgeKind.SUCCESS
        is StepStatus.Skipped -> stringResource(R.string.debloat_pill_skipped) to BadgeKind.NEUTRAL
        is StepStatus.Failed -> stringResource(R.string.debloat_pill_failed) to BadgeKind.DANGER
        StepStatus.Untried -> stringResource(R.string.debloat_pill_untried) to BadgeKind.NEUTRAL
        StepStatus.Restored -> stringResource(R.string.debloat_pill_restored) to BadgeKind.SUCCESS
        StepStatus.MayLand -> stringResource(R.string.debloat_pill_may_land) to BadgeKind.WARNING
        null -> when (entryState) {
            DebloatEntryState.WILL_DISABLE -> stringResource(R.string.debloat_pill_will) to BadgeKind.WARNING
            DebloatEntryState.ALREADY_DISABLED -> stringResource(R.string.debloat_pill_already) to BadgeKind.DISABLED
            DebloatEntryState.NOT_ON_TV -> stringResource(R.string.debloat_pill_absent) to BadgeKind.NEUTRAL
            DebloatEntryState.PROTECTED -> ("◆ " + stringResource(R.string.debloat_pill_protected)) to BadgeKind.PROTECTED
        }
    }
    Badge(text, kind, compact = true)
}

// endregion

/** The Android release for an API level, as people say it. */
private fun androidName(sdk: Int): String =
    when (sdk) {
        26 -> "8.0"
        27 -> "8.1"
        28 -> "9"
        29 -> "10"
        30 -> "11"
        31 -> "12"
        32 -> "12L"
        33 -> "13"
        34 -> "14"
        35 -> "15"
        36 -> "16"
        else -> "API $sdk"
    }

/**
 * A tab. The open one cannot be missed: a dot, accent text, a tinted card with an accent border and a line under its
 * whole width. The others are plain muted text. Pressing OK opens a tab; the focus alone only moves.
 */
@Composable
private fun DebloatTabItem(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    // A focus row always fills the width it is given, so each tab is given an equal share of the row. No padding on the
    // row itself: the line under the label must reach both edges of the tab.
    FocusRow(modifier, onClick = onClick, selected = selected, shape = RoundedCornerShape(14.vp), contentPadding = 0.vp, minHeight = 56.vp) {
        val palette = TuneupTheme.colors
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.padding(horizontal = 14.vp, vertical = 14.vp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.vp)) {
                if (selected) Text("•", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = palette.accent)
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) palette.accent else palette.muted, maxLines = 1)
            }
            Box(Modifier.fillMaxWidth().height(4.vp).background(if (selected) palette.accent else Color.Transparent))
        }
    }
}
