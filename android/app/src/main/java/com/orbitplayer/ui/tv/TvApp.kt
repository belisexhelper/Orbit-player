package com.orbitplayer.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.orbitplayer.ui.AppViewModel
import kotlinx.coroutines.delay
import com.orbitplayer.ui.Route
import com.orbitplayer.ui.common.AddPlaylistScreen
import com.orbitplayer.ui.common.DiagnosticsScreen
import com.orbitplayer.ui.common.FavoritesScreen
import com.orbitplayer.ui.common.GuideScreen
import com.orbitplayer.ui.common.MovieDetailScreen
import com.orbitplayer.ui.common.SearchScreen
import com.orbitplayer.ui.common.SeriesDetailScreen
import com.orbitplayer.ui.common.SettingsScreen
import com.orbitplayer.ui.common.SettingsSectionScreen
import com.orbitplayer.ui.common.SpeedTestScreen
import com.orbitplayer.ui.common.VodBrowseScreen
import com.orbitplayer.ui.components.ConfirmDialog
import com.orbitplayer.ui.components.Focusable
import com.orbitplayer.ui.theme.Orbit

private data class RailItem(val route: Route, val label: String, val icon: ImageVector)

private val RAIL = listOf(
    RailItem(Route.Search, "Search", Icons.Filled.Search),
    RailItem(Route.Live, "Live TV", Icons.Filled.LiveTv),
    RailItem(Route.Guide, "Catch-up", Icons.Filled.History),
    RailItem(Route.Movies, "Movies", Icons.Filled.Movie),
    RailItem(Route.Series, "Series", Icons.Filled.VideoLibrary),
    RailItem(Route.Favorites, "My list", Icons.Filled.Star),
    RailItem(Route.Settings, "Settings", Icons.Filled.Settings),
)

val RAIL_COLLAPSED = 76.dp

/** Where focus lands when moving from the side menu into a screen (the screen sets it, e.g. the playing channel) */
class ContentEntry {
    var requester: FocusRequester? = null
    /** where focus goes when the full-screen player closes (e.g. the channel that is playing) */
    var afterPlayer: FocusRequester? = null
    /** route whose screen last had focus (then focus is restored there instead of the entry point) */
    var visited: Route? = null
}

val LocalContentEntry = staticCompositionLocalOf { ContentEntry() }

/** Registers where focus goes when the player closes on this screen (the playing channel) */
@Composable
fun TvAfterPlayer(requester: FocusRequester) {
    val entry = LocalContentEntry.current
    DisposableEffect(requester) {
        entry.afterPlayer = requester
        onDispose { if (entry.afterPlayer === requester) entry.afterPlayer = null }
    }
}

/** Registers [requester] as the entry point of the current TV screen */
@Composable
fun TvEntryPoint(requester: FocusRequester) {
    val entry = LocalContentEntry.current
    DisposableEffect(requester) {
        entry.requester = requester
        onDispose { if (entry.requester === requester) entry.requester = null }
    }
}
private val RAIL_EXPANDED = 228.dp

