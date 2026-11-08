package com.orbitplayer.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orbitplayer.core.Fmt
import com.orbitplayer.data.db.ChannelEntity
import com.orbitplayer.data.db.ProgramEntity
import com.orbitplayer.playback.PlayItem
import com.orbitplayer.ui.AppViewModel
import com.orbitplayer.ui.ChannelGroup
import com.orbitplayer.ui.NowNext
import com.orbitplayer.ui.Route
import com.orbitplayer.ui.common.AddPlaylistScreen
import com.orbitplayer.ui.common.DiagnosticsScreen
import com.orbitplayer.ui.common.FavoritesScreen
import com.orbitplayer.ui.common.GuideScreen
import com.orbitplayer.ui.common.MovieDetailScreen
import com.orbitplayer.ui.common.NoPlaylist
import com.orbitplayer.ui.common.PlayerSurface
import com.orbitplayer.ui.common.PosterData
import com.orbitplayer.ui.common.PosterRow
import com.orbitplayer.ui.common.SearchScreen
import com.orbitplayer.ui.common.SeriesDetailScreen
import com.orbitplayer.ui.common.SettingsScreen
import com.orbitplayer.ui.common.SettingsSectionScreen
import com.orbitplayer.ui.common.SpeedTestScreen
import com.orbitplayer.ui.common.VodBrowseScreen
import com.orbitplayer.ui.components.ActionsDialog
import com.orbitplayer.ui.components.ChannelLogo
import com.orbitplayer.ui.components.EmptyState
import com.orbitplayer.ui.components.Focusable
import com.orbitplayer.ui.components.GradientTile
import com.orbitplayer.ui.components.IconAction
import com.orbitplayer.ui.components.ListRow
import com.orbitplayer.ui.components.MenuAction
import com.orbitplayer.ui.components.OrbitButton
import com.orbitplayer.ui.components.ProgressLine
import com.orbitplayer.ui.components.SectionHeader
import com.orbitplayer.ui.theme.Orbit
import com.orbitplayer.ui.tv.progressOf

private data class Tab(val route: Route, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Route.Home, "Home", Icons.Filled.Home),
    Tab(Route.Live, "Live", Icons.Filled.LiveTv),
    Tab(Route.Guide, "Catch-up", Icons.Filled.History),
    Tab(Route.Movies, "Movies", Icons.Filled.Movie),
    Tab(Route.Series, "Series", Icons.Filled.VideoLibrary),
)

