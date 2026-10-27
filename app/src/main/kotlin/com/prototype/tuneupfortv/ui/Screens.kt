package com.prototype.tuneupfortv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prototype.tuneupfortv.R
import com.prototype.tuneupfortv.device.Category
import com.prototype.tuneupfortv.device.DeviceFailure
import com.prototype.tuneupfortv.device.DeviceSnapshot
import com.prototype.tuneupfortv.device.EssentialReason
import com.prototype.tuneupfortv.device.ListFilter
import com.prototype.tuneupfortv.device.PackageItem
import com.prototype.tuneupfortv.device.PackageList
import com.prototype.tuneupfortv.device.ProcessItem
import com.prototype.tuneupfortv.device.SortOrder
import com.prototype.tuneupfortv.safety.ActionKind
import com.prototype.tuneupfortv.safety.ActionPlanner
import com.prototype.tuneupfortv.safety.ForceCloseAll
import com.prototype.tuneupfortv.safety.LivePackage
import com.prototype.tuneupfortv.session.SessionProblem
import com.prototype.tuneupfortv.session.SessionState

@Composable
fun TuneupApp(state: AppUiState, actions: AppActions) {
    val noticeAccepted = state.settings.noticeAccepted
    val connected = state.connected
    // Back goes up inside the app; at Home, or while the intro is showing, it leaves the app.
    BackHandler(enabled = noticeAccepted && connected != null && (state.action != ActionUi.None || state.backStack.size > 1)) { actions.onBack() }

    // The lists keep their scroll position while an action screen is shown over them, and when the user comes back.
    val packagesListState = rememberLazyListState()
    val processesListState = rememberLazyListState()
    val packagesGridState = rememberLazyGridState()
    val processesGridState = rememberLazyGridState()
    // A fresh visit to a list starts at its top: the states live here, so they would otherwise keep where the last visit
    // left off, which with another list (say, system apps shown or hidden since) points at some other item. Coming back from
    // a package's page keeps the place, and so does an action shown over the list, which never leaves this screen.
    var previousScreen by remember { mutableStateOf(Screen.HOME) }
    LaunchedEffect(state.screen) {
        if (previousScreen != Screen.PACKAGE_DETAIL) {
            when (state.screen) {
                Screen.PACKAGES -> { packagesListState.requestScrollToItem(0); packagesGridState.requestScrollToItem(0) }
                Screen.PROCESSES -> { processesListState.requestScrollToItem(0); processesGridState.requestScrollToItem(0) }
                else -> Unit
            }
        }
        previousScreen = state.screen
    }

    val palette = TuneupTheme.colors
    // A Surface, not just a background: it also sets the text color, which would otherwise be black.
    Surface(Modifier.fillMaxSize(), color = palette.screen) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                // A faint glow in the top right corner, the only decoration of the screen.
                drawRect(
                    Brush.radialGradient(
                        listOf(palette.screenGlow, Color.Transparent),
                        center = Offset(size.width * 0.82f, size.height * 0.07f),
                        radius = size.width * 0.32f,
                    ),
                )
            },
        ) {
            // The safe area: many TVs crop the edges (overscan).
            Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
                when {
                    !noticeAccepted -> NoticeScreen(onAccept = actions::acceptNotice)
                    connected == null -> IntroScreen(state.session, actions)
                    state.action != ActionUi.None -> ActionOverlay(state, connected, actions)
                    else -> when (state.screen) {
                        Screen.HOME -> HomeScreen(state, connected, actions)
                        Screen.PACKAGES -> PackagesScreen(state, connected, actions, packagesListState, packagesGridState)
                        Screen.PACKAGE_DETAIL -> PackageDetailScreen(state, connected, actions)
                        Screen.PROCESSES -> ProcessesScreen(state, connected, actions, processesListState, processesGridState)
                        Screen.HEALTH -> HealthScreen(connected, actions)
                        Screen.DEBLOAT -> DebloatScreen(state, connected, actions)
                        Screen.SETTINGS -> SettingsScreen(state, connected, actions)
                    }
                }
            }
        }
    }
}

// region shared frame

/** Common frame of the inner screens: a header with the connection state, whatever is going wrong, then the content. Back is the remote's key. */
@Composable
internal fun InnerScreen(
    connected: SessionState.Connected,
    title: String,
    subtitle: String?,
    trailing: @Composable RowScope.() -> Unit = { StatusDot(connected.snapshot.identity.model) },
    subtitleTextStyle: TextStyle = subtitleStyle(),
    caption: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.vp)) {
        ScreenHeader(title, subtitle, trailing = trailing, subtitleTextStyle = subtitleTextStyle, caption = caption)
        ProblemBanners(connected, Modifier.padding(horizontal = 6.vp))
        content()
    }
}

/** What is going on that the user should know: a read in progress, and anything that failed without breaking the session. */
@Composable
private fun ProblemBanners(connected: SessionState.Connected, modifier: Modifier = Modifier, showRefreshing: Boolean = true) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.vp)) {
        if (showRefreshing && connected.refreshing) LoadingRow(stringResource(R.string.refreshing))
        SessionProblem.entries.filter { it in connected.problems }.forEach { problem ->
            val message = when (problem) {
                SessionProblem.REFRESH_FAILED -> R.string.problem_refresh_failed
                SessionProblem.CHOICE_NOT_SAVED -> R.string.problem_choice_not_saved
                SessionProblem.JOURNAL_NOT_WRITTEN -> R.string.problem_journal_not_written
                SessionProblem.HEALTH_NOT_READ -> R.string.problem_health_not_read
            }
            // Losing the journal can leave a change unrecorded: that is the one problem that is not just a warning.
            Banner(stringResource(message), if (problem == SessionProblem.JOURNAL_NOT_WRITTEN) Tone.DANGER else Tone.WARNING)
        }
    }
}

// endregion

// region intro

/** The centered layout of the screens before Home and of the action screens: a symbol, a kicker, a title and a body. */
@Composable
internal fun CenterScreen(
    tone: Tone,
    glyph: String,
    title: String,
    modifier: Modifier = Modifier,
    kicker: String? = null,
    /** Everything on the middle line of the screen, text included, instead of starting at the left of the block. */
    centered: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 1200.vp).verticalScroll(rememberScrollState()).padding(10.vp),
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            StatusSymbol(tone, glyph, Modifier.padding(bottom = 24.vp))
            if (kicker != null) Text(kicker, style = MaterialTheme.typography.titleSmall, color = TuneupTheme.colors.muted, modifier = Modifier.padding(bottom = 8.vp))
            Text(title, style = MaterialTheme.typography.headlineLarge, textAlign = if (centered) TextAlign.Center else null, modifier = Modifier.padding(bottom = 16.vp))
            content()
        }
    }
}

@Composable
private fun NoticeScreen(onAccept: () -> Unit) {
    CenterScreen(Tone.INFO, "i", stringResource(R.string.notice_title), kicker = stringResource(R.string.app_name)) {
        Text(stringResource(R.string.notice_body), style = MaterialTheme.typography.bodyLarge, color = TuneupTheme.colors.muted)
        Spacer(Modifier.height(32.vp))
        FocusButton(stringResource(R.string.notice_accept), onClick = onAccept, requestInitialFocus = true, style = ButtonStyle.PRIMARY)
    }
}

/**
 * Everything before the first Home: connecting, the permission prompt, or why it did not work.
 * There is no Back here, so Back leaves the app.
 */
