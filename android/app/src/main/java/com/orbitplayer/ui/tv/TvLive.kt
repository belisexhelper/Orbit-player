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

/** Live TV (TiviMate style): info / preview on top, groups and channels below */
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

/** Stateless layout of the Live screen (also used for screenshots) */
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
) {
    val c = Orbit.colors
    val start = channels.indexOfFirst { it.key == playingKey }.coerceAtLeast(0)
    val listState = rememberLazyListState((start - 3).coerceAtLeast(0))
    val firstFocus = remember { FocusRequester() }
    val groupFocus = rememberListFocus()
    val channelFocus = rememberListFocus()
    // coming from the menu or back from the player: the channel that is playing
    if (channels.isNotEmpty()) TvEntryPoint(firstFocus)
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

    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 24.dp, top = 20.dp)) {
        // ------- top band: preview + programme of the focused channel -------
        Row(Modifier.fillMaxWidth().height(196.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(
                Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(c.surface),
                contentAlignment = Alignment.Center,
            ) {
                if (preview != null) preview()
                else if (focused != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ChannelLogo(focused.logo, focused.name, 150.dp, 90.dp)
                    Spacer(Modifier.height(10.dp))
                    Text("Press OK to watch", color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(top = 4.dp)) {
                val nn = focused?.let { nowNext[it.epgKey] }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        focused?.let { (if (settings.showChannelNumbers) "${it.num}  " else "") + displayName(it) } ?: "",
                        color = c.accent, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f),
                    )
                    Text(Fmt.time(System.currentTimeMillis()), color = c.text, style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(6.dp))
                val p = nn?.now
                Text(p?.title ?: "No programme information", color = c.text, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p != null) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${Fmt.time(p.start)} – ${Fmt.time(p.stop)}", color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
                        ProgressLine(progressOf(p), Modifier.width(160.dp), height = 4.dp)
                        Text("${((p.stop - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)} min left", color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (p.description.isNotBlank()) Text(p.description, Modifier.padding(top = 8.dp), color = c.textMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                nn?.next?.let { n -> Text("Next  ${Fmt.time(n.start)}  ${n.title}", color = c.textMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
                if (loadingText.isNotBlank()) Text(loadingText, color = c.accentAlt, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(14.dp))

        // ------- groups | channels -------
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LazyColumn(
                state = groupState,
                modifier = Modifier.width(236.dp).fillMaxHeight().restoreListFocus(groupFocus),
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
            if (channels.isEmpty()) {
                EmptyState("No channels in this group", if (loadingText.isNotBlank()) loadingText else "Choose another group on the left.", Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxHeight().restoreListFocus(channelFocus),
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
    }
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
        Row(Modifier.height(58.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showNumber) Text("${ch.num}", Modifier.width(40.dp), color = if (playing) c.accent else c.textMuted, style = MaterialTheme.typography.bodyMedium)
            ChannelLogo(ch.logo, ch.name, 68.dp, 40.dp)
            Spacer(Modifier.width(14.dp))
            Row(Modifier.weight(1.15f), verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = c.text, style = MaterialTheme.typography.bodyLarge, fontWeight = if (playing || focused) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (playing) Icon(Icons.Filled.PlayArrow, null, Modifier.padding(start = 6.dp).size(18.dp), tint = c.accent)
                if (favorite) Icon(Icons.Filled.Star, null, Modifier.padding(start = 6.dp).size(16.dp), tint = c.accentAlt)
                if (ch.archive) Icon(Icons.Filled.History, "Archive", Modifier.padding(start = 6.dp).size(16.dp), tint = c.textMuted)
            }
            Spacer(Modifier.width(16.dp))
            val p = nn?.now
            Column(Modifier.weight(1.1f)) {
                Text(p?.title ?: "", color = if (focused) c.text else c.text.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p != null) ProgressLine(progressOf(p), Modifier.fillMaxWidth().padding(top = 6.dp), height = 3.dp)
            }
            Spacer(Modifier.width(16.dp))
            val n = nn?.next
            Text(
                if (n != null) "${Fmt.time(n.start)}  ${n.title}" else "",
                Modifier.weight(0.75f),
                color = c.textMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
