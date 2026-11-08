package com.orbitplayer.ui.components

import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.orbitplayer.ui.theme.LocalIsTv
import com.orbitplayer.ui.theme.Orbit
import kotlinx.coroutines.delay

/** Panel used by every dialog */
@Composable
fun OrbitDialog(onDismiss: () -> Unit, title: String = "", content: @Composable () -> Unit) {
    val tv = LocalIsTv.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(min = 300.dp, max = if (tv) 520.dp else 440.dp)
                .background(Orbit.colors.elevated, RoundedCornerShape(16.dp))
                .padding(vertical = 18.dp, horizontal = 12.dp),
        ) {
            if (title.isNotBlank()) Text(title, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.titleLarge, color = Orbit.colors.text)
            content()
        }
    }
}

/** Pick one of several options; the current one has a check mark and gets focus */
@Composable
fun ChoiceDialog(title: String, options: List<Pair<String, String>>, selected: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val first = remember { FocusRequester() }
    val selIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    OrbitDialog(onDismiss, title) {
        LazyColumn(Modifier.heightIn(max = 460.dp)) {
            itemsIndexed(options) { i, (value, label) ->
                ListRow(
                    title = label,
                    focusRequester = if (i == selIndex) first else null,
                    trailing = { if (value == selected) Icon(Icons.Filled.Check, null, tint = Orbit.colors.accent) },
                ) {
                    onPick(value)
                    onDismiss()
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { (if (focusConfirm) second else first).requestFocus() }
    }
}

data class MenuAction(val label: String, val icon: ImageVector? = null, val run: () -> Unit)

/** Context menu (long press OK / long touch) */
@Composable
fun ActionsDialog(title: String, actions: List<MenuAction>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    OrbitDialog(onDismiss, title) {
        Column {
            actions.forEachIndexed { i, a ->
                ListRow(
                    title = a.label,
                    focusRequester = if (i == 0) first else null,
                    leading = a.icon?.let { ic -> { Icon(ic, null, Modifier.size(22.dp), tint = Orbit.colors.textMuted) } },
                ) {
                    onDismiss()
                    a.run()
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, focusConfirm: Boolean = false, onConfirm: () -> Unit) {
    val first = remember { FocusRequester() }
    val second = remember { FocusRequester() }
    OrbitDialog(onDismiss, title) {
        Text(text, Modifier.padding(12.dp), color = Orbit.colors.textMuted, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            OrbitButton("Cancel", focusRequester = first) { onDismiss() }
            OrbitButton(confirm, primary = true, focusRequester = second) {
                onDismiss()
                onConfirm()
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
}

@Composable
fun orbitFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Orbit.colors.accent,
    unfocusedBorderColor = Orbit.colors.divider,
    focusedLabelColor = Orbit.colors.accent,
    unfocusedLabelColor = Orbit.colors.textMuted,
    cursorColor = Orbit.colors.accent,
    focusedTextColor = Orbit.colors.text,
    unfocusedTextColor = Orbit.colors.text,
    focusedContainerColor = Orbit.colors.surface,
    unfocusedContainerColor = Orbit.colors.surface,
)

/** Text field used across the app (works with the TV on-screen keyboard) */
@Composable
fun OrbitTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    focusRequester: FocusRequester? = null,
    onDone: (() -> Unit)? = null,
) {
    // passwords are visible by default (easier to type with a remote); the eye hides them
    var hidden by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password && hidden) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (password) ({
            androidx.compose.material3.IconButton(onClick = { hidden = !hidden }) {
                Icon(
                    if (hidden) androidx.compose.material.icons.Icons.Filled.Visibility else androidx.compose.material.icons.Icons.Filled.VisibilityOff,
                    if (hidden) "Show password" else "Hide password",
                    tint = Orbit.colors.textMuted,
                )
            }
        }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (password && hidden) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onGo = { onDone?.invoke() }, onSearch = { onDone?.invoke() }),
        colors = orbitFieldColors(),
        modifier = (if (focusRequester != null) modifier.focusRequester(focusRequester) else modifier).fillMaxWidth(),
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, label: String, keyboard: KeyboardType = KeyboardType.Text, password: Boolean = false, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    OrbitDialog(onDismiss, title) {
        Box(Modifier.padding(12.dp)) { OrbitTextField(text, { text = it }, label, keyboard = keyboard, password = password, focusRequester = fr, onDone = { onDone(text); onDismiss() }) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            OrbitButton("Cancel") { onDismiss() }
            OrbitButton("Save", primary = true) {
                onDone(text)
                onDismiss()
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { fr.requestFocus() }
    }
}

/** PIN entry with an on-screen keypad (no keyboard needed on TV) */
@Composable
fun PinDialog(title: String, onDismiss: () -> Unit, onSubmit: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    OrbitDialog(onDismiss, title) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (pin.isEmpty()) "Enter PIN" else "•".repeat(pin.length), style = MaterialTheme.typography.headlineSmall, color = if (error) Orbit.colors.live else Orbit.colors.text)
            Spacer(Modifier.height(14.dp))
            Keypad(
                onDigit = { d ->
                    error = false
                    pin = (pin + d).take(8)
                    if (pin.length >= 4 && onSubmit(pin)) onDismiss() else if (pin.length >= 8) {
                        error = true
                        pin = ""
                    }
                },
                onDelete = { pin = pin.dropLast(1) },
                first = first,
            )
        }
    }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
}

@Composable
fun Keypad(onDigit: (Char) -> Unit, onDelete: () -> Unit, first: FocusRequester? = null) {
    val rows = listOf("123", "456", "789", "C0⌫")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { ri, r ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.forEachIndexed { ci, ch ->
                    Focusable(
                        onClick = {
                            when (ch) {
                                '⌫' -> onDelete()
                                'C' -> repeat(8) { onDelete() }
                                else -> onDigit(ch)
                            }
                        },
                        modifier = Modifier.size(68.dp, 54.dp),
                        color = Orbit.colors.surfaceFocus,
                        focusedColor = Orbit.colors.accent.copy(alpha = 0.35f),
                        focusRequester = if (ri == 0 && ci == 0) first else null,
                    ) { _ ->
                        Box(Modifier.size(68.dp, 54.dp), contentAlignment = Alignment.Center) {
                            Text(ch.toString(), style = MaterialTheme.typography.titleLarge, color = Orbit.colors.text)
                        }
                    }
                }
            }
        }
    }
}