@Composable
private fun IntroScreen(session: SessionState, actions: AppActions) {
    val palette = TuneupTheme.colors
    when (session) {
        SessionState.Idle -> SplashScreen(stringResource(R.string.loading), hint = null)
        is SessionState.Connected -> Unit
        // The first connection can sit here until the user answers the prompt on the device.
        SessionState.Connecting -> SplashScreen(stringResource(R.string.connecting), hint = stringResource(R.string.connecting_hint))
        SessionState.NeedsAuthorization -> CenterScreen(Tone.INFO, "↗", stringResource(R.string.auth_title), kicker = stringResource(R.string.kicker_auth)) {
            Text(stringResource(R.string.auth_body), style = MaterialTheme.typography.bodyLarge, color = palette.muted)
            IntroActions(actions)
        }
        is SessionState.Failed -> CenterScreen(Tone.DANGER, "×", stringResource(R.string.failure_title), kicker = stringResource(R.string.kicker_connection)) {
            Text(failureMessage(session.failure, session.detail), style = MaterialTheme.typography.bodyLarge, color = palette.muted)
            IntroActions(actions)
        }
    }
}

/**
 * What the app looks like while it starts: the mark, the name, and always a spinner with what is going on. It is the
 * first thing seen on every launch, so it has the brand and moves a little, and it never looks like a frozen screen.
 */
@Composable
private fun SplashScreen(status: String, hint: String?) {
    val palette = TuneupTheme.colors
    val pulse by rememberInfiniteTransition(label = "splashPulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "splashPulseScale",
    )
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(200.vp).graphicsLayer { scaleX = pulse; scaleY = pulse }.clip(RoundedCornerShape(56.vp)).background(Color(0xFFF2F6F9)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Glyphs.Brand, contentDescription = null, tint = Color(0xFF0A0C0F), modifier = Modifier.size(100.vp))
            }
            Spacer(Modifier.height(28.vp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.brand_subtitle), style = subtitleStyle(), color = palette.muted)
        }
        // What is going on sits in the corner, small: the brand is the screen, the loading is only a sign of life.
        Column(Modifier.align(Alignment.BottomEnd), horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.vp)) {
                Text(status, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                Spinner(size = 36.vp)
            }
            if (hint != null) {
                // The whole width of the screen and one line: the notice is short, and a wrapped one looks like a mistake.
                Text(hint, style = subtitleStyle(), color = palette.muted, textAlign = TextAlign.End, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 24.vp))
            }
        }
    }
}

@Composable
private fun IntroActions(actions: AppActions) {
    Spacer(Modifier.height(32.vp))
    FocusButton(stringResource(R.string.retry), onClick = actions::retry, requestInitialFocus = true, style = ButtonStyle.PRIMARY)
}

@Composable
private fun failureMessage(failure: DeviceFailure?, detail: String?): String =
    when (failure) {
        DeviceFailure.Unreachable -> stringResource(R.string.failure_unreachable)
        DeviceFailure.Refused -> stringResource(R.string.failure_refused)
        DeviceFailure.Unauthorized -> stringResource(R.string.failure_unauthorized)
        DeviceFailure.Timeout -> stringResource(R.string.failure_timeout)
        is DeviceFailure.IdentityMismatch -> stringResource(R.string.failure_identity)
        is DeviceFailure.Other -> stringResource(R.string.failure_other, failure.message)
        // The loader reports a request for authorization as its own state, never as a failure screen.
        DeviceFailure.AwaitingAuthorization -> stringResource(R.string.auth_body)
        null -> stringResource(R.string.failure_unreliable, detail ?: "?")
    }

// endregion

// region home

private fun languageLabel(language: AppLanguage): Int =
    when (language) {
        AppLanguage.SYSTEM -> R.string.language_system
        AppLanguage.ENGLISH -> R.string.language_english
        AppLanguage.SPANISH -> R.string.language_spanish
    }

/** The main screen once a device is connected: four big cards, each with one live fact, and a strip about the TV. */
@Composable
private fun HomeScreen(state: AppUiState, connected: SessionState.Connected, actions: AppActions) {
    val snapshot = connected.snapshot
    val runningApps = remember(snapshot) { snapshot.processPackages.values.filterNotNull().toSet().size }
    val freeKb = connected.health.now?.memoryAvailableKb
    val unknown = stringResource(R.string.health_unknown_short)
    // Refresh reads packages and processes again: while it does, the numbers that come from them are skeletons.
    val refreshing = connected.refreshing
    val packagesFact = if (refreshing) null else stringResource(R.string.home_fact_packages, snapshot.packages.size, runningApps)
    val processesFact = if (refreshing) null else stringResource(R.string.home_fact_processes, snapshot.processes.size)
    // Health is read in the background right after connecting: until it answers (or fails) its numbers are skeletons.
    val healthPending = healthPending(connected)
    val healthFact = if (healthPending) null else if (freeKb == null) unknown else stringResource(R.string.home_fact_health_free, freeKb / 1024)
    // The bundled lists load in the background too, and what applies depends on what the TV reports.
    val suggestedCount = remember(state.debloat.lists, snapshot, connected.classifications) { debloatSuggestedCount(state, connected) }
    val suggested = if (refreshing) null else suggestedCount
    val debloatFact = when (suggested) {
        null -> null
        0 -> stringResource(R.string.home_fact_debloat_none)
        else -> stringResource(R.string.home_fact_debloat, suggested)
    }
    val settingsFact = stringResource(languageLabel(state.language))
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.vp)) {
        BrandBar(snapshot.identity.model)
        HomeNotices(connected, state.settings.dismissedUntestedModels, actions)
        // Same side inset as the brand bar and the strip below, so all three share edges. Nothing clips, so a focused card grows into the margin.
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.vp, vertical = 24.vp), verticalArrangement = Arrangement.spacedBy(24.vp)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.vp)) {
                HomeCard(R.string.packages, packagesFact, Glyphs.Packages, primary = true, onClick = { actions.open(Screen.PACKAGES) }, requestInitialFocus = true, modifier = Modifier.weight(1f))
                HomeCard(R.string.processes, processesFact, Glyphs.Processes, primary = false, onClick = { actions.open(Screen.PROCESSES) }, modifier = Modifier.weight(1f))
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.vp)) {
                HomeCard(R.string.health_title, healthFact, Glyphs.Health, primary = false, onClick = { actions.open(Screen.HEALTH) }, modifier = Modifier.weight(1f))
                HomeCard(R.string.debloat_title, debloatFact, Glyphs.Debloat, primary = false, onClick = actions::openDebloat, modifier = Modifier.weight(1f))
                HomeCard(R.string.settings, settingsFact, Glyphs.Settings, primary = false, onClick = { actions.open(Screen.SETTINGS) }, modifier = Modifier.weight(1f))
            }
        }
        Box(Modifier.padding(horizontal = 6.vp)) { DeviceStrip(connected, runningApps, actions) }
    }
}

/** True from connecting until the first health read answers or fails. */
private fun healthPending(connected: SessionState.Connected): Boolean =
    connected.health.now == null && SessionProblem.HEALTH_NOT_READ !in connected.problems