/** TV interface (TiviMate style): collapsible side menu + content */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvApp(vm: AppViewModel, onExit: () -> Unit) {
    val playerOpen by vm.playerOpen.collectAsState()
    val settings by vm.settings.collectAsState()
    var railFocused by remember { mutableStateOf(false) }
    // the menu opens only when focus stays in it for a moment, so it never flashes while focus passes through
    var railOpen by remember { mutableStateOf(false) }
    LaunchedEffect(railFocused) {
        if (railFocused) {
            delay(180)
            railOpen = true
        } else railOpen = false
    }
    var askExit by remember { mutableStateOf(false) }
    val railFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    val entry = remember { ContentEntry() }

    val route = vm.route.let { if (it == Route.Home) Route.Live else it }
    val section = vm.backStack.getOrNull(1)?.let { if (it == Route.Home) Route.Live else it } ?: Route.Live

    /** From the menu into the screen: back to the last focused element, else the screen's entry point */
    fun enterContent() {
        val target = entry.requester
        if (target != null && runCatching { target.requestFocus() }.isSuccess) return
        runCatching { contentFocus.requestFocus() }
    }

    // Back works like pressing Left: one step to the left each time (programmes → channels → groups),
    // then the menu, then the exit question. Detail pages (a movie, a series) go back to their list.
    val focusManager = LocalFocusManager.current
    var enterRequest by remember { mutableIntStateOf(0) }
    var stuckBacks by remember { mutableIntStateOf(0) }
    LaunchedEffect(railFocused) { if (!railFocused) stuckBacks = 0 }
    BackHandler(enabled = !playerOpen) {
        when {
            vm.backStack.size > 2 -> {
                vm.back()
                enterRequest++
            }
            railFocused -> if (settings.confirmExit) askExit = true else onExit()
            else -> {
                val moved = runCatching { focusManager.moveFocus(FocusDirection.Left) }.getOrDefault(false)
                if (!moved) {
                    stuckBacks++
                    if (stuckBacks >= 2) { if (settings.confirmExit) askExit = true else onExit() }
                    else runCatching { railFocus.requestFocus() }
                }
            }
        }
    }
    // after choosing a menu item or going back from a detail page: focus goes into the screen
    LaunchedEffect(enterRequest) {
        if (enterRequest == 0) return@LaunchedEffect
        delay(120)
        repeat(4) {
            enterContent()
            delay(120)
            if (!railFocused) return@LaunchedEffect
        }
    }
    // after the full-screen player: back to the element that was focused before it opened.
    // Done just before the player disappears, so focus never passes through the menu.
    var beforePlayer by remember { mutableStateOf<FocusRequester?>(null) }
    DisposableEffect(vm) {
        vm.beforePlayerCloses = {
            // the playing channel when the screen offers it (it may differ after zapping), else the element used before
            val playing = entry.afterPlayer
            val ok = (playing != null && runCatching { playing.requestFocus() }.isSuccess) ||
                (beforePlayer?.let { b -> runCatching { b.requestFocus() }.isSuccess } == true)
            if (!ok) {
                val e = entry.requester
                if (e == null || runCatching { e.requestFocus() }.isFailure) runCatching { contentFocus.requestFocus() }
            }
        }
        onDispose { vm.beforePlayerCloses = null }
    }
    LaunchedEffect(playerOpen) {
        if (playerOpen) {
            beforePlayer = com.orbitplayer.ui.components.FocusMemory.last
            return@LaunchedEffect
        }
        val target = beforePlayer ?: return@LaunchedEffect
        beforePlayer = null
        repeat(5) {
            delay(80)
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    // Safety net: with a remote, "nothing focused" means the user is stuck (keys go nowhere).
    // If that happens outside the player, focus the screen's start point or the menu.
    var anyFocus by remember { mutableStateOf(false) }
    LaunchedEffect(anyFocus, playerOpen, route) {
        if (anyFocus || playerOpen) return@LaunchedEffect
        delay(700)
        if (anyFocus || vm.playerOpen.value) return@LaunchedEffect
        val t = entry.requester
        if (t == null || runCatching { t.requestFocus() }.isFailure) runCatching { railFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(Orbit.colors.background).onFocusChanged { anyFocus = it.hasFocus }) {
        Row(Modifier.fillMaxSize()) {
            Spacer(Modifier.width(RAIL_COLLAPSED))
            // Content: Left from its left edge goes to the menu; focus never wanders off in other directions.
            // Coming from the menu, focus returns to where it was, else to the screen's entry point.
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .focusRequester(contentFocus)
                    .onFocusChanged { if (it.hasFocus) entry.visited = route }
                    .focusProperties { exit = { d -> com.orbitplayer.ui.components.arrowExit(d, left = railFocus) } }
                    .focusGroup(),
            ) {
              CompositionLocalProvider(LocalContentEntry provides entry) {
                when (val r = route) {
                    Route.Live -> TvLiveScreen(vm)
                    Route.Guide -> GuideScreen(vm)
                    Route.Movies -> VodBrowseScreen(vm, series = false)
                    Route.Series -> VodBrowseScreen(vm, series = true)
                    is Route.MovieDetail -> MovieDetailScreen(vm, r.key)
                    is Route.SeriesDetail -> SeriesDetailScreen(vm, r.key)
                    Route.Search -> SearchScreen(vm)
                    Route.Favorites -> FavoritesScreen(vm)
                    Route.Settings -> SettingsScreen(vm)
                    is Route.SettingsSection -> SettingsSectionScreen(vm, r.id)
                    is Route.AddPlaylist -> AddPlaylistScreen(vm, r.editId)
                    Route.SpeedTest -> SpeedTestScreen(vm)
                    Route.Diagnostics -> DiagnosticsScreen(vm)
                    else -> TvLiveScreen(vm)
                }
              }
            }
        }
        // Side menu: icons only, expands with labels when focused
        val width by animateDpAsState(if (railOpen) RAIL_EXPANDED else RAIL_COLLAPSED, tween(160), label = "rail")
        if (railOpen) Box(Modifier.fillMaxSize().background(Color(0x99000000)))
        Column(
            Modifier
                .width(width)
                .fillMaxHeight()
                .background(Color(0xFF10141D))
                .onFocusChanged { railFocused = it.hasFocus }
                // entering the menu always lands on the current section; Right goes back into the screen
                .onPreviewKeyEvent { e ->
                    if (e.key == Key.DirectionRight) {
                        if (e.type == KeyEventType.KeyDown) enterContent()
                        true
                    } else false
                }
                .focusProperties {
                    enter = { d -> if (d == FocusDirection.Enter) FocusRequester.Default else railFocus }
                    exit = { d -> com.orbitplayer.ui.components.arrowExit(d) }
                }
                .focusGroup()
                .padding(vertical = 28.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.padding(start = 8.dp, bottom = 22.dp).height(32.dp), contentAlignment = Alignment.CenterStart) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Orbit.colors.accent), contentAlignment = Alignment.Center) {
                        Text("O", color = Orbit.colors.onAccent, fontWeight = FontWeight.Bold)
                    }
                    if (railOpen) Text("  Orbit Player", color = Orbit.colors.text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                }
            }
            RAIL.forEach { item ->
                val active = item.route == section
                Focusable(
                    onClick = {
                        if (item.route != section) vm.root(item.route)
                        enterRequest++
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent,
                    selected = active,
                    scaleOnFocus = 1f,
                    focusRequester = if (active) railFocus else null,
                ) { focused ->
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(item.icon, item.label, Modifier.size(24.dp), tint = if (active || focused) Orbit.colors.accent else Orbit.colors.textMuted)
                        if (railOpen) {
                            Spacer(Modifier.width(16.dp))
                            Text(item.label, color = if (focused || active) Orbit.colors.text else Orbit.colors.textMuted, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    if (askExit) ConfirmDialog("Exit Orbit Player?", "Playback will stop.", "Exit", onDismiss = { askExit = false }) { onExit() }
}
