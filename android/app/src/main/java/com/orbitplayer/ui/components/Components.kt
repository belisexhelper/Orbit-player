package com.orbitplayer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.orbitplayer.ui.theme.LocalIsTv
import com.orbitplayer.ui.theme.Orbit

/**
 * For focusProperties { exit = ... }: blocks only the arrow keys. Other directions (focus requested
 * from code, Back to the menu, dialogs) must pass, otherwise focus gets stuck.
 */
fun arrowExit(d: androidx.compose.ui.focus.FocusDirection, left: FocusRequester? = null, right: FocusRequester? = null): FocusRequester = when (d) {
    androidx.compose.ui.focus.FocusDirection.Left -> left ?: FocusRequester.Cancel
    androidx.compose.ui.focus.FocusDirection.Right -> right ?: FocusRequester.Cancel
    androidx.compose.ui.focus.FocusDirection.Up, androidx.compose.ui.focus.FocusDirection.Down -> FocusRequester.Cancel
    else -> FocusRequester.Default
}

/** OK / Enter / D-pad center */
fun isOkKey(key: Key) = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/**
 * Basic selectable element for remote control and touch.
 * Focused (TV): accent border + slight scale + lighter background.
 * Long press of OK (or long touch) calls [onLongClick].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(10.dp),
    color: Color = Orbit.colors.surface,
    focusedColor: Color = Orbit.colors.surfaceFocus,
    selected: Boolean = false,
    scaleOnFocus: Float = 1.04f,
    border: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val tv = LocalIsTv.current
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val press = remember { BooleanArray(2) } // [0] = OK is down, [1] = long press fired
    val scale by animateFloatAsState(if (focused && tv) scaleOnFocus else 1f, tween(120), label = "focus-scale")
    val accent = Orbit.colors.accent
    var m = modifier
    if (focusRequester != null) m = m.focusRequester(focusRequester)
    m = m
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocus?.invoke()
        }
        .onPreviewKeyEvent { e ->
            if (!isOkKey(e.key)) return@onPreviewKeyEvent false
            when (e.type) {
                KeyEventType.KeyDown -> {
                    if (e.nativeKeyEvent.repeatCount == 0) {
                        press[0] = true
                        press[1] = false
                    } else if (press[0] && !press[1] && onLongClick != null) {
                        press[1] = true
                        onLongClick()
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    if (press[0] && !press[1]) onClick()
                    press[0] = false
                    true
                }
                else -> false
            }
        }
        .scale(scale)
        .clip(shape)
        .background(if (focused) focusedColor else if (selected) accent.copy(alpha = 0.14f) else color)
        .then(if (border && focused && tv) Modifier.border(3.dp, accent, shape) else Modifier)
        .combinedClickable(
            interactionSource = interaction,
            indication = if (tv) null else LocalIndication.current,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    Box(m) { content(focused) }
}

/** Row in a list (channel, setting, group) */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = Orbit.colors
    Focusable(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
        selected = selected,
        scaleOnFocus = 1.0f,
        focusRequester = focusRequester,
        onFocus = onFocus,
    ) { focused ->
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selected) Box(Modifier.width(4.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (focused || selected) c.text else c.text.copy(alpha = 0.92f),
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke(this)
        }
    }
}

/** Setting with its current value on the right */
@Composable
fun SettingRow(title: String, value: String = "", subtitle: String = "", focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    val c = Orbit.colors
    ListRow(title = title, subtitle = subtitle, focusRequester = focusRequester, onClick = onClick, trailing = {
        if (value.isNotBlank()) Text(value, color = c.accent, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.textMuted)
    })
}

@Composable
fun ToggleRow(title: String, checked: Boolean, subtitle: String = "", onChange: (Boolean) -> Unit) {
    val c = Orbit.colors
    ListRow(title = title, subtitle = subtitle, onClick = { onChange(!checked) }, trailing = {
        Box(
            Modifier.size(44.dp, 26.dp).clip(CircleShape).background(if (checked) c.accent else c.elevated),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.padding(3.dp).size(20.dp).clip(CircleShape).background(if (checked) c.onAccent else c.textMuted)) }
    })
}