@Composable
private fun BrandBar(model: String) {
    val palette = TuneupTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.vp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(72.vp).clip(RoundedCornerShape(20.vp)).background(Color(0xFFF2F6F9)), contentAlignment = Alignment.Center) {
            Icon(Glyphs.Brand, contentDescription = null, tint = Color(0xFF0A0C0F), modifier = Modifier.size(36.vp))
        }
        Column(Modifier.weight(1f).padding(start = 20.vp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        }
        val pill = palette.successTone
        Row(
            Modifier.clip(CircleShape).background(pill.background).padding(horizontal = 24.vp, vertical = 14.vp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.vp),
        ) {
            Box(Modifier.size(14.vp).clip(CircleShape).background(palette.success))
            Text(stringResource(R.string.connected_pill, model), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold, color = pill.content, maxLines = 1)
        }
    }
}

/** What Home must not hide: the device is untested, a read is going on or failed, or changes wait for a decision. */
@Composable
private fun HomeNotices(connected: SessionState.Connected, dismissedUntested: Set<String>, actions: AppActions) {
    val hasOpenEntries = connected.openEntries.isNotEmpty()
    val model = connected.identity.model
    val untested = !connected.profile.verified && model !in dismissedUntested
    if (!untested && !hasOpenEntries && connected.problems.isEmpty()) return
    Column(Modifier.padding(horizontal = 6.vp), verticalArrangement = Arrangement.spacedBy(8.vp)) {
        if (untested) {
            Banner(stringResource(R.string.profile_unverified), Tone.WARNING, actionLabel = stringResource(R.string.profile_unverified_ok), onAction = { actions.dismissUntested(model) })
        }
        // The refresh shows on the button and on the numbers it changes, not as a message.
        ProblemBanners(connected, showRefreshing = false)
        if (hasOpenEntries) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.vp)) {
                Box(Modifier.weight(1f)) { Banner(stringResource(R.string.open_entries_banner, connected.openEntries.size), Tone.WARNING) }
                FocusButton(stringResource(R.string.review_resolve), onClick = actions::openReview)
            }
        }
    }
}

