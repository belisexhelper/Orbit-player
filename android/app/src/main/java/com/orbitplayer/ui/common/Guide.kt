package com.orbitplayer.ui.common

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.rememberLazyListState
import com.orbitplayer.ui.tv.TvEntryPoint
import com.orbitplayer.ui.components.ListRow
import com.orbitplayer.ui.components.listItemFocus
import com.orbitplayer.ui.components.restoreListFocus
import com.orbitplayer.ui.components.rememberListFocus
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.orbitplayer.core.Fmt
import com.orbitplayer.data.db.ChannelEntity
import com.orbitplayer.data.db.ProgramEntity
import com.orbitplayer.ui.AppViewModel
import com.orbitplayer.ui.components.ActionsDialog
import com.orbitplayer.ui.components.ChannelLogo
import com.orbitplayer.ui.components.ChoiceDialog
import com.orbitplayer.ui.components.EmptyState
import com.orbitplayer.ui.components.Focusable
import com.orbitplayer.ui.components.MenuAction
import com.orbitplayer.ui.components.OrbitButton
import com.orbitplayer.ui.components.ProgressLine
import com.orbitplayer.ui.theme.LocalIsTv
import com.orbitplayer.ui.theme.Orbit
import kotlinx.coroutines.delay

private const val HALF_HOUR = 30 * 60_000L

fun guideStart(now: Long = System.currentTimeMillis()) = now / HALF_HOUR * HALF_HOUR - HALF_HOUR

