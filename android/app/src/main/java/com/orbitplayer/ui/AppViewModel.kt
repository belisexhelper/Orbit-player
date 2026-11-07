package com.orbitplayer.ui

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orbitplayer.App
import com.orbitplayer.core.ChannelNames
import com.orbitplayer.data.Episode
import com.orbitplayer.data.db.CategoryEntity
import com.orbitplayer.data.db.ChannelEntity
import com.orbitplayer.data.db.FavoriteEntity
import com.orbitplayer.data.db.PlaylistEntity
import com.orbitplayer.data.db.ProgramEntity
import com.orbitplayer.data.db.SeriesEntity
import com.orbitplayer.data.db.VodEntity
import com.orbitplayer.playback.PlayItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Screens */
sealed class Route {
    data object Home : Route()
    data object Live : Route()
    data object Guide : Route()
    data object Movies : Route()
    data object Series : Route()
    data class MovieDetail(val key: String) : Route()
    data class SeriesDetail(val key: String) : Route()
    data object ChannelView : Route() // phone: mini player + guide
    data object Search : Route()
    data object Favorites : Route()
    data object Downloads : Route()
    data object Settings : Route()
    data class SettingsSection(val id: String) : Route()
    data class AddPlaylist(val editId: String? = null) : Route()
    data object SpeedTest : Route()
    data object Diagnostics : Route()
}

/** A group in the channel list */
data class ChannelGroup(val id: String, val name: String, val count: Int, val special: Boolean = false, val key: String = "")

data class NowNext(val now: ProgramEntity?, val next: ProgramEntity?)

