package com.orbitplayer.ui.common

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orbitplayer.core.Fmt
import com.orbitplayer.data.db.ChannelEntity
import com.orbitplayer.data.db.ProgramEntity
import com.orbitplayer.ui.AppViewModel
import com.orbitplayer.ui.components.ChannelLogo
import com.orbitplayer.ui.components.ChoiceDialog
import com.orbitplayer.ui.components.EmptyState
import com.orbitplayer.ui.components.Focusable
import com.orbitplayer.ui.components.ListRow
import com.orbitplayer.ui.components.OrbitButton
import com.orbitplayer.ui.components.ProgressLine
import com.orbitplayer.ui.theme.LocalIsTv
import com.orbitplayer.ui.theme.Orbit
import com.orbitplayer.ui.tv.TvEntryPoint
import kotlinx.coroutines.delay
import java.util.Calendar

private const val DAY = 86_400_000L

private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dayLabel(offset: Int, dayStart: Long): String = when (offset) {
    0 -> "Today"
    1 -> "Yesterday"
    else -> Fmt.date(dayStart)
}

/**
 * Catch-up: channels with an archive → day → programmes that already aired (or are on now and can be
 * watched from the beginning). Without guide data, the day is offered in one-hour blocks.
 */
@Composable
fun CatchupScreen(vm: AppViewModel) {
    val tv = LocalIsTv.current
    val c = Orbit.colors
    val playlists by vm.playlists.collectAsState()
    if (playlists?.isEmpty() == true) return NoPlaylist(vm)
    val all by vm.channels.collectAsState()
    val archive = remember(all) { all.filter { it.archive } }
    if (archive.isEmpty()) {
        EmptyState("No archive", "None of your channels offers catch-up (archive). Channels that do are marked with the history icon.")
        return
    }
    var chKey by remember { mutableStateOf(vm.store.lastChannelKey.takeIf { k -> archive.any { it.key == k } } ?: archive.first().key) }
    val ch = archive.firstOrNull { it.key == chKey } ?: archive.first()
    var day by remember(ch.key) { mutableIntStateOf(0) }
    var pickChannel by remember { mutableStateOf(false) }
    val now = remember(ch.key, day) { System.currentTimeMillis() }
    val maxDays = ch.archiveDays.coerceIn(1, 14)
    val dayStart = startOfDay(now) - day * DAY
    val programmes by produceState<List<ProgramEntity>?>(null, ch.key, day) {
        value = null
        val earliest = now - maxDays * DAY
        val to = minOf(dayStart + DAY, now)
        val list = vm.programs(ch, dayStart - 3 * 3600_000L, to)
            .filter { it.start < to && it.stop > dayStart && it.start >= earliest && it.start < now }
            .distinctBy { it.start }
        value = list.ifEmpty {
            // no guide: whole hours
            var t = maxOf(dayStart, earliest / 3600_000L * 3600_000L + 3600_000L)
            buildList {
                while (t < to) {
                    add(ProgramEntity(-t, ch.epgKey, ch.playlistId, t, t + 3600_000L, "Archive ${Fmt.time(t)} – ${Fmt.time(t + 3600_000L)}", ""))
                    t += 3600_000L
                }
            }
        }
    }

    val dayList: @Composable (Modifier) -> Unit = { m ->
        if (tv) {
            LazyColumn(m, contentPadding = PaddingValues(vertical = 4.dp)) {
                items((0..maxDays).toList()) { d ->
                    ListRow(dayLabel(d, startOfDay(now) - d * DAY), selected = d == day, onFocus = { day = d }) { day = d }
                }
            }
        } else {
            LazyRow(m, contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items((0..maxDays).toList()) { d -> OrbitButton(dayLabel(d, startOfDay(now) - d * DAY), primary = d == day) { day = d } }
            }
        }
    }

    val programmeList: @Composable (Modifier) -> Unit = { m ->
        val list = programmes
        when {
            list == null -> Box(m)
            list.isEmpty() -> Box(m) { EmptyState("Nothing archived", "No programmes for this day yet.") }
            else -> {
                // today: start near the latest programmes
                val state = rememberLazyListState(if (day == 0) (list.size - 6).coerceAtLeast(0) else 0)
                LazyColumn(m, state = state, contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(list, key = { it.start }) { p ->
                        val onNow = p.stop > now
                        Focusable(onClick = { vm.playCatchup(ch, p) }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), scaleOnFocus = 1.02f) { focused ->
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(Fmt.time(p.start), Modifier.width(if (tv) 70.dp else 56.dp), color = if (onNow) c.accent else c.textMuted, style = MaterialTheme.typography.titleMedium)
                                Column(Modifier.weight(1f)) {
                                    Text(p.title, color = c.text, style = MaterialTheme.typography.bodyLarge, fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (onNow) "On now · watch from the beginning" else "${Fmt.time(p.start)} – ${Fmt.time(p.stop)} · ${Fmt.minutes(p.stop - p.start)}",
                                        color = if (onNow) c.accent else c.textMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    )
                                    if (onNow) ProgressLine((now - p.start).toFloat() / (p.stop - p.start), Modifier.fillMaxWidth().padding(top = 4.dp), height = 3.dp)
                                }
                                Icon(if (onNow) Icons.Filled.Replay else Icons.Filled.History, null, Modifier.padding(start = 10.dp).size(22.dp), tint = c.accentAlt)
                            }
                        }
                    }
                }
            }
        }
    }

    if (tv) {
        val entry = remember { FocusRequester() }
        TvEntryPoint(entry)
        Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 24.dp, top = 18.dp)) {
            Text("Catch-up", color = c.text, style = MaterialTheme.typography.headlineSmall)
            Text("Watch what already aired on channels with an archive", color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val idx = archive.indexOfFirst { it.key == ch.key }.coerceAtLeast(0)
                val chState = rememberLazyListState((idx - 3).coerceAtLeast(0))
                LazyColumn(Modifier.width(280.dp).fillMaxHeight().background(c.surface, RoundedCornerShape(12.dp)), state = chState, contentPadding = PaddingValues(6.dp)) {
                    itemsIndexed(archive, key = { _, x -> x.key }) { _, x ->
                        ListRow(
                            vm.displayName(x),
                            subtitle = "${x.archiveDays.coerceAtLeast(1)} days",
                            selected = x.key == ch.key,
                            focusRequester = if (x.key == ch.key) entry else null,
                            leading = { ChannelLogo(x.logo, x.name, 52.dp, 32.dp) },
                            onFocus = { chKey = x.key },
                        ) { chKey = x.key }
                    }
                }
                dayList(Modifier.width(170.dp).fillMaxHeight())
                programmeList(Modifier.weight(1f).fillMaxHeight())
            }
        }
        LaunchedEffect(Unit) {
            repeat(5) {
                delay(150)
                if (runCatching { entry.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            PageTitle("Catch-up", vm)
            Focusable(onClick = { pickChannel = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { _ ->
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChannelLogo(ch.logo, ch.name, 56.dp, 34.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(vm.displayName(ch), color = c.text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text("Archive: ${maxDays} days · tap to change channel", color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Filled.ArrowDropDown, null, tint = c.textMuted)
                }
            }
            Spacer(Modifier.height(8.dp))
            dayList(Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            programmeList(Modifier.fillMaxSize().padding(horizontal = 8.dp))
        }
        if (pickChannel) {
            ChoiceDialog("Channel with archive", archive.map { it.key to vm.displayName(it) }, ch.key, { pickChannel = false }) {
                chKey = it
                pickChannel = false
            }
        }
    }
}
