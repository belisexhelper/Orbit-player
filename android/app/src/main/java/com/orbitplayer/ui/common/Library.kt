package com.orbitplayer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.orbitplayer.core.Fmt
import com.orbitplayer.data.db.SeriesEntity
import com.orbitplayer.data.db.VodEntity
import com.orbitplayer.playback.PlayItem
import com.orbitplayer.ui.AppViewModel
import com.orbitplayer.ui.Route
import com.orbitplayer.ui.components.ChannelLogo
import com.orbitplayer.ui.components.EmptyState
import com.orbitplayer.ui.components.ListRow
import com.orbitplayer.ui.components.OrbitButton
import com.orbitplayer.ui.components.OrbitTextField
import com.orbitplayer.ui.components.PosterCard
import com.orbitplayer.ui.components.SectionHeader
import com.orbitplayer.ui.theme.LocalIsTv
import com.orbitplayer.ui.theme.Orbit
import kotlinx.coroutines.delay

/** Shown when no playlist has been added yet */
@Composable
fun NoPlaylist(vm: AppViewModel) {
    val fr = remember { FocusRequester() }
    EmptyState(
        "Add your first playlist",
        "Orbit Player is a player only and has no channels of its own. Add the Xtream Codes account or M3U address you got from your provider.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OrbitButton("Add playlist", icon = Icons.Filled.Add, primary = true, focusRequester = fr) { vm.open(Route.AddPlaylist()) }
            OrbitButton("Restore backup") { vm.open(Route.SettingsSection("backup")) }
        }
    }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { fr.requestFocus() }
    }
}

@Composable
fun SearchScreen(vm: AppViewModel) {
    val tv = LocalIsTv.current
    val c = Orbit.colors
    var q by remember { mutableStateOf("") }
    val pid by vm.activePlaylistId.collectAsState()
    val fr = remember { FocusRequester() }
    var history by remember { mutableStateOf(vm.store.searchHistory) }
    val results by produceState(AppViewModel.SearchResults(), q, pid) {
        if (q.trim().length < 2) {
            value = AppViewModel.SearchResults()
            return@produceState
        }
        delay(350)
        value = vm.search(q)
    }
    val pad = if (tv) 28.dp else 16.dp
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = pad, vertical = 16.dp)) {
        item {
            Text("Search", color = c.text, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            OrbitTextField(q, { q = it }, "Channel, programme, movie or series", focusRequester = fr, onDone = {
                vm.rememberSearch(q)
                history = vm.store.searchHistory
            })
        }
        if (q.trim().length < 2 && history.isNotEmpty()) {
            item { SectionHeader("Recent searches", action = { OrbitButton("Clear") { vm.store.searchHistory = emptyList(); history = emptyList() } }) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(history) { h -> OrbitButton(h, icon = Icons.Filled.History) { q = h } }
                }
            }
        }
        if (results.channels.isNotEmpty()) {
            item { SectionHeader("Channels") }
            items(results.channels, key = { "c" + it.key }) { ch ->
                ListRow(vm.displayName(ch), subtitle = "Channel ${ch.num}", leading = { ChannelLogo(ch.logo, ch.name, 56.dp, 34.dp) }) {
                    vm.rememberSearch(q)
                    vm.playChannel(ch, results.channels)
                }
            }
        }
        if (results.programs.isNotEmpty()) {
            item { SectionHeader("TV guide") }
            items(results.programs, key = { "p" + it.first.id }) { (p, ch) ->
                val now = System.currentTimeMillis()
                val live = p.start <= now && p.stop > now
                ListRow(
                    p.title,
                    subtitle = "${vm.displayName(ch)} · ${Fmt.date(p.start)} ${Fmt.time(p.start)}" + if (live) " · on now" else "",
                    leading = { ChannelLogo(ch.logo, ch.name, 56.dp, 34.dp) },
                ) {
                    vm.rememberSearch(q)
                    when {
                        live -> vm.playChannel(ch, listOf(ch))
                        p.stop <= now && ch.archive -> vm.playCatchup(ch, p)
                        else -> vm.toast("Starts ${Fmt.date(p.start)} at ${Fmt.time(p.start)}")
                    }
                }
            }
        }
        if (results.movies.isNotEmpty()) {
            item { SectionHeader("Movies") }
            item { PosterRow(results.movies.map { PosterData(it.key, it.name, it.poster) { vm.rememberSearch(q); vm.open(Route.MovieDetail(it.key)) } }) }
        }
        if (results.series.isNotEmpty()) {
            item { SectionHeader("Series") }
            item { PosterRow(results.series.map { PosterData(it.key, it.name, it.cover) { vm.rememberSearch(q); vm.open(Route.SeriesDetail(it.key)) } }) }
        }
        if (q.trim().length >= 2 && results.isEmpty) {
            item { Text("No results for “$q”.", Modifier.padding(vertical = 16.dp), color = c.textMuted) }
        }
    }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { fr.requestFocus() }
    }
}

