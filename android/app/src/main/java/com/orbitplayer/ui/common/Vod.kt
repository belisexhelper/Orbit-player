package com.orbitplayer.ui.common

import com.orbitplayer.ui.components.IconAction
import com.orbitplayer.ui.components.OrbitTextField
import com.orbitplayer.ui.components.ChoiceDialog
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.orbitplayer.core.Fmt
import com.orbitplayer.data.Episode
import com.orbitplayer.data.SeriesDetail
import com.orbitplayer.data.VodDetail
import com.orbitplayer.data.db.SeriesEntity
import com.orbitplayer.data.db.VodEntity
import com.orbitplayer.ui.AppViewModel
import com.orbitplayer.ui.Route
import com.orbitplayer.ui.components.EmptyState
import com.orbitplayer.ui.components.ListRow
import com.orbitplayer.ui.components.Loading
import com.orbitplayer.ui.components.OrbitButton
import com.orbitplayer.ui.components.PosterCard
import com.orbitplayer.ui.components.ProgressLine
import com.orbitplayer.ui.components.Focusable
import com.orbitplayer.ui.theme.LocalIsTv
import com.orbitplayer.ui.theme.Orbit
import kotlinx.coroutines.delay

private const val CAT_ALL = ""
private const val CAT_FAV = "__fav"
private const val CAT_CONTINUE = "__continue"

