package com.prototype.tuneupfortv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp

private val CONTROL_SHAPE = RoundedCornerShape(24.vp)
private val ROW_SHAPE = RoundedCornerShape(24.vp)
private const val DISABLED_ALPHA: Float = 0.38f

/** Where a focusable control takes the D-pad when its screen opens: the most likely or the safest action. */
@Composable
private fun rememberInitialFocus(requestInitialFocus: Boolean, enabled: Boolean = true): FocusRequester {
    val focusRequester = remember { FocusRequester() }
    if (requestInitialFocus) {
        // Only at the first composition: a control that becomes enabled later must not steal the focus from the user.
        LaunchedEffect(Unit) { if (enabled) focusRequester.requestFocus() }
    }
    return focusRequester
}

enum class ButtonStyle { PRIMARY, SECONDARY, DANGER, DONE }

private val FOCUSED_CONTENT = Color(0xFF071015)
private val FOCUSED_DANGER_CONTAINER = Color(0xFFFFD6D6)
private val FOCUSED_DANGER_CONTENT = Color(0xFF7A1D1D)

/** What is inside a focused element sits on near white, so it reads its colors from the inverted palette. */
@Composable
internal fun FocusedPalette(focused: Boolean, content: @Composable () -> Unit) {
    val palette = if (focused) InvertedTuneupColors else TuneupTheme.colors
    CompositionLocalProvider(LocalTuneupColors provides palette, LocalContentColor provides palette.text, content = content)
}

@Composable
fun FocusButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Gives the button initial focus, so a TV remote has somewhere to start. */
    requestInitialFocus: Boolean = false,
    style: ButtonStyle = ButtonStyle.SECONDARY,
    icon: ImageVector? = null,
    minHeight: Dp = 80.vp,
    /** A spinner takes the place of the icon: the button stays where it is, so the D-pad keeps its focus. */
    loading: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val focusRequester = rememberInitialFocus(requestInitialFocus, enabled)
    val palette = TuneupTheme.colors
    val tone = when (style) {
        ButtonStyle.PRIMARY -> palette.primary
        ButtonStyle.SECONDARY -> palette.secondary
        ButtonStyle.DANGER -> palette.dangerButton
        ButtonStyle.DONE -> palette.done
    }
    val background = when {
        focused && style == ButtonStyle.DANGER -> FOCUSED_DANGER_CONTAINER
        focused -> palette.text
        else -> tone.background
    }
    val content = when {
        focused && style == ButtonStyle.DANGER -> FOCUSED_DANGER_CONTENT
        focused -> FOCUSED_CONTENT
        else -> tone.content
    }
    // A disabled button is not clickable, so the D-pad skips it: it can never be the target of a stray OK press.
    Row(
        modifier = modifier
            .heightIn(min = minHeight)
            .focusRequester(focusRequester)
            .tvFocus(source, CONTROL_SHAPE, FocusKind.CONTROL)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(CONTROL_SHAPE)
            .background(background)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 32.vp),
        horizontalArrangement = Arrangement.spacedBy(12.vp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(32.vp), color = content, strokeWidth = 4.vp)
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(32.vp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/**
 * A list row that takes D-pad focus. With [onClick] it is activated by center/enter or a tap;
 * without it, it is only focusable so the D-pad can still scroll through a list.
 * Focused, it turns to near white: its content must read [TuneupTheme] inside the lambda, not before it.
 */
@Composable
fun FocusRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /** Takes the focus when the row first appears. Lists use [focusRequester] instead, once per screen. */
    requestInitialFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    kind: FocusKind = FocusKind.ROW,
    selected: Boolean = false,
    shape: RoundedCornerShape = ROW_SHAPE,
    idleBackground: Color = Color.Transparent,
    /** Paints over [idleBackground] while the row is not focused: the one primary card of Home uses it. */
    idleBrush: Brush? = null,
    contentPadding: Dp = 24.vp,
    minHeight: Dp = 96.vp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val ownRequester = rememberInitialFocus(requestInitialFocus)
    val focused by source.collectIsFocusedAsState()
    val palette = TuneupTheme.colors
    val interactive = if (onClick != null) {
        Modifier.clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
    } else {
        Modifier.focusable(interactionSource = source)
    }
    val background = when {
        focused -> palette.text
        selected -> palette.surfaceSelected
        else -> idleBackground
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .focusRequester(focusRequester ?: ownRequester)
            .tvFocus(source, shape, kind)
            .clip(shape)
            .background(background)
            .then(if (idleBrush != null && !focused && !selected) Modifier.background(idleBrush, shape) else Modifier)
            .then(if (selected && !focused) Modifier.border(1.vp, palette.accent.copy(alpha = 0.4f), shape) else Modifier)
            .then(interactive)
            .padding(contentPadding),
        verticalArrangement = Arrangement.Center,
    ) {
        val scope = this
        FocusedPalette(focused) { scope.content() }
    }
}

