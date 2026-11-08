package com.orbitplayer.ui.tv

import com.orbitplayer.ui.components.listItemFocus
import com.orbitplayer.ui.components.restoreListFocus
import com.orbitplayer.ui.components.rememberListFocus
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Dp
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

/** Height of one row in every column, so the chosen rows of all columns sit on the same line */
private val ROW = 46.dp

/** Keeps the focused row of a list on the middle line (EON style): the list moves, the focus stays put */
@OptIn(ExperimentalFoundationApi::class)
private object CenterPivot : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - (containerSize - size) / 2f
}

/**
 * Stateless layout of the Live screen (also used for screenshots), EON style:
 * groups | channels | days | programmes, the chosen row of every column on one line across the screen,
 * the focused channel playing behind it all. A past programme with ↺ plays from the archive.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
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
    val listState = rememberLazyListState(start)
    val firstFocus = remember { FocusRequester() }
    val groupFocus = rememberListFocus()
    val channelFocus = rememberListFocus()
    val dayFocus = rememberListFocus()
    val progFocus = rememberListFocus()
    var channelsHaveFocus by remember { mutableStateOf(false) }
    // coming from the menu or back from the player: the channel that is playing
    if (channels.isNotEmpty()) {
        TvEntryPoint(firstFocus)
        TvAfterPlayer(firstFocus)
    }
    val groupState = rememberLazyListState(groups.indexOfFirst { it.id == selectedGroup }.coerceAtLeast(0))
    // keep the current group on the line (it can change from the player's channel list)
    LaunchedEffect(selectedGroup, groups.size) {
        val i = groups.indexOfFirst { it.id == selectedGroup }
        if (i >= 0 && groupState.firstVisibleItemIndex != i) groupState.scrollToItem(i)
    }
    var pendingGroup by remember { mutableStateOf<ChannelGroup?>(null) }
    LaunchedEffect(pendingGroup) {
        val g = pendingGroup ?: return@LaunchedEffect
        delay(250)
        if (g.id != selectedGroup) onGroupFocus(g)
    }
    LaunchedEffect(selectedGroup) { listState.scrollToItem(start) }
    // the playing channel changed elsewhere (zapping in the player): bring it to the line,
    // but never while the user is moving through the channels
    LaunchedEffect(playingKey, channels.size) {
        if (playingKey == null || channels.isEmpty() || channelsHaveFocus) return@LaunchedEffect
        if (listState.firstVisibleItemIndex != start) listState.scrollToItem(start)
    }
    LaunchedEffect(refocusKey, channels.isNotEmpty()) {
        // refocus the playing channel when the player closes; never while it is open (it would take the remote away from it)
        if (channels.isEmpty() || refocusKey == true) return@LaunchedEffect
        repeat(6) {
            delay(60)
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    // programmes of the focused channel for the chosen day
    val now = remember(focused?.key) { System.currentTimeMillis() }
    val today = remember(now) { startOfDay(now) }
    val back = if (focused?.archive == true) focused.archiveDays.coerceIn(1, 7) else 0
    var day by remember(focused?.key) { mutableStateOf(0) } // 0 = today, -1 = yesterday …
    val dayStart = today + day * 86_400_000L
    val programmes by produceState<List<ProgramEntity>?>(null, focused?.key, day) {
        value = null
        val ch = focused ?: return@produceState
        delay(150) // fast scrolling through channels: load only where the user stops
        value = loadPrograms(ch, dayStart, dayStart + 86_400_000L).filter { it.stop > dayStart && it.start < dayStart + 86_400_000L }
    }
    var focusedProg by remember { mutableStateOf<ProgramEntity?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val band = (maxHeight - ROW) / 2
        val pad = PaddingValues(top = band, bottom = band)
        // the focused channel, full screen behind the lists
        if (preview != null) Box(Modifier.fillMaxSize()) { preview() }
        Box(Modifier.fillMaxSize().background(c.background.copy(alpha = if (preview != null) 0.80f else 1f)))
        // the line
        Box(Modifier.fillMaxWidth().height(ROW).offset(y = band).background(c.accent.copy(alpha = 0.10f)))
        Box(Modifier.fillMaxWidth().height(2.dp).offset(y = band + ROW).background(c.accent))

        CompositionLocalProvider(LocalBringIntoViewSpec provides CenterPivot) {
            Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 12.dp)) {
                // ------- groups -------
                LazyColumn(
                    state = groupState,
                    modifier = Modifier.width(200.dp).fillMaxHeight().restoreListFocus(groupFocus),
                    contentPadding = pad,
                ) {
                    itemsIndexed(groups, key = { _, g -> g.id }) { _, g ->
                        BandRow(
                            modifier = Modifier.listItemFocus(groupFocus, g.id, g.id == selectedGroup || (groups.none { it.id == selectedGroup } && g == groups.first())),
                            selected = g.id == selectedGroup,
                            onFocus = { pendingGroup = g },
                            onLongClick = if (g.special) null else ({ onGroupLongPress(g) }),
                            onClick = { onGroupFocus(g) },
                        ) { f ->
                            Text(g.name, Modifier.weight(1f), color = rowColor(f, g.id == selectedGroup), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (f || g.id == selectedGroup) FontWeight.SemiBold else FontWeight.Normal)
                            Text("${g.count}", color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Divider()
                // ------- channels -------
                Box(Modifier.width(270.dp).fillMaxHeight()) {
                    if (channels.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(top = band - 40.dp)) {
                            EmptyState("No channels in this group", if (loadingText.isNotBlank()) loadingText else "Choose another group on the left.")
                        }
                    } else {
                        val cTarget = channelFocus.focusedKey?.takeIf { k -> channels.any { it.key == k } } ?: channels[start].key
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().onFocusChanged { channelsHaveFocus = it.hasFocus }.restoreListFocus(channelFocus),
                            contentPadding = pad,
                        ) {
                            itemsIndexed(channels, key = { _, ch -> ch.key }) { i, ch ->
                                val sel = ch.key == focused?.key
                                BandRow(
                                    modifier = Modifier.listItemFocus(channelFocus, ch.key, ch.key == cTarget)
                                        .then(if (i == start) Modifier.focusRequester(firstFocus) else Modifier),
                                    selected = sel,
                                    onFocus = { onChannelFocus(ch) },
                                    onLongClick = { onChannelLongPress(ch) },
                                    onClick = { onChannelClick(ch) },
                                ) { f ->
                                    if (settings.showChannelNumbers) Text("${ch.num}", Modifier.width(40.dp), color = if (ch.key == playingKey) c.accent else c.textMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    ChannelLogo(ch.logo, ch.name, 52.dp, 30.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(displayName(ch), Modifier.weight(1f, fill = false), color = rowColor(f, sel), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (f || sel) FontWeight.SemiBold else FontWeight.Normal)
                                    if (ch.key == playingKey) Icon(Icons.Filled.PlayArrow, null, Modifier.padding(start = 4.dp).size(16.dp), tint = c.accent)
                                    if (ch.key in favorites) Icon(Icons.Filled.Star, null, Modifier.padding(start = 4.dp).size(14.dp), tint = c.accentAlt)
                                    if (ch.archive) Icon(Icons.Filled.History, "Archive", Modifier.padding(start = 4.dp).size(14.dp), tint = c.accentAlt)
                                }
                            }
                        }
                    }
                }
                Divider()
                // ------- days -------
                if (focused != null) {
                    val days = (-back..2).toList()
                    val dayState = remember(focused.key) { LazyListState(days.indexOf(0).coerceAtLeast(0)) }
                    LazyColumn(
                        state = dayState,
                        modifier = Modifier.width(112.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.05f)).restoreListFocus(dayFocus),
                        contentPadding = pad,
                    ) {
                        items(days, key = { it }) { d ->
                            BandRow(
                                modifier = Modifier.listItemFocus(dayFocus, d, d == day),
                                selected = d == day,
                                onFocus = { day = d },
                                onClick = { day = d },
                                horizontal = 10.dp,
                            ) { f ->
                                Column {
                                    if (d in -1..1) Text(dayLabel(d), color = if (f || d == day) c.accent else c.textMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    Text(dayDate(today + d * 86_400_000L), color = rowColor(f, d == day), style = MaterialTheme.typography.bodyMedium, fontWeight = if (f || d == day) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                                }
                            }
                        }
                    }
                    Divider()
                }
                // ------- programmes -------
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val list = programmes
                    when {
                        focused == null -> Unit
                        list == null -> Unit
                        list.isEmpty() -> Text("No programme information for this day", Modifier.padding(start = 16.dp, top = band + 12.dp), color = c.textMuted, style = MaterialTheme.typography.bodyLarge)
                        else -> key(focused.key, day) {
                            val nowIdx = list.indexOfFirst { it.start <= now && it.stop > now }.let { if (it < 0) list.indexOfFirst { p -> p.start > now } else it }
                            val first = if (day == 0 && nowIdx >= 0) nowIdx else if (day > 0) 0 else (list.size - 1).coerceAtLeast(0)
                            val pTarget = progFocus.focusedKey?.takeIf { k -> list.any { progKey(it) == k } } ?: progKey(list[first])
                            val st = rememberLazyListState(first)
                            LazyColumn(state = st, modifier = Modifier.fillMaxSize().restoreListFocus(progFocus), contentPadding = pad) {
                                items(list, key = { progKey(it) }) { p ->
                                    val past = p.stop <= now
                                    val isNow = p.start <= now && p.stop > now
                                    val archived = focused.archive && p.start < now && p.start >= now - back * 86_400_000L
                                    BandRow(
                                        modifier = Modifier.listItemFocus(progFocus, progKey(p), progKey(p) == pTarget),
                                        selected = isNow,
                                        onFocus = { focusedProg = p },
                                        onClick = { onProgramClick(focused, p) },
                                        onLongClick = { onProgramLongClick(focused, p) },
                                        horizontal = 14.dp,
                                    ) { f ->
                                        Box(Modifier.width(22.dp)) {
                                            when {
                                                isNow -> Icon(Icons.Filled.PlayArrow, "On now", Modifier.size(18.dp), tint = c.accent)
                                                archived -> Icon(Icons.Filled.History, "From the archive", Modifier.size(16.dp), tint = c.accentAlt)
                                            }
                                        }
                                        Text(Fmt.time(p.start), Modifier.width(54.dp), color = if (isNow) c.accent else if (f) Color.White else c.textMuted, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isNow || f) FontWeight.SemiBold else FontWeight.Normal)
                                        Text(
                                            p.title, Modifier.weight(1f),
                                            color = when { f -> Color.White; isNow -> c.accent; past && !archived -> c.textMuted.copy(alpha = 0.7f); !past -> c.textMuted; else -> c.text },
                                            style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (isNow || f) FontWeight.SemiBold else FontWeight.Normal,
                                        )
                                        if (isNow) ProgressLine(progressOf(p, now), Modifier.width(70.dp).padding(start = 10.dp), height = 3.dp)
                                    }
                                }
                            }
                        }
                    }
                    // header of the column: which channel and the time; the programmes scroll underneath it
                    Box(
                        Modifier.fillMaxWidth().height(76.dp)
                            .background(Brush.verticalGradient(0f to c.background, 0.75f to c.background.copy(alpha = 0.92f), 1f to Color.Transparent)),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (focused != null) Text(displayName(focused), color = c.accent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (loadingText.isNotBlank()) Text(loadingText, color = c.accentAlt, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(16.dp))
                            Text(Fmt.time(System.currentTimeMillis()), color = c.text, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }

        val fp = focusedProg
        if (fp != null && focused != null && fp.epgKey == focused.epgKey) {
            val hint = when {
                fp.start <= now && fp.stop > now -> if (focused.archive) "OK  watch live   ·   hold OK  from the start" else "OK  watch live"
                fp.stop <= now && focused.archive && fp.start >= now - back * 86_400_000L -> "OK  watch from the archive"
                fp.stop <= now -> "No longer in the archive"
                else -> "Starts ${Fmt.date(fp.start)} at ${Fmt.time(fp.start)}"
            }
            Text(hint, Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 14.dp), color = c.textMuted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun progKey(p: ProgramEntity): Long = p.id * 31 + p.start

@Composable
private fun rowColor(focused: Boolean, selected: Boolean): Color =
    if (focused) Color.White else if (selected) Orbit.colors.accent else Orbit.colors.text

@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.08f)))
}

/** One row of a column: same height everywhere, focus = blue on the line */
@Composable
private fun BandRow(
    modifier: Modifier,
    selected: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    horizontal: Dp = 12.dp,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    val c = Orbit.colors
    Focusable(
        onClick = onClick,
        onLongClick = onLongClick,
        onFocus = onFocus,
        modifier = modifier.fillMaxWidth().height(ROW),
        shape = RoundedCornerShape(6.dp),
        color = Color.Transparent,
        focusedColor = c.accent.copy(alpha = 0.55f),
        scaleOnFocus = 1f,
        border = false,
    ) { f ->
        Row(Modifier.fillMaxSize().padding(horizontal = horizontal), verticalAlignment = Alignment.CenterVertically) { content(f) }
    }
}

private fun startOfDay(ms: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = ms
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dayLabel(d: Int): String = when (d) {
    0 -> "Today"
    -1 -> "Yesterday"
    1 -> "Tomorrow"
    else -> ""
}

private fun dayDate(dayStart: Long): String = java.text.SimpleDateFormat("EEE dd.MM.", java.util.Locale.ENGLISH).format(java.util.Date(dayStart))

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
