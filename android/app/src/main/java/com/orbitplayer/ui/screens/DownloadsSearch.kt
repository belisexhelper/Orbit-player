package com.orbitplayer.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.orbitplayer.data.db.ChannelEntity
import com.orbitplayer.data.db.DownloadEntity
import com.orbitplayer.data.db.SeriesEntity
import com.orbitplayer.data.db.VodEntity
import com.orbitplayer.player.PlayItem
import com.orbitplayer.player.PlaySession
import com.orbitplayer.player.SubtitleSource
import com.orbitplayer.ui.MainViewModel
import com.orbitplayer.ui.Screen
import com.orbitplayer.ui.ScreenHeader
import com.orbitplayer.ui.components.ActionButton
import com.orbitplayer.ui.components.ChannelLogo
import com.orbitplayer.ui.components.EmptyState
import com.orbitplayer.ui.components.ListRow
import com.orbitplayer.ui.components.SectionTitle
import com.orbitplayer.ui.theme.LocalIsTv
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private fun mb(b: Long) = if (b <= 0) "?" else "%.0f MB".format(b / 1048576.0)

@Composable
fun DownloadsScreen(vm: MainViewModel) {
    val list by vm.downloads.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tv = LocalIsTv.current
    var subFor by remember { mutableStateOf<DownloadEntity?>(null) }
    var subUrl by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<DownloadEntity?>(null) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val d = subFor
        subFor = null
        subUrl = ""
        if (uri != null && d != null) scope.launch {
            runCatching { vm.app.downloads.addSubtitle(d.id, uri, null, "sr") }
                .onSuccess { Toast.makeText(context, "Prevod je dodat", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
        }
    }
    // napredak preuzimanja
    LaunchedEffect(Unit) {
        while (true) {
            vm.app.downloads.refresh()
            delay(2000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Preuzeto", vm)
        if (list.isEmpty()) {
            EmptyState("Nema preuzetih snimaka", "Na stranici filma ili epizode izaberite „Preuzmi” da biste gledali bez interneta.")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = if (tv) 24.dp else 8.dp, vertical = 8.dp)) {
            items(list, key = { it.id }) { d ->
                val subs = d.subtitlePaths.lines().filter { it.isNotBlank() }
                Column {
                    ListRow(
                        title = d.title,
                        subtitle = when (d.status) {
                            "done" -> "Preuzeto · ${mb(d.sizeBytes)}" + if (subs.isNotEmpty()) " · prevoda: ${subs.size}" else ""
                            "failed" -> "Neuspešno: ${d.error}"
                            "paused" -> "Pauzirano (čeka mrežu) · ${mb(d.downloadedBytes)} / ${mb(d.sizeBytes)}"
                            "queued" -> "Na čekanju"
                            else -> "Preuzimanje · ${mb(d.downloadedBytes)} / ${mb(d.sizeBytes)}"
                        },
                        leading = {
                            AsyncImage(model = d.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.width(56.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)))
                        },
                    ) {
                        if (d.status == "done" && File(d.filePath).exists()) {
                            PlaySession.play(
                                context,
                                PlayItem(
                                    kind = "local",
                                    title = d.title,
                                    url = File(d.filePath).toURI().toString(),
                                    poster = d.poster,
                                    progressKey = d.contentKey,
                                    downloadId = d.id,
                                    subtitles = subs.mapIndexed { i, p -> SubtitleSource(File(p).toURI().toString(), "Prevod ${i + 1}") },
                                ),
                            )
                        }
                    }
                    if (d.status == "running" && d.sizeBytes > 0) {
                        LinearProgressIndicator(progress = { d.downloadedBytes.toFloat() / d.sizeBytes }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    }
                    Row(Modifier.padding(start = 82.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (d.status == "done") {
                            if (!tv) ActionButton("Prevod iz fajla") { subFor = d; subUrl = "__picking"; pickFile.launch(arrayOf("*/*")) }
                            ActionButton("Prevod sa adrese") { subFor = d; subUrl = "" }
                        }
                        ActionButton("Obriši") { confirmDelete = d }
                    }
                }
            }
        }
    }

    val sf = subFor
    if (sf != null && subUrl != "__picking") {
        AlertDialog(
            onDismissRequest = { subFor = null },
            title = { Text("Prevod sa adrese") },
            text = { OutlinedTextField(value = subUrl, onValueChange = { subUrl = it }, label = { Text("https://…/prevod.srt") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val url = subUrl.trim()
                    subFor = null
                    scope.launch {
                        runCatching { vm.app.downloads.addSubtitle(sf.id, null, url, "sr") }
                            .onSuccess { Toast.makeText(context, "Prevod je preuzet", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                    }
                }) { Text("Preuzmi prevod") }
            },
            dismissButton = { TextButton(onClick = { subFor = null }) { Text("Odustani") } },
        )
    }
    confirmDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Obrisati?") },
            text = { Text("„${d.title}” i njegovi prevodi biće obrisani sa uređaja.") },
            confirmButton = { TextButton(onClick = { confirmDelete = null; scope.launch { vm.app.downloads.delete(d.id) } }) { Text("Obriši") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Odustani") } },
        )
    }
}

@Composable
fun SearchScreen(vm: MainViewModel) {
    var q by remember { mutableStateOf("") }
    val pid by vm.activePlaylistId.collectAsState()
    val tv = LocalIsTv.current
    data class Results(val channels: List<ChannelEntity>, val vod: List<VodEntity>, val series: List<SeriesEntity>)
    val results by produceState(Results(emptyList(), emptyList(), emptyList()), q, pid) {
        if (q.trim().length < 2) {
            value = Results(emptyList(), emptyList(), emptyList())
            return@produceState
        }
        delay(300)
        val like = "%${q.trim()}%"
        val c = vm.app.db.content()
        value = Results(c.searchChannels(pid, like), c.searchVod(pid, like), c.searchSeries(pid, like))
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Pretraga", vm)
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            label = { Text("Kanal, film ili serija") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = if (tv) 32.dp else 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(contentPadding = PaddingValues(horizontal = if (tv) 24.dp else 8.dp, vertical = 8.dp)) {
            if (results.channels.isNotEmpty()) {
                item { SectionTitle("Kanali", Modifier.padding(horizontal = 8.dp)) }
                items(results.channels, key = { "c" + it.key }) { ch ->
                    ListRow(ch.name, subtitle = "Kanal ${ch.num}", leading = { ChannelLogo(ch.logo, ch.name, 36.dp) }) { vm.playChannel(results.channels, ch) }
                }
            }
            if (results.vod.isNotEmpty()) {
                item { SectionTitle("Filmovi", Modifier.padding(horizontal = 8.dp)) }
                items(results.vod, key = { "v" + it.key }) { v -> ListRow(v.name, subtitle = "Film") { vm.open(Screen.MovieDetail(v.key)) } }
            }
            if (results.series.isNotEmpty()) {
                item { SectionTitle("Serije", Modifier.padding(horizontal = 8.dp)) }
                items(results.series, key = { "s" + it.key }) { s -> ListRow(s.name, subtitle = "Serija") { vm.open(Screen.SeriesDetail(s.key)) } }
            }
            if (q.trim().length >= 2 && results.channels.isEmpty() && results.vod.isEmpty() && results.series.isEmpty()) {
                item { Text("Nema rezultata za „$q”.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
