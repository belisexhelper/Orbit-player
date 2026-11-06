package com.orbitplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orbitplayer.data.db.ChannelEntity
import com.orbitplayer.data.db.ProgramEntity
import com.orbitplayer.player.PlayItem
import com.orbitplayer.player.PlaySession
import com.orbitplayer.ui.MainViewModel
import com.orbitplayer.ui.ScreenHeader
import com.orbitplayer.ui.components.ActionButton
import com.orbitplayer.ui.components.ChannelLogo
import com.orbitplayer.ui.components.EmptyState
import com.orbitplayer.ui.components.Focusable
import com.orbitplayer.ui.components.ListRow
import com.orbitplayer.ui.theme.LocalIsTv
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val GROUP_ALL = "__all"
const val GROUP_FAV = "__fav"

private val timeFmt get() = SimpleDateFormat("HH:mm", Locale.getDefault())

/** Trenutne emisije za listu kanala (osvežava se svakog minuta) */
@Composable
fun rememberNowPlaying(vm: MainViewModel, channels: List<ChannelEntity>): Map<String, ProgramEntity> {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    val map by produceState(emptyMap<String, ProgramEntity>(), channels, now / 60_000) {
        val keys = channels.map { it.epgKey }.filter { it.isNotBlank() }.distinct()
        val out = HashMap<String, ProgramEntity>()
        for (chunk in keys.chunked(400)) {
            vm.app.db.epg().programsFor(chunk, now, now + 1).forEach { out[it.epgKey] = it }
        }
        value = out
    }
    return map
}

@Composable
fun LiveScreen(vm: MainViewModel) {
    val tv = LocalIsTv.current
    val data by vm.live.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val activePid by vm.activePlaylistId.collectAsState()
    var group by remember { mutableStateOf(if (favorites.isNotEmpty()) GROUP_FAV else GROUP_ALL) }
    var focusedChannel by remember { mutableStateOf<ChannelEntity?>(null) }

    val channels = remember(data, group, favorites) {
        when (group) {
            GROUP_ALL -> data.channels
            GROUP_FAV -> data.channels.filter { it.key in favorites }
            else -> data.channels.filter { it.categoryId == group }
        }
    }
    val now = rememberNowPlaying(vm, channels)
    val groups = remember(data, favorites) {
        buildList {
            if (favorites.isNotEmpty()) add(GROUP_FAV to "Omiljeni")
            add(GROUP_ALL to "Svi kanali")
            data.categories.forEach { add(it.categoryId to it.name) }
        }
    }

    if (data.channels.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Uživo", vm)
            EmptyState("Nema kanala", "Lista se još učitava ili ne sadrži kanale uživo.")
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Uživo", vm) {
            if (playlists.size > 1) PlaylistSwitcher(vm, playlists.map { it.id to it.name }, activePid)
            if (tv) ActionButton("TV vodič") { vm.open(com.orbitplayer.ui.Screen.Guide) }
        }
        if (tv) {
            Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // grupe
                LazyColumn(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(groups, key = { it.first }) { (id, name) ->
                        ListRow(name, selected = id == group, onLongClick = { if (id != GROUP_ALL && id != GROUP_FAV) vm.hideGroup("$activePid:live:$id") }) { group = id }
                    }
                }
                // kanali
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(channels, key = { it.key }) { ch ->
                        ChannelRow(ch, now[ch.epgKey], ch.key in favorites, onFocus = { focusedChannel = ch }, onLong = { vm.toggleFavorite(ch.key, "live") }) { vm.playChannel(channels, ch) }
                    }
                }
                // vodič za kanal u fokusu
                Box(Modifier.width(360.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)) {
                    focusedChannel?.let { ChannelGuide(vm, it) } ?: Text("Izaberite kanal", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups, key = { it.first }) { (id, name) ->
                    ActionButton(name, primary = id == group) { group = id }
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                items(channels, key = { it.key }) { ch ->
                    ChannelRow(ch, now[ch.epgKey], ch.key in favorites, onLong = { vm.toggleFavorite(ch.key, "live") }) { vm.playChannel(channels, ch) }
                }
            }
        }
    }
}

@Composable
fun PlaylistSwitcher(vm: MainViewModel, items: List<Pair<String, String>>, active: String) {
    val name = items.firstOrNull { it.first == active }?.second ?: "Lista"
    ActionButton("Lista: $name") {
        val idx = items.indexOfFirst { it.first == active }
        val next = items[(idx + 1) % items.size]
        vm.setActivePlaylist(next.first)
    }
}