@Composable
private fun HomeCard(
    title: Int,
    fact: String?,
    icon: ImageVector,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    requestInitialFocus: Boolean = false,
) {
    val palette = TuneupTheme.colors
    val brush = if (primary) Brush.verticalGradient(listOf(Color(0xFF26303A), palette.surface)) else null
    FocusRow(
        modifier.fillMaxHeight(),
        onClick = onClick,
        requestInitialFocus = requestInitialFocus,
        kind = FocusKind.CARD,
        shape = RoundedCornerShape(32.vp),
        idleBackground = palette.surface,
        idleBrush = brush,
        contentPadding = 32.vp,
    ) {
        // Read inside the row: a focused card turns light and its content must follow.
        val inner = TuneupTheme.colors
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.vp)) {
            Box(
                Modifier.size(96.vp).clip(RoundedCornerShape(26.vp)).background(if (primary) inner.primary.background else inner.surfaceRaised),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = if (primary) inner.primary.content else inner.text, modifier = Modifier.size(48.vp)) }
            Column(verticalArrangement = Arrangement.spacedBy(4.vp)) {
                Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
                // The fact and its skeleton live in a box of the line's own height, so the card does not move when the number arrives.
                Box(Modifier.height(22.dp), contentAlignment = Alignment.CenterStart) {
                    if (fact != null) {
                        Text(fact, style = MaterialTheme.typography.bodyMedium, color = inner.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Skeleton(Modifier.width(220.dp).height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceStrip(connected: SessionState.Connected, runningApps: Int, actions: AppActions) {
    val snapshot = connected.snapshot
    Panel(Modifier.fillMaxWidth(), padding = 20.vp, radius = 28.vp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.vp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(40.vp), verticalAlignment = Alignment.CenterVertically) {
                Stat(if (connected.refreshing) null else snapshot.packages.size.toString(), stringResource(R.string.overview_packages))
                Stat(if (connected.refreshing) null else runningApps.toString(), stringResource(R.string.overview_running))
            }
            FocusButton(stringResource(R.string.action_refresh_title), onClick = { if (!connected.refreshing) actions.refresh() }, icon = Glyphs.Refresh, loading = connected.refreshing)
            FocusButton(stringResource(R.string.action_reconnect_title), onClick = actions::retry, icon = Glyphs.Plug)
        }
    }
}

@Composable
private fun Stat(value: String?, label: String, skeletonWidth: Dp = 56.dp) {
    Column {
        // The value and its skeleton live in a box of the line's own height: nothing around it moves when the number arrives.
        Box(Modifier.height(28.dp), contentAlignment = Alignment.CenterStart) {
            if (value != null) {
                Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Skeleton(Modifier.width(skeletonWidth).height(20.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = TuneupTheme.colors.muted, maxLines = 1)
    }
}

// endregion

// region labels

@Composable
internal fun categoryLabel(category: Category): String =
    stringResource(
        when (category) {
            Category.ESSENTIAL -> R.string.category_essential
            Category.UNINSTALLED_USER0 -> R.string.category_uninstalled
            Category.OVERLAY -> R.string.category_overlay
            Category.USER_INSTALLED -> R.string.category_user
            Category.PREINSTALLED_USER -> R.string.category_preinstalled
            Category.VENDOR_SERVICE -> R.string.category_vendor
            Category.GOOGLE_ANDROID_SERVICE -> R.string.category_google
        },
    )

@Composable
private fun essentialReasonLabel(reason: EssentialReason): String =
    stringResource(
        when (reason) {
            EssentialReason.PROFILE_PROTECTED -> R.string.essential_reason_profile
            EssentialReason.PERSISTENT -> R.string.essential_reason_persistent
            EssentialReason.SYSTEM_UID -> R.string.essential_reason_system_uid
            EssentialReason.HOME_LAUNCHER -> R.string.essential_reason_home
            EssentialReason.KEYBOARD -> R.string.essential_reason_keyboard
            EssentialReason.THIS_APP -> R.string.essential_reason_this_app
        },
    )

private val DISABLED_STATES: Set<Int> = setOf(2, 3, 4)

/** Memory column: the figure, or an en dash when nothing of the package is running. */
@Composable
private fun memoryText(memoryKb: Long): String = if (memoryKb > 0) stringResource(R.string.memory_mb, memoryKb / 1024) else "–"

private fun badgeKindFor(category: Category): BadgeKind =
    when (category) {
        Category.USER_INSTALLED -> BadgeKind.USER
        Category.ESSENTIAL -> BadgeKind.PROTECTED
        else -> BadgeKind.NEUTRAL
    }

/** A native daemon belongs to no app: Force stop has nothing to stop there, and the list says so. */
@Composable
private fun NotCloseableBadge() {
    Badge(stringResource(R.string.process_not_closeable), BadgeKind.DISABLED, compact = true)
}

/** "Running" with what it holds, in one pill: the same one for a package and for a single process. */
@Composable
private fun RunningBadge(memoryKb: Long, modifier: Modifier = Modifier, compact: Boolean = false) {
    Badge("● " + stringResource(R.string.status_running) + " · " + memoryText(memoryKb), BadgeKind.RUNNING, modifier, compact)
}

/** The category as a pill; the protected one carries a diamond so it stands out from the rest. */
@Composable
private fun CategoryBadge(category: Category, modifier: Modifier = Modifier, compact: Boolean = false) {
    val label = categoryLabel(category)
    Badge(if (category == Category.ESSENTIAL) "◆ $label" else label, badgeKindFor(category), modifier, compact)
}

/** The one status of a package: uninstalled, disabled, running (with its memory) or just enabled. The category says if it is protected. */
@Composable
private fun PackageStatusBadge(item: PackageItem, snapshot: DeviceSnapshot, modifier: Modifier = Modifier, compact: Boolean = false) {
    when {
        item.record.id in snapshot.uninstalledForUser0 -> Badge(stringResource(R.string.category_uninstalled), BadgeKind.DISABLED, modifier, compact)
        item.record.state?.enabled in DISABLED_STATES -> Badge(stringResource(R.string.tag_disabled), BadgeKind.DISABLED, modifier, compact)
        item.memoryKb > 0 -> RunningBadge(item.memoryKb, modifier, compact)
        else -> Badge(stringResource(R.string.info_enabled), BadgeKind.NEUTRAL, modifier, compact)
    }
}

// endregion

// region lists

private val TILE_HEIGHT = 350.vp
private val PID_WIDTH = 150.vp

/**
 * Items as rows or as a grid of tiles, with the list scrolling under the action bar. Nothing frames the list: a focused
 * row grows and glows, so the padding is what keeps that from being clipped.
 *
 * The initial focus belongs to the screen: [initialFocusKey] scrolls to that item and focuses it once, when the
 * screen opens. Rows never ask for focus themselves, or they would take it back every time they scroll into view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T> ItemsPanel(
    mode: ViewMode,
    listState: LazyListState,
    gridState: LazyGridState,
    items: List<T>,
    key: (T) -> Any,
    emptyTitle: String,
    emptyHint: String?,
    initialFocusKey: Any?,
    /** Raised when the list has to go back to the top; lowered here once it has. */
    scrollReset: MutableState<Boolean>,
    row: @Composable (T, FocusRequester?) -> Unit,
    tile: @Composable (T, FocusRequester?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialRequester = remember { FocusRequester() }
    LaunchedEffect(scrollReset.value) {
        if (scrollReset.value) {
            // Only the one on screen: a list state that was never laid out waits forever for its first layout, and would
            // keep the other one from ever being reset.
            if (mode == ViewMode.LIST) listState.scrollToItem(0) else gridState.scrollToItem(0)
            scrollReset.value = false
        }
    }
    // Kept from the first composition: once the screen has recomposed, the row must still be the one holding the requester.
    val startKey = remember { initialFocusKey }
    LaunchedEffect(Unit) {
        val index = if (startKey == null) -1 else items.indexOfFirst { key(it) == startKey }
        if (index >= 0) {
            if (mode == ViewMode.LIST) listState.scrollToItem(index) else gridState.scrollToItem(index)
            withFrameNanos { }
            try {
                initialRequester.requestFocus()
            } catch (notAttached: IllegalStateException) {
                // The row left the screen before it could take the focus: the D-pad simply starts from the action bar.
            }
        }
    }
    // A focused item grows and glows past its own bounds: scrolling has to leave that room, or it needs one more nudge each time.
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberFocusMarginSpec(FOCUS_SCROLL_MARGIN)) {
    Box(modifier.fillMaxWidth()) {
        if (items.isEmpty()) {
            EmptyState(emptyTitle, emptyHint, Modifier.align(Alignment.Center))
        } else if (mode == ViewMode.LIST) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 40.vp, vertical = 24.vp), verticalArrangement = Arrangement.spacedBy(8.vp)) {
                items(items, key = key) { row(it, if (key(it) == startKey) initialRequester else null) }
            }
        } else {
            LazyVerticalGrid(
                modifier = Modifier.fillMaxSize(),
                state = gridState,
                columns = GridCells.Fixed(6),
                contentPadding = PaddingValues(horizontal = 32.vp, vertical = 24.vp),
                horizontalArrangement = Arrangement.spacedBy(24.vp),
                verticalArrangement = Arrangement.spacedBy(24.vp),
            ) {
                gridItems(items, key = key) { tile(it, if (key(it) == startKey) initialRequester else null) }
            }
        }
    }
    }
}

private val FOCUS_SCROLL_MARGIN = 14.dp

/**
 * Scrolls only as far as needed to bring the focused item fully into view, plus [margin] on each side. The default
 * stops with the item's edge flush against the list's edge, and a focused item grows past that edge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberFocusMarginSpec(margin: androidx.compose.ui.unit.Dp): BringIntoViewSpec {
    val marginPx = with(LocalDensity.current) { margin.toPx() }
    return remember(marginPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val trailing = offset + size
                return when {
                    // Taller than the room it has: show its start.
                    size + 2 * marginPx > containerSize -> offset - marginPx
                    offset < marginPx -> offset - marginPx
                    trailing > containerSize - marginPx -> trailing - (containerSize - marginPx)
                    else -> 0f
                }
            }
        }
    }
}

/**
 * A new sort, view, visibility, category or search starts the list from the top. This only raises the flag: the list
 * itself scrolls, because it is not on screen while Settings is open (which is when most of these change) and a list
 * that is not on screen cannot be scrolled.
 */
@Composable
private fun rememberScrollResetFlag(filter: ListFilter, viewMode: ViewMode): MutableState<Boolean> {
    val pending = remember { mutableStateOf(false) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(filter, viewMode) {
        // The first run is the screen opening: it keeps the scroll it came back with.
        if (first) first = false else pending.value = true
    }
    return pending
}

/** What a list says when it has nothing to show: no matches for the search, or nothing at all with the current settings. */
@Composable
private fun emptyTitle(filter: ListFilter, nothingAtAll: Int): String =
    if (filter.query.isNotBlank()) stringResource(R.string.empty_no_matches, filter.query.trim()) else stringResource(nothingAtAll)

@Composable
private fun emptyHint(filter: ListFilter): String =
    stringResource(if (filter.query.isNotBlank()) R.string.empty_try_search else R.string.empty_try_settings)

/** How many filters are changing the list from its default. */
private fun activeFilters(filter: ListFilter): Int = (if (filter.showAll) 1 else 0) + filter.categories.size + (if (filter.sort != ListFilter().sort) 1 else 0)

/**
 * The list screens keep one row of actions. Everything that shapes the list (sort, Show all, categories, the view)
 * lives behind Filters: while it is open it replaces the list, and Back closes it and returns to its button.
 */
@Composable
private fun ColumnScope.FiltersHost(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    filter: ListFilter,
    viewMode: ViewMode,
    actions: AppActions,
    forProcesses: Boolean = false,
    content: @Composable ColumnScope.(openFilters: () -> Unit, filtersFocus: FocusRequester) -> Unit,
) {
    var returning by remember { mutableStateOf(false) }
    val filtersFocus = remember { FocusRequester() }
    val close = {
        onOpenChange(false)
        returning = true
    }
    BackHandler(enabled = open, onBack = close)
    if (open) {
        FiltersView(filter, viewMode, actions, forProcesses)
    } else {
        if (returning) {
            LaunchedEffect(Unit) {
                withFrameNanos { }
                try {
                    filtersFocus.requestFocus()
                } catch (notAttached: IllegalStateException) {
                    // The button is not on screen any more: nothing to return the focus to.
                }
                returning = false
            }
        }
        content({ onOpenChange(true) }, filtersFocus)
    }
}

@Composable
private fun filtersLabel(filter: ListFilter): String {
    val active = activeFilters(filter)
    return if (active == 0) stringResource(R.string.filters) else stringResource(R.string.filters_count, active)
}

/** The filters as a short settings-like screen: the list is not shown, but the header keeps counting what would be. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.FiltersView(filter: ListFilter, viewMode: ViewMode, actions: AppActions, forProcesses: Boolean) {
    // Two columns so everything fits without scrolling: how the list is ordered and shown, and what it includes.
    Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 40.vp, vertical = 16.vp), horizontalArrangement = Arrangement.spacedBy(40.vp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.vp)) {
            SectionTitle(stringResource(R.string.sort_by), Modifier.padding(top = 0.vp))
            // Processes do not show a category, so they cannot be ordered by one.
            val sorts = listOf(SortOrder.MEMORY to R.string.sort_memory, SortOrder.NAME to R.string.sort_name, SortOrder.CATEGORY to R.string.sort_category)
            sorts.filter { !forProcesses || it.first != SortOrder.CATEGORY }.forEach { (order, label) ->
                ChoiceRow(stringResource(label), null, filter.sort == order, { actions.updateFilter { it.copy(sort = order) } }, requestInitialFocus = order == filter.sort)
            }
            SectionTitle(stringResource(R.string.filters_view))
            ChoiceRow(stringResource(R.string.view_list), null, viewMode == ViewMode.LIST, { actions.setViewMode(ViewMode.LIST) })
            ChoiceRow(stringResource(R.string.view_grid), null, viewMode == ViewMode.GRID, { actions.setViewMode(ViewMode.GRID) })
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.vp)) {
            SectionTitle(stringResource(R.string.filters_visibility), Modifier.padding(top = 0.vp))
            FocusToggleRow(
                stringResource(if (forProcesses) R.string.filters_include_processes else R.string.filters_include_apps),
                filter.showAll,
                // The line says what the switch is doing right now, so it changes with it.
                supporting = stringResource(
                    when {
                        forProcesses && filter.showAll -> R.string.filters_include_processes_on
                        forProcesses -> R.string.filters_include_processes_off
                        filter.showAll -> R.string.filters_include_apps_on
                        else -> R.string.filters_include_apps_off
                    },
                ),
                onToggle = { actions.updateFilter { it.copy(showAll = !it.showAll) } },
            )
            if (filter.showAll) {
                SectionTitle(stringResource(R.string.filters_categories))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.vp), verticalArrangement = Arrangement.spacedBy(12.vp)) {
                    // A running process never belongs to an overlay or an uninstalled package, and protected ones are not listed.
                    val unlisted = setOf(Category.ESSENTIAL, Category.OVERLAY, Category.UNINSTALLED_USER0)
                    Category.DISPLAY_ORDER.filter { !forProcesses || it !in unlisted }.forEach { category ->
                        FocusChip(categoryLabel(category), selected = category in filter.categories, onClick = {
                            actions.updateFilter { it.copy(categories = if (category in it.categories) it.categories - category else it.categories + category) }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier.padding(top = 14.vp)) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = TuneupTheme.colors.muted, modifier = modifier.padding(start = 6.vp))
}

// endregion

// region packages

@Composable
internal fun SelectBox(selected: Boolean) {
    val palette = TuneupTheme.colors
    val shape = RoundedCornerShape(12.vp)
    Box(
        Modifier.size(52.vp).clip(shape).background(if (selected) palette.accent else palette.surfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Glyphs.Check, contentDescription = null, tint = palette.onAccent, modifier = Modifier.size(32.vp))
    }
}

/** The one row of actions of the packages list: start choosing, search, filters. While choosing: what can be done with the choice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackagesActionBar(
    state: AppUiState,
    shownIds: List<String>,
    initialFocus: Boolean,
    openFilters: () -> Unit,
    filtersFocus: FocusRequester,
    actions: AppActions,
) {
    val filter = state.filter
    if (state.selecting) {
        val count = state.selection.size
        val allSelected = shownIds.isNotEmpty() && state.selection.containsAll(shownIds)
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 6.vp), horizontalArrangement = Arrangement.spacedBy(16.vp), verticalArrangement = Arrangement.spacedBy(8.vp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.select_summary, count, shownIds.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(end = 8.vp))
            // The three changes are disabled and skipped by the D-pad with nothing selected; this button takes the focus.
            if (allSelected) {
                FocusButton(stringResource(R.string.select_clear), onClick = actions::clearSelection, requestInitialFocus = initialFocus)
            } else {
                FocusButton(stringResource(R.string.select_all_shown), onClick = { actions.selectAll(shownIds) }, requestInitialFocus = initialFocus)
            }
            FocusButton(stringResource(R.string.batch_disable, count), onClick = { actions.askBatch(ActionKind.DISABLE) }, enabled = count > 0, style = ButtonStyle.DANGER)
            FocusButton(stringResource(R.string.batch_restore, count), onClick = { actions.askBatch(ActionKind.RESTORE) }, enabled = count > 0)
            FocusButton(stringResource(R.string.batch_force_stop, count), onClick = { actions.askBatch(ActionKind.FORCE_STOP) }, enabled = count > 0)
            FocusButton(stringResource(R.string.batch_uninstall, count), onClick = { actions.askBatch(ActionKind.UNINSTALL) }, enabled = count > 0, style = ButtonStyle.DANGER)
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.vp), horizontalArrangement = Arrangement.spacedBy(16.vp), verticalAlignment = Alignment.CenterVertically) {
            FocusButton(stringResource(R.string.select_start), onClick = actions::startSelecting, requestInitialFocus = initialFocus)
            TextEntryField(filter.query, { value -> actions.updateFilter { it.copy(query = value) } }, stringResource(R.string.search_hint), Modifier.weight(1f))
            FocusButton(filtersLabel(filter), onClick = openFilters, modifier = Modifier.focusRequester(filtersFocus))
        }
    }
}

@Composable
private fun PackagesScreen(state: AppUiState, connected: SessionState.Connected, actions: AppActions, listState: LazyListState, gridState: LazyGridState) {
    val snapshot = connected.snapshot
    val items = remember(snapshot, connected.classifications, connected.profile) { PackageList.items(snapshot, connected.classifications, connected.profile) }
    val filter = state.filter
    val visible = remember(items, filter) { PackageList.filterPackages(items, filter) }
    val subtitle = when {
        state.selecting -> stringResource(R.string.select_subtitle)
        // Nothing to count: the blank state in the list says it, so the header does not say "0".
        visible.isEmpty() -> null
        filter.query.isNotBlank() -> stringResource(R.string.matches_shown, visible.size)
        filter.showAll -> stringResource(R.string.packages_shown_all, visible.size)
        else -> stringResource(R.string.packages_shown, visible.size)
    }
    // Coming back from a package's page, the focus returns to that package's row; a fresh visit starts on the first action.
    val returnKey = state.selectedPackage?.takeIf { id -> visible.any { it.record.id == id } }
    // Only the first composition of the screen places the initial focus: closing Filters must not run it again.
    var visited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visited = true }
    val placeInitialFocus = !visited
    val scrollReset = rememberScrollResetFlag(filter, state.viewMode)
    PrefetchAppVisuals(remember(visible) { visible.map { it.record.id } })
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val title = when {
        filtersOpen -> stringResource(R.string.packages) + " - " + stringResource(R.string.filters)
        state.selecting -> stringResource(R.string.select_title)
        else -> stringResource(R.string.packages)
    }
    InnerScreen(connected, title, subtitle) {
        FiltersHost(filtersOpen, { filtersOpen = it }, filter, state.viewMode, actions) { openFilters, filtersFocus ->
            PackagesActionBar(state, visible.map { it.record.id }, placeInitialFocus && returnKey == null, openFilters, filtersFocus, actions)
            ItemsPanel(
                mode = state.viewMode,
                listState = listState,
                gridState = gridState,
                items = visible,
                key = { it.record.id },
                emptyTitle = emptyTitle(filter, R.string.empty_no_apps),
                emptyHint = emptyHint(filter),
                scrollReset = scrollReset,
                initialFocusKey = if (placeInitialFocus) returnKey else null,
                row = { item, focusRequester ->
                    PackageRow(
                        item,
                        snapshot,
                        selecting = state.selecting,
                        selected = item.record.id in state.selection,
                        focusRequester = focusRequester,
                        onClick = { if (state.selecting) actions.toggleSelected(item.record.id) else actions.openPackage(item.record.id) },
                    )
                },
                tile = { item, focusRequester ->
                    PackageTile(
                        item,
                        snapshot,
                        selecting = state.selecting,
                        selected = item.record.id in state.selection,
                        focusRequester = focusRequester,
                        onClick = { if (state.selecting) actions.toggleSelected(item.record.id) else actions.openPackage(item.record.id) },
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PackageRow(item: PackageItem, snapshot: DeviceSnapshot, selecting: Boolean, selected: Boolean, focusRequester: FocusRequester?, onClick: () -> Unit) {
    val visual = rememberAppVisual(item.record.id)
    val showId = visual.label != item.record.id
    FocusRow(
        onClick = onClick,
        focusRequester = focusRequester,
        selected = selected,
        idleBackground = TuneupTheme.colors.surface,
        contentPadding = 20.vp,
        minHeight = 120.vp,
    ) {
        val palette = TuneupTheme.colors
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.vp)) {
            if (selecting) SelectBox(selected)
            AppIconImage(visual, 72.vp)
            Column(Modifier.weight(1f)) {
                if (visual.loaded) {
                    Text(visual.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (showId) Text(item.record.id, style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    NameSkeleton()
                }
            }
            CategoryBadge(item.classification.category, Modifier.widthIn(max = 340.vp), compact = true)
            PackageStatusBadge(item, snapshot, compact = true)
        }
    }
}

/** The name and the id of a row while they are being read: two bars where the two lines will be. */
@Composable
private fun NameSkeleton() {
    // The height of the two real lines (a 24 dp title and a 20 dp subtitle), so the row does not grow when the name arrives.
    Column(Modifier.height(44.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        Skeleton(Modifier.width(260.dp).height(16.dp))
        Skeleton(Modifier.width(180.dp).height(12.dp))
    }
}

/** A compact tile for the grid: the icon, the name and one fact. */
@Composable
private fun Tile(
    visual: AppVisual,
    name: String,
    extra: @Composable ColumnScope.() -> Unit,
    selecting: Boolean,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: (() -> Unit)?,
) {
    FocusRow(
        // One height for every tile: whatever a name or a pill needs, no card is taller than the one beside it.
        Modifier.height(TILE_HEIGHT),
        onClick = onClick,
        focusRequester = focusRequester,
        kind = FocusKind.CARD,
        selected = selected,
        shape = RoundedCornerShape(24.vp),
        idleBackground = TuneupTheme.colors.surface,
        contentPadding = 12.vp,
        minHeight = TILE_HEIGHT,
    ) {
        val palette = TuneupTheme.colors
        // The icon and the name at the top, the pills glued to the bottom: they line up from tile to tile.
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
          Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.vp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AppIconImage(visual, 120.vp)
                if (selecting) Box(Modifier.align(Alignment.TopEnd)) { SelectBox(selected) }
            }
            // Always two lines of height, so a long name does not make its card taller than the one beside it.
            if (visual.loaded) {
                Text(name, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), fontWeight = FontWeight.Bold, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            } else {
                Column(Modifier.fillMaxWidth().height(44.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                    Skeleton(Modifier.fillMaxWidth(0.85f).height(14.dp))
                    Skeleton(Modifier.fillMaxWidth(0.55f).height(14.dp))
                }
            }
          }
          Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.vp)) { extra() }
        }
    }
}