/**
 * A text field that merely takes D-pad focus pops the on-screen keyboard open over the list, so here it is a row:
 * center opens the editor, and leaving the editor (Done, or moving focus away) goes back to the row.
 */
@Composable
fun TextEntryField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var editing by remember { mutableStateOf(false) }
    var returnFocusToRow by remember { mutableStateOf(false) }
    val rowFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    if (editing) {
        var gainedFocus by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { fieldFocus.requestFocus() }
        EditableTextField(
            value = value,
            onValueChange = onValueChange,
            hint = hint,
            keyboardType = keyboardType,
            modifier = modifier,
            fieldModifier = Modifier
                .focusRequester(fieldFocus)
                .onFocusChanged { state ->
                    if (state.isFocused) gainedFocus = true else if (gainedFocus) editing = false
                },
            onDone = {
                returnFocusToRow = true
                editing = false
            },
        )
    } else {
        LaunchedEffect(returnFocusToRow) {
            if (returnFocusToRow) {
                rowFocus.requestFocus()
                returnFocusToRow = false
            }
        }
        FocusRow(
            modifier.focusRequester(rowFocus),
            onClick = { editing = true },
            kind = FocusKind.CONTROL,
            idleBackground = TuneupTheme.colors.surface,
            contentPadding = 24.vp,
            minHeight = 80.vp,
        ) {
            val palette = TuneupTheme.colors
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.vp)) {
                Icon(Glyphs.Search, contentDescription = null, tint = palette.muted, modifier = Modifier.size(32.vp))
                Text(
                    value.ifEmpty { hint },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (value.isEmpty()) palette.muted else palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The search field while it is being typed in. It looks like the row it came from (same shape, height, icon and
 * place for the text), already in its focused colors, so opening the keyboard changes nothing but the cursor.
 */
@Composable
private fun EditableTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: () -> Unit = {},
) {
    val source = remember { MutableInteractionSource() }
    val palette = TuneupTheme.colors
    val inverted = InvertedTuneupColors
    val textStyle = MaterialTheme.typography.titleMedium
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 80.vp)
            .tvFocus(source, ROW_SHAPE, FocusKind.CONTROL)
            .clip(ROW_SHAPE)
            .background(palette.text)
            .padding(24.vp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.vp),
    ) {
        Icon(Glyphs.Search, contentDescription = null, tint = inverted.muted, modifier = Modifier.size(32.vp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                interactionSource = source,
                textStyle = textStyle.copy(color = inverted.text),
                cursorBrush = SolidColor(inverted.text),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = fieldModifier.fillMaxWidth(),
            )
            if (value.isEmpty()) Text(hint, style = textStyle, color = inverted.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Screen title with consistent spacing. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.padding(bottom = 8.vp),
    )
}

/** A ring that turns, so the user always knows the app is working and not stuck. */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 48.vp) {
    val palette = TuneupTheme.colors
    CircularProgressIndicator(modifier.size(size), color = palette.accent, trackColor = palette.track, strokeWidth = 6.vp)
}

