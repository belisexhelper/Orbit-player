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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.orbitplayer.ui.AppViewModel
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
    RailItem(Route.Guide, "TV guide", Icons.Filled.DateRange),
    RailItem(Route.Movies, "Movies", Icons.Filled.Movie),
    RailItem(Route.Series, "Series", Icons.Filled.VideoLibrary),
    RailItem(Route.Favorites, "My list", Icons.Filled.Star),
    RailItem(Route.Settings, "Settings", Icons.Filled.Settings),
)

val RAIL_COLLAPSED = 76.dp
private val RAIL_EXPANDED = 228.dp

/** TV interface (TiviMate style): collapsible side menu + content */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvApp(vm: AppViewModel, onExit: () -> Unit) {
    val playerOpen by vm.playerOpen.collectAsState()
    val settings by vm.settings.collectAsState()
    var railFocused by remember { mutableStateOf(false) }
    var askExit by remember { mutableStateOf(false) }
    val railFocus = remember { FocusRequester() }
    val route = vm.route.let { if (it == Route.Home) Route.Live else it }
    val section = vm.backStack.getOrNull(1)?.let { if (it == Route.Home) Route.Live else it } ?: Route.Live

    BackHandler(enabled = !playerOpen) {
        when {
            vm.backStack.size > 2 -> vm.back()
            route != Route.Live && !railFocused -> runCatching { railFocus.requestFocus() }
            route != Route.Live -> vm.root(Route.Live)
            !railFocused -> runCatching { railFocus.requestFocus() }
            settings.confirmExit -> askExit = true
            else -> onExit()
        }
    }

    Box(Modifier.fillMaxSize().background(Orbit.colors.background)) {
        Row(Modifier.fillMaxSize()) {
            Spacer(Modifier.width(RAIL_COLLAPSED))
            Box(Modifier.weight(1f).fillMaxHeight()) {
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
        // Side menu: icons only, expands with labels when focused
        val width by animateDpAsState(if (railFocused) RAIL_EXPANDED else RAIL_COLLAPSED, tween(160), label = "rail")
        if (railFocused) Box(Modifier.fillMaxSize().background(Color(0x99000000)))
        Column(
            Modifier
                .width(width)
                .fillMaxHeight()
                .background(Color(0xFF10141D))
                .onFocusChanged { railFocused = it.hasFocus }
                .focusRestorer()
                .focusGroup()
                .padding(vertical = 28.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.padding(start = 8.dp, bottom = 22.dp).height(32.dp), contentAlignment = Alignment.CenterStart) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Orbit.colors.accent), contentAlignment = Alignment.Center) {
                        Text("O", color = Orbit.colors.onAccent, fontWeight = FontWeight.Bold)
                    }
                    if (railFocused) Text("  Orbit Player", color = Orbit.colors.text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                }
            }
            RAIL.forEach { item ->
                val active = item.route == section
                Focusable(
                    onClick = { vm.root(item.route) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent,
                    selected = active,
                    scaleOnFocus = 1f,
                    focusRequester = if (active) railFocus else null,
                ) { focused ->
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(item.icon, item.label, Modifier.size(24.dp), tint = if (active || focused) Orbit.colors.accent else Orbit.colors.textMuted)
                        if (railFocused) {
                            Spacer(Modifier.width(16.dp))
                            Text(item.label, color = if (focused || active) Orbit.colors.text else Orbit.colors.textMuted, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    if (askExit) ConfirmDialog("Exit Orbit Player?", "Playback will stop.", "Exit", onDismiss = { askExit = false }) { onExit() }
    LaunchedEffect(route) { railFocused = false }
}
