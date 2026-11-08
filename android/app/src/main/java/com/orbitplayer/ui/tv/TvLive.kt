package com.orbitplayer.ui.tv

import com.orbitplayer.ui.components.listItemFocus
import com.orbitplayer.ui.components.restoreListFocus
import com.orbitplayer.ui.components.rememberListFocus
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orbitplayer.core.AppSettings
import com.orbitplayer.core.Fmt
import com.orbitplayer.data.db.ChannelEntity
import com.orbitplayer.data.db.ProgramEntity
import com.orbitplayer.ui.AppViewModel
import com.orbitplayer.ui.ChannelGroup
import com.orbitplayer.ui.NowNext
import com.orbitplayer.ui.Route
import com.orbitplayer.ui.common.NoPlaylist
import com.orbitplayer.ui.common.PlayerSurface
import com.orbitplayer.ui.components.ActionsDialog
import com.orbitplayer.ui.components.ChannelLogo
import com.orbitplayer.ui.components.EmptyState
import com.orbitplayer.ui.components.Focusable
import com.orbitplayer.ui.components.ListRow
import com.orbitplayer.ui.components.MenuAction
import com.orbitplayer.ui.components.ProgressLine
import com.orbitplayer.ui.theme.Orbit
import kotlinx.coroutines.delay