@Composable
private fun PackageTile(item: PackageItem, snapshot: DeviceSnapshot, selecting: Boolean, selected: Boolean, focusRequester: FocusRequester?, onClick: () -> Unit) {
    val visual = rememberAppVisual(item.record.id)
    val category = item.classification.category
    Tile(
        visual,
        visual.label,
        {
            // The same pills as the list: the category and the state, which says how much memory it holds when it is running.
            CategoryBadge(category, compact = true)
            PackageStatusBadge(item, snapshot, compact = true)
        },
        selecting,
        selected,
        focusRequester,
        onClick,
    )
}

// endregion

// region package detail

/** One block of the page: a quiet title, then its content. Blocks are told apart by the space between them. */
@Composable
private fun DetailSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.vp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = TuneupTheme.colors.muted)
        content()
    }
}

/** The space between two blocks of the page. */
private val SECTION_GAP = 48.vp

/** One fact of the page: a quiet label over its value. No box around it. */
@Composable
private fun Fact(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = subtitleStyle(), fontWeight = FontWeight.Bold, color = TuneupTheme.colors.muted)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A read-only line of the side panel: it explains, it is never a D-pad stop. */
@Composable
private fun SideInfo(title: String, subtitle: String?, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f).padding(end = 12.vp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, style = subtitleStyle(), color = TuneupTheme.colors.muted)
        }
        trailing?.invoke()
    }
}