/** Movies or series: categories + poster grid */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VodBrowseScreen(vm: AppViewModel, series: Boolean) {
    val tv = LocalIsTv.current
    val c = Orbit.colors
    val playlists by vm.playlists.collectAsState()
    if (playlists?.isEmpty() == true) return NoPlaylist(vm)
    var cat by remember(series) { mutableStateOf(CAT_ALL) }
    val cats by remember(series) { if (series) vm.seriesCategories() else vm.vodCategories() }.collectAsState(emptyList())
    val favorites by vm.favorites.collectAsState()
    val progress by vm.allProgress.collectAsState()
    val cont by vm.continueWatching.collectAsState()
    val sortFlow = if (series) vm.seriesSort else vm.vodSort
    val sort by sortFlow.collectAsState()
    var searchOpen by remember(series) { mutableStateOf(false) }
    var query by remember(series) { mutableStateOf("") }
    var debounced by remember(series) { mutableStateOf("") }
    LaunchedEffect(query) {
        delay(300)
        debounced = if (query.trim().length >= 2) query.trim() else ""
    }
    val searching = debounced.isNotEmpty()
    var sortDialog by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val queryCat = if (cat == CAT_FAV || cat == CAT_CONTINUE) CAT_ALL else cat
    val movies by remember(queryCat, series, debounced) { vm.movies(queryCat, debounced) }.collectAsState(null)
    val shows by remember(queryCat, series, debounced) { vm.series(queryCat, debounced) }.collectAsState(null)

    data class Card(val key: String, val title: String, val image: String, val sub: String, val progress: Float, val open: () -> Unit)

    val cards: List<Card>? = if (series) shows?.let { l ->
        val src = if (searching) l else when (cat) {
            CAT_FAV -> l.filter { it.key in favorites }
            CAT_CONTINUE -> {
                val keys = cont.filter { it.kind == "episode" }.map { it.seriesKey }.distinct()
                val byKey = l.associateBy { it.key }
                keys.mapNotNull { byKey[it] }
            }
            else -> l
        }
        src.map { s -> Card(s.key, s.name, s.cover, rating(s.rating), -1f) { vm.open(Route.SeriesDetail(s.key)) } }
    } else movies?.let { l ->
        val src = if (searching) l else when (cat) {
            CAT_FAV -> l.filter { it.key in favorites }
            CAT_CONTINUE -> {
                val byKey = l.associateBy { "vod:${it.key}" }
                cont.filter { it.kind == "vod" }.mapNotNull { byKey[it.key] }
            }
            else -> l
        }
        src.map { v ->
            val p = progress["vod:${v.key}"]
            Card(v.key, v.name, v.poster, rating(v.rating), p?.takeIf { !it.finished && it.durationMs > 0 }?.let { it.positionMs.toFloat() / it.durationMs } ?: -1f) { vm.open(Route.MovieDetail(v.key)) }
        }
    }

    val catList = buildList {
        add(CAT_ALL to "All")
        add(CAT_CONTINUE to "Continue watching")
        add(CAT_FAV to "Favorites")
        cats.forEach { add(it.categoryId to it.name) }
    }
    val title = if (series) "Series" else "Movies"
    val sortOptions = listOf("added" to "Recently added", "name" to "A–Z", "rating" to "Top rated")
    val sortLabel = sortOptions.firstOrNull { it.first == sort }?.second ?: "Recently added"
    val heading = if (searching) "Results for “$debounced”" else catList.firstOrNull { it.first == cat }?.second ?: title

    // Search field (searches every category) and the sort chooser, shared by TV and phone
    val searchBar: @Composable () -> Unit = {
        if (searchOpen) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                OrbitTextField(query, { query = it }, if (series) "Search all series" else "Search all movies", Modifier.weight(1f), focusRequester = searchFocus)
                IconAction(Icons.Filled.Close, "Close") { query = ""; debounced = ""; searchOpen = false }
            }
            LaunchedEffect(Unit) {
                delay(100)
                runCatching { searchFocus.requestFocus() }
            }
        }
    }
    val sortButton: @Composable () -> Unit = {
        Focusable(onClick = { sortDialog = true }, shape = RoundedCornerShape(20.dp), color = c.surface) { _ ->
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Sort, null, Modifier.size(18.dp), tint = c.accent)
                Spacer(Modifier.width(6.dp))
                Text(sortLabel, color = c.text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(20.dp), tint = c.textMuted)
            }
        }
    }
    val searchButton: @Composable () -> Unit = {
        IconAction(Icons.Filled.Search, "Search", active = searchOpen) { searchOpen = !searchOpen; if (!searchOpen) { query = ""; debounced = "" } }
    }
    if (sortDialog) {
        ChoiceDialog("Sort ${title.lowercase()} by", sortOptions, sort, onDismiss = { sortDialog = false }) { v ->
            sortFlow.value = v
            sortDialog = false
        }
    }

    val grid: @Composable (Modifier) -> Unit = { m ->
        when {
            cards == null -> Loading(modifier = m)
            cards.isEmpty() && searching -> EmptyState("Nothing found", "No ${title.lowercase()} match “$debounced”.", m)
            cards.isEmpty() -> EmptyState(
                when (cat) { CAT_FAV -> "No favorites yet"; CAT_CONTINUE -> "Nothing to continue"; else -> "Nothing here" },
                when (cat) { CAT_FAV -> "Long-press a poster to add it to favorites."; CAT_CONTINUE -> "What you start watching appears here."; else -> "This category is empty, or the playlist is still loading." },
                m,
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(if (tv) 148.dp else 108.dp),
                modifier = m.focusRestorer().focusGroup(),
                contentPadding = PaddingValues(if (tv) 10.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(if (tv) 18.dp else 10.dp),
                verticalArrangement = Arrangement.spacedBy(if (tv) 16.dp else 10.dp),
            ) {
                items(cards, key = { it.key }) { card ->
                    PosterCard(card.title, card.image, subtitle = card.sub, progress = card.progress, onLongClick = { vm.toggleFavorite(card.key, if (series) "series" else "vod") }) { card.open() }
                }
            }
        }
    }

    if (tv) {
        Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
            Column(Modifier.width(240.dp).fillMaxHeight()) {
                Text(title, Modifier.padding(start = 14.dp, bottom = 10.dp), color = c.text, style = MaterialTheme.typography.headlineSmall)
                LazyColumn(Modifier.focusRestorer().focusGroup(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(catList, key = { it.first }) { (id, name) ->
                        ListRow(name, selected = id == cat, onFocus = { cat = id }) { cat = id }
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(heading, color = c.text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (cards != null) Text("${cards.size}", color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
                    searchButton()
                    sortButton()
                }
                searchBar()
                grid(Modifier.weight(1f))
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = c.text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                searchButton()
                sortButton()
            }
            Box(Modifier.padding(horizontal = 12.dp)) { Column { searchBar() } }
            if (searching) {
                Text("${heading} · all categories" + (cards?.let { " · ${it.size}" } ?: ""), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(catList, key = { it.first }) { (id, name) -> OrbitButton(name, primary = id == cat) { cat = id } }
                }
            }
            grid(Modifier.weight(1f))
        }
    }
}

private fun rating(r: String): String = r.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) } ?: ""

// ---------------------------------------------------------------- details

@Composable
private fun DetailHeader(
    title: String,
    poster: String,
    backdrop: String,
    meta: String,
    plot: String,
    extra: List<String>,
    buttons: @Composable () -> Unit,
) {
    val tv = LocalIsTv.current
    val c = Orbit.colors
    Box(Modifier.fillMaxWidth()) {
        if (backdrop.isNotBlank()) {
            AsyncImage(model = backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize(), alpha = 0.35f)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, c.background))))
        }
        Row(Modifier.padding(if (tv) 28.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(if (tv) 28.dp else 16.dp)) {
            Box(Modifier.width(if (tv) 200.dp else 112.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(c.surfaceFocus)) {
                if (poster.isNotBlank()) AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, color = c.text, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (meta.isNotBlank()) Text(meta, color = c.accent, style = MaterialTheme.typography.bodyMedium)
                if (plot.isNotBlank()) Text(plot, color = c.text.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium, maxLines = if (tv) 4 else 6, overflow = TextOverflow.Ellipsis)
                extra.filter { it.isNotBlank() }.forEach { Text(it, color = c.textMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (tv) {
                    Spacer(Modifier.height(6.dp))
                    buttons()
                }
            }
        }
    }
    if (!tv) Box(Modifier.padding(horizontal = 16.dp)) { buttons() }
}

@Composable
fun MovieDetailScreen(vm: AppViewModel, key: String) {
    val tv = LocalIsTv.current
    val favorites by vm.favorites.collectAsState()
    val progress by vm.allProgress.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val v by produceState<VodEntity?>(null, key) { value = vm.app.db.content().vod(key) }
    val detail by produceState<VodDetail?>(null, v) { v?.let { value = vm.repo.vodDetail(it) } }
    val movie = v ?: return Loading()
    val p = progress["vod:${movie.key}"]?.takeIf { !it.finished && it.positionMs > 30_000 }
    val dl = downloads.firstOrNull { it.contentKey == "vod:${movie.key}" }
    val playFocus = remember { FocusRequester() }
    val fav = movie.key in favorites
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            DetailHeader(
                title = movie.name,
                poster = movie.poster,
                backdrop = detail?.backdrop ?: "",
                meta = listOfNotNull(detail?.year, detail?.duration, detail?.genre, rating(movie.rating)).filter { it.isNotBlank() }.joinToString("  ·  "),
                plot = detail?.plot ?: "",
                extra = listOf(detail?.director?.takeIf { it.isNotBlank() }?.let { "Director: $it" } ?: "", detail?.cast?.takeIf { it.isNotBlank() }?.let { "Cast: $it" } ?: ""),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OrbitButton(if (p != null) "Resume from ${Fmt.duration(p.positionMs)}" else "Play", icon = Icons.Filled.PlayArrow, primary = true, focusRequester = playFocus) { vm.playMovie(movie) }
                    if (p != null) OrbitButton("Start over", icon = Icons.Filled.Replay) { vm.playMovie(movie, fromStart = true) }
                    OrbitButton(if (fav) "Favorite" else "Add to favorites", icon = if (fav) Icons.Filled.Star else Icons.Filled.StarBorder) { vm.toggleFavorite(movie.key, "vod") }
                    if (!tv) DownloadButton(vm, dl?.status) {
                        vm.launch {
                            vm.app.downloads.enqueue("vod:${movie.key}", "vod", movie.name, movie.poster, vm.repo.vodUrl(movie), movie.ext)
                            vm.toast("Download started")
                        }
                    }
                }
            }
        }
        if (p != null && p.durationMs > 0) item {
            ProgressLine(p.positionMs.toFloat() / p.durationMs, Modifier.padding(horizontal = if (tv) 28.dp else 16.dp, vertical = 12.dp).fillMaxWidth(0.5f), height = 4.dp)
        }
    }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { playFocus.requestFocus() }
    }
}