/** Live TV: groups, channels and the focused channel's programmes (back in time and ahead) */
@Composable
fun TvLiveScreen(vm: AppViewModel) {
    val playlists by vm.playlists.collectAsState()
    if (playlists?.isEmpty() == true) return NoPlaylist(vm)
    val groups by vm.groups.collectAsState()
    val selected by vm.selectedGroup.collectAsState()
    val channels by vm.groupChannels.collectAsState()
    val nowNext by vm.nowNext.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val settings by vm.settings.collectAsState()
    val playing by vm.playback.item.collectAsState()
    val playerOpen by vm.playerOpen.collectAsState()
    val sync by vm.syncStatus.collectAsState()
    var focused by remember { mutableStateOf<ChannelEntity?>(null) }
    var menuFor by remember { mutableStateOf<ChannelEntity?>(null) }
    var groupMenu by remember { mutableStateOf<ChannelGroup?>(null) }

    // Preview: plays the focused channel after a short pause (so fast scrolling doesn't start streams)
    LaunchedEffect(focused, settings.livePreview, playerOpen) {
        val ch = focused ?: return@LaunchedEffect
        if (!settings.livePreview || playerOpen) return@LaunchedEffect
        delay(700)
        if (playing?.channel?.key != ch.key) vm.playChannel(ch, channels, fullscreen = false)
    }

    TvLiveContent(
        groups = groups,
        selectedGroup = selected,
        channels = channels,
        nowNext = nowNext,
        favorites = favorites,
        settings = settings,
        playingKey = playing?.channel?.key,
        focused = focused ?: channels.firstOrNull { it.key == playing?.channel?.key } ?: channels.firstOrNull(),
        loadingText = sync.values.firstOrNull { it.running }?.step ?: "",
        refocusKey = playerOpen,
        displayName = vm::displayName,
        onGroupFocus = { vm.selectGroup(it.id) },
        onGroupLongPress = { groupMenu = it },
        onChannelFocus = { focused = it },
        onChannelClick = { ch -> vm.playChannel(ch, channels, fullscreen = true) },
        onChannelLongPress = { menuFor = it },
        preview = if (settings.livePreview && !playerOpen && playing?.isLive == true) {
            { PlayerSurface(vm.playback, settings, settings.aspect, Modifier.fillMaxSize()) }
        } else null,
        loadPrograms = { ch, from, to -> vm.programs(ch, from, to) },
        onProgramClick = { ch, p ->
            val now = System.currentTimeMillis()
            when {
                p.start <= now && p.stop > now -> vm.playChannel(ch, channels, fullscreen = true)
                p.stop <= now && ch.archive -> vm.playCatchup(ch, p)
                p.stop <= now -> vm.toast("This channel has no archive")
                else -> vm.toast("Starts ${Fmt.date(p.start)} at ${Fmt.time(p.start)}")
            }
        },
        onProgramLongClick = { ch, p ->
            // hold OK on the programme that is on now: watch it from the beginning
            if (ch.archive && p.start <= System.currentTimeMillis()) vm.playCatchup(ch, p)
        },
    )

    menuFor?.let { ch ->
        val fav = ch.key in favorites
        ActionsDialog(vm.displayName(ch), buildList {
            add(MenuAction("Watch", Icons.Filled.PlayArrow) { vm.playChannel(ch, channels) })
            add(MenuAction(if (fav) "Remove from favorites" else "Add to favorites", if (fav) Icons.Filled.StarBorder else Icons.Filled.Star) { vm.toggleFavorite(ch.key, "live") })
            if (ch.archive) add(MenuAction("Programme archive (TV guide)", Icons.Filled.History) { vm.root(Route.Guide) })
            add(MenuAction("Hide channel", Icons.Filled.VisibilityOff) { vm.hideChannel(ch) })
            add(MenuAction("Open in another player", Icons.Filled.OpenInNew) {
                vm.launch {
                    val url = vm.repo.channelUrl(ch)
                    val i = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(url), "video/*")
                    runCatching { vm.app.startActivity(android.content.Intent.createChooser(i, "Open with").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            })
        }, onDismiss = { menuFor = null })
    }
    groupMenu?.let { g ->
        ActionsDialog(g.name, listOf(MenuAction("Hide this group", Icons.Filled.VisibilityOff) { vm.hideGroup(g) }), onDismiss = { groupMenu = null })
    }
}

/**
 * Stateless layout of the Live screen (also used for screenshots):
 * groups | channels | programmes of the focused channel (days back and ahead), so going back in time
 * starts right here: a past programme with ↺ plays from the archive.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvLiveContent(
    groups: List<ChannelGroup>,
    selectedGroup: String,
    channels: List<ChannelEntity>,
    nowNext: Map<String, NowNext>,
    favorites: Set<String>,
    settings: AppSettings,
    playingKey: String?,
    focused: ChannelEntity?,
    loadingText: String,
    refocusKey: Any?,
    displayName: (ChannelEntity) -> String,
    onGroupFocus: (ChannelGroup) -> Unit,
    onGroupLongPress: (ChannelGroup) -> Unit,
    onChannelFocus: (ChannelEntity) -> Unit,
    onChannelClick: (ChannelEntity) -> Unit,
    onChannelLongPress: (ChannelEntity) -> Unit,
    preview: (@Composable () -> Unit)?,
    loadPrograms: suspend (ChannelEntity, Long, Long) -> List<ProgramEntity> = { _, _, _ -> emptyList() },
    onProgramClick: (ChannelEntity, ProgramEntity) -> Unit = { _, _ -> },
    onProgramLongClick: (ChannelEntity, ProgramEntity) -> Unit = { _, _ -> },
) {
    val c = Orbit.colors
    val start = channels.indexOfFirst { it.key == playingKey }.coerceAtLeast(0)
    val listState = rememberLazyListState((start - 3).coerceAtLeast(0))
    val firstFocus = remember { FocusRequester() }
    val groupFocus = rememberListFocus()
    val channelFocus = rememberListFocus()
    // coming from the menu or back from the player: the channel that is playing
    if (channels.isNotEmpty()) {
        TvEntryPoint(firstFocus)
        TvAfterPlayer(firstFocus)
    }
    val groupState = rememberLazyListState((groups.indexOfFirst { it.id == selectedGroup } - 3).coerceAtLeast(0))
    // keep the current group in view (it can change from the player's channel list)
    LaunchedEffect(selectedGroup, groups.size) {
        val i = groups.indexOfFirst { it.id == selectedGroup }
        if (i < 0) return@LaunchedEffect
        val visible = groupState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == i } || visible.lastOrNull()?.index == i) groupState.scrollToItem((i - 3).coerceAtLeast(0))
    }
    var pendingGroup by remember { mutableStateOf<ChannelGroup?>(null) }

    LaunchedEffect(pendingGroup) {
        val g = pendingGroup ?: return@LaunchedEffect
        delay(250)
        if (g.id != selectedGroup) onGroupFocus(g)
    }
    LaunchedEffect(selectedGroup) { listState.scrollToItem((start - 3).coerceAtLeast(0)) }
    // keep the playing channel on screen (it changes when zapping in the player), so it can get focus
    LaunchedEffect(playingKey, channels.size) {
        if (playingKey == null || channels.isEmpty()) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.size < 3 || visible.none { it.index == start } || visible.first().index == start || visible.last().index == start) {
            listState.scrollToItem((start - 3).coerceAtLeast(0))
        }
    }
    LaunchedEffect(refocusKey, channels.isNotEmpty()) {
        // refocus the playing channel when the player closes; never while it is open (it would take the remote away from it)
        if (channels.isEmpty() || refocusKey == true) return@LaunchedEffect
        repeat(6) {
            delay(60)
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 20.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // ------- groups -------
        Column(Modifier.width(196.dp).fillMaxHeight()) {
            Text("Live TV", Modifier.padding(start = 8.dp, bottom = 8.dp), color = c.text, style = MaterialTheme.typography.headlineSmall)
            LazyColumn(
                state = groupState,
                modifier = Modifier.fillMaxSize().restoreListFocus(groupFocus),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val gTarget = groupFocus.target(groups.map { it.id }, groupState.firstVisibleItemIndex).let { t -> if (groups.any { it.id == selectedGroup }) selectedGroup else t }
                itemsIndexed(groups, key = { _, g -> g.id }) { _, g ->
                    ListRow(
                        modifier = Modifier.listItemFocus(groupFocus, g.id, g.id == gTarget),
                        title = g.name,
                        selected = g.id == selectedGroup,
                        onFocus = { pendingGroup = g },
                        onLongClick = if (g.special) null else ({ onGroupLongPress(g) }),
                        trailing = { Text("${g.count}", color = c.textMuted, style = MaterialTheme.typography.bodySmall) },
                    ) { onGroupFocus(g) }
                }
            }
        }
        // ------- channels -------
        Box(Modifier.width(300.dp).fillMaxHeight().padding(top = 40.dp)) {
            if (channels.isEmpty()) {
                EmptyState("No channels in this group", if (loadingText.isNotBlank()) loadingText else "Choose another group on the left.")
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().restoreListFocus(channelFocus),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    val cTarget = channelFocus.target(channels.map { it.key }, listState.firstVisibleItemIndex)
                    itemsIndexed(channels, key = { _, ch -> ch.key }) { i, ch ->
                        TvChannelRow(
                            modifier = Modifier.listItemFocus(channelFocus, ch.key, ch.key == cTarget),
                            ch = ch,
                            name = displayName(ch),
                            nn = nowNext[ch.epgKey],
                            showNumber = settings.showChannelNumbers,
                            favorite = ch.key in favorites,
                            playing = ch.key == playingKey,
                            focusRequester = if (i == start) firstFocus else null,
                            onFocus = { onChannelFocus(ch) },
                            onLongClick = { onChannelLongPress(ch) },
                        ) { onChannelClick(ch) }
                    }
                }
            }
        }
        // ------- the focused channel: preview / now, days, programmes -------
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.End) {
                if (loadingText.isNotBlank()) Text(loadingText, Modifier.weight(1f), color = c.accentAlt, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Text(Fmt.time(System.currentTimeMillis()), color = c.text, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(10.dp)).background(c.surface)) {
                if (preview != null) preview()
                else if (focused != null) Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChannelLogo(focused.logo, focused.name, 96.dp, 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(displayName(focused), color = c.text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val p = nowNext[focused.epgKey]?.now
                        Text(p?.title ?: "Press OK to watch", color = c.textMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (p != null) ProgressLine(progressOf(p), Modifier.fillMaxWidth().padding(top = 6.dp), height = 3.dp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (focused != null) ChannelDays(focused, loadPrograms, onProgramClick, onProgramLongClick, Modifier.fillMaxSize())
        }
    }
}

/** Days (archive back … tomorrow) and the programmes of the chosen day for one channel */
@Composable
fun ChannelDays(
    ch: ChannelEntity,
    loadPrograms: suspend (ChannelEntity, Long, Long) -> List<ProgramEntity>,
    onProgramClick: (ChannelEntity, ProgramEntity) -> Unit,
    onProgramLongClick: (ChannelEntity, ProgramEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Orbit.colors
    val now = remember(ch.key) { System.currentTimeMillis() }
    val today = remember(now) { startOfDay(now) }
    val back = if (ch.archive) ch.archiveDays.coerceIn(1, 7) else 0
    var day by remember(ch.key) { mutableStateOf(0) } // 0 = today, -1 = yesterday, 1 = tomorrow
    val dayStart = today + day * 86_400_000L
    val programmes by produceState<List<ProgramEntity>?>(null, ch.key, day) {
        value = null
        delay(180) // fast scrolling through channels: load only where the user stops
        value = loadPrograms(ch, dayStart, dayStart + 86_400_000L).filter { it.stop > dayStart && it.start < dayStart + 86_400_000L }
    }
    Column(modifier) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 6.dp)) {
            items((-back..1).toList()) { d ->
                Focusable(onClick = { day = d }, onFocus = { day = d }, shape = RoundedCornerShape(16.dp), color = if (d == day) c.accent else c.surface, scaleOnFocus = 1.05f) { f ->
                    Text(dayName(d, today + d * 86_400_000L), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = if (d == day && !f) c.onAccent else c.text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
        val list = programmes
        when {
            list == null -> Unit
            list.isEmpty() -> Text("No programme information for this day", Modifier.padding(8.dp), color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
            else -> {
                val nowIdx = list.indexOfFirst { it.start <= now && it.stop > now }
                val state = remember(ch.key, day) { androidx.compose.foundation.lazy.LazyListState(if (day == 0 && nowIdx > 0) (nowIdx - 2).coerceAtLeast(0) else 0) }
                LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(list, key = { it.id * 31 + it.start }) { p ->
                        val past = p.stop <= now
                        val isNow = p.start <= now && p.stop > now
                        val archived = ch.archive && p.start < now && p.start >= now - ch.archiveDays.coerceAtLeast(1) * 86_400_000L
                        Focusable(
                            onClick = { onProgramClick(ch, p) },
                            onLongClick = { onProgramLongClick(ch, p) },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (isNow) c.accent.copy(alpha = 0.14f) else c.surface,
                            scaleOnFocus = 1.02f,
                        ) { f ->
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(Fmt.time(p.start), Modifier.width(48.dp), color = if (isNow) c.accent else c.textMuted, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal)
                                Column(Modifier.weight(1f)) {
                                    Text(p.title, color = if (past && !archived) c.textMuted else c.text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (f) FontWeight.SemiBold else FontWeight.Normal)
                                    if (isNow) ProgressLine(progressOf(p, now), Modifier.fillMaxWidth().padding(top = 4.dp), height = 3.dp)
                                    if (f && archived && !isNow) Text("OK = watch from the archive", color = c.accentAlt, style = MaterialTheme.typography.labelSmall)
                                    if (f && isNow && ch.archive) Text("OK = live · hold OK = from the start", color = c.accentAlt, style = MaterialTheme.typography.labelSmall)
                                }
                                when {
                                    isNow -> Text("LIVE", Modifier.background(c.live, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    archived -> Icon(Icons.Filled.History, "From the archive", Modifier.size(18.dp), tint = c.accentAlt)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun startOfDay(ms: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = ms
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dayName(d: Int, dayStart: Long): String = when (d) {
    0 -> "Today"
    -1 -> "Yesterday"
    1 -> "Tomorrow"
    else -> java.text.SimpleDateFormat("EEE d", java.util.Locale.ENGLISH).format(java.util.Date(dayStart))
}

fun progressOf(p: ProgramEntity, now: Long = System.currentTimeMillis()): Float =
    ((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)

/** Channel row: number, logo, name, archive mark, now (with progress) and next */
@Composable
fun TvChannelRow(
    ch: ChannelEntity,
    name: String,
    nn: NowNext?,
    showNumber: Boolean,
    favorite: Boolean,
    playing: Boolean,
    focusRequester: FocusRequester?,
    onFocus: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = Orbit.colors
    Focusable(
        onClick = onClick,
        onLongClick = onLongClick,
        onFocus = onFocus,
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        color = if (playing) c.accent.copy(alpha = 0.10f) else Color.Transparent,
        scaleOnFocus = 1.0f,
        focusRequester = focusRequester,
    ) { focused ->
        Row(Modifier.height(62.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(ch.logo, ch.name, 60.dp, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showNumber) Text("${ch.num}  ", color = if (playing) c.accent else c.textMuted, style = MaterialTheme.typography.bodyMedium)
                    Text(name, color = c.text, style = MaterialTheme.typography.bodyLarge, fontWeight = if (playing || focused) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (playing) Icon(Icons.Filled.PlayArrow, null, Modifier.padding(start = 4.dp).size(16.dp), tint = c.accent)
                    if (favorite) Icon(Icons.Filled.Star, null, Modifier.padding(start = 4.dp).size(14.dp), tint = c.accentAlt)
                    if (ch.archive) Icon(Icons.Filled.History, "Archive", Modifier.padding(start = 4.dp).size(14.dp), tint = c.accentAlt)
                }
                val p = nn?.now
                Text(p?.title ?: "", color = c.textMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p != null) ProgressLine(progressOf(p), Modifier.fillMaxWidth().padding(top = 4.dp), height = 3.dp)
            }
        }
    }
}