@Composable
fun Pill(text: String, color: Color = Orbit.colors.accent, filled: Boolean = false) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(if (filled) color else color.copy(alpha = 0.18f)).padding(horizontal = 7.dp, vertical = 2.dp),
    ) { Text(text, style = MaterialTheme.typography.labelSmall, color = if (filled) Color.White else color, maxLines = 1) }
}

/** Channel logo on a subtle plate, so dark and transparent logos stay visible */
@Composable
fun ChannelLogo(url: String, name: String, width: Dp = 64.dp, height: Dp = 40.dp) {
    val c = Orbit.colors
    Box(
        Modifier.size(width, height).clip(RoundedCornerShape(6.dp)).background(if (c.isLight) Color(0x0F000000) else Color(0x14FFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNotBlank()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
        } else {
            Text(initials(name), style = MaterialTheme.typography.labelLarge, color = c.textMuted)
        }
    }
}

fun initials(name: String): String =
    name.split(' ', '-', '|').filter { it.isNotBlank() && it.first().isLetterOrDigit() }.take(2).joinToString("") { it.take(1) }.uppercase().ifBlank { "TV" }

/** Thin progress line (programme progress, watched progress) */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = Orbit.colors.accent, height: Dp = 3.dp) {
    Box(modifier.height(height).clip(RoundedCornerShape(height)).background(Orbit.colors.divider)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(RoundedCornerShape(height)).background(color))
    }
}

/** Movie / series poster card */
@Composable
fun PosterCard(
    title: String,
    image: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    progress: Float = -1f,
    badge: String = "",
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = Orbit.colors
    Focusable(onClick = onClick, onLongClick = onLongClick, onFocus = onFocus, modifier = modifier, scaleOnFocus = 1.06f) { _ ->
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(c.surfaceFocus)) {
                if (image.isNotBlank()) {
                    AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(title, Modifier.align(Alignment.Center).padding(10.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, maxLines = 4)
                }
                if (badge.isNotBlank()) Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { Pill(badge, c.accentAlt, filled = true) }
                if (progress in 0f..1f) ProgressLine(progress, Modifier.fillMaxWidth().align(Alignment.BottomStart), height = 4.dp)
            }
            Text(title, Modifier.padding(start = 8.dp, end = 8.dp, top = 7.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = c.text)
            Text(subtitle.ifBlank { " " }, Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), maxLines = 1, color = c.textMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Large tile (phone home: Live TV, Movies …) with the blue gradient */
@Composable
fun GradientTile(label: String, icon: ImageVector, modifier: Modifier = Modifier, detail: String = "", focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    val c = Orbit.colors
    Focusable(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        focusedColor = Color.Transparent,
        focusRequester = focusRequester,
    ) { focused ->
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(if (focused) c.tileEnd else c.tileStart, if (focused) c.accent else c.tileEnd)),
            ),
        ) {
            Column(Modifier.align(Alignment.Center).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, null, Modifier.size(44.dp), tint = Color.White)
                Spacer(Modifier.height(10.dp))
                Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                if (detail.isNotBlank()) Text(detail, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Small icon button with a label under it (home utility row, player bars) */
@Composable
fun IconAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, active: Boolean = false, tint: Color = Orbit.colors.text, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    val c = Orbit.colors
    Focusable(onClick = onClick, modifier = modifier, color = Color.Transparent, selected = active, scaleOnFocus = 1.06f, focusRequester = focusRequester) { _ ->
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, label, Modifier.size(26.dp), tint = if (active) c.accent else tint)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (active) c.accent else tint, maxLines = 1)
        }
    }
}

/** Button: filled (primary) or tonal */
@Composable
fun OrbitButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val c = Orbit.colors
    val bg = if (primary) c.accent else c.surfaceFocus
    val fg = if (primary) c.onAccent else c.text
    Focusable(
        onClick = { if (enabled) onClick() },
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (enabled) bg else bg.copy(alpha = 0.45f),
        focusedColor = if (primary) c.accent else c.elevated,
        scaleOnFocus = 1.05f,
        focusRequester = focusRequester,
    ) { _ ->
        Row(Modifier.padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Orbit.colors.text)
        action?.invoke()
    }
}

@Composable
fun Loading(text: String = "", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = Orbit.colors.accent)
        if (text.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(text, color = Orbit.colors.textMuted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun EmptyState(title: String, text: String = "", modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, color = Orbit.colors.text)
        if (text.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(text, color = Orbit.colors.textMuted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}
