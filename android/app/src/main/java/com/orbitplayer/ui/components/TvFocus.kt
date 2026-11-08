package com.orbitplayer.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Remembers which item of a list had focus and sends focus back to it when the list is entered
 * again with the remote. Replaces Compose's focusRestorer, which crashes in lazy lists in this
 * Compose version ("Release should only be called once").
 */
@Stable
class ListFocus {
    val requester = FocusRequester()
    /** key of the item that had focus last */
    var focusedKey: Any? = null
    /** key of the item that currently carries [requester] (null = not on screen) */
    internal var attachedKey: Any? = null

    /** Item that should get focus when the list is entered: the last focused one, else the first visible */
    fun target(keys: List<Any>, firstVisible: Int): Any? =
        focusedKey?.takeIf { k -> keys.contains(k) } ?: keys.getOrNull(firstVisible.coerceIn(0, (keys.size - 1).coerceAtLeast(0)))
}

@Composable
fun rememberListFocus(): ListFocus = remember { ListFocus() }

/**
 * For the list itself: moving into it with the arrows lands on the remembered item (when on screen).
 * Focus requested by the app for a specific row (e.g. the playing channel) is left alone.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.restoreListFocus(lf: ListFocus): Modifier =
    this.focusProperties {
        enter = { d -> if (d != FocusDirection.Enter && lf.attachedKey != null) lf.requester else FocusRequester.Default }
    }.focusGroup()

/** For each item: remembers focus; the [isTarget] item receives focus when the list is entered */
@Composable
fun Modifier.listItemFocus(lf: ListFocus, key: Any, isTarget: Boolean): Modifier {
    if (isTarget) {
        DisposableEffect(lf, key) {
            lf.attachedKey = key
            onDispose { if (lf.attachedKey == key) lf.attachedKey = null }
        }
    }
    val base = if (isTarget) this.focusRequester(lf.requester) else this
    return base.onFocusChanged { if (it.isFocused) lf.focusedKey = key }
}
