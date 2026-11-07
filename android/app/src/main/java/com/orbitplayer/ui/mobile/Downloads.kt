package com.orbitplayer.ui.mobile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.orbitplayer.core.Fmt
import com.orbitplayer.data.db.DownloadEntity
import com.orbitplayer.playback.PlayItem
import com.orbitplayer.playback.SubtitleSource
import com.orbitplayer.ui.AppViewModel
import com.orbitplayer.ui.common.PageTitle
import com.orbitplayer.ui.components.ConfirmDialog
import com.orbitplayer.ui.components.EmptyState
import com.orbitplayer.ui.components.ListRow
import com.orbitplayer.ui.components.OrbitButton
import com.orbitplayer.ui.components.ProgressLine
import com.orbitplayer.ui.components.TextInputDialog
import com.orbitplayer.ui.theme.LocalIsTv
import kotlinx.coroutines.delay
import java.io.File

/** Downloaded movies and episodes for offline viewing, with their subtitles */
@Composable
fun DownloadsScreen(vm: AppViewModel) {
    val list by vm.downloads.collectAsState()
    val tv = LocalIsTv.current
    var subFor by remember { mutableStateOf<DownloadEntity?>(null) }
    var urlFor by remember { mutableStateOf<DownloadEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<DownloadEntity?>(null) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val d = subFor
        subFor = null
        if (uri != null && d != null) vm.launch {
            runCatching { vm.app.downloads.addSubtitle(d.id, uri, null, "en") }
                .onSuccess { vm.toast("Subtitle added") }
                .onFailure { vm.toast(it.message ?: "Could not add subtitle") }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { vm.app.downloads.refresh() }
            delay(2000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageTitle("Downloads", vm)
        if (list.isEmpty()) {
            EmptyState("Nothing downloaded yet", "Open a movie or an episode and choose Download to watch it without internet.")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = if (tv) 24.dp else 8.dp, vertical = 8.dp)) {
            items(list, key = { it.id }) { d ->
                val subs = d.subtitlePaths.lines().filter { it.isNotBlank() }
                Column {
                    ListRow(
                        d.title,
                        subtitle = when (d.status) {
                            "done" -> "Downloaded · ${Fmt.bytes(d.sizeBytes)}" + if (subs.isNotEmpty()) " · ${subs.size} subtitle(s)" else ""
                            "failed" -> "Failed: ${d.error}"
                            "paused" -> "Paused, waiting for network · ${Fmt.bytes(d.downloadedBytes)} / ${Fmt.bytes(d.sizeBytes)}"
                            "queued" -> "Queued"
                            else -> "Downloading · ${Fmt.bytes(d.downloadedBytes)} / ${Fmt.bytes(d.sizeBytes)}"
                        },
                        leading = {
                            AsyncImage(model = d.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.width(52.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)))
                        },
                    ) {
                        if (d.status == "done" && File(d.filePath).exists()) {
                            vm.playItem(
                                PlayItem(
                                    kind = "local",
                                    title = d.title,
                                    url = File(d.filePath).toURI().toString(),
                                    poster = d.poster,
                                    progressKey = d.contentKey,
                                    subtitles = subs.mapIndexed { i, p -> SubtitleSource(File(p).toURI().toString(), "Subtitle ${i + 1}") },
                                ),
                            )
                        } else if (d.status == "failed") {
                            vm.toast(d.error.ifBlank { "Download failed" })
                        }
                    }
                    if (d.status == "running" && d.sizeBytes > 0) {
                        ProgressLine(d.downloadedBytes.toFloat() / d.sizeBytes, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    }
                    Row(Modifier.padding(start = 76.dp, top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (d.status == "done") {
                            if (!tv) OrbitButton("Subtitle file", icon = Icons.Filled.Subtitles) { subFor = d; pickFile.launch(arrayOf("*/*")) }
                            OrbitButton("Subtitle URL", icon = Icons.Filled.Link) { urlFor = d }
                        }
                        OrbitButton("Delete", icon = Icons.Filled.Delete) { confirmDelete = d }
                    }
                }
            }
        }
    }
    urlFor?.let { d ->
        TextInputDialog("Subtitle from address", "", "https://…/subtitle.srt", onDismiss = { urlFor = null }) { url ->
            urlFor = null
            vm.launch {
                runCatching { vm.app.downloads.addSubtitle(d.id, null, url.trim(), "en") }
                    .onSuccess { vm.toast("Subtitle downloaded") }
                    .onFailure { vm.toast(it.message ?: "Could not download subtitle") }
            }
        }
    }
    confirmDelete?.let { d ->
        ConfirmDialog("Delete download?", "“${d.title}” and its subtitles will be removed from this device.", "Delete", onDismiss = { confirmDelete = null }) {
            confirmDelete = null
            vm.launch { vm.app.downloads.delete(d.id) }
        }
    }
}