/** A spinner with a message, so the user always knows the app is working and not stuck. */
@Composable
fun LoadingRow(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Spinner(size = 40.vp)
        Spacer(Modifier.width(16.vp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Progress of a long action. A null [fraction] is a bar of unknown length. */
@Composable
fun TvProgressBar(fraction: Float?, modifier: Modifier = Modifier) {
    val palette = TuneupTheme.colors
    val barModifier = modifier.fillMaxWidth().heightIn(min = 9.vp).clip(CircleShape)
    if (fraction == null) {
        LinearProgressIndicator(modifier = barModifier, color = palette.accent, trackColor = palette.track)
    } else {
        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = barModifier, color = palette.accent, trackColor = palette.track)
    }
}

/** The switch drawn by a toggle: it only shows the state, whoever contains it handles the click. */
@Composable
fun TvSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val palette = TuneupTheme.colors
    val track by animateColorAsState(if (checked) palette.switchOnTrack else palette.switchOffTrack, tween(160), label = "switchTrack")
    val knob by animateColorAsState(if (checked) palette.switchOnKnob else palette.switchOffKnob, tween(160), label = "switchKnob")
    val offset by animateDpAsState(if (checked) 36.vp else 0.vp, tween(160), label = "switchKnobOffset")
    Box(modifier.size(width = 84.vp, height = 48.vp).clip(CircleShape).background(track).padding(5.vp)) {
        Box(Modifier.offset(x = offset).size(38.vp).clip(CircleShape).background(knob))
    }
}

/** A row with a switch. The whole row is the target, so the D-pad center toggles it. */
@Composable
fun FocusToggleRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    requestInitialFocus: Boolean = false,
) {
    FocusRow(
        modifier.semantics { role = Role.Switch; toggleableState = ToggleableState(checked) },
        onClick = onToggle,
        requestInitialFocus = requestInitialFocus,
        kind = FocusKind.CONTROL,
        idleBackground = TuneupTheme.colors.surface,
        minHeight = 96.vp,
    ) {
        val palette = TuneupTheme.colors
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(end = 16.vp)) {
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (supporting != null) Text(supporting, style = subtitleStyle(), color = palette.muted)
            }
            // The row handles the click; the switch only shows the state.
            TvSwitch(checked)
        }
    }
}

@Composable
fun FocusChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val palette = TuneupTheme.colors
    val shape = RoundedCornerShape(20.vp)
    Box(
        modifier = modifier
            .heightIn(min = 80.vp)
            .tvFocus(source, shape, FocusKind.CONTROL)
            .clip(shape)
            .background(
                when {
                    focused -> palette.text
                    selected -> palette.primary.background
                    else -> palette.surface
                },
            )
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { toggleableState = ToggleableState(selected) }
            .padding(horizontal = 28.vp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                focused -> FOCUSED_CONTENT
                selected -> palette.primary.content
                else -> palette.muted
            },
            maxLines = 1,
        )
    }
}

enum class BadgeKind { NEUTRAL, USER, RUNNING, PROTECTED, DISABLED, SUCCESS, WARNING, DANGER, INFO }

/** A short status or category label. It always carries text, so it never relies on color alone. */
@Composable
fun Badge(text: String, kind: BadgeKind = BadgeKind.NEUTRAL, modifier: Modifier = Modifier, compact: Boolean = false) {
    val palette = TuneupTheme.colors
    val tone = when (kind) {
        BadgeKind.NEUTRAL -> palette.neutral
        BadgeKind.USER -> palette.user
        BadgeKind.RUNNING -> palette.runningBadge
        BadgeKind.PROTECTED -> palette.protectedBadge
        BadgeKind.DISABLED -> palette.disabledBadge
        BadgeKind.SUCCESS -> palette.successTone
        BadgeKind.WARNING -> palette.warningTone
        BadgeKind.DANGER -> palette.dangerTone
        BadgeKind.INFO -> palette.info
    }
    Text(
        text,
        style = if (compact) MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp) else MaterialTheme.typography.labelMedium,
        color = tone.content,
        // A compact pill may take two lines before it cuts a word: a pill nobody can read is no use.
        maxLines = if (compact) 2 else 1,
        overflow = TextOverflow.Ellipsis,
        // A pill that takes two lines keeps its text in the middle of both.
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(if (compact) RoundedCornerShape(18.vp) else CircleShape)
            .background(tone.background)
            .heightIn(min = if (compact) 32.vp else 44.vp)
            .padding(horizontal = if (compact) 12.vp else 20.vp, vertical = if (compact) 5.vp else 8.vp),
    )
}

/** The small line under a title or a section: 12 sp, quieter than anything it describes. */
@Composable
fun subtitleStyle(): TextStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp)