@Composable
private fun DownloadButton(vm: AppViewModel, status: String?, onStart: () -> Unit) {
    when (status) {
        null, "failed" -> OrbitButton("Download", icon = Icons.Filled.Download) { onStart() }
        "done" -> OrbitButton("Downloaded", icon = Icons.Filled.DownloadDone) { vm.open(Route.Downloads) }
        else -> OrbitButton("Downloading…", icon = Icons.Filled.Download) { vm.open(Route.Downloads) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SeriesDetailScreen(vm: AppViewModel, key: String) {
    val tv = LocalIsTv.current
    val favorites by vm.favorites.collectAsState()
    val progress by vm.allProgress.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val s by produceState<SeriesEntity?>(null, key) { value = vm.app.db.content().series(key) }
    var error by remember { mutableStateOf("") }
    val detail by produceState<SeriesDetail?>(null, s) {
        val se = s ?: return@produceState
        value = runCatching { vm.repo.seriesDetail(se) }.onFailure { error = it.message ?: "Error" }.getOrNull()
    }
    var season by remember { mutableIntStateOf(-1) }
    val show = s ?: return Loading()
    val playFocus = remember { FocusRequester() }
    val fav = show.key in favorites

    val d = detail
    val seasonNums = d?.seasons?.keys?.toList() ?: emptyList()
    // resume: the last started episode, else the first one
    val lastEp = remember(d, progress) {
        d?.seasons?.flatMap { (sn, eps) -> eps.map { sn to it } }?.maxByOrNull { (_, e) -> progress["ep:${show.playlistId}:${e.id}"]?.updatedAt ?: 0L }
            ?.takeIf { (_, e) -> progress["ep:${show.playlistId}:${e.id}"] != null }
    }
    val cur = if (season in seasonNums) season else lastEp?.first ?: seasonNums.firstOrNull() ?: 1
    val episodes = d?.seasons?.get(cur).orEmpty()

    LazyColumn(Modifier.fillMaxSize().focusRestorer().focusGroup(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            DetailHeader(
                title = show.name,
                poster = d?.cover?.ifBlank { show.cover } ?: show.cover,
                backdrop = d?.backdrop ?: "",
                meta = listOfNotNull(d?.year, d?.genre, rating(d?.rating ?: show.rating), d?.let { "${it.seasons.size} seasons" }).filter { it.isNotBlank() }.joinToString("  ·  "),
                plot = d?.plot ?: show.plot,
                extra = listOf(d?.cast?.takeIf { it.isNotBlank() }?.let { "Cast: $it" } ?: ""),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val (rs, re) = lastEp ?: (seasonNums.firstOrNull() to d?.seasons?.values?.firstOrNull()?.firstOrNull())
                    if (re != null && rs != null) {
                        OrbitButton(if (lastEp != null) "Continue S$rs E${re.number}" else "Play S$rs E${re.number}", icon = Icons.Filled.PlayArrow, primary = true, focusRequester = playFocus) {
                            vm.playEpisodes(show, rs, d!!.seasons[rs].orEmpty(), re)
                        }
                    }
                    OrbitButton(if (fav) "Favorite" else "Add to favorites", icon = if (fav) Icons.Filled.Star else Icons.Filled.StarBorder) { vm.toggleFavorite(show.key, "series") }
                }
            }
        }
        when {
            d == null && error.isNotBlank() -> item { EmptyState("Series not loaded", error, Modifier.height(200.dp)) }
            d == null -> item { Loading(modifier = Modifier.height(200.dp)) }
            seasonNums.isEmpty() -> item { EmptyState("No episodes", modifier = Modifier.height(200.dp)) }
            else -> {
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = if (tv) 28.dp else 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasonNums) { n -> OrbitButton("Season $n", primary = n == cur) { season = n } }
                    }
                }
                items(episodes, key = { it.id }) { e ->
                    val pKey = "ep:${show.playlistId}:${e.id}"
                    val pr = progress[pKey]
                    val dl = downloads.firstOrNull { it.contentKey == pKey }
                    EpisodeRow(e, pr?.let { if (it.finished) 1f else if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else -1f } ?: -1f, dl?.status,
                        onDownload = if (tv) null else ({
                            vm.launch {
                                vm.app.downloads.enqueue(pKey, "episode", "${show.name} S${cur}E${e.number}", show.cover, vm.repo.episodeUrl(show.playlistId, e), e.ext)
                                vm.toast("Download started")
                            }
                        }),
                    ) { vm.playEpisodes(show, cur, episodes, e) }
                }
            }
        }
    }
    LaunchedEffect(d != null) {
        delay(150)
        runCatching { playFocus.requestFocus() }
    }
}

