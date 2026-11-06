package com.orbitplayer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.orbitplayer.BuildConfig
import com.orbitplayer.kiosk.InstalledApp
import com.orbitplayer.player.PlayItem
import com.orbitplayer.player.PlaySession
import com.orbitplayer.ui.MainActivity
import com.orbitplayer.ui.MainViewModel
import com.orbitplayer.ui.Screen
import com.orbitplayer.ui.components.ChannelLogo
import com.orbitplayer.ui.components.Focusable
import com.orbitplayer.ui.components.Poster
import com.orbitplayer.ui.components.SectionTitle
import com.orbitplayer.ui.components.Tile
import com.orbitplayer.ui.theme.LocalIsTv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(vm: MainViewModel, activity: MainActivity) {
    val tv = LocalIsTv.current
    val context = LocalContext.current
    val launcher by vm.launcher.collectAsState()
    val settings by vm.settings.collectAsState()
    val progress by vm.progress.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val live by vm.live.collectAsState()
    val suspended by vm.kioskSuspended.collectAsState()
    val launcherOn = vm.app.kiosk.launcherActive()
    val apps by produceState(initialValue = emptyList<InstalledApp>(), launcher, launcherOn) {
        value = if (launcherOn) withContext(Dispatchers.IO) { vm.app.kiosk.allowedInstalledApps() } else emptyList()
    }
    val favChannels = remember(live, favorites) { live.channels.filter { it.key in favorites } }
    val firstTile = remember { FocusRequester() }
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val f = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            clock = f.format(Date())
            delay(15_000)
        }
    }
    val pad = if (tv) 32.dp else 16.dp

    Box(Modifier.fillMaxSize()) {
        if (launcherOn && launcher.backgroundUrl.isNotBlank()) {
            AsyncImage(model = launcher.backgroundUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.35f)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = pad, vertical = if (tv) 24.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (launcherOn && launcher.logoUrl.isNotBlank()) {
                        AsyncImage(model = launcher.logoUrl, contentDescription = null, modifier = Modifier.height(48.dp).width(140.dp), contentScale = ContentScale.Fit)
                        Spacer(Modifier.width(16.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(launcher.title.takeIf { launcherOn && it.isNotBlank() } ?: BuildConfig.APP_NAME, style = MaterialTheme.typography.headlineMedium)
                        val welcome = if (launcherOn) launcher.welcomeText else ""
                        if (welcome.isNotBlank()) Text(welcome, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (settings.supportMessage.isNotBlank()) Text(settings.supportMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(clock, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (suspended) {
                    Text("Kiosk mod je privremeno isključen (servisni pristup).", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = pad))
                }
            }

            // glavne pločice
            item {
                val tileW = if (tv) 200.dp else 150.dp
                LazyRow(contentPadding = PaddingValues(horizontal = pad, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (settings.showLive) item { Tile("Uživo", Modifier.width(tileW), icon = Icons.Filled.LiveTv, accent = true, focusRequester = firstTile) { vm.open(Screen.Live) } }
                    if (settings.showLive && tv) item { Tile("TV vodič", Modifier.width(tileW), icon = Icons.Filled.DateRange) { vm.open(Screen.Guide) } }
                    if (settings.showLive && vm.app.prefs.lastChannelKey.isNotBlank()) item { Tile("Poslednji kanal", Modifier.width(tileW), icon = Icons.Filled.History) { activity.playLastChannel() } }
                    if (settings.showMovies) item { Tile("Filmovi", Modifier.width(tileW), icon = Icons.Filled.Movie) { vm.open(Screen.Movies) } }
                    if (settings.showSeries) item { Tile("Serije", Modifier.width(tileW), icon = Icons.Filled.VideoLibrary) { vm.open(Screen.Series) } }
                    items(apps, key = { it.pkg }) { a -> Tile(a.label, Modifier.width(tileW), drawable = a.icon) { runCatching { context.startActivity(a.launch) } } }
                    if (launcherOn && !launcher.locked) item { Tile("Sve aplikacije", Modifier.width(tileW), icon = Icons.Filled.Apps) { vm.open(Screen.Apps) } }
                    if (launcherOn && launcher.showSystemSettingsTile && (!launcher.blockSettings || suspended)) {
                        item { Tile("Podešavanja uređaja", Modifier.width(tileW), icon = Icons.Filled.SettingsApplications) { vm.app.kiosk.openSystemSettings(activity) } }
                    }
                    item { Tile("Podešavanja", Modifier.width(tileW), icon = Icons.Filled.Settings) { vm.open(Screen.Settings) } }
                }
            }

            if (progress.isNotEmpty()) {
                item { SectionTitle("Nastavi gledanje", Modifier.padding(horizontal = pad)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = pad), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(progress, key = { it.key }) { p ->
                            Poster(
                                title = p.title,
                                image = p.poster,
                                progress = if (p.durationMs > 0) p.positionMs.toFloat() / p.durationMs else -1f,
                                modifier = Modifier.width(if (tv) 150.dp else 120.dp),
                            ) {
                                PlaySession.play(context, PlayItem(kind = p.kind, title = p.title, url = p.url, poster = p.poster, progressKey = p.key, seriesKey = p.seriesKey, playlistId = p.playlistId))
                            }
                        }
                    }
                }
            }

            if (favChannels.isNotEmpty()) {
                item { SectionTitle("Omiljeni kanali", Modifier.padding(horizontal = pad)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = pad), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(favChannels, key = { it.key }) { ch ->
                            Focusable(onClick = { vm.playChannel(favChannels, ch) }, modifier = Modifier.width(if (tv) 170.dp else 130.dp)) { _ ->
                                Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    ChannelLogo(ch.logo, ch.name, 64.dp)
                                    Spacer(Modifier.height(8.dp))
                                    Text(ch.name, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (tv) LaunchedEffect(Unit) { runCatching { delay(150); firstTile.requestFocus() } }
}