const val GROUP_FAV = "__fav"
const val GROUP_RECENT = "__recent"
const val GROUP_ALL = "__all"

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as App
    val store = app.store
    val repo = app.repo
    val playback = app.playback

    // ---------- navigation ----------
    val backStack = mutableStateListOf<Route>(Route.Home)
    val route: Route get() = backStack.last()

    fun open(r: Route) {
        if (backStack.last() != r) backStack.add(r)
    }

    /** Main sections (side menu / bottom bar): replaces the stack */
    fun root(r: Route) {
        backStack.clear()
        backStack.add(Route.Home)
        if (r != Route.Home) backStack.add(r)
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.size - 1)
        return true
    }

    /** Full-screen player is open */
    val playerOpen = MutableStateFlow(false)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    fun toast(text: String) {
        _messages.tryEmit(text)
    }

    // ---------- data ----------
    val settings = store.settings
    val syncStatus = repo.syncStatus

    val playlists: StateFlow<List<PlaylistEntity>?> = repo.playlists.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val activePlaylistId = MutableStateFlow(store.activePlaylistId)

    val activePlaylist: StateFlow<PlaylistEntity?> = combine(repo.playlists, activePlaylistId) { list, id -> list.firstOrNull { it.id == id } ?: list.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setActivePlaylist(id: String) {
        store.activePlaylistId = id
        activePlaylistId.value = id
        selectedGroup.value = GROUP_ALL
    }

    val favorites: StateFlow<Set<String>> = app.db.user().observeFavorites().map { l -> l.map { it.key }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val continueWatching = app.db.user().observeContinue(30).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allProgress = app.db.user().observeAllProgress().map { l -> l.associateBy { it.key } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val downloads = app.downloads.all.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val hiddenGroups = MutableStateFlow(store.hiddenGroups)
    val hiddenChannels = MutableStateFlow(store.hiddenChannels)
    val lockedGroups = MutableStateFlow(store.lockedGroups)
    val recent = MutableStateFlow(store.recentChannels)
    val adultUnlocked = MutableStateFlow(false)

    private data class Filters(val hiddenG: Set<String>, val hiddenC: Set<String>, val unlocked: Boolean, val hideAdult: Boolean, val locked: Set<String>)

    private val filters = combine(hiddenGroups, hiddenChannels, adultUnlocked, settings, lockedGroups) { hg, hc, u, s, l -> Filters(hg, hc, u, s.hideAdult, l) }

    private fun pid() = activePlaylist.value?.id ?: activePlaylistId.value

    val liveCategories: StateFlow<List<CategoryEntity>> = activePlaylist.flatMapLatest { p ->
        if (p == null) kotlinx.coroutines.flow.flowOf(emptyList()) else app.db.content().observeCategories(p.id, "live")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val rawChannels: StateFlow<List<ChannelEntity>> = activePlaylist.flatMapLatest { p ->
        if (p == null) kotlinx.coroutines.flow.flowOf(emptyList()) else app.db.content().observeChannels(p.id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Visible categories (hidden and adult groups removed) */
    val visibleCategories: StateFlow<List<CategoryEntity>> = combine(liveCategories, filters) { cats, f ->
        cats.filter { it.key !in f.hiddenG && !(f.hideAdult && it.adult && !f.unlocked) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** All visible channels */
    val channels: StateFlow<List<ChannelEntity>> = combine(rawChannels, liveCategories, filters) { chans, cats, f ->
        val hiddenCats = cats.filter { it.key in f.hiddenG || (f.hideAdult && it.adult && !f.unlocked) }.map { it.categoryId }.toHashSet()
        chans.filter { it.key !in f.hiddenC && it.categoryId !in hiddenCats && !(f.hideAdult && it.adult && !f.unlocked) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val groups: StateFlow<List<ChannelGroup>> = combine(visibleCategories, channels, favorites, recent) { cats, chans, fav, rec ->
        val counts = chans.groupingBy { it.categoryId }.eachCount()
        val keys = chans.mapTo(HashSet()) { it.key }
        buildList {
            val favN = fav.count { it in keys }
            if (favN > 0) add(ChannelGroup(GROUP_FAV, "Favorites", favN, special = true))
            val recN = rec.count { it in keys }
            if (recN > 0) add(ChannelGroup(GROUP_RECENT, "Recently watched", recN, special = true))
            add(ChannelGroup(GROUP_ALL, "All channels", chans.size, special = true))
            cats.forEach { c -> add(ChannelGroup(c.categoryId, c.name, counts[c.categoryId] ?: 0, key = c.key)) }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val selectedGroup = MutableStateFlow(store.lastGroupKey.ifBlank { GROUP_ALL })

    fun selectGroup(id: String) {
        selectedGroup.value = id
        store.lastGroupKey = id
    }

    val groupChannels: StateFlow<List<ChannelEntity>> = combine(channels, selectedGroup, favorites, recent) { chans, g, fav, rec ->
        channelsOf(chans, g, fav, rec)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun channelsOf(chans: List<ChannelEntity>, group: String, fav: Set<String> = favorites.value, rec: List<String> = recent.value): List<ChannelEntity> = when (group) {
        GROUP_ALL -> chans
        GROUP_FAV -> chans.filter { it.key in fav }
        GROUP_RECENT -> {
            val byKey = chans.associateBy { it.key }
            rec.mapNotNull { byKey[it] }
        }
        else -> chans.filter { it.categoryId == group }
    }

    fun displayName(ch: ChannelEntity): String = if (settings.value.cleanChannelNames) ChannelNames.clean(ch.name) else ch.name

    // ---------- current and next programme ----------
    private val minuteTick = MutableStateFlow(System.currentTimeMillis())
    private val _nowNext = MutableStateFlow<Map<String, NowNext>>(emptyMap())
    val nowNext: StateFlow<Map<String, NowNext>> = _nowNext

    init {
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                minuteTick.value = System.currentTimeMillis()
            }
        }
        viewModelScope.launch {
            combine(groupChannels, minuteTick, syncStatus) { chans, now, _ -> chans to now }.collect { (chans, now) ->
                _nowNext.value = loadNowNext(chans.map { it.epgKey }, now)
            }
        }
        viewModelScope.launch {
            playback.item.collect { recent.value = store.recentChannels }
        }
    }

    suspend fun loadNowNext(keys: List<String>, now: Long = System.currentTimeMillis()): Map<String, NowNext> = withContext(Dispatchers.IO) {
        val out = HashMap<String, NowNext>()
        val distinct = keys.filter { it.isNotBlank() }.distinct()
        for (chunk in distinct.chunked(400)) {
            val progs = app.db.epg().programsFor(chunk, now, now + 12 * 3600_000L)
            progs.groupBy { it.epgKey }.forEach { (k, list) ->
                val cur = list.firstOrNull { it.start <= now && it.stop > now }
                val nxt = list.firstOrNull { it.start >= (cur?.stop ?: now) }
                out[k] = NowNext(cur, nxt)
            }
        }
        out
    }

    suspend fun programs(ch: ChannelEntity, from: Long, to: Long): List<ProgramEntity> =
        if (ch.epgKey.isBlank()) emptyList() else withContext(Dispatchers.IO) { app.db.epg().programs(ch.epgKey, from, to) }

    // ---------- playback ----------
    fun liveItem(ch: ChannelEntity) = PlayItem(kind = "live", title = displayName(ch), channel = ch, poster = ch.logo, playlistId = ch.playlistId)

    /** Plays a channel; the other channels of the list are used for channel up/down */
    fun playChannel(ch: ChannelEntity, list: List<ChannelEntity> = groupChannels.value, fullscreen: Boolean = true) {
        val l = if (list.any { it.key == ch.key }) list else listOf(ch)
        playback.playQueue(l.map { liveItem(it) }, l.indexOfFirst { it.key == ch.key }.coerceAtLeast(0))
        if (fullscreen) playerOpen.value = true
    }

    fun playCatchup(ch: ChannelEntity, prog: ProgramEntity) = viewModelScope.launch {
        val url = repo.catchupUrl(ch, prog) ?: return@launch toast("This channel has no archive")
        playback.play(PlayItem(kind = "catchup", title = displayName(ch), subtitle = prog.title, url = url, poster = ch.logo, channel = ch, playlistId = ch.playlistId))
        playerOpen.value = true
    }

    fun playMovie(v: VodEntity, fromStart: Boolean = false) = viewModelScope.launch {
        val key = "vod:${v.key}"
        if (fromStart) withContext(Dispatchers.IO) { app.db.user().deleteProgress(key) }
        val url = repo.vodUrl(v)
        playback.play(PlayItem(kind = "vod", title = v.name, url = url, poster = v.poster, progressKey = key, playlistId = v.playlistId))
        playerOpen.value = true
    }

    fun playEpisodes(s: SeriesEntity, season: Int, episodes: List<Episode>, start: Episode, fromStart: Boolean = false) = viewModelScope.launch {
        if (fromStart) withContext(Dispatchers.IO) { app.db.user().deleteProgress("ep:${s.playlistId}:${start.id}") }
        val items = episodes.map { e ->
            PlayItem(
                kind = "episode",
                title = s.name,
                subtitle = "S${season} E${e.number}" + if (e.title.isNotBlank()) " · ${e.title}" else "",
                url = repo.episodeUrl(s.playlistId, e),
                poster = e.image.ifBlank { s.cover },
                progressKey = "ep:${s.playlistId}:${e.id}",
                seriesKey = s.key,
                playlistId = s.playlistId,
            )
        }
        playback.playQueue(items, episodes.indexOf(start).coerceAtLeast(0))
        playerOpen.value = true
    }

    fun playItem(item: PlayItem) {
        playback.play(item)
        playerOpen.value = true
    }

    /** Last watched channel (TV start-up) */
    fun playLastChannel(fullscreen: Boolean): Boolean {
        val key = store.lastChannelKey
        val ch = channels.value.firstOrNull { it.key == key } ?: return false
        val group = groups.value.firstOrNull { g -> !g.special && g.id == ch.categoryId }?.id ?: GROUP_ALL
        selectGroup(group)
        playChannel(ch, channelsOf(channels.value, group), fullscreen)
        return true
    }

    fun closePlayer(stop: Boolean) {
        playerOpen.value = false
        if (stop) playback.stop()
    }

    /**
     * Back from full screen. TV: to the Live screen (keeps playing in the preview when it is on).
     * Phone, live channel: to the mini player. Movies and episodes: stop.
     */
    fun leavePlayer(tv: Boolean) {
        val item = playback.item.value
        when {
            item?.isLive != true -> closePlayer(stop = true)
            tv -> {
                if (route != Route.Live) root(Route.Live)
                closePlayer(stop = !settings.value.livePreview)
            }
            wideLayout -> {
                // tablet / phone in landscape: the Live screen shows the mini player next to the list
                closePlayer(stop = false)
                if (route != Route.Live) root(Route.Live)
            }
            else -> {
                closePlayer(stop = false)
                if (route != Route.ChannelView) open(Route.ChannelView)
            }
        }
    }

    /** Phone/tablet: true when the wide layout (list + mini player side by side) is shown */
    var wideLayout = false

    /** Open the mini player screen on the TV guide tab (archive button in the player) */
    var channelViewGuide = false

    /** Leaves the mini player screen: nothing is visible any more, so playback stops */
    fun closeChannelView() {
        playback.stop()
        back()
    }

    fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }

    // ---------- user actions ----------
    fun toggleFavorite(key: String, kind: String) = viewModelScope.launch(Dispatchers.IO) {
        if (key in favorites.value) {
            app.db.user().removeFavorite(key)
            toast("Removed from favorites")
        } else {
            app.db.user().addFavorite(FavoriteEntity(key, kind, System.currentTimeMillis()))
            toast("Added to favorites")
        }
    }

    fun hideGroup(g: ChannelGroup) {
        if (g.special || g.key.isBlank()) return
        store.hiddenGroups = store.hiddenGroups + g.key
        hiddenGroups.value = store.hiddenGroups
        if (selectedGroup.value == g.id) selectGroup(GROUP_ALL)
        toast("Group hidden. Show it again in Settings → Playlists.")
    }

    fun setGroupHidden(key: String, hidden: Boolean) {
        store.hiddenGroups = if (hidden) store.hiddenGroups + key else store.hiddenGroups - key
        hiddenGroups.value = store.hiddenGroups
    }

    fun hideChannel(ch: ChannelEntity) {
        store.hiddenChannels = store.hiddenChannels + ch.key
        hiddenChannels.value = store.hiddenChannels
        toast("Channel hidden")
    }

    fun showAllHidden() {
        store.hiddenGroups = emptySet()
        store.hiddenChannels = emptySet()
        hiddenGroups.value = emptySet()
        hiddenChannels.value = emptySet()
        toast("All hidden groups and channels are visible again")
    }

    fun syncNow() = viewModelScope.launch { repo.syncAll(force = true) }

    fun refreshPlaylist(id: String) = viewModelScope.launch {
        repo.syncPlaylist(id)
        repo.syncEpg(id)
    }

    fun deletePlaylist(id: String) = viewModelScope.launch {
        repo.removePlaylist(id)
        activePlaylistId.value = store.activePlaylistId
    }

    // ---------- movies and series ----------
    val vodSort = MutableStateFlow("added") // added | name | rating
    val seriesSort = MutableStateFlow("added")

    fun vodCategories(): Flow<List<CategoryEntity>> = combine(
        activePlaylist.flatMapLatest { p -> if (p == null) kotlinx.coroutines.flow.flowOf(emptyList()) else app.db.content().observeCategories(p.id, "vod") },
        filters,
    ) { c, f -> c.filter { it.key !in f.hiddenG && !(f.hideAdult && it.adult && !f.unlocked) } }

    fun seriesCategories(): Flow<List<CategoryEntity>> = combine(
        activePlaylist.flatMapLatest { p -> if (p == null) kotlinx.coroutines.flow.flowOf(emptyList()) else app.db.content().observeCategories(p.id, "series") },
        filters,
    ) { c, f -> c.filter { it.key !in f.hiddenG && !(f.hideAdult && it.adult && !f.unlocked) } }

    /** Movies of a category ("" = all), sorted; a non-empty [query] searches all categories */
    fun movies(category: String, query: String = ""): Flow<List<VodEntity>> = combine(activePlaylist, vodSort) { p, s -> p to s }.flatMapLatest { (p, s) ->
        if (p == null) kotlinx.coroutines.flow.flowOf(emptyList())
        else if (query.isNotBlank()) app.db.content().observeVodSorted(p.id, "", "%${query.trim()}%", s, 500)
        else app.db.content().observeVodSorted(p.id, category, "", s, 30_000)
    }.combine(filters) { l, f -> if (f.hideAdult && !f.unlocked) l.filter { !it.adult } else l }

    fun series(category: String, query: String = ""): Flow<List<SeriesEntity>> = combine(activePlaylist, seriesSort) { p, s -> p to s }.flatMapLatest { (p, s) ->
        if (p == null) kotlinx.coroutines.flow.flowOf(emptyList())
        else if (query.isNotBlank()) app.db.content().observeSeriesSorted(p.id, "", "%${query.trim()}%", s, 500)
        else app.db.content().observeSeriesSorted(p.id, category, "", s, 30_000)
    }.combine(filters) { l, f -> if (f.hideAdult && !f.unlocked) l.filter { !it.adult } else l }

    // ---------- search ----------
    data class SearchResults(
        val channels: List<ChannelEntity> = emptyList(),
        val programs: List<Pair<ProgramEntity, ChannelEntity>> = emptyList(),
        val movies: List<VodEntity> = emptyList(),
        val series: List<SeriesEntity> = emptyList(),
    ) {
        val isEmpty get() = channels.isEmpty() && programs.isEmpty() && movies.isEmpty() && series.isEmpty()
    }

    suspend fun search(q: String): SearchResults = withContext(Dispatchers.IO) {
        val p = pid()
        val like = "%${q.trim()}%"
        val c = app.db.content()
        val visibleKeys = channels.value.mapTo(HashSet()) { it.key }
        val chans = c.searchChannels(p, like).filter { it.key in visibleKeys }
        val progs = app.db.epg().search(p, like, System.currentTimeMillis()).mapNotNull { pr ->
            c.channelByEpgKey(pr.epgKey)?.takeIf { it.key in visibleKeys }?.let { pr to it }
        }
        val hideAdult = settings.value.hideAdult && !adultUnlocked.value
        SearchResults(
            chans,
            progs.take(40),
            c.searchVod(p, like).filter { !(hideAdult && it.adult) },
            c.searchSeries(p, like).filter { !(hideAdult && it.adult) },
        )
    }

    fun rememberSearch(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        store.searchHistory = listOf(t) + store.searchHistory.filter { !it.equals(t, true) }
    }

    // ---------- start-up ----------
    fun startup() = viewModelScope.launch {
        launch {
            if (settings.value.autoUpdateCheck) {
                app.updater.checkIfDue()?.let { r -> toast("New version available (build ${r.versionCode}). Open Settings → Updates & about.") }
            }
        }
        repo.syncAll()
        app.downloads.refresh()
    }
}
