package com.orbitplayer.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.orbitplayer.data.Episode
import com.orbitplayer.data.SeriesDetail
import com.orbitplayer.data.VodDetail
import com.orbitplayer.data.db.CategoryEntity
import com.orbitplayer.data.db.SeriesEntity
import com.orbitplayer.data.db.VodEntity
import com.orbitplayer.player.PlayItem
import com.orbitplayer.player.PlaySession
import com.orbitplayer.ui.MainViewModel
import com.orbitplayer.ui.Screen
import com.orbitplayer.ui.ScreenHeader
import com.orbitplayer.ui.components.ActionButton
import com.orbitplayer.ui.components.EmptyState
import com.orbitplayer.ui.components.ListRow
import com.orbitplayer.ui.components.Loading
import com.orbitplayer.ui.components.Poster
import com.orbitplayer.ui.theme.LocalIsTv
import kotlinx.coroutines.launch

@Composable
private fun CategoryBar(cats: List<CategoryEntity>, selected: String, onSelect: (String) -> Unit) {
    val tv = LocalIsTv.current
    LazyRow(contentPadding = PaddingValues(horizontal = if (tv) 32.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ActionButton("Sve", primary = selected.isEmpty()) { onSelect("") } }
        items(cats, key = { it.key }) { c -> ActionButton(c.name, primary = c.categoryId == selected) { onSelect(c.categoryId) } }
    }
}

@Composable
private fun gridCells(): GridCells = if (LocalIsTv.current) GridCells.Adaptive(150.dp) else GridCells.Adaptive(110.dp)

@Composable
fun MoviesScreen(vm: MainViewModel) {
    var cat by remember { mutableStateOf("") }
    val cats by remember { vm.vodCategories() }.collectAsState(emptyList())
    val list by remember(cat) { vm.vod(cat) }.collectAsState(null)
    val progress by vm.progress.collectAsState()
    val tv = LocalIsTv.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Filmovi", vm)
        CategoryBar(cats, cat) { cat = it }
        Spacer(Modifier.height(12.dp))
        val rows = list
        when {
            rows == null -> Loading()
            rows.isEmpty() -> EmptyState("Nema filmova", "U ovoj listi nema filmova ili se još učitavaju.")
            else -> LazyVerticalGrid(
                columns = gridCells(),
                contentPadding = PaddingValues(horizontal = if (tv) 32.dp else 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(rows, key = { it.key }) { v ->
                    val p = progress.firstOrNull { it.key == "vod:${v.key}" }
                    Poster(v.name, v.poster, subtitle = v.rating.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" } ?: "", progress = p?.let { if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else -1f } ?: -1f) {
                        vm.open(Screen.MovieDetail(v.key))
                    }
                }
            }
        }
    }
}