@Composable
fun ChannelRow(ch: ChannelEntity, program: ProgramEntity?, favorite: Boolean, onFocus: () -> Unit = {}, onLong: () -> Unit, onClick: () -> Unit) {
    val f = timeFmt
    Focusable(onClick = onClick, onLongClick = onLong, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), background = Color.Transparent, scaleOnFocus = false) { focused ->
        if (focused) LaunchedEffect(ch.key) { onFocus() }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(ch.num.toString(), Modifier.width(44.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            ChannelLogo(ch.logo, ch.name)
            Column(Modifier.weight(1f)) {
                Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                if (program != null) {
                    Text("${f.format(Date(program.start))} ${program.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val frac = ((System.currentTimeMillis() - program.start).toFloat() / (program.stop - program.start)).coerceIn(0f, 1f)
                    Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(frac).height(3.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
            if (ch.archive) Text("arhiva", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (favorite) Icon(Icons.Filled.Star, "Omiljeni", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Raspored jednog kanala (desna kolona na TV-u) — prošle emisije sa arhivom mogu da se puste */
@Composable
fun ChannelGuide(vm: MainViewModel, ch: ChannelEntity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val f = timeFmt
    val progs by produceState(emptyList<ProgramEntity>(), ch.key) {
        val now = System.currentTimeMillis()
        val from = if (ch.archive) now - ch.archiveDays.coerceAtMost(7) * 86_400_000L else now - 3 * 3600_000L
        value = if (ch.epgKey.isBlank()) emptyList() else vm.app.db.epg().programs(ch.epgKey, from, now + 24 * 3600_000L)
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(ch.name, style = MaterialTheme.typography.titleLarge)
        if (progs.isEmpty()) {
            Text("Nema TV vodiča za ovaj kanal.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            return@Column
        }
        val now = System.currentTimeMillis()
        val startIdx = progs.indexOfFirst { it.stop > now }.coerceAtLeast(0)
        val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = startIdx)
        LazyColumn(state = state, modifier = Modifier.padding(top = 8.dp)) {
            items(progs, key = { it.id }) { p ->
                val past = p.stop < now
                val isNow = p.start <= now && p.stop > now
                ListRow(
                    title = "${f.format(Date(p.start))}  ${p.title}",
                    subtitle = when {
                        isNow -> "Sada"
                        past && ch.archive -> "Pusti iz arhive"
                        else -> p.description.take(80)
                    },
                    selected = isNow,
                ) {
                    scope.launch {
                        when {
                            isNow -> vm.playChannel(listOf(ch), ch)
                            past && ch.archive -> {
                                val url = vm.app.repo.catchupUrl(ch, p) ?: return@launch
                                PlaySession.play(context, PlayItem(kind = "catchup", title = "${ch.name}: ${p.title}", url = url, poster = ch.logo, playlistId = ch.playlistId))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** TV vodič u obliku mreže (kao TiviMate): kanali u redovima, vreme po horizontali */
@Composable
fun GuideScreen(vm: MainViewModel) {
    val data by vm.live.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var group by remember { mutableStateOf(if (favorites.isNotEmpty()) GROUP_FAV else GROUP_ALL) }
    val hourMs = 3600_000L
    var windowStart by remember { mutableLongStateOf((System.currentTimeMillis() / (30 * 60_000L)) * (30 * 60_000L) - 30 * 60_000L) }
    val windowMs = 3 * hourMs
    val dpPerMin = 3.5f
    val channelW = 190.dp
    val f = timeFmt

    val channels = remember(data, group, favorites) {
        when (group) {
            GROUP_ALL -> data.channels
            GROUP_FAV -> data.channels.filter { it.key in favorites }
            else -> data.channels.filter { it.categoryId == group }
        }
    }
    val groups = remember(data, favorites) {
        buildList {
            if (favorites.isNotEmpty()) add(GROUP_FAV to "Omiljeni")
            add(GROUP_ALL to "Svi")
            data.categories.forEach { add(it.categoryId to it.name) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("TV vodič", vm) {
            ActionButton("← 1 h") { windowStart -= hourMs }
            ActionButton("Sada") { windowStart = (System.currentTimeMillis() / (30 * 60_000L)) * (30 * 60_000L) - 30 * 60_000L }
            ActionButton("1 h →") { windowStart += hourMs }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups, key = { it.first }) { (id, name) -> ActionButton(name, primary = id == group) { group = id } }
        }
        // vremenska osa
        Row(Modifier.padding(start = 24.dp + channelW, top = 10.dp, bottom = 6.dp)) {
            for (i in 0 until 6) {
                Text(f.format(Date(windowStart + i * 30 * 60_000L)), Modifier.width((30 * dpPerMin).dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            items(channels, key = { it.key }) { ch ->
                val progs by produceState(emptyList<ProgramEntity>(), ch.key, windowStart) {
                    value = if (ch.epgKey.isBlank()) emptyList() else vm.app.db.epg().programs(ch.epgKey, windowStart, windowStart + windowMs)
                }
                Row(Modifier.height(64.dp).padding(vertical = 2.dp)) {
                    Focusable(onClick = { vm.playChannel(channels, ch) }, modifier = Modifier.width(channelW).fillMaxHeight(), shape = RoundedCornerShape(8.dp), scaleOnFocus = false) { _ ->
                        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            ChannelLogo(ch.logo, ch.name, 40.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Box(Modifier.fillMaxHeight().width((windowMs / 60_000 * dpPerMin).dp)) {
                        if (progs.isEmpty()) {
                            Box(Modifier.padding(start = 4.dp).fillMaxSize().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface)) {
                                Text("Nema podataka", Modifier.padding(10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        progs.forEach { p ->
                            val s = maxOf(p.start, windowStart)
                            val e = minOf(p.stop, windowStart + windowMs)
                            if (e > s) {
                                val x = ((s - windowStart) / 60_000f * dpPerMin).dp
                                val w = ((e - s) / 60_000f * dpPerMin).dp
                                val now = System.currentTimeMillis()
                                val isNow = p.start <= now && p.stop > now
                                Focusable(
                                    onClick = {
                                        scope.launch {
                                            when {
                                                isNow -> vm.playChannel(channels, ch)
                                                p.stop < now && ch.archive -> vm.app.repo.catchupUrl(ch, p)?.let { url ->
                                                    PlaySession.play(context, PlayItem(kind = "catchup", title = "${ch.name}: ${p.title}", url = url, poster = ch.logo))
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.offset(x = x).width(w).fillMaxHeight().padding(start = 4.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    background = if (isNow) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
                                    scaleOnFocus = false,
                                ) { _ ->
                                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                        Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                        Text("${f.format(Date(p.start))}–${f.format(Date(p.stop))}", maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