/** Full TV guide: channels in rows, time across */
@Composable
fun GuideScreen(vm: AppViewModel) {
    val tv = LocalIsTv.current
    val playlists by vm.playlists.collectAsState()
    if (playlists?.isEmpty() == true) return NoPlaylist(vm)
    val groups by vm.groups.collectAsState()
    val selected by vm.selectedGroup.collectAsState()
    val channels by vm.groupChannels.collectAsState()
    val settings by vm.settings.collectAsState()
    val c = Orbit.colors
    var windowStart by remember { mutableLongStateOf(guideStart()) }
    var focused by remember { mutableStateOf<Pair<ChannelEntity, ProgramEntity>?>(null) }
    var details by remember { mutableStateOf<Pair<ChannelEntity, ProgramEntity>?>(null) }
    val minStart = guideStart() - settings.epgPastDays * 86_400_000L
    val maxStart = guideStart() + 7 * 86_400_000L

    fun open(ch: ChannelEntity, p: ProgramEntity) {
        val now = System.currentTimeMillis()
        when {
            p.start <= now && p.stop > now -> vm.playChannel(ch, channels)
            p.stop <= now && ch.archive -> vm.playCatchup(ch, p)
            else -> details = ch to p
        }
    }

    // TV: groups on the left (like Live TV), the guide grid on the right
    val groupFocus = rememberListFocus()
    if (tv) TvEntryPoint(groupFocus.requester)
    // back from the player: the channel that is playing (also after changing channel/group in the player)
    val playingItem by vm.playback.item.collectAsState()
    val playingFocus = remember { FocusRequester() }
    if (tv) com.orbitplayer.ui.tv.TvAfterPlayer(playingFocus)
    var pendingGroup by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingGroup) {
        val g = pendingGroup ?: return@LaunchedEffect
        delay(250)
        if (g != vm.selectedGroup.value) vm.selectGroup(g)
    }
    Row(Modifier.fillMaxSize()) {
    if (tv) {
        val gState = rememberLazyListState((groups.indexOfFirst { it.id == selected } - 3).coerceAtLeast(0))
        LazyColumn(
            state = gState,
            modifier = Modifier.width(220.dp).fillMaxHeight().padding(start = 12.dp, top = 18.dp).restoreListFocus(groupFocus),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(groups, key = { _, g -> g.id }) { _, g ->
                ListRow(
                    g.name,
                    modifier = Modifier.listItemFocus(groupFocus, g.id, g.id == selected),
                    selected = g.id == selected,
                    onFocus = { pendingGroup = g.id },
                    trailing = { Text("${g.count}", color = c.textMuted, style = MaterialTheme.typography.bodySmall) },
                ) { vm.selectGroup(g.id) }
            }
        }
    }
    Column(Modifier.weight(1f).fillMaxHeight().padding(start = if (tv) 14.dp else 0.dp, end = if (tv) 24.dp else 0.dp, top = if (tv) 18.dp else 0.dp)) {
        // header
        Row(Modifier.fillMaxWidth().padding(horizontal = if (tv) 0.dp else 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("TV guide", color = c.text, style = MaterialTheme.typography.headlineSmall)
            Text(Fmt.date(windowStart + HALF_HOUR), color = c.textMuted, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (!tv) OrbitButton("Now") { windowStart = guideStart() }
        }
        if (!tv) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups, key = { it.id }) { g -> OrbitButton(g.name, primary = g.id == selected) { vm.selectGroup(g.id) } }
            }
            Spacer(Modifier.height(6.dp))
        }
        // info about the focused programme (TV)
        if (tv) {
            val f = focused
            Column(Modifier.fillMaxWidth().height(96.dp).padding(vertical = 4.dp)) {
                if (f != null) {
                    val (ch, p) = f
                    Text(p.title, color = c.text, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${vm.displayName(ch)}  ·  ${Fmt.date(p.start)}  ${Fmt.time(p.start)} – ${Fmt.time(p.stop)}  ·  ${Fmt.minutes(p.stop - p.start)}", color = c.accent, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    if (p.description.isNotBlank()) Text(p.description, color = c.textMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (channels.isEmpty()) {
            EmptyState("No channels", "This group is empty.")
            return@Column
        }
        EpgGrid(
            channels = channels,
            windowStart = windowStart,
            visibleMinutes = 120,
            displayName = vm::displayName,
            loadPrograms = { ch, from, to -> vm.programs(ch, from, to) },
            onWindowChange = { windowStart = it.coerceIn(minStart, maxStart) },
            canGoBack = { _ -> windowStart > minStart },
            onProgramFocus = { ch, p -> focused = ch to p },
            onProgramClick = ::open,
            onProgramLongClick = { ch, p -> details = ch to p },
            onChannelClick = { ch -> vm.playChannel(ch, channels) },
            playingKey = if (tv) playingItem?.channel?.key else null,
            playingFocus = playingFocus,
        )
    }
    }

    details?.let { (ch, p) ->
        val now = System.currentTimeMillis()
        ActionsDialog(
            "${p.title}\n${Fmt.date(p.start)} ${Fmt.time(p.start)} – ${Fmt.time(p.stop)} · ${vm.displayName(ch)}" + if (p.description.isNotBlank()) "\n\n${p.description.take(400)}" else "",
            buildList {
                add(MenuAction("Watch ${vm.displayName(ch)} now", Icons.Filled.PlayArrow) { vm.playChannel(ch, channels) })
                if (p.stop <= now && ch.archive) add(MenuAction("Watch from archive", Icons.Filled.History) { vm.playCatchup(ch, p) })
            },
            onDismiss = { details = null },
        )
    }
}

/** Grid of programmes. TV: moves in 30-minute steps with the remote; phone: drag sideways. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EpgGrid(
    channels: List<ChannelEntity>,
    windowStart: Long,
    visibleMinutes: Int,
    displayName: (ChannelEntity) -> String,
    loadPrograms: suspend (ChannelEntity, Long, Long) -> List<ProgramEntity>,
    onWindowChange: (Long) -> Unit,
    canGoBack: (ChannelEntity) -> Boolean,
    onProgramFocus: (ChannelEntity, ProgramEntity) -> Unit,
    onProgramClick: (ChannelEntity, ProgramEntity) -> Unit,
    onProgramLongClick: (ChannelEntity, ProgramEntity) -> Unit,
    onChannelClick: (ChannelEntity) -> Unit,
    playingKey: String? = null,
    playingFocus: FocusRequester? = null,
) {
    val tv = LocalIsTv.current
    val c = Orbit.colors
    val channelCol: Dp = if (tv) 190.dp else 116.dp
    val rowH: Dp = if (tv) 60.dp else 64.dp
    val windowMs = visibleMinutes * 60_000L
    val density = LocalDensity.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val first = remember { FocusRequester() }
    var latestStart by remember { mutableLongStateOf(windowStart) }
    latestStart = windowStart

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val timeline = maxWidth - channelCol
        val dpPerMin = timeline / visibleMinutes.toFloat()
        fun xOf(t: Long): Dp = dpPerMin * ((t - windowStart) / 60_000f)

        Column(Modifier.fillMaxSize()) {
            // time scale
            Box(Modifier.fillMaxWidth().height(30.dp)) {
                var t = windowStart
                while (t <= windowStart + windowMs) {
                    Text(Fmt.time(t), Modifier.offset(x = channelCol + xOf(t) + 6.dp, y = 4.dp), color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                    t += HALF_HOUR
                }
            }
            Box(Modifier.fillMaxSize()) {
                // a different channel list (another group) starts at the top
                val listState = remember(channels.firstOrNull()?.key, channels.size) {
                    androidx.compose.foundation.lazy.LazyListState((channels.indexOfFirst { it.key == playingKey } - 2).coerceAtLeast(0))
                }
                // keep the playing channel on screen while zapping in the player
                LaunchedEffect(playingKey, channels.size) {
                    val i = channels.indexOfFirst { it.key == playingKey }
                    if (i < 0) return@LaunchedEffect
                    val vis = listState.layoutInfo.visibleItemsInfo
                    if (vis.none { it.index == i } || vis.first().index == i || vis.last().index == i) listState.scrollToItem((i - 2).coerceAtLeast(0))
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusGroup()
                        .then(
                            if (!tv) Modifier.pointerInput(dpPerMin) {
                                detectHorizontalDragGestures { change, drag ->
                                    change.consume()
                                    val minutes = -drag / with(density) { dpPerMin.toPx() }
                                    onWindowChange(latestStart + (minutes * 60_000).toLong())
                                }
                            } else Modifier,
                        ),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    itemsIndexed(channels, key = { _, ch -> ch.key }) { i, ch ->
                        val bucket = windowStart / (2 * 3600_000L)
                        val programs by produceState(emptyList<ProgramEntity>(), ch.key, bucket) {
                            val b = bucket * 2 * 3600_000L
                            value = loadPrograms(ch, b - 2 * 3600_000L, b + 8 * 3600_000L)
                        }
                        Row(Modifier.fillMaxWidth().height(rowH).padding(vertical = 2.dp)) {
                            val isPlayingRow = ch.key == playingKey && playingFocus != null
                            val nowVisible = isPlayingRow && programs.any { it.start <= now && it.stop > now && it.stop > windowStart && it.start < windowStart + windowMs }
                            Focusable(
                                focusRequester = if (isPlayingRow && !nowVisible) playingFocus else null,
                                onClick = { onChannelClick(ch) },
                                modifier = Modifier.width(channelCol - 4.dp).fillMaxHeight(),
                                shape = RoundedCornerShape(8.dp),
                                scaleOnFocus = 1f,
                            ) { _ ->
                                Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ChannelLogo(ch.logo, ch.name, if (tv) 56.dp else 48.dp, if (tv) 34.dp else 30.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(displayName(ch), color = c.text, style = MaterialTheme.typography.bodyMedium, maxLines = if (tv) 1 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (ch.archive) Icon(Icons.Filled.History, "Archive", Modifier.padding(start = 4.dp).size(16.dp), tint = c.accentAlt)
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))) {
                                val visible = programs.filter { it.stop > windowStart && it.start < windowStart + windowMs }
                                if (visible.isEmpty()) {
                                    Box(Modifier.fillMaxSize().background(c.surface, RoundedCornerShape(8.dp)), contentAlignment = Alignment.CenterStart) {
                                        Text(if (ch.epgKey.isBlank()) "No guide for this channel" else "No information", Modifier.padding(horizontal = 12.dp), color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                visible.forEachIndexed { pi, p ->
                                    key(p.id) {
                                        val s = maxOf(p.start, windowStart)
                                        val e = minOf(p.stop, windowStart + windowMs)
                                        val isNow = p.start <= now && p.stop > now
                                        val past = p.stop <= now
                                        val dim = past && !ch.archive
                                        Focusable(
                                            onClick = { onProgramClick(ch, p) },
                                            onLongClick = { onProgramLongClick(ch, p) },
                                            onFocus = { onProgramFocus(ch, p) },
                                            modifier = Modifier
                                                .offset(x = xOf(s))
                                                .width((dpPerMin * ((e - s) / 60_000f) - 3.dp).coerceAtLeast(4.dp))
                                                .fillMaxHeight()
                                                .onPreviewKeyEvent { ev ->
                                                    if (!tv || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                                    when {
                                                        ev.key == Key.DirectionRight && p.stop >= windowStart + windowMs -> {
                                                            onWindowChange(windowStart + HALF_HOUR); true
                                                        }
                                                        ev.key == Key.DirectionLeft && p.start <= windowStart && canGoBack(ch) -> {
                                                            onWindowChange(windowStart - HALF_HOUR); true
                                                        }
                                                        else -> false
                                                    }
                                                },
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isNow) c.accent.copy(alpha = 0.16f) else c.surface,
                                            scaleOnFocus = 1f,
                                            focusRequester = if (isPlayingRow && nowVisible && isNow) playingFocus else if (i == 0 && pi == 0) first else null,
                                        ) { focused ->
                                            Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (past && ch.archive) Icon(Icons.Filled.History, null, Modifier.size(14.dp).padding(end = 2.dp), tint = c.accentAlt)
                                                    Text(
                                                        p.title,
                                                        color = if (dim) c.textMuted else c.text,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = if (isNow || focused) FontWeight.SemiBold else FontWeight.Normal,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                Text("${Fmt.time(p.start)} – ${Fmt.time(p.stop)}", color = c.textMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                                if (isNow) {
                                                    Spacer(Modifier.weight(1f))
                                                    ProgressLine(((now - p.start).toFloat() / (p.stop - p.start)), Modifier.fillMaxWidth(), height = 3.dp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // "now" line
                if (now in windowStart..(windowStart + windowMs)) {
                    Box(Modifier.offset(x = channelCol + xOf(now)).width(2.dp).fillMaxHeight().background(c.accent))
                }
            }
        }
    }
}
