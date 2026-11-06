package com.orbitplayer.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orbitplayer.BuildConfig
import com.orbitplayer.core.DeviceInfo
import com.orbitplayer.ui.components.ActionButton
import com.orbitplayer.ui.components.EmptyState
import com.orbitplayer.ui.components.Focusable
import com.orbitplayer.ui.components.Loading
import com.orbitplayer.ui.screens.AboutScreen
import com.orbitplayer.ui.screens.ActivationScreen
import com.orbitplayer.ui.screens.AddPlaylistScreen
import com.orbitplayer.ui.screens.AppsScreen
import com.orbitplayer.ui.screens.DiagnosticsScreen
import com.orbitplayer.ui.screens.DownloadsScreen
import com.orbitplayer.ui.screens.GuideScreen
import com.orbitplayer.ui.screens.HomeScreen
import com.orbitplayer.ui.screens.LiveScreen
import com.orbitplayer.ui.screens.MovieDetailScreen
import com.orbitplayer.ui.screens.MoviesScreen
import com.orbitplayer.ui.screens.OnboardingScreen
import com.orbitplayer.ui.screens.PairingScreen
import com.orbitplayer.ui.screens.PinDialog
import com.orbitplayer.ui.screens.PlaylistsScreen
import com.orbitplayer.ui.screens.SearchScreen
import com.orbitplayer.ui.screens.SeriesDetailScreen
import com.orbitplayer.ui.screens.SeriesScreen
import com.orbitplayer.ui.screens.SettingsScreen
import com.orbitplayer.ui.screens.SpeedTestScreen
import com.orbitplayer.ui.theme.LocalIsTv

private data class NavItem(val screen: Screen, val label: String, val icon: ImageVector)

@Composable
fun AppRoot(vm: MainViewModel, activity: MainActivity) {
    val tv = LocalIsTv.current
    val link by vm.link.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val settings by vm.settings.collectAsState()
    var skipWait by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            BuildConfig.SERVER_ENABLED && link.blocked -> EmptyState(
                "Uređaj je blokiran",
                "Obratite se podršci${if (settings.supportContact.isNotBlank()) ": ${settings.supportContact}" else "."}",
            )
            !vm.playlistsLoaded.value -> Loading()
            BuildConfig.SERVER_ENABLED && playlists.isEmpty() && !link.configLoaded && link.error.isBlank() && !skipWait ->
                Loading("Povezivanje sa serverom…")
            playlists.isEmpty() && vm.current !in listOf(Screen.AddPlaylist, Screen.Activation, Screen.Pairing, Screen.Diagnostics, Screen.SpeedTest) ->
                OnboardingScreen(vm, onSkipWait = { skipWait = true })
            else -> Shell(vm, activity)
        }

        activity.serverMessage?.let { msg ->
            AlertDialog(
                onDismissRequest = { activity.serverMessage = null },
                confirmButton = { TextButton(onClick = { activity.serverMessage = null }) { Text("U redu") } },
                title = { Text("Obaveštenje") },
                text = { Text(msg) },
            )
        }
        if (activity.pinDialog) PinDialog(vm, onClose = { activity.pinDialog = false })
    }
}

@Composable
private fun Shell(vm: MainViewModel, activity: MainActivity) {
    val tv = LocalIsTv.current
    val settings by vm.settings.collectAsState()
    val tablet = DeviceInfo.isTablet(LocalContext.current)
    val items = buildList {
        add(NavItem(Screen.Home, "Početna", Icons.Filled.Home))
        if (settings.showLive) add(NavItem(Screen.Live, "Uživo", Icons.Filled.LiveTv))
        if (settings.showMovies) add(NavItem(Screen.Movies, "Filmovi", Icons.Filled.Movie))
        if (settings.showSeries) add(NavItem(Screen.Series, "Serije", Icons.Filled.VideoLibrary))
        if (settings.showDownloads && !tv) add(NavItem(Screen.Downloads, "Preuzeto", Icons.Filled.Download))
        add(NavItem(Screen.Search, "Pretraga", Icons.Filled.Search))
        add(NavItem(Screen.Settings, "Podešavanja", Icons.Filled.Settings))
    }
    val current = vm.current
    val rootOf = vm.backStack.getOrNull(1) ?: Screen.Home
    val selected = items.firstOrNull { it.screen == current || it.screen == rootOf }?.screen ?: Screen.Home

    if (tv) {
        Row(Modifier.fillMaxSize()) {
            TvNav(items, selected) { vm.root(it) }
            Box(Modifier.weight(1f).fillMaxHeight()) { ScreenContent(vm, activity, current) }
        }
    } else if (tablet) {
        Row(Modifier.fillMaxSize().safeDrawingPadding()) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                items.forEach { n ->
                    NavigationRailItem(selected = selected == n.screen, onClick = { vm.root(n.screen) }, icon = { Icon(n.icon, null) }, label = { Text(n.label) })
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { ScreenContent(vm, activity, current) }
        }
    } else {
        // telefon: donja traka sa najviše 5 stavki
        val bottom = items.filter { it.screen != Screen.Search }.take(5)
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(Modifier.weight(1f)) { ScreenContent(vm, activity, current) }
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                bottom.forEach { n ->
                    NavigationBarItem(
                        selected = selected == n.screen,
                        onClick = { vm.root(n.screen) },
                        icon = { Icon(n.icon, null) },
                        label = { Text(n.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}

/** Bočni meni za TV: ikone, a naziv stavke u fokusu */
@Composable
private fun TvNav(items: List<NavItem>, selected: Screen, onSelect: (Screen) -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(200.dp).background(Color(0xFF0B1426)).padding(vertical = 24.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(BuildConfig.APP_NAME, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp, bottom = 18.dp))
        items.forEach { n ->
            val isSel = n.screen == selected
            Focusable(
                onClick = { onSelect(n.screen) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                background = if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                scaleOnFocus = false,
            ) { _ ->
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(n.icon, null, Modifier.size(24.dp), tint = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(n.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ScreenContent(vm: MainViewModel, activity: MainActivity, s: Screen) {
    when (s) {
        Screen.Home -> HomeScreen(vm, activity)
        Screen.Live -> LiveScreen(vm)
        Screen.Guide -> GuideScreen(vm)
        Screen.Movies -> MoviesScreen(vm)
        is Screen.MovieDetail -> MovieDetailScreen(vm, s.key)
        Screen.Series -> SeriesScreen(vm)
        is Screen.SeriesDetail -> SeriesDetailScreen(vm, s.key)
        Screen.Downloads -> DownloadsScreen(vm)
        Screen.Search -> SearchScreen(vm)
        Screen.Settings -> SettingsScreen(vm, activity)
        Screen.Playlists -> PlaylistsScreen(vm)
        Screen.AddPlaylist -> AddPlaylistScreen(vm)
        Screen.Pairing -> PairingScreen(vm)
        Screen.Activation -> ActivationScreen(vm)
        Screen.Diagnostics -> DiagnosticsScreen(vm)
        Screen.SpeedTest -> SpeedTestScreen()
        Screen.Apps -> AppsScreen(vm, activity)
        Screen.About -> AboutScreen(vm)
    }
}

@Composable
fun ScreenHeader(title: String, vm: MainViewModel? = null, actions: @Composable () -> Unit = {}) {
    val tv = LocalIsTv.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (tv) 32.dp else 16.dp, vertical = if (tv) 20.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (vm != null && !tv && vm.backStack.size > 2) {
            ActionButton("Nazad") { vm.back() }
            Spacer(Modifier.width(12.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
    Spacer(Modifier.height(4.dp))
}
