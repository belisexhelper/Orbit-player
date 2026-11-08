package com.orbitplayer.ui.common

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbitplayer.BuildConfig
import com.orbitplayer.core.DeviceInfo
import com.orbitplayer.core.Net
import com.orbitplayer.data.PlaylistInput
import com.orbitplayer.data.XtreamClient
import com.orbitplayer.tools.SpeedResult
import com.orbitplayer.tools.SpeedTest
import com.orbitplayer.ui.AppViewModel
import com.orbitplayer.ui.Route
import com.orbitplayer.ui.components.OrbitButton
import com.orbitplayer.ui.components.OrbitTextField
import com.orbitplayer.ui.components.ToggleRow
import com.orbitplayer.ui.theme.LocalIsTv
import com.orbitplayer.ui.theme.Orbit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Add or edit a playlist (Xtream Codes, M3U address or M3U file) */
@Composable
fun AddPlaylistScreen(vm: AppViewModel, editId: String?) {
    val tv = LocalIsTv.current
    val c = Orbit.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf("xtream") }
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var vod by remember { mutableStateOf(true) }
    var series by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val sync by vm.syncStatus.collectAsState()
    var savedId by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    LaunchedEffect(editId) {
        val p = editId?.let { vm.repo.db.playlists().get(it) } ?: return@LaunchedEffect
        type = if (p.type == "xtream") "xtream" else if (p.url.startsWith("content://")) "file" else "m3u"
        name = p.name
        server = p.server
        user = p.username
        pass = p.password
        url = p.url
        epg = p.epgUrl
        vod = p.includeVod
        series = p.includeSeries
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            url = uri.toString()
            if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: ""
        }
    }

    fun save() {
        if (busy) return
        busy = true
        error = ""
        scope.launch {
            try {
                val input = PlaylistInput(name, if (type == "xtream") "xtream" else "m3u", server, user, pass, url, epg, vod, series)
                val p = vm.repo.savePlaylist(input, editId)
                savedId = p.id
                vm.repo.syncPlaylist(p.id)
                val st = vm.repo.syncStatus.value[p.id]
                if (st?.error?.isNotBlank() == true) {
                    error = st.error
                    busy = false
                    return@launch
                }
                vm.setActivePlaylist(p.id)
                vm.toast("${p.name} is ready. Loading TV guide…")
                vm.launch { vm.repo.syncEpg(p.id) }
                vm.root(if (tv) Route.Live else Route.Home)
            } catch (e: Exception) {
                error = e.message ?: "The playlist could not be saved"
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = if (tv) 20.dp else 0.dp, top = if (tv) 18.dp else 0.dp)) {
        PageTitle(if (editId != null) "Edit playlist" else "Add playlist", vm)
        Column(Modifier.widthIn(max = 720.dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrbitButton("Xtream Codes", primary = type == "xtream", focusRequester = first) { type = "xtream" }
                OrbitButton("M3U address", primary = type == "m3u") { type = "m3u" }
                OrbitButton("M3U file", primary = type == "file") { type = "file" }
            }
            OrbitTextField(name, { name = it }, "Playlist name")
            when (type) {
                "xtream" -> {
                    OrbitTextField(server, { server = it }, "Server address (http://server:port)", keyboard = KeyboardType.Uri)
                    OrbitTextField(user, { user = it }, "Username")
                    OrbitTextField(pass, { pass = it }, "Password", password = true)
                }
                "m3u" -> OrbitTextField(url, { url = it }, "M3U address (http://…)", keyboard = KeyboardType.Uri)
                else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OrbitButton("Choose file") {
                        runCatching { pickFile.launch(arrayOf("*/*")) }.onFailure { error = "This device has no file manager" }
                    }
                    Text(if (url.startsWith("content://")) Uri.parse(url).lastPathSegment ?: url else "No file chosen", color = c.textMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
            }
            Box(Modifier.fillMaxWidth()) { ToggleRow("Include movies", vod) { vod = it } }
            if (type == "xtream") Box(Modifier.fillMaxWidth()) { ToggleRow("Include series", series) { series = it } }
            OrbitTextField(epg, { epg = it }, "TV guide (XMLTV) address — optional", keyboard = KeyboardType.Uri)
            Text(
                if (type == "xtream") "The TV guide of Xtream accounts loads automatically; fill the guide address only to use a different one."
                else "If the M3U list contains a guide address (url-tvg), it is used automatically.",
                color = c.textMuted, style = MaterialTheme.typography.bodySmall,
            )
            if (error.isNotBlank()) Text(error, color = c.live, style = MaterialTheme.typography.bodyMedium)
            if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), color = c.accent)
                Text(savedId?.let { sync[it]?.step }?.ifBlank { null } ?: "Checking…", color = c.textMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OrbitButton(if (busy) "Please wait…" else "Save and load", primary = true, enabled = !busy) { save() }
                OrbitButton("Cancel") { vm.back() }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { first.requestFocus() }
    }
}

// ---------------------------------------------------------------- speed test

@Composable
fun SpeedTestScreen(vm: AppViewModel) {
    val c = Orbit.colors
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf("idle") }
    var speed by remember { mutableDoubleStateOf(0.0) }
    var result by remember { mutableStateOf<SpeedResult?>(null) }
    val again = remember { FocusRequester() }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    if (LocalIsTv.current) com.orbitplayer.ui.tv.TvEntryPoint(again)
    fun start() {
        result = null
        speed = 0.0
        phase = "ping"
        job?.cancel()
        job = scope.launch {
            vm.playback.stop() // a playing stream would share the line (and the server's connection limit)
            val ch = vm.groupChannels.value.firstOrNull() ?: vm.channels.value.firstOrNull()
            val providerUrl = ch?.let { runCatching { vm.repo.channelUrl(it) }.getOrNull() }
            val r = SpeedTest.run(providerUrl = providerUrl) { p, mbps -> phase = p; speed = mbps }
            result = r
            phase = "done"
        }
    }
    LaunchedEffect(Unit) {
        start()
        // the screen always has a button with focus (Stop while measuring, Run again after)
        repeat(5) {
            kotlinx.coroutines.delay(80)
            if (runCatching { again.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageTitle("Speed test", vm)
        Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val accent = c.accent
            val track = c.surfaceFocus
            Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                val frac = (kotlin.math.ln(1 + speed) / kotlin.math.ln(101.0)).toFloat().coerceIn(0f, 1f)
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 18.dp.toPx()
                    val d = size.minDimension - stroke
                    val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
                    drawArc(track, 135f, 270f, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
                    drawArc(accent, 135f, 270f * frac, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(String.format(Locale.US, "%.1f", result?.downloadMbps ?: speed), fontSize = 46.sp, fontWeight = FontWeight.Bold, color = c.text)
                    Text("Mbps", color = c.textMuted)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(when (phase) { "ping" -> "Measuring response time…"; "download" -> "Measuring internet speed…"; "provider" -> "Measuring your IPTV server…"; else -> "" }, color = c.textMuted)
            result?.let { r ->
                if (r.error.isNotBlank()) Text("The test failed: ${r.error}", color = c.live)
                else {
                    Text("Internet: ${String.format(Locale.US, "%.1f", r.downloadMbps)} Mbps · Ping ${r.pingMs} ms · Jitter ${r.jitterMs} ms", color = c.text, style = MaterialTheme.typography.titleMedium)
                    if (r.source.isNotBlank()) Text("Test server: ${r.source}", color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            r.downloadMbps >= 25 -> "Excellent: enough for 4K."
                            r.downloadMbps >= 12 -> "Good: enough for Full HD."
                            r.downloadMbps >= 5 -> "Enough for HD. 4K may stutter."
                            else -> "Slow: expect buffering. Try a cable instead of Wi-Fi."
                        },
                        color = c.textMuted,
                    )
                }
                if (r.providerMbps >= 0 || r.providerError.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (r.providerError.isNotBlank()) "Your IPTV server: not reachable (${r.providerError})"
                        else "Your IPTV server: ${String.format(Locale.US, "%.1f", r.providerMbps)} Mbps · answers in ${r.providerPingMs} ms",
                        color = if (r.providerError.isNotBlank()) c.live else c.text,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("Measured on one live channel. A channel needs about 3–8 Mbps for HD and 15–25 Mbps for 4K.", color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (result == null && phase != "done") {
                OrbitButton("Stop", focusRequester = again) {
                    job?.cancel()
                    phase = "done"
                    result = SpeedResult(downloadMbps = speed, error = if (speed <= 0.05) "Stopped" else "")
                }
            } else {
                OrbitButton("Run again", primary = true, focusRequester = again) { start() }
            }
        }
    }
}

// ---------------------------------------------------------------- diagnostics

data class DiagItem(val title: String, val value: String, val ok: Boolean?)

@Composable
fun DiagnosticsScreen(vm: AppViewModel) {
    val c = Orbit.colors
    val tv = LocalIsTv.current
    var items by remember { mutableStateOf<List<DiagItem>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var run by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val runFocus = remember { FocusRequester() }
    if (tv) com.orbitplayer.ui.tv.TvEntryPoint(runFocus)
    LaunchedEffect(Unit) {
        repeat(5) {
            kotlinx.coroutines.delay(80)
            if (runCatching { runFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    LaunchedEffect(run) {
        running = true
        items = runDiagnostics(vm, context, tv) { step = it }
        running = false
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PageTitle("Diagnostics", vm) }
            OrbitButton(if (running) "Checking…" else "Run again", enabled = !running, focusRequester = runFocus) { run++ }
            Spacer(Modifier.size(16.dp))
        }
        if (running) Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), color = c.accent)
            Spacer(Modifier.size(12.dp))
            Text(step, color = c.textMuted)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items) { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when (d.ok) {
                        true -> Icons.Filled.CheckCircle to c.success
                        false -> Icons.Filled.Error to c.live
                        null -> Icons.Filled.Info to c.textMuted
                    }
                    Icon(icon, null, tint = tint)
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text(d.title, color = c.text, style = MaterialTheme.typography.bodyLarge)
                        Text(d.value, color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private suspend fun runDiagnostics(vm: AppViewModel, context: android.content.Context, tv: Boolean, onStep: (String) -> Unit): List<DiagItem> = withContext(Dispatchers.IO) {
    val quick = Net.iptv.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    fun timed(block: () -> Unit): Long? {
        val t = System.nanoTime()
        return try {
            block(); (System.nanoTime() - t) / 1_000_000
        } catch (e: Exception) {
            null
        }
    }
    val out = ArrayList<DiagItem>()
    val (total, avail) = DeviceInfo.memoryMb(context)
    out += DiagItem("Device", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", null)
    out += DiagItem("App", "Orbit Player ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${if (tv) "TV interface" else "touch interface"}", null)
    out += DiagItem("Memory", "$avail MB free of $total MB", avail > 200)
    val free = DeviceInfo.freeStorageMb(context)
    out += DiagItem("Storage", "$free MB free", free > 500)
    val net = DeviceInfo.networkType(context)
    out += DiagItem("Network", net, net != "No connection")
    onStep("Checking DNS…")
    val dns = timed { InetAddress.getByName("google.com") }
    out += DiagItem("DNS", dns?.let { "answers in $it ms" } ?: "not working", dns != null && dns < 500)
    onStep("Checking internet…")
    val inet = timed { quick.newCall(Request.Builder().url("https://www.google.com/generate_204").build()).execute().close() }
    out += DiagItem("Internet", inet?.let { "working ($it ms)" } ?: "no access", inet != null)
    for (p in vm.repo.db.playlists().all()) {
        onStep("Checking ${p.name}…")
        val target = if (p.type == "xtream") p.server else p.url
        val host = runCatching { URI(target).host }.getOrNull() ?: "local file"
        if (p.type == "xtream") {
            var detail = ""
            val ms = timed {
                val acc = XtreamClient(p.server, p.username, p.password).account()
                val ui = acc.optJSONObject("user_info")
                val exp = ui?.optString("exp_date")?.toLongOrNull()
                detail = buildString {
                    append("status ${ui?.optString("status") ?: "?"}")
                    append(", connections ${ui?.optString("active_cons") ?: "?"}/${ui?.optString("max_connections") ?: "?"}")
                    if (exp != null && exp > 0) append(", expires ${java.text.DateFormat.getDateInstance().format(java.util.Date(exp * 1000))}")
                }
            }
            out += DiagItem("Playlist “${p.name}” ($host)", ms?.let { "answers in $it ms, $detail" } ?: "server not answering or wrong login", ms != null && ms < 2000)
        } else if (p.url.startsWith("http")) {
            val ms = timed { quick.newCall(Request.Builder().url(p.url).head().build()).execute().close() }
            out += DiagItem("Playlist “${p.name}” ($host)", ms?.let { "answers in $it ms" } ?: "not reachable", ms != null)
        }
        if (p.lastError.isNotBlank()) out += DiagItem("Last error of “${p.name}”", p.lastError, false)
    }
    out
}