/** Phone and tablet interface (XCIPTV style) */
@Composable
fun MobileApp(vm: AppViewModel, onExit: () -> Unit) {
    val playerOpen by vm.playerOpen.collectAsState()
    var lastBack by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = !playerOpen) {
        when {
            vm.route == Route.ChannelView -> vm.closeChannelView()
            vm.backStack.size > 2 -> vm.back()
            vm.route != Route.Home -> vm.root(Route.Home)
            System.currentTimeMillis() - lastBack < 2000 -> onExit()
            else -> {
                lastBack = System.currentTimeMillis()
                vm.toast("Press back again to exit")
            }
        }
    }
    val section = vm.backStack.getOrNull(1) ?: Route.Home
    val selectedTab = TABS.firstOrNull { it.route == section }?.route ?: if (vm.backStack.size == 1) Route.Home else null
    val playing by vm.playback.item.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize().background(Orbit.colors.background)) {
        val wide = maxWidth >= 600.dp
        SideEffect { vm.wideLayout = wide }
        // Sound only while a picture is visible: full screen, the mini player screen, or the wide Live screen.
        // Rotating moves the mini player between its own screen (portrait) and the Live screen (landscape).
        val route = vm.route
        LaunchedEffect(wide, route, playerOpen, playing?.isLive) {
            if (playerOpen) return@LaunchedEffect
            val item = vm.playback.item.value ?: return@LaunchedEffect
            when {
                wide && route == Route.ChannelView -> {
                    vm.back()
                    if (vm.route != Route.Live) vm.root(Route.Live)
                }
                !wide && route == Route.Live && item.isLive -> vm.open(Route.ChannelView)
                route == Route.ChannelView || (wide && route == Route.Live && item.isLive) -> Unit
                else -> vm.playback.stop()
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                NavigationRail(containerColor = Orbit.colors.surface) {
                    Spacer(Modifier.height(12.dp))
                    TABS.forEach { t ->
                        NavigationRailItem(
                            selected = selectedTab == t.route,
                            onClick = { vm.root(t.route) },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                            colors = NavigationRailItemDefaults.colors(selectedIconColor = Orbit.colors.onAccent, indicatorColor = Orbit.colors.accent, unselectedIconColor = Orbit.colors.textMuted, unselectedTextColor = Orbit.colors.textMuted, selectedTextColor = Orbit.colors.text),
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { MobileContent(vm, wide = true) }
            }
        } else {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Box(Modifier.weight(1f)) { MobileContent(vm, wide = false) }
                if (vm.route !is Route.ChannelView) {
                    NavigationBar(containerColor = Orbit.colors.surface, tonalElevation = 0.dp) {
                        TABS.forEach { t ->
                            NavigationBarItem(
                                selected = selectedTab == t.route,
                                onClick = { vm.root(t.route) },
                                icon = { Icon(t.icon, null) },
                                label = { Text(t.label, maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = Orbit.colors.onAccent, indicatorColor = Orbit.colors.accent, unselectedIconColor = Orbit.colors.textMuted, unselectedTextColor = Orbit.colors.textMuted, selectedTextColor = Orbit.colors.text),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileContent(vm: AppViewModel, wide: Boolean) {
    when (val r = vm.route) {
        Route.Home -> MobileHome(vm, wide)
        Route.Live -> MobileLive(vm, wide)
        Route.ChannelView -> if (wide) MobileLive(vm, wide = true) else ChannelView(vm, wide = false)
        Route.Guide -> GuideScreen(vm)
        Route.Movies -> VodBrowseScreen(vm, series = false)
        Route.Series -> VodBrowseScreen(vm, series = true)
        is Route.MovieDetail -> MovieDetailScreen(vm, r.key)
        is Route.SeriesDetail -> SeriesDetailScreen(vm, r.key)
        Route.Search -> SearchScreen(vm)
        Route.Favorites -> FavoritesScreen(vm)
        Route.Downloads -> DownloadsScreen(vm)
        Route.Settings -> SettingsScreen(vm)
        is Route.SettingsSection -> SettingsSectionScreen(vm, r.id)
        is Route.AddPlaylist -> AddPlaylistScreen(vm, r.editId)
        Route.SpeedTest -> SpeedTestScreen(vm)
        Route.Diagnostics -> DiagnosticsScreen(vm)
    }
}

// ---------------------------------------------------------------- home

@Composable
fun MobileHome(vm: AppViewModel, wide: Boolean) {
    val playlists by vm.playlists.collectAsState()
    if (playlists?.isEmpty() == true) return NoPlaylist(vm)
    val active by vm.activePlaylist.collectAsState()
    val cont by vm.continueWatching.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val channels by vm.channels.collectAsState()
    val sync by vm.syncStatus.collectAsState()
    val favChannels = remember(channels, favorites) { channels.filter { it.key in favorites } }
    MobileHomeContent(
        playlistName = active?.name ?: "",
        expires = active?.expiresAt ?: 0,
        counts = active?.let { Triple(it.liveCount, it.vodCount, it.seriesCount) } ?: Triple(0, 0, 0),
        loading = sync.values.firstOrNull { it.running }?.step ?: "",
        wide = wide,
        onOpen = { vm.root(it) },
        onRoute = { vm.open(it) },
        onRefresh = { vm.syncNow(); vm.toast("Refreshing…") },
        continueRow = {
            if (cont.isNotEmpty()) {
                SectionHeader("Continue watching", Modifier.padding(horizontal = 16.dp))
                Box(Modifier.padding(start = 16.dp)) {
                    PosterRow(cont.map { p ->
                        PosterData(p.key, p.title, p.poster, if (p.kind == "episode") "Episode" else Fmt.duration(p.durationMs - p.positionMs) + " left",
                            if (p.durationMs > 0) p.positionMs.toFloat() / p.durationMs else -1f) {
                            vm.playItem(PlayItem(kind = p.kind, title = p.title, url = p.url, poster = p.poster, progressKey = p.key, seriesKey = p.seriesKey, playlistId = p.playlistId))
                        }
                    })
                }
            }
            if (favChannels.isNotEmpty()) {
                SectionHeader("Favorite channels", Modifier.padding(horizontal = 16.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(favChannels, key = { it.key }) { ch ->
                        Focusable(onClick = { vm.playChannel(ch, favChannels, fullscreen = false); vm.open(Route.ChannelView) }, modifier = Modifier.width(112.dp)) { _ ->
                            Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                ChannelLogo(ch.logo, ch.name, 84.dp, 50.dp)
                                Spacer(Modifier.height(6.dp))
                                Text(vm.displayName(ch), color = Orbit.colors.text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
    )
}

/** Stateless home layout (also used for screenshots) */
@Composable
fun MobileHomeContent(
    playlistName: String,
    expires: Long,
    counts: Triple<Int, Int, Int>,
    loading: String,
    wide: Boolean,
    onOpen: (Route) -> Unit,
    onRoute: (Route) -> Unit,
    onRefresh: () -> Unit,
    continueRow: @Composable () -> Unit,
) {
    val c = Orbit.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(c.tileStart.copy(alpha = 0.55f), c.background)))
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                        Text("O", color = c.onAccent, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Orbit Player", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        Text(
                            listOfNotNull(playlistName.ifBlank { null }, if (expires > 0) "expires ${Fmt.longDate(expires)}" else null).joinToString(" · "),
                            color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        )
                    }
                    IconAction(Icons.Filled.Search, "Search", tint = Color.White) { onRoute(Route.Search) }
                    IconAction(Icons.Filled.Settings, "Settings", tint = Color.White) { onRoute(Route.Settings) }
                }
                if (loading.isNotBlank()) Text(loading, Modifier.padding(top = 6.dp), color = c.accentAlt, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            val tiles: List<Triple<String, ImageVector, Pair<Route, String>>> = listOf(
                Triple("Live TV", Icons.Filled.LiveTv, Route.Live to "${counts.first} channels"),
                Triple("Catch-up", Icons.Filled.History, Route.Guide to "Guide & archive"),
                Triple("Movies", Icons.Filled.Movie, Route.Movies to "${counts.second} titles"),
                Triple("Series", Icons.Filled.VideoLibrary, Route.Series to "${counts.third} shows"),
            )
            val perRow = if (wide) 4 else 2
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tiles.chunked(perRow).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { (label, icon, rd) ->
                            GradientTile(label, icon, Modifier.weight(1f).aspectRatio(if (wide) 1.25f else 1.1f), detail = rd.second) { onOpen(rd.first) }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconAction(Icons.Filled.Star, "Favorites") { onRoute(Route.Favorites) }
                IconAction(Icons.Filled.Download, "Downloads") { onRoute(Route.Downloads) }
                IconAction(Icons.Filled.Speed, "Speed test") { onRoute(Route.SpeedTest) }
                IconAction(Icons.Filled.Refresh, "Refresh") { onRefresh() }
            }
        }
        item { continueRow() }
    }
}

// ---------------------------------------------------------------- live

@Composable
fun MobileLive(vm: AppViewModel, wide: Boolean) {
    val playlists by vm.playlists.collectAsState()
    if (playlists?.isEmpty() == true) return NoPlaylist(vm)
    val groups by vm.groups.collectAsState()
    val selected by vm.selectedGroup.collectAsState()
    val channels by vm.groupChannels.collectAsState()
    val nowNext by vm.nowNext.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val playing by vm.playback.item.collectAsState()
    val sync by vm.syncStatus.collectAsState()
    var menu by remember { mutableStateOf<ChannelEntity?>(null) }
    var groupMenu by remember { mutableStateOf<ChannelGroup?>(null) }
    val open: (ChannelEntity) -> Unit = { ch ->
        vm.playChannel(ch, channels, fullscreen = false)
        if (!wide) vm.open(Route.ChannelView)
    }
    if (wide) {
        // tablet: groups | channels | mini player with guide (XCIPTV style)
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.width(220.dp).fillMaxHeight().background(Orbit.colors.surface), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(groups, key = { it.id }) { g ->
                    ListRow(g.name, selected = g.id == selected, onLongClick = if (g.special) null else ({ groupMenu = g }), trailing = { Text("${g.count}", color = Orbit.colors.textMuted, style = MaterialTheme.typography.bodySmall) }) { vm.selectGroup(g.id) }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                ChannelList(vm, channels, nowNext, favorites, playing?.channel?.key, sync.values.firstOrNull { it.running }?.step ?: "", onLong = { menu = it }, onClick = open)
            }
            if (playing?.isLive == true) Box(Modifier.weight(1f).fillMaxHeight()) { ChannelView(vm, wide = true, embedded = true) }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Live TV", color = Orbit.colors.text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconAction(Icons.Filled.Search, "Search") { vm.open(Route.Search) }
            }
            val gState = rememberLazyListState((groups.indexOfFirst { it.id == selected } - 1).coerceAtLeast(0))
            LazyRow(state = gState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups, key = { it.id }) { g ->
                    Focusable(onClick = { vm.selectGroup(g.id) }, onLongClick = if (g.special) null else ({ groupMenu = g }), shape = RoundedCornerShape(20.dp), color = if (g.id == selected) Orbit.colors.accent else Orbit.colors.surface) { _ ->
                        Text("${g.name}  ${g.count}", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = if (g.id == selected) Orbit.colors.onAccent else Orbit.colors.text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
            ChannelList(vm, channels, nowNext, favorites, playing?.channel?.key, sync.values.firstOrNull { it.running }?.step ?: "", onLong = { menu = it }, onClick = open)
        }
    }
    menu?.let { ch ->
        val fav = ch.key in favorites
        ActionsDialog(vm.displayName(ch), listOf(
            MenuAction("Watch full screen", Icons.Filled.PlayArrow) { vm.playChannel(ch, channels) },
            MenuAction(if (fav) "Remove from favorites" else "Add to favorites", if (fav) Icons.Filled.StarBorder else Icons.Filled.Star) { vm.toggleFavorite(ch.key, "live") },
            MenuAction("Hide channel", Icons.Filled.VisibilityOff) { vm.hideChannel(ch) },
        ), onDismiss = { menu = null })
    }
    groupMenu?.let { g -> ActionsDialog(g.name, listOf(MenuAction("Hide this group", Icons.Filled.VisibilityOff) { vm.hideGroup(g) }), onDismiss = { groupMenu = null }) }
}

@Composable
fun ChannelList(
    vm: AppViewModel,
    channels: List<ChannelEntity>,
    nowNext: Map<String, NowNext>,
    favorites: Set<String>,
    playingKey: String?,
    loading: String,
    onLong: (ChannelEntity) -> Unit,
    onClick: (ChannelEntity) -> Unit,
) {
    if (channels.isEmpty()) {
        EmptyState("No channels", loading.ifBlank { "Choose another group." })
        return
    }
    val showNum = vm.settings.value.showChannelNumbers
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        items(channels, key = { it.key }) { ch ->
            MobileChannelRow(ch, vm.displayName(ch), nowNext[ch.epgKey], showNum, ch.key in favorites, ch.key == playingKey, { onLong(ch) }) { onClick(ch) }
        }
    }
}

@Composable
fun MobileChannelRow(ch: ChannelEntity, name: String, nn: NowNext?, showNumber: Boolean, favorite: Boolean, playing: Boolean, onLong: () -> Unit, onClick: () -> Unit) {
    val c = Orbit.colors
    Focusable(onClick = onClick, onLongClick = onLong, modifier = Modifier.fillMaxWidth(), color = if (playing) c.accent.copy(alpha = 0.12f) else Color.Transparent, scaleOnFocus = 1f) { _ ->
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(ch.logo, ch.name, 64.dp, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showNumber) Text("${ch.num}  ", color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                    Text(name, color = c.text, style = MaterialTheme.typography.bodyLarge, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (favorite) Icon(Icons.Filled.Star, null, Modifier.padding(start = 4.dp).size(14.dp), tint = c.accentAlt)
                    if (ch.archive) Icon(Icons.Filled.History, null, Modifier.padding(start = 4.dp).size(14.dp), tint = c.textMuted)
                }
                val p = nn?.now
                if (p != null) {
                    Text(p.title, color = c.textMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ProgressLine(progressOf(p), Modifier.fillMaxWidth().padding(top = 4.dp), height = 3.dp)
                }
            }
            if (nn?.now != null) Text(Fmt.time(nn.now.stop), Modifier.padding(start = 8.dp), color = c.textMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---------------------------------------------------------------- mini player

/** Mini player on top; tap the picture for full screen. Below: the group's channels or the channel's guide. */
@Composable
fun ChannelView(vm: AppViewModel, wide: Boolean, embedded: Boolean = false) {
    val c = Orbit.colors
    val item by vm.playback.item.collectAsState()
    val settings by vm.settings.collectAsState()
    val queue by vm.playback.queue.collectAsState()
    val playerOpen by vm.playerOpen.collectAsState()
    val ch = item?.channel
    var tab by remember { mutableStateOf(if (embedded || vm.channelViewGuide) 1 else 0) }
    LaunchedEffect(Unit) { vm.channelViewGuide = false }
    val now = System.currentTimeMillis()
    val nn by produceState<NowNext?>(null, ch?.key) { value = ch?.let { vm.loadNowNext(listOf(it.epgKey))[it.epgKey] } }
    val progs by produceState(emptyList<ProgramEntity>(), ch?.key) {
        val cc = ch ?: return@produceState
        val from = if (cc.archive) now - cc.archiveDays.coerceAtMost(7) * 86_400_000L else now - 3 * 3600_000L
        value = vm.programs(cc, from, now + 24 * 3600_000L)
    }
    Column(Modifier.fillMaxSize()) {
        if (!embedded) Row(verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Back") { vm.closeChannelView() }
            Text("Live TV", color = c.text, style = MaterialTheme.typography.titleLarge)
        }
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            if (item != null && !playerOpen) {
                PlayerSurface(vm.playback, settings, settings.aspect, Modifier.fillMaxSize())
            }
            Focusable(onClick = { if (item != null) vm.playerOpen.value = true }, modifier = Modifier.fillMaxSize(), color = Color.Transparent, focusedColor = Color.Transparent) { _ -> }
            if (item == null) Text("Choose a channel", Modifier.align(Alignment.Center), color = Color.White)
            else Text("Tap for full screen", Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        if (ch != null) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(ch.logo, ch.name, 64.dp, 40.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(vm.displayName(ch), color = c.text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    nn?.now?.let { p ->
                        Text("${Fmt.time(p.start)} – ${Fmt.time(p.stop)}  ${p.title}", color = c.textMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ProgressLine(progressOf(p), Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
            }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!embedded) OrbitButton("Channels", primary = tab == 0) { tab = 0 }
            OrbitButton("TV guide", primary = tab == 1) { tab = 1 }
        }
        Spacer(Modifier.height(6.dp))
        if (tab == 0 && !embedded) {
            val list = queue.mapNotNull { it.channel }
            val nowNextMap by produceState(emptyMap<String, NowNext>(), list) { value = vm.loadNowNext(list.map { it.epgKey }) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp)) {
                itemsIndexed(list, key = { _, x -> x.key }) { _, x ->
                    MobileChannelRow(x, vm.displayName(x), nowNextMap[x.epgKey], settings.showChannelNumbers, false, x.key == ch?.key, {}) { vm.playChannel(x, list, fullscreen = false) }
                }
            }
        } else if (ch != null) {
            if (progs.isEmpty()) EmptyState("No TV guide", "This channel has no programme information.")
            val start = progs.indexOfFirst { it.stop > now }.coerceAtLeast(0)
            val st = rememberLazyListState(start)
            LazyColumn(state = st, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp)) {
                items(progs, key = { it.id }) { p ->
                    val past = p.stop <= now
                    val isNow = p.start <= now && p.stop > now
                    ListRow(
                        "${Fmt.time(p.start)}  ${p.title}",
                        subtitle = when {
                            isNow -> "On now"
                            past && ch.archive -> "Watch from archive"
                            else -> Fmt.date(p.start)
                        },
                        selected = isNow,
                        trailing = { if (past && ch.archive) Icon(Icons.Filled.History, null, tint = c.accentAlt) },
                    ) {
                        when {
                            isNow -> vm.playerOpen.value = true
                            past && ch.archive -> vm.playCatchup(ch, p)
                            else -> vm.toast(p.description.ifBlank { "Starts at ${Fmt.time(p.start)}" }.take(200))
                        }
                    }
                }
            }
        }
    }
}