@Composable
private fun EpisodeRow(e: Episode, progress: Float, downloadStatus: String?, onDownload: (() -> Unit)?, onClick: () -> Unit) {
    val tv = LocalIsTv.current
    val c = Orbit.colors
    Focusable(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = if (tv) 20.dp else 8.dp, vertical = 3.dp), color = Color.Transparent, scaleOnFocus = 1f) { _ ->
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.width(if (tv) 176.dp else 116.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(c.surfaceFocus)) {
                if (e.image.isNotBlank()) AsyncImage(model = e.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text("E${e.number}", Modifier.align(Alignment.Center), color = c.textMuted, style = MaterialTheme.typography.titleMedium)
                if (progress in 0f..0.999f) ProgressLine(progress, Modifier.fillMaxWidth().align(Alignment.BottomStart), height = 4.dp)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${e.number}. ${e.title.ifBlank { "Episode ${e.number}" }}", color = c.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (progress >= 0.999f) Icon(Icons.Filled.CheckCircle, "Watched", Modifier.padding(start = 6.dp).size(18.dp), tint = c.success)
                }
                val meta = listOfNotNull(if (e.durationSec > 0) "${e.durationSec / 60} min" else null, when (downloadStatus) { "done" -> "Downloaded"; null, "failed" -> null; else -> "Downloading" }).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                if (e.plot.isNotBlank()) Text(e.plot, color = c.textMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (onDownload != null && (downloadStatus == null || downloadStatus == "failed")) {
                Focusable(onClick = onDownload, color = Color.Transparent, shape = RoundedCornerShape(50)) { _ ->
                    Icon(Icons.Filled.Download, "Download", Modifier.padding(10.dp), tint = c.textMuted)
                }
            }
        }
    }
}