@Composable
private fun PackageDetailScreen(state: AppUiState, connected: SessionState.Connected, actions: AppActions) {
    val snapshot = connected.snapshot
    val packageId = state.selectedPackage
    val item = remember(snapshot, connected.classifications, connected.profile, packageId) {
        packageId?.let { PackageList.items(snapshot, connected.classifications, connected.profile).firstOrNull { it.record.id == packageId } }
    }
    if (packageId == null || item == null) {
        // The package is gone (an app removed for good): nothing is left to show here, and a blank screen is a dead end.
        LaunchedEffect(packageId) { actions.back() }
        return
    }
    val palette = TuneupTheme.colors
    val classification = item.classification
    val record = item.record
    val visual = rememberAppVisual(record.id)
    // What the last read allows. The real check runs again, on a live read, before anything is sent.
    val live = LivePackage(record, classification.essentialReason)
    val allowedActions = ActionPlanner.availableActions(live, isRunning = item.memoryKb > 0)
    val blockedReason = ActionPlanner.disableBlockedReason(live)
    val disabled = record.id in snapshot.uninstalledForUser0 || record.state?.enabled in DISABLED_STATES
    val subtitle = stringResource(
        when {
            classification.essentialReason != null -> R.string.detail_sub_protected
            item.memoryKb > 0 -> R.string.detail_sub_running
            disabled -> R.string.detail_sub_disabled
            else -> R.string.detail_sub_normal
        },
    )
    var changingCategory by rememberSaveable { mutableStateOf(false) }
    InnerScreen(connected, visual.label, subtitle) {
        Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.vp, vertical = 6.vp), horizontalArrangement = Arrangement.spacedBy(24.vp)) {
            Panel(Modifier.weight(1.4f).fillMaxHeight(), scrollable = true) {
                // The name is the title of the screen: here the icon, the id and the two pills that say what the app is.
                Row(horizontalArrangement = Arrangement.spacedBy(24.vp), verticalAlignment = Alignment.Top) {
                    AppIconImage(visual, 112.vp)
                    Column(verticalArrangement = Arrangement.spacedBy(10.vp)) {
                        // The id starts at the icon's top edge; the text box has a little air above its letters, so it is pulled up.
                        Text(record.id, style = subtitleStyle(), color = palette.muted, modifier = Modifier.offset(y = (-3).dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.vp)) {
                            CategoryBadge(classification.category, compact = true)
                            PackageStatusBadge(item, snapshot, compact = true)
                        }
                    }
                }
                Spacer(Modifier.height(SECTION_GAP))
                if (allowedActions.isEmpty()) {
                    val protectedReason = classification.essentialReason
                    if (protectedReason != null) {
                        ProtectedBlock(essentialReasonLabel(protectedReason))
                    } else if (blockedReason != null) {
                        MutedNote(rejectReasonLabel(blockedReason), textStyle = subtitleStyle())
                    }
                } else {
                    DetailSection(stringResource(R.string.detail_available_actions)) {
                        ActionButtons(packageId, allowedActions, blockedReason, actions)
                    }
                }
                Spacer(Modifier.height(SECTION_GAP))
                DetailSection(stringResource(R.string.detail_facts_title)) {
                    DetailFacts(item, snapshot, disabled)
                }
                if (item.commonlyDisabled) {
                    Spacer(Modifier.height(SECTION_GAP))
                    InfoNote(stringResource(R.string.detail_hint), textStyle = subtitleStyle())
                }
            }
            Panel(Modifier.weight(0.8f).fillMaxHeight(), scrollable = true) {
                Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
                  DetailSection(stringResource(R.string.classification_title)) {
                    SideInfo(
                        categoryLabel(classification.category),
                        if (classification.isOverridden) stringResource(R.string.detail_overridden, categoryLabel(classification.automatic)) else stringResource(R.string.classification_automatic),
                    )
                  }
                  DetailSection(stringResource(R.string.protection_title)) {
                    if (classification.essentialReason != null) {
                        SideInfo(stringResource(R.string.category_essential), stringResource(R.string.protection_protected)) { Badge(stringResource(R.string.protection_protected), BadgeKind.PROTECTED) }
                    } else if (allowedActions.isEmpty() && blockedReason != null) {
                        SideInfo(stringResource(R.string.protection_not_protected), rejectReasonLabel(blockedReason))
                    } else {
                        SideInfo(stringResource(R.string.protection_not_protected), stringResource(R.string.protection_not_protected_sub)) {
                            Badge(stringResource(R.string.protection_allowed), BadgeKind.SUCCESS)
                        }
                    }
                  }
                    if (classification.essentialReason == null) {
                        // Only categories a person would pick. Uninstalled and Overlay are facts about the package, not labels to choose;
                        // one of them still shows when it is the automatic one, so a choice made by the user can always be undone.
                        val pickable = Category.DISPLAY_ORDER.filter { it != Category.ESSENTIAL && it != Category.UNINSTALLED_USER0 && it != Category.OVERLAY }
                        val overrideChoices = if (classification.automatic in pickable || classification.automatic == Category.ESSENTIAL) pickable else pickable + classification.automatic
                        FocusRow(
                            onClick = { changingCategory = !changingCategory },
                            // With nothing to press on the page, this is the one useful stop.
                            requestInitialFocus = allowedActions.isEmpty(),
                            kind = FocusKind.CONTROL,
                            idleBackground = palette.surfaceRaised,
                            contentPadding = 20.vp,
                            minHeight = 96.vp,
                        ) {
                            val inner = TuneupTheme.colors
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.change_category), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text(stringResource(R.string.change_category_sub, overrideChoices.size + 1), style = subtitleStyle(), color = inner.muted)
                                }
                                Icon(Glyphs.Chevron, contentDescription = null, tint = inner.muted, modifier = Modifier.size(32.vp).rotate(if (changingCategory) 90f else 0f))
                            }
                        }
                        if (changingCategory) {
                          // One block, so the options sit close together instead of a section gap apart.
                          Column(verticalArrangement = Arrangement.spacedBy(4.vp)) {
                            // The automatic category is one of the options, marked as such: choosing it gives the choice back to the app.
                            overrideChoices.forEach { category ->
                                val isAutomatic = category == classification.automatic
                                CategoryChoice(
                                    if (isAutomatic) stringResource(R.string.category_option_automatic, categoryLabel(category)) else categoryLabel(category),
                                    selected = classification.category == category,
                                    onClick = { actions.setOverride(record.id, if (isAutomatic) null else category) },
                                )
                            }
                          }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    // Options are the quietest text of the page: 14 sp, under the 16 of the "Change category" row that opens them.
    ChoiceRow(label, null, selected, onClick, minHeight = 80.vp, contentPadding = 14.vp, checkSize = 36.vp, titleStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp))
}