/** The frame of the inner screens: the title with its subtitle and, on the right, whatever the screen needs. Back is the remote's key. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    /** For a screen whose subtitle names something (a TV and its Android) and is worth more than the usual 12 sp. */
    subtitleTextStyle: TextStyle = subtitleStyle(),
    /** A third line under the subtitle, always at the subtitle size of the app. */
    caption: String? = null,
) {
    val palette = TuneupTheme.colors
    Row(modifier.fillMaxWidth().padding(horizontal = 6.vp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.vp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = subtitleTextStyle, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (caption != null) Text(caption, style = subtitleStyle(), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** The small dot and text on the right of a header: the connection state. */
@Composable
fun StatusDot(text: String, modifier: Modifier = Modifier) {
    val palette = TuneupTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.vp)) {
        Box(Modifier.size(14.vp).clip(CircleShape).background(palette.success))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = palette.muted, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** The big round-square glyph of the center and action screens: info, warning, success or failure. */
@Composable
fun StatusSymbol(tone: Tone, glyph: String, modifier: Modifier = Modifier) {
    val colors = TuneupTheme.colors.tone(tone)
    val shape = RoundedCornerShape(32.vp)
    Box(
        modifier.size(120.vp).clip(shape).background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 32.sp, fontWeight = FontWeight.Black, color = colors.content)
    }
}

/** A placeholder for something that is still being read: a quiet block that pulses, so the screen keeps its shape. */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.vp)) {
    val alpha by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Box(modifier.clip(shape).background(TuneupTheme.colors.surfaceRaised.copy(alpha = alpha)))
}

/** A raised card that is not focusable by itself. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: Dp = 32.vp,
    radius: Dp = 32.vp,
    /** Scrolls its content inside the card, so the D-pad can reach a control that does not fit. */
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = TuneupTheme.colors
    val shape = RoundedCornerShape(radius)
    val scrollState = rememberScrollState()
    Column(
        modifier
            .clip(shape)
            .background(palette.surface)
            .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** A quiet explanation under an action. */
@Composable
fun MutedNote(text: String, modifier: Modifier = Modifier, textStyle: TextStyle = MaterialTheme.typography.bodyMedium) {
    val palette = TuneupTheme.colors
    val shape = RoundedCornerShape(20.vp)
    Text(
        text,
        style = textStyle,
        color = palette.muted,
        modifier = modifier.clip(shape).background(palette.surfaceRaised).padding(horizontal = 24.vp, vertical = 20.vp),
    )
}

/** A hint about the choice in front of the user, in the accent tone. */
@Composable
fun InfoNote(text: String, modifier: Modifier = Modifier, textStyle: TextStyle = MaterialTheme.typography.bodyMedium) {
    val palette = TuneupTheme.colors
    val shape = RoundedCornerShape(20.vp)
    Text(
        text,
        style = textStyle,
        color = palette.muted,
        modifier = modifier.fillMaxWidth().clip(shape).background(palette.surfaceSelected).padding(horizontal = 24.vp, vertical = 20.vp),
    )
}

/**
 * Makes a scrolling list [room] wider than the space it is given, on each side, without changing the space it takes.
 * A list clips what is outside its own edges, and a focused row grows and glows past its own: with the extra width
 * (and the same extra `contentPadding`) the rows stay where they were and the growth has somewhere to go.
 */
fun Modifier.bleed(room: Dp): Modifier =
    layout { measurable, constraints ->
        val extra = (room * 2).roundToPx()
        val placeable = measurable.measure(constraints.offset(horizontal = extra))
        layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
    }

/**
 * A message about something that is going on or went wrong, with the weight of its [tone]. With [actionLabel] and
 * [onAction] it carries a button at its end, inside the same bar.
 */
@Composable
fun Banner(text: String, tone: Tone, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val colors = TuneupTheme.colors.tone(tone)
    val shape = RoundedCornerShape(20.vp)
    val hasAction = actionLabel != null && onAction != null
    // No clip: the button inside grows and glows past its own bounds.
    Row(
        modifier.fillMaxWidth().background(colors.background, shape)
            .padding(start = 24.vp, end = if (hasAction) 12.vp else 24.vp, top = if (hasAction) 12.vp else 18.vp, bottom = if (hasAction) 12.vp else 18.vp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.vp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = colors.content, modifier = Modifier.weight(1f))
        if (hasAction) FocusButton(actionLabel!!, onClick = onAction!!, minHeight = 56.vp)
    }
}

/** Nothing to show: a blank state in the middle of the list or grid, with what to try, so it reads as the result and not as a glitch. */
@Composable
fun EmptyState(title: String, hint: String?, modifier: Modifier = Modifier) {
    val palette = TuneupTheme.colors
    Column(modifier.fillMaxWidth().padding(60.vp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.vp)) {
        Box(Modifier.size(120.vp).clip(CircleShape).background(palette.surface), contentAlignment = Alignment.Center) {
            Icon(Glyphs.Search, contentDescription = null, tint = palette.muted, modifier = Modifier.size(56.vp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = palette.muted, textAlign = TextAlign.Center)
    }
}
