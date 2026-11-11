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
            // one button whose text changes (Stop → Run again), so the focus stays on it when the test ends
            val measuring = result == null && phase != "done"
            OrbitButton(if (measuring) "Stop" else "Run again", focusRequester = again) {
                if (measuring) {
                    job?.cancel()
                    phase = "done"
                    result = SpeedResult(downloadMbps = speed, error = if (speed <= 0.05) "Stopped" else "")
                } else start()
            }
        }
    }
}

// ---------------------------------------------------------------- diagnostics

/** good = green, warn = orange, bad = red, info = grey, pending = still being tested */
enum class DiagStatus { GOOD, WARN, BAD, INFO, PENDING }

data class DiagItem(val title: String, val value: String, val status: DiagStatus, val mono: Boolean = false) {
    constructor(title: String, value: String, ok: Boolean?, mono: Boolean = false) :
        this(title, value, when (ok) { true -> DiagStatus.GOOD; false -> DiagStatus.BAD; null -> DiagStatus.INFO }, mono)
}

private val WARN_COLOR = androidx.compose.ui.graphics.Color(0xFFFFA726)

/**
 * One button runs everything (about a minute, the 30 s stream test included); on TV every result is on one
 * screen in three columns, filled in as each test finishes: device & network · IPTV server & stream · live TV.
 */