@Composable
fun MovieDetailScreen(vm: MainViewModel, key: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tv = LocalIsTv.current
    val favorites by vm.favorites.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val v by produceState<VodEntity?>(null, key) { value = vm.app.db.content().vod(key) }
    val detail by produceState<VodDetail?>(null, v) { v?.let { value = vm.app.repo.vodDetail(it) } }
    val resume by produceState<Long>(0L, key) { value = vm.app.db.user().progress("vod:$key")?.positionMs ?: 0L }
    val movie = v ?: return Loading()
    val dl = downloads.firstOrNull { it.contentKey == "vod:$key" }

    fun play() = scope.launch {
        val url = vm.app.repo.vodUrl(movie)
        PlaySession.play(context, PlayItem(kind = "vod", title = movie.name, url = url, poster = movie.poster, progressKey = "vod:${movie.key}", playlistId = movie.playlistId))
    }

    Box(Modifier.fillMaxSize()) {
        detail?.backdrop?.takeIf { it.isNotBlank() }?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.25f)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(if (tv) 32.dp else 16.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    AsyncImage(
                        model = movie.poster, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(if (tv) 220.dp else 130.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(movie.name, style = MaterialTheme.typography.headlineMedium)
                        val meta = listOfNotNull(
                            detail?.year?.takeIf { it.isNotBlank() },
                            detail?.duration?.takeIf { it.isNotBlank() },
                            detail?.genre?.takeIf { it.isNotBlank() },
                            movie.rating.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" },
                        ).joinToString(" · ")
                        if (meta.isNotBlank()) Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        detail?.plot?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = if (tv) 6 else 10, overflow = TextOverflow.Ellipsis) }
                        detail?.director?.takeIf { it.isNotBlank() }?.let { Text("Režija: $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        detail?.cast?.takeIf { it.isNotBlank() }?.let { Text("Uloge: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            item {
                Spacer(Modifier.height(20.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { ActionButton(if (resume > 30_000) "Nastavi" else "Pusti", icon = Icons.Filled.PlayArrow, primary = true) { play() } }
                    if (resume > 30_000) item {
                        ActionButton("Od početka") {
                            scope.launch {
                                vm.app.db.user().deleteProgress("vod:${movie.key}")
                                play()
                            }
                        }
                    }
                    item { ActionButton(if (movie.key in favorites) "Ukloni iz omiljenih" else "Dodaj u omiljene", icon = if (movie.key in favorites) Icons.Filled.Star else Icons.Filled.StarBorder) { vm.toggleFavorite(movie.key, "vod") } }
                    item {
                        val label = when (dl?.status) {
                            null -> "Preuzmi"
                            "done" -> "Preuzeto"
                            "failed" -> "Preuzmi ponovo"
                            else -> "Preuzima se…"
                        }
                        ActionButton(label, icon = Icons.Filled.Download) {
                            if (dl?.status == "done") vm.open(Screen.Downloads)
                            else scope.launch {
                                val url = vm.app.repo.vodUrl(movie)
                                vm.app.downloads.enqueue("vod:${movie.key}", "vod", movie.name, movie.poster, url, movie.ext)
                                Toast.makeText(context, "Preuzimanje je počelo", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesScreen(vm: MainViewModel) {
    var cat by remember { mutableStateOf("") }
    val cats by remember { vm.seriesCategories() }.collectAsState(emptyList())
    val list by remember(cat) { vm.series(cat) }.collectAsState(null)
    val tv = LocalIsTv.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Serije", vm)
        CategoryBar(cats, cat) { cat = it }
        Spacer(Modifier.height(12.dp))
        val rows = list
        when {
            rows == null -> Loading()
            rows.isEmpty() -> EmptyState("Nema serija", "U ovoj listi nema serija ili se još učitavaju.")
            else -> LazyVerticalGrid(
                columns = gridCells(),
                contentPadding = PaddingValues(horizontal = if (tv) 32.dp else 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(rows, key = { it.key }) { s ->
                    Poster(s.name, s.cover, subtitle = s.rating.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" } ?: "") { vm.open(Screen.SeriesDetail(s.key)) }
                }
            }
        }
    }
}

@Composable
fun SeriesDetailScreen(vm: MainViewModel, key: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tv = LocalIsTv.current
    val favorites by vm.favorites.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val s by produceState<SeriesEntity?>(null, key) { value = vm.app.db.content().series(key) }
    var error by remember { mutableStateOf("") }
    val detail by produceState<SeriesDetail?>(null, s) {
        val se = s ?: return@produceState
        value = runCatching { vm.app.repo.seriesDetail(se) }.onFailure { error = it.message ?: "Greška" }.getOrNull()
    }
    val watched by produceState(emptyMap<String, Long>(), key) {
        value = vm.app.db.user().progressForSeries(key).associate { it.key to it.positionMs }
    }
    var season by remember { mutableIntStateOf(-1) }
    val series = s ?: return Loading()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(series.name, vm) {
            ActionButton(if (series.key in favorites) "Ukloni iz omiljenih" else "Dodaj u omiljene", icon = if (series.key in favorites) Icons.Filled.Star else Icons.Filled.StarBorder) { vm.toggleFavorite(series.key, "series") }
        }
        val d = detail
        when {
            d == null && error.isNotBlank() -> EmptyState("Serija nije učitana", error)
            d == null -> Loading()
            d.seasons.isEmpty() -> EmptyState("Nema epizoda")
            else -> {
                val seasonNums = d.seasons.keys.toList()
                val cur = if (season in seasonNums) season else seasonNums.first()
                if (d.plot.isNotBlank()) Text(d.plot, Modifier.padding(horizontal = if (tv) 32.dp else 16.dp), maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = if (tv) 32.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasonNums) { n -> ActionButton("Sezona $n", primary = n == cur) { season = n } }
                }
                Spacer(Modifier.height(10.dp))
                val episodes = d.seasons[cur].orEmpty()
                LazyColumn(contentPadding = PaddingValues(horizontal = if (tv) 24.dp else 8.dp, vertical = 8.dp)) {
                    items(episodes, key = { it.id }) { e ->
                        val pKey = "ep:${series.playlistId}:${e.id}"
                        val dl = downloads.firstOrNull { it.contentKey == pKey }
                        ListRow(
                            title = "${e.number}. ${e.title.ifBlank { "Epizoda ${e.number}" }}",
                            subtitle = listOfNotNull(
                                if (e.durationSec > 0) "${e.durationSec / 60} min" else null,
                                if ((watched[pKey] ?: 0) > 0) "započeto" else null,
                                when (dl?.status) { "done" -> "preuzeto"; null, "failed" -> null; else -> "preuzima se" },
                                e.plot.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            leading = {
                                if (e.image.isNotBlank()) AsyncImage(model = e.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.width(if (tv) 140.dp else 96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)))
                            },
                            trailing = {
                                if (dl == null || dl.status == "failed") ActionButton("Preuzmi", icon = Icons.Filled.Download) {
                                    scope.launch {
                                        val url = vm.app.repo.episodeUrl(series.playlistId, e)
                                        vm.app.downloads.enqueue(pKey, "episode", "${series.name} S${cur}E${e.number}", series.cover, url, e.ext)
                                        Toast.makeText(context, "Preuzimanje je počelo", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        ) {
                            scope.launch { playEpisodes(vm, context, series, cur, episodes, e) }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun playEpisodes(vm: MainViewModel, context: android.content.Context, series: SeriesEntity, season: Int, episodes: List<Episode>, start: Episode) {
    val items = episodes.map { e ->
        PlayItem(
            kind = "episode",
            title = "${series.name} — S${season}E${e.number}",
            url = vm.app.repo.episodeUrl(series.playlistId, e),
            poster = e.image.ifBlank { series.cover },
            progressKey = "ep:${series.playlistId}:${e.id}",
            seriesKey = series.key,
            playlistId = series.playlistId,
            subtitle = e.title,
        )
    }
    PlaySession.start(context, items, episodes.indexOf(start))
}