data class PosterData(val key: String, val title: String, val image: String, val subtitle: String = "", val progress: Float = -1f, val onClick: () -> Unit)

@Composable
fun PosterRow(items: List<PosterData>) {
    val tv = LocalIsTv.current
    // room around the posters so the enlarged, outlined focused poster is not cut off
    LazyRow(horizontalArrangement = Arrangement.spacedBy(if (tv) 18.dp else 10.dp), contentPadding = if (tv) PaddingValues(horizontal = 10.dp, vertical = 14.dp) else PaddingValues(vertical = 6.dp)) {
        items(items, key = { it.key }) { p ->
            PosterCard(p.title, p.image, Modifier.width(if (tv) 140.dp else 108.dp), subtitle = p.subtitle, progress = p.progress) { p.onClick() }
        }
    }
}

/** My list: continue watching, favorite channels, movies and series */
@Composable
fun FavoritesScreen(vm: AppViewModel) {
    val tv = LocalIsTv.current
    val c = Orbit.colors
    val favorites by vm.favorites.collectAsState()
    val channels by vm.channels.collectAsState()
    val cont by vm.continueWatching.collectAsState()
    val favChannels = remember(channels, favorites) { channels.filter { it.key in favorites } }
    val favMovies by produceState(emptyList<VodEntity>(), favorites) { value = vm.app.db.content().vodByKeys(favorites.toList()) }
    val favSeries by produceState(emptyList<SeriesEntity>(), favorites) { value = vm.app.db.content().seriesByKeys(favorites.toList()) }
    val pad = if (tv) 28.dp else 16.dp
    if (favChannels.isEmpty() && favMovies.isEmpty() && favSeries.isEmpty() && cont.isEmpty()) {
        EmptyState("Your list is empty", if (tv) "Hold OK on a channel, movie or series to add it to favorites." else "Long-press a channel, movie or series to add it to favorites.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = pad, vertical = 16.dp)) {
        item { Text("My list", color = c.text, style = MaterialTheme.typography.headlineSmall) }
        if (cont.isNotEmpty()) {
            item { SectionHeader("Continue watching") }
            item {
                PosterRow(cont.map { p ->
                    PosterData(p.key, p.title, p.poster, if (p.kind == "episode") "Episode" else Fmt.duration(p.durationMs - p.positionMs) + " left",
                        if (p.durationMs > 0) p.positionMs.toFloat() / p.durationMs else -1f) {
                        vm.playItem(PlayItem(kind = p.kind, title = p.title, url = p.url, poster = p.poster, progressKey = p.key, seriesKey = p.seriesKey, playlistId = p.playlistId))
                    }
                })
            }
        }
        if (favChannels.isNotEmpty()) {
            item { SectionHeader("Favorite channels") }
            items(favChannels, key = { it.key }) { ch ->
                ListRow(vm.displayName(ch), subtitle = "Channel ${ch.num}", leading = { ChannelLogo(ch.logo, ch.name, 56.dp, 34.dp) }, onLongClick = { vm.toggleFavorite(ch.key, "live") }) {
                    vm.playChannel(ch, favChannels)
                }
            }
        }
        if (favMovies.isNotEmpty()) {
            item { SectionHeader("Movies") }
            item { PosterRow(favMovies.map { PosterData(it.key, it.name, it.poster) { vm.open(Route.MovieDetail(it.key)) } }) }
        }
        if (favSeries.isNotEmpty()) {
            item { SectionHeader("Series") }
            item { PosterRow(favSeries.map { PosterData(it.key, it.name, it.cover) { vm.open(Route.SeriesDetail(it.key)) } }) }
        }
    }
}