@Composable
private fun DetailFacts(item: PackageItem, snapshot: DeviceSnapshot, disabled: Boolean) {
    val record = item.record
    val yes = stringResource(R.string.info_yes)
    val no = stringResource(R.string.info_no)
    val reason = item.classification.essentialReason
    val state = when {
        record.id in snapshot.uninstalledForUser0 -> stringResource(R.string.category_uninstalled)
        disabled -> stringResource(R.string.tag_disabled)
        else -> stringResource(R.string.info_enabled)
    }
    val lastLabel = stringResource(if (reason != null) R.string.info_reason else R.string.info_hint)
    val lastValue = when {
        reason != null -> essentialReasonLabel(reason)
        item.commonlyDisabled -> stringResource(R.string.badge_hint)
        else -> stringResource(R.string.info_no_hint)
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.vp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.vp)) {
            Fact(stringResource(R.string.info_memory), if (item.memoryKb > 0) stringResource(R.string.memory_mb, item.memoryKb / 1024) else stringResource(R.string.detail_not_running), Modifier.weight(1f))
            Fact(stringResource(R.string.info_installer), record.installer ?: stringResource(R.string.info_unknown), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.vp)) {
            Fact(stringResource(R.string.info_system), if (record.isSystem) yes else no, Modifier.weight(1f))
            Fact(stringResource(R.string.info_state), state, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.vp)) {
            Fact(stringResource(R.string.info_persistent), if (record.isPersistent) yes else no, Modifier.weight(1f))
            Fact(lastLabel, lastValue, Modifier.weight(1f))
        }
    }
}