@Composable
fun DiagnosticsScreen(vm: AppViewModel) {
    val c = Orbit.colors
    val tv = LocalIsTv.current
    val context = LocalContext.current
    // results by key (filled in as they come); the order of the keys is the order on screen
    val results = remember { androidx.compose.runtime.mutableStateMapOf<String, DiagItem>() }
    var running by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var run by remember { mutableIntStateOf(0) }
    var startedAt by remember { mutableStateOf(0L) }
    val runFocus = remember { FocusRequester() }
    if (tv) com.orbitplayer.ui.tv.TvEntryPoint(runFocus)
    LaunchedEffect(Unit) {
        repeat(5) {
            delay(80)
            if (runCatching { runFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    LaunchedEffect(run) {
        running = true
        startedAt = System.currentTimeMillis()
        results.clear()
        runDiagnostics(vm, context, tv, onStep = { step = it }) { key, item -> results[key] = item }
        running = false
        step = ""
    }
    // session numbers change while the tests run
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val session = remember(tick, running) { sessionItems() }

    val device = results.filterKeys { it.startsWith("d") }.toSortedMap().values.toList()
    val server = results.filterKeys { it.startsWith("s") }.toSortedMap().values.toList()

    Column(Modifier.fillMaxSize().padding(end = 24.dp)) {
        // header: title, what is being tested, the button (always on screen)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PageTitle("Diagnostics", vm) }
            OrbitButton(if (running) "Testing…" else "Run all tests again", enabled = !running, focusRequester = runFocus) { run++ }
        }
        val verdict = overallVerdict(device + server, running)
        Row(Modifier.padding(start = 24.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (running) {
                CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
                val secs = ((System.currentTimeMillis() - startedAt) / 1000).toInt().let { if (tick >= 0) it else 0 }
                Text("$step  ·  $secs s (about 1 minute in total)", color = c.textMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            } else if (verdict != null) {
                Text(verdict.first, color = statusColor(verdict.second), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
        }
        if (tv) {
            Row(Modifier.fillMaxSize().padding(start = 24.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                DiagColumn("Device & network", device, Modifier.weight(1f))
                DiagColumn("IPTV server & stream", server, Modifier.weight(1.15f))
                DiagColumn("Live TV since the app started", session, Modifier.weight(1f))
            }
        } else {
            // phone: one list (scrolls)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(device + server + session) { d -> DiagRow(d) }
            }
        }
    }
}

@Composable
private fun DiagColumn(title: String, items: List<DiagItem>, modifier: Modifier) {
    val c = Orbit.colors
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title.uppercase(Locale.ROOT), color = c.accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        if (items.isEmpty()) Text("waiting…", color = c.textMuted, style = MaterialTheme.typography.bodySmall)
        items.forEach { DiagRow(it, compact = true) }
    }
}

@Composable
private fun statusColor(s: DiagStatus) = when (s) {
    DiagStatus.GOOD -> Orbit.colors.success
    DiagStatus.WARN -> WARN_COLOR
    DiagStatus.BAD -> Orbit.colors.live
    DiagStatus.INFO, DiagStatus.PENDING -> Orbit.colors.textMuted
}

@Composable
private fun DiagRow(d: DiagItem, compact: Boolean = false) {
    val c = Orbit.colors
    Row(verticalAlignment = Alignment.Top) {
        if (d.status == DiagStatus.PENDING) CircularProgressIndicator(Modifier.padding(top = 2.dp).size(16.dp), color = c.accent, strokeWidth = 2.dp)
        else Icon(
            when (d.status) {
                DiagStatus.GOOD -> Icons.Filled.CheckCircle
                DiagStatus.WARN, DiagStatus.BAD -> Icons.Filled.Error
                else -> Icons.Filled.Info
            },
            null, Modifier.size(if (compact) 18.dp else 22.dp), tint = statusColor(d.status),
        )
        Spacer(Modifier.size(8.dp))
        Column {
            Text(d.title, color = c.text, style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                d.value,
                color = if (d.status == DiagStatus.WARN || d.status == DiagStatus.BAD) statusColor(d.status) else c.textMuted,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                fontFamily = if (d.mono) androidx.compose.ui.text.font.FontFamily.Monospace else null,
                maxLines = if (d.mono) 8 else 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/** one sentence on top: what is wrong, if anything */
private fun overallVerdict(items: List<DiagItem>, running: Boolean): Pair<String, DiagStatus>? {
    if (running || items.isEmpty()) return null
    val bad = items.filter { it.status == DiagStatus.BAD }
    val warn = items.filter { it.status == DiagStatus.WARN }
    return when {
        bad.isNotEmpty() -> "Problem: " + bad.joinToString(", ") { it.title.substringBefore(" (").lowercase(Locale.ROOT) } to DiagStatus.BAD
        warn.isNotEmpty() -> "Works, but watch out: " + warn.joinToString(", ") { it.title.substringBefore(" (").lowercase(Locale.ROOT) } to DiagStatus.WARN
        else -> "Everything looks good: the connection and the server are stable." to DiagStatus.GOOD
    }
}

/** what happened while watching since the app started */
private fun sessionItems(): List<DiagItem> {
    val s = com.orbitplayer.playback.PlaybackStats
    val watchedMin = s.totalWatchedMs / 60_000
    val out = ArrayList<DiagItem>()
    out += DiagItem("Watched", "$watchedMin min", DiagStatus.INFO)
    out += DiagItem(
        "Stalls",
        if (s.stalls == 0) "none" else "${s.stalls} · together %.0f s · longest %.0f s".format(s.stallMs / 1000f, s.longestStallMs / 1000f),
        when { s.stalls == 0 -> if (watchedMin < 1) DiagStatus.INFO else DiagStatus.GOOD; s.stalls <= 2 -> DiagStatus.WARN; else -> DiagStatus.BAD },
    )
    out += DiagItem(
        "Connections replaced / reconnects",
        "${s.newConnections} / ${s.reconnects}",
        when { s.newConnections + s.reconnects == 0 -> if (watchedMin < 1) DiagStatus.INFO else DiagStatus.GOOD; s.reconnects == 0 -> DiagStatus.WARN; else -> DiagStatus.BAD },
    )
    if (s.learnedBuffer.isNotEmpty()) out += DiagItem(
        "Auto buffer raised",
        s.learnedBuffer.entries.joinToString("\n") { "${it.key}: +%.0f s".format(it.value / 1000f) },
        DiagStatus.WARN,
    )
    val events = s.recent()
    out += DiagItem("Recent events", if (events.isEmpty()) "nothing yet" else events.take(8).joinToString("\n"), DiagStatus.INFO, mono = events.isNotEmpty())
    return out
}

/** ping: under 10 ms green, 10–50 ms orange, over 50 ms or lost / uneven red (unstable) */
private fun latencyItem(title: String, host: String, port: Int, count: Int, progress: (Int) -> Unit): DiagItem {
    val l = com.orbitplayer.tools.ConnectionTest.latency(host, port, count, progress = progress)
    if (l.failed == l.samples) return DiagItem(title, "no answer at all", DiagStatus.BAD)
    val status = when {
        l.failed > 0 || l.avgMs > 50 || l.jitterMs > 30 -> DiagStatus.BAD
        l.avgMs >= 10 -> DiagStatus.WARN
        else -> DiagStatus.GOOD
    }
    val verdict = when {
        l.failed > 0 -> "attempts lost · unstable"
        l.jitterMs > 30 -> "very uneven · unstable"
        l.avgMs > 50 -> "unstable"
        l.avgMs >= 10 -> "ok"
        else -> "excellent"
    }
    return DiagItem(title, "${l.avgMs} ms (${l.minMs}–${l.maxMs}), jitter ${l.jitterMs} ms, lost ${l.failed}/${l.samples} · $verdict", status)
}

/**
 * All tests, results handed over one by one ([put] with a key: "d…" device & network, "s…" server & stream;
 * keys sort into screen order).
 */
private suspend fun runDiagnostics(
    vm: AppViewModel, context: android.content.Context, tv: Boolean,
    onStep: (String) -> Unit, put: (String, DiagItem) -> Unit,
) = withContext(Dispatchers.IO) {
    val main: (String, DiagItem) -> Unit = { k, v -> kotlinx.coroutines.runBlocking(Dispatchers.Main) { put(k, v) } }
    val quick = Net.iptv.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    fun timed(block: () -> Unit): Long? {
        val t = System.nanoTime()
        return try {
            block(); (System.nanoTime() - t) / 1_000_000
        } catch (e: Exception) {
            null
        }
    }
    // ---- device & network
    val (total, avail) = DeviceInfo.memoryMb(context)
    main("d01", DiagItem("Device", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} · Orbit ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", DiagStatus.INFO))
    val free = DeviceInfo.freeStorageMb(context)
    main("d02", DiagItem("Memory · storage", "$avail of $total MB free · $free MB storage free", avail > 200 && free > 500))
    val net = DeviceInfo.networkType(context)
    val (netText, netOk) = com.orbitplayer.tools.ConnectionTest.networkDetails(context)
    main("d03", DiagItem("Connection", if (netText != net) netText else net, if (net == "No connection") DiagStatus.BAD else when (netOk) { true -> DiagStatus.GOOD; false -> DiagStatus.WARN; null -> DiagStatus.INFO }))
    onStep("Checking DNS and internet…")
    val dns = timed { InetAddress.getByName("google.com") }
    val inet = timed { quick.newCall(Request.Builder().url("https://www.google.com/generate_204").build()).execute().close() }
    main("d04", DiagItem(
        "DNS · internet",
        (dns?.let { "DNS $it ms" } ?: "DNS not working") + " · " + (inet?.let { "internet working ($it ms)" } ?: "no internet"),
        when { dns == null || inet == null -> DiagStatus.BAD; dns > 500 -> DiagStatus.WARN; else -> DiagStatus.GOOD },
    ))
    main("d05", DiagItem("Ping to the internet (google.com)", "measuring…", DiagStatus.PENDING))
    main("d05", latencyItem("Ping to the internet (google.com)", "google.com", 443, 10) { onStep("Ping to the internet… $it/10") })

    // ---- IPTV server(s)
    val playlists = vm.repo.db.playlists().all()
    if (playlists.isEmpty()) main("s00", DiagItem("IPTV", "no playlist added", DiagStatus.INFO))
    playlists.forEachIndexed { i, p ->
        val k = "s%02d".format(i * 4)
        onStep("Checking ${p.name}…")
        val target = if (p.type == "xtream") p.server else p.url
        val host = runCatching { URI(target).host }.getOrNull() ?: "local file"
        main("${k}a", DiagItem("Playlist “${p.name}” ($host)", "checking…", DiagStatus.PENDING))
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
            main("${k}a", DiagItem("Playlist “${p.name}” ($host)", ms?.let { "answers in $it ms, $detail" } ?: "server not answering or wrong login", when { ms == null -> DiagStatus.BAD; ms > 2000 -> DiagStatus.WARN; else -> DiagStatus.GOOD }))
        } else if (p.url.startsWith("http")) {
            val ms = timed { quick.newCall(Request.Builder().url(p.url).head().build()).execute().close() }
            main("${k}a", DiagItem("Playlist “${p.name}” ($host)", ms?.let { "answers in $it ms" } ?: "not reachable", ms != null))
        } else main("${k}a", DiagItem("Playlist “${p.name}”", "local file", DiagStatus.INFO))
        if (p.lastError.isNotBlank()) main("${k}b", DiagItem("Last error of “${p.name}”", p.lastError, DiagStatus.BAD))
        com.orbitplayer.tools.ConnectionTest.hostPort(target)?.let { (h, port) ->
            main("${k}c", DiagItem("Ping to the server ($h)", "measuring…", DiagStatus.PENDING))
            main("${k}c", latencyItem("Ping to the server ($h)", h, port, 20) { onStep("Ping to ${p.name}… $it/20") })
        }
    }

    // ---- stream test: the last watched channel (or the first one) read for 30 s
    val channels = vm.channels.value
    val ch = channels.firstOrNull { it.key == vm.store.lastChannelKey } ?: channels.firstOrNull()
    if (ch == null) {
        main("s90", DiagItem("Stream test", "no channels to test", DiagStatus.INFO))
    } else {
        val name = vm.displayName(ch)
        // one connection at a time (many servers allow only one): stop what plays
        withContext(Dispatchers.Main) { vm.playback.stop() }
        main("s90", DiagItem("Stream test: $name", "starting…", DiagStatus.PENDING))
        val url = runCatching { vm.repo.channelUrl(ch) }.getOrNull()
        if (url == null) {
            main("s90", DiagItem("Stream test: $name", "no address for this channel", DiagStatus.BAD))
        } else {
            var lastShown = 0
            val r = com.orbitplayer.tools.ConnectionTest.stream(url, 30) { sec, mbps ->
                onStep("Reading the channel “$name”… $sec / 30 s")
                if (sec != lastShown) {
                    lastShown = sec
                    main("s90", DiagItem("Stream test: $name", "reading… $sec / 30 s, %.1f Mbit/s".format(mbps), DiagStatus.PENDING))
                }
            }
            val stable = r.error == null && r.emptySeconds == 0
            main("s90", DiagItem(
                "Stream test: $name",
                if (r.seconds <= 1 && r.error != null) "could not read the stream: ${r.error}"
                else buildString {
                    append("%.1f Mbit/s average over ${r.seconds} s".format(r.avgMbps))
                    append(" · seconds without data: ${r.emptySeconds}")
                    if (r.gaps > 0) append(" (${r.gaps}×, longest ${r.longestGapS} s)")
                    if (r.error != null) append(" · stopped: ${r.error}")
                },
                when { stable -> DiagStatus.GOOD; r.longestGapS >= 4 || (r.seconds <= 1 && r.error != null) -> DiagStatus.BAD; else -> DiagStatus.WARN },
            ))
            main("s91", DiagItem("Data per second (· = nothing arrived)", com.orbitplayer.tools.ConnectionTest.graph(r.perSecondKb), DiagStatus.INFO, mono = true))
            main("s92", DiagItem(
                "What it means",
                when {
                    r.seconds <= 1 && r.error != null -> "The server didn't send the channel. Check the playlist login or try another channel."
                    stable -> "The channel arrived every second: the connection to this server is stable."
                    r.longestGapS >= 4 -> "Data stopped for ${r.longestGapS} s at once: the connection drops for a moment (Wi-Fi signal, router or the server). Keep “Reconnect when a channel stops sending” and Auto buffer on; a cable or a stronger Wi-Fi signal usually fixes it."
                    else -> "Short pauses in the data: the connection is uneven. Auto buffer covers them."
                },
                DiagStatus.INFO,
            ))
        }
    }
}
