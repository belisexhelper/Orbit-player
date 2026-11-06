package com.orbitplayer.ui

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orbitplayer.App
import com.orbitplayer.data.db.CategoryEntity
import com.orbitplayer.data.db.ChannelEntity
import com.orbitplayer.data.db.FavoriteEntity
import com.orbitplayer.data.db.PlaylistEntity
import com.orbitplayer.player.PlayItem
import com.orbitplayer.player.PlaySession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Ekrani aplikacije */
sealed class Screen {
    data object Home : Screen()
    data object Live : Screen()
    data object Guide : Screen()
    data object Movies : Screen()
    data class MovieDetail(val key: String) : Screen()
    data object Series : Screen()
    data class SeriesDetail(val key: String) : Screen()
    data object Downloads : Screen()
    data object Search : Screen()
    data object Settings : Screen()
    data object Playlists : Screen()
    data object AddPlaylist : Screen()
    data object Pairing : Screen()
    data object Activation : Screen()
    data object Diagnostics : Screen()
    data object SpeedTest : Screen()
    data object Apps : Screen()
    data object About : Screen()
}

/** Kanal sa kategorijom za prikaz */
data class LiveData(
    val categories: List<CategoryEntity> = emptyList(),
    val channels: List<ChannelEntity> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as App
    private val repo = app.repo

    // ---------- navigacija ----------
    val backStack = mutableStateListOf<Screen>(Screen.Home)
    val current get() = backStack.last()

    fun open(s: Screen) {
        if (backStack.last() != s) backStack.add(s)
    }

    /** Zamenjuje ceo stek (npr. glavni meni) */
    fun root(s: Screen) {
        backStack.clear()
        backStack.add(Screen.Home)
        if (s != Screen.Home) backStack.add(s)
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.size - 1)
        return true
    }

    // ---------- podaci ----------
    val settings = repo.settings
    val syncStatus = repo.syncStatus
    val link = app.sync.state
    val launcher = app.kiosk.config
    val kioskSuspended = app.kiosk.suspended
    val update = app.updater.state

    val playlists: StateFlow<List<PlaylistEntity>> = repo.playlists.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val playlistsLoaded = mutableStateOf(false)

    val activePlaylistId = MutableStateFlow(app.prefs.activePlaylistId)

    fun setActivePlaylist(id: String) {
        app.prefs.activePlaylistId = id
        activePlaylistId.value = id
    }

    val favorites: StateFlow<Set<String>> = app.db.user().observeFavorites().map { l -> l.map { it.key }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val progress = app.db.user().observeProgress(30).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val downloads = app.downloads.all.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Filtrira grupe za odrasle i grupe koje je korisnik sakrio */
    private fun visibleCats(list: List<CategoryEntity>, adultUnlocked: Boolean): List<CategoryEntity> {
        val hidden = app.prefs.hiddenGroups
        val hideAdult = settings.value.hideAdultGroups && !adultUnlocked
        return list.filter { !(hideAdult && it.adult) && it.key !in hidden }
    }

    val adultUnlocked = MutableStateFlow(false)
    private val groupsVersion = MutableStateFlow(0)
    /** Emituje se kad se otključaju grupe za odrasle ili korisnik sakrije/prikaže grupu */
    private val filterFlow = combine(adultUnlocked, groupsVersion) { u, _ -> u }

    val live: StateFlow<LiveData> = activePlaylistId.flatMapLatest { pid ->
        combine(app.db.content().observeCategories(pid, "live"), app.db.content().observeChannels(pid), filterFlow) { cats, chans, unlocked ->
            val vis = visibleCats(cats, unlocked)
            val ids = vis.map { it.categoryId }.toHashSet()
            val hideAdult = settings.value.hideAdultGroups && !unlocked
            LiveData(vis, chans.filter { (it.categoryId in ids || cats.none { c -> c.categoryId == it.categoryId }) && !(hideAdult && it.adult) })
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LiveData())

    fun vodCategories() = activePlaylistId.flatMapLatest { pid -> app.db.content().observeCategories(pid, "vod") }
        .combine(filterFlow) { c, u -> visibleCats(c, u) }

    fun seriesCategories() = activePlaylistId.flatMapLatest { pid -> app.db.content().observeCategories(pid, "series") }
        .combine(filterFlow) { c, u -> visibleCats(c, u) }

    fun vod(category: String) = activePlaylistId.flatMapLatest { pid -> app.db.content().observeVod(pid, category, 3000) }
        .combine(filterFlow) { l, u -> if (settings.value.hideAdultGroups && !u) l.filter { !it.adult } else l }

    fun series(category: String) = activePlaylistId.flatMapLatest { pid -> app.db.content().observeSeries(pid, category, 3000) }
        .combine(filterFlow) { l, u -> if (settings.value.hideAdultGroups && !u) l.filter { !it.adult } else l }

    fun toggleFavorite(key: String, kind: String) = viewModelScope.launch {
        if (key in favorites.value) app.db.user().removeFavorite(key) else app.db.user().addFavorite(FavoriteEntity(key, kind, System.currentTimeMillis()))
    }

    fun hideGroup(key: String) {
        app.prefs.hiddenGroups = app.prefs.hiddenGroups + key
        groupsVersion.value++
    }

    fun showAllGroups() {
        app.prefs.hiddenGroups = emptySet()
        groupsVersion.value++
    }

    /** Pušta kanal; ostali kanali iz iste liste služe za prebacivanje gore/dole */
    fun playChannel(list: List<ChannelEntity>, ch: ChannelEntity) {
        val items = list.map { PlayItem(kind = "live", title = it.name, channel = it, poster = it.logo, playlistId = it.playlistId) }
        PlaySession.start(app, items, list.indexOfFirst { it.key == ch.key }.coerceAtLeast(0))
    }

    fun syncNow() = viewModelScope.launch { repo.syncAll(force = true) }

    fun startup() = viewModelScope.launch {
        if (activePlaylistId.value.isBlank()) {
            repo.db.playlists().all().firstOrNull()?.let { setActivePlaylist(it.id) }
        }
        playlistsLoaded.value = true
        repo.syncAll()
        app.downloads.refresh()
    }
}