/** Why there are no buttons: this package is protected, and the page says so instead of leaving a blank. */
@Composable
private fun ProtectedBlock(reason: String) {
    val tone = TuneupTheme.colors.protectedBadge
    val shape = RoundedCornerShape(24.vp)
    Column(Modifier.fillMaxWidth().clip(shape).background(tone.background).padding(24.vp), verticalArrangement = Arrangement.spacedBy(6.vp)) {
        Text(stringResource(R.string.essential_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = tone.content)
        Text(reason, style = subtitleStyle(), color = tone.content)
        Text(stringResource(R.string.essential_note), style = subtitleStyle(), color = tone.content)
    }
}

// endregion

// region processes

@Composable
private fun ProcessesScreen(state: AppUiState, connected: SessionState.Connected, actions: AppActions, listState: LazyListState, gridState: LazyGridState) {
    val snapshot = connected.snapshot
    val items = remember(snapshot, connected.classifications) { PackageList.processItems(snapshot, connected.classifications) }
    val filter = state.filter
    val visible = remember(items, filter) { PackageList.filterProcesses(items, filter) }
    // Processes and apps are not the same count: an app can run several, and Force stop all stops apps.
    val closableApps = remember(snapshot, connected.classifications, filter) { ForceCloseAll.shown(snapshot, connected.classifications, filter).size }
    val subtitle = if (visible.isEmpty()) null else stringResource(R.string.processes_shown_apps, visible.size, closableApps)
    var visited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visited = true }
    val scrollReset = rememberScrollResetFlag(filter, state.viewMode)
    PrefetchAppVisuals(remember(visible) { visible.mapNotNull { it.packageId }.distinct() })
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.processes) + if (filtersOpen) " - " + stringResource(R.string.filters) else ""
    InnerScreen(connected, title, subtitle) {
        FiltersHost(filtersOpen, { filtersOpen = it }, filter, state.viewMode, actions, forProcesses = true) { openFilters, filtersFocus ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.vp), horizontalArrangement = Arrangement.spacedBy(16.vp), verticalAlignment = Alignment.CenterVertically) {
                FocusButton(stringResource(R.string.force_stop_all), onClick = actions::askCloseAll, enabled = visible.isNotEmpty(), style = ButtonStyle.DANGER)
                TextEntryField(filter.query, { value -> actions.updateFilter { it.copy(query = value) } }, stringResource(R.string.search_processes_hint), Modifier.weight(1f))
                FocusButton(filtersLabel(filter), onClick = openFilters, modifier = Modifier.focusRequester(filtersFocus))
            }
            ItemsPanel(
                mode = state.viewMode,
                listState = listState,
                gridState = gridState,
                items = visible,
                key = { it.process.pid },
                emptyTitle = emptyTitle(filter, R.string.empty_no_processes),
                emptyHint = emptyHint(filter),
                // The list starts on its first row (or on the process of the package just closed), not on the button that stops everything.
                scrollReset = scrollReset,
                // A native daemon has no package (null), which must not match the "no package selected" of a fresh visit.
                initialFocusKey = if (visited) null else (state.selectedPackage?.let { selected -> visible.firstOrNull { it.packageId == selected } } ?: visible.firstOrNull())?.process?.pid,
                row = { item, focusRequester -> ProcessRow(item, focusRequester, openPackageOf(item, actions)) },
                tile = { item, focusRequester -> ProcessTile(item, focusRequester, openPackageOf(item, actions)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** OK on a process opens the page of its package, the same one Packages opens. A native daemon has no package: it only takes focus. */
private fun openPackageOf(item: ProcessItem, actions: AppActions): (() -> Unit)? =
    item.packageId?.let { packageId -> { actions.openPackage(packageId) } }

@Composable
private fun ProcessVisual(item: ProcessItem): AppVisual =
    // A native daemon belongs to no package: no icon, and its process name stands in for the app name.
    if (item.packageId != null) rememberAppVisual(item.packageId) else AppVisual(item.process.name, null)

@Composable
private fun ProcessRow(item: ProcessItem, focusRequester: FocusRequester?, onClick: (() -> Unit)?) {
    val visual = ProcessVisual(item)
    val support = item.packageId ?: stringResource(R.string.process_no_package)
    FocusRow(onClick = onClick, focusRequester = focusRequester, idleBackground = TuneupTheme.colors.surface, contentPadding = 20.vp, minHeight = 120.vp) {
        val palette = TuneupTheme.colors
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.vp)) {
            AppIconImage(visual, 72.vp)
            Column(Modifier.weight(1f)) {
                if (visual.loaded) {
                    Text(displayName(visual, item.process.name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(support, style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    NameSkeleton()
                }
            }
            if (item.packageId == null) NotCloseableBadge()
            RunningBadge(item.process.rssKb, compact = true)
            Text(stringResource(R.string.pid_label, item.process.pid), style = MaterialTheme.typography.bodySmall, color = palette.muted, textAlign = TextAlign.End, modifier = Modifier.width(PID_WIDTH))
        }
    }
}

@Composable
private fun ProcessTile(item: ProcessItem, focusRequester: FocusRequester?, onClick: (() -> Unit)?) {
    val visual = ProcessVisual(item)
    Tile(visual, visual.label, {
        if (item.packageId == null) NotCloseableBadge()
        RunningBadge(item.process.rssKb, compact = true)
    }, selecting = false, selected = false, focusRequester = focusRequester, onClick = onClick)
}

// endregion

// region settings

/** One choice among several: a title, a line of help and a check on the one in use. */
@Composable
internal fun ChoiceRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    requestInitialFocus: Boolean = false,
    minHeight: androidx.compose.ui.unit.Dp = 96.vp,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    contentPadding: androidx.compose.ui.unit.Dp = 20.vp,
    checkSize: androidx.compose.ui.unit.Dp = 48.vp,
) {
    FocusRow(
        modifier,
        onClick = onClick,
        requestInitialFocus = requestInitialFocus,
        kind = FocusKind.CONTROL,
        idleBackground = TuneupTheme.colors.surface,
        contentPadding = contentPadding,
        minHeight = minHeight,
    ) {
        val palette = TuneupTheme.colors
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = titleStyle, fontWeight = FontWeight.Bold)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = palette.muted)
            }
            // The place of the check is always there, empty or not: choosing an option must not change the height of its row.
            Box(Modifier.size(checkSize), contentAlignment = Alignment.Center) {
                if (selected) {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                        Icon(Glyphs.Check, contentDescription = null, tint = palette.onAccent, modifier = Modifier.size(checkSize * 0.58f))
                    }
                }
            }
        }
    }
}

/** Settings as one centered list, like the TV's own: a section, then its rows. */
@Composable
private fun SettingsScreen(state: AppUiState, connected: SessionState.Connected, actions: AppActions) {
    val palette = TuneupTheme.colors
    InnerScreen(
        connected,
        stringResource(R.string.settings),
        stringResource(R.string.settings_subtitle),
        trailing = { if (!state.settingsSaveFailed) StatusDot(stringResource(R.string.settings_saved)) },
    ) {
        if (state.settingsSaveFailed) Banner(stringResource(R.string.settings_not_saved), Tone.DANGER, Modifier.padding(horizontal = 6.vp))
        Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 40.vp, vertical = 16.vp), horizontalArrangement = Arrangement.spacedBy(40.vp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.vp)) {
                Text(stringResource(R.string.language), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.language_hint), style = subtitleStyle(), color = palette.muted, modifier = Modifier.padding(bottom = 6.vp))
                AppLanguage.entries.forEach { option ->
                    val subtitle = when (option) {
                        AppLanguage.SYSTEM -> stringResource(R.string.language_system_sub)
                        AppLanguage.ENGLISH -> stringResource(R.string.language_english_sub)
                        AppLanguage.SPANISH -> stringResource(R.string.language_spanish_sub)
                    }
                    ChoiceRow(stringResource(languageLabel(option)), subtitle, option == state.language, { actions.setLanguage(option) }, requestInitialFocus = option == state.language)
                }
            }
            // The right column is kept for what comes next: the language stays on the left half.
            Spacer(Modifier.weight(1f))
        }
    }
}

// endregion
