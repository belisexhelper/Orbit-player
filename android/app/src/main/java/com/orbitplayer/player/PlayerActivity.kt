package com.orbitplayer.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.orbitplayer.App
import com.orbitplayer.core.AppSettings
import com.orbitplayer.core.DeviceInfo
import com.orbitplayer.core.Net
import com.orbitplayer.data.db.ProgramEntity
import com.orbitplayer.data.db.ProgressEntity
import com.orbitplayer.download.Downloads
import com.orbitplayer.server.UiEvent
import com.orbitplayer.ui.components.ActionButton
import com.orbitplayer.ui.components.ChannelLogo
import com.orbitplayer.ui.components.ListRow
import com.orbitplayer.ui.theme.LocalIsTv
import com.orbitplayer.ui.theme.OrbitTheme
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plejer za kanale uživo, filmove, epizode, arhivu i preuzete fajlove.
 * Na TV-u: gore/dole menja kanal, OK otvara listu kanala, brojevi biraju kanal, Meni otvara zvuk i prevode.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private val app get() = application as App
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private lateinit var settings: AppSettings
    private var isTv = false

    // stanje koje prikazuje Compose
    private var item by mutableStateOf<PlayItem?>(null)
    private var overlay by mutableStateOf("") // "" | channels | tracks | subtitle_url | info
    private var banner by mutableStateOf(false)
    private var bannerProgram by mutableStateOf<ProgramEntity?>(null)
    private var nextProgram by mutableStateOf<ProgramEntity?>(null)
    private var message by mutableStateOf<String?>(null)
    private var errorText by mutableStateOf<String?>(null)
    private var digits by mutableStateOf("")
    private var tracksVersion by mutableStateOf(0)

    private var retries = 0
    private var triedHls = false
    private var extraSubs = mutableListOf<SubtitleSource>()
    private var digitJob: Job? = null
    private var bannerJob: Job? = null
    private var progressJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settings = app.repo.settings.value
        isTv = DeviceInfo.isTv(this)
        buildPlayer()
        setContent {
            CompositionLocalProvider(LocalIsTv provides isTv) {
                OrbitTheme(light = false, accentHex = settings.accent, isTv = isTv) { PlayerScreen() }
            }
        }
        lifecycleScope.launch {
            app.sync.events.collect { e ->
                when (e) {
                    is UiEvent.Message -> showMessage(e.text, e.durationSec)
                    is UiEvent.PlayChannel -> playChannelBy(e.number, e.name)
                    is UiEvent.GoHome, is UiEvent.Unpaired -> finish()
                    is UiEvent.Toast -> Toast.makeText(this@PlayerActivity, e.text, Toast.LENGTH_LONG).show()
                    else -> Unit
                }
            }
        }
        startItem(PlaySession.index)
    }

    // ---------------- plejer ----------------

    private fun buildPlayer() {
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        if (!settings.hardwareDecoding) {
            renderers.setMediaCodecSelector(MediaCodecSelector { mime, secure, tunneling ->
                val all = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
                val sw = all.filter { it.softwareOnly }
                if (sw.isEmpty()) all else sw
            })
        }
        val minBuf = settings.bufferMs.coerceAtLeast(5000)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuf, (minBuf * 2).coerceAtLeast(50_000), 2000, 5000.coerceAtMost(minBuf))
            .build()
        val http = OkHttpDataSource.Factory(Net.iptv).setUserAgent(Net.USER_AGENT)
        val dataSource = DefaultDataSource.Factory(this, http)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        val p = ExoPlayer.Builder(this, renderers)
            .setLoadControl(load)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
            .build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(settings.subtitleLanguage)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !settings.subtitlesByDefault)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onError(error)
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    retries = 0
                    errorText = null
                }
                if (state == Player.STATE_ENDED) onEnded()
            }
            override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
            }
        })
        player = p
    }

    private fun configureView(v: PlayerView) {
        v.player = player
        v.setShowSubtitleButton(true)
        v.controllerShowTimeoutMs = 4000
        v.resizeMode = when (settings.aspect) {
            "fill", "16:9", "4:3" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        v.subtitleView?.let { sv ->
            sv.setStyle(
                CaptionStyleCompat(
                    AColor.WHITE,
                    if (settings.subtitleBackground) AColor.argb(160, 0, 0, 0) else AColor.TRANSPARENT,
                    AColor.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    AColor.BLACK,
                    null,
                )
            )
            val f = when (settings.subtitleSize) {
                "small" -> 0.8f
                "large" -> 1.35f
                else -> 1.05f
            }
            sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * f)
        }
    }

    private fun startItem(index: Int) {
        val it = PlaySession.items.getOrNull(index) ?: run { finish(); return }
        PlaySession.index = index
        saveProgress()
        item = it
        retries = 0
        triedHls = false
        extraSubs = it.subtitles.toMutableList()
        errorText = null
        lifecycleScope.launch {
            val url = when {
                it.kind == "live" && it.channel != null -> app.repo.channelUrl(it.channel)
                else -> it.url
            }
            if (url.isBlank()) {
                errorText = "Adresa za puštanje nije dostupna"
                return@launch
            }
            if (settings.playerEngine == "vlc" && it.kind != "local" && openExternal(url, preferVlc = true)) {
                finish()
                return@launch
            }
            val resume = if (it.progressKey.isNotBlank() && it.kind != "live") app.db.user().progress(it.progressKey) else null
            val startAt = if (resume != null && (resume.durationMs <= 0 || resume.positionMs < resume.durationMs - 60_000)) resume.positionMs else 0L
            prepare(url, startAt)
            if (resume != null && resume.positionMs > 30_000) toast("Nastavljeno od ${fmtTime(resume.positionMs)}")
            app.sync.screen = "player"
            app.sync.nowPlaying = it.title
            if (it.kind == "live" && it.channel != null) {
                app.prefs.lastChannelKey = it.channel.key
                showBanner()
            }
        }
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (true) {
                delay(10_000)
                saveProgress()
            }
        }
    }

    private fun prepare(url: String, startMs: Long, forceHls: Boolean = false) {
        val p = player ?: return
        val b = MediaItem.Builder().setUri(Uri.parse(url))
        if (forceHls || url.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (url.contains(".mpd")) b.setMimeType(MimeTypes.APPLICATION_MPD)
        if (extraSubs.isNotEmpty()) {
            b.setSubtitleConfigurations(extraSubs.map { s ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.uri))
                    .setMimeType(s.mime.ifBlank { Downloads.subtitleMime(s.uri) })
                    .setLanguage(s.language.ifBlank { null })
                    .setLabel(s.label)
                    .build()
            })
        }
        p.setMediaItem(b.build(), startMs)
        p.prepare()
        p.playWhenReady = true
    }

    private fun currentUrl(): String = player?.currentMediaItem?.localConfiguration?.uri?.toString() ?: ""

    private fun onError(error: PlaybackException) {
        val p = player ?: return
        val it = item ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            p.seekToDefaultPosition()
            p.prepare()
            return
        }
        val url = currentUrl()
        // HLS bez .m3u8 u adresi: probaj ponovo kao HLS
        if (!triedHls && !url.contains(".m3u8") && (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED || error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)) {
            triedHls = true
            prepare(url, p.currentPosition, forceHls = true)
            return
        }
        // Xtream kanal: ako .ts ne radi, probaj .m3u8
        if (it.kind == "live" && it.channel != null && it.channel.url.isBlank() && url.endsWith(".ts") && retries == 1) {
            prepare(url.removeSuffix(".ts") + ".m3u8", 0)
            retries++
            return
        }
        val live = it.kind == "live"
        val maxRetries = if (live) 1000 else 4
        if (settings.autoReconnect && retries < maxRetries) {
            retries++
            val wait = (2000L * retries).coerceAtMost(10_000L)
            errorText = "Veza je prekinuta, ponovno povezivanje… (${retries})"
            lifecycleScope.launch {
                delay(wait)
                val pos = if (live) 0 else p.currentPosition
                prepare(url, pos)
            }
        } else {
            errorText = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Server je odbio zahtev. Proverite da li je pretplata aktivna i da li je dostignut broj istovremenih veza."
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Nema veze sa serverom. Proverite internet."
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "Uređaj ne može da dekodira ovaj snimak. Probajte da uključite/isključite hardversko dekodiranje u podešavanjima."
                else -> "Snimak ne može da se pusti (${error.errorCodeName})."
            }
            com.orbitplayer.core.AppLog.w("Greška plejera: ${error.errorCodeName} za ${it.title}")
        }
    }

    private fun onEnded() {
        val it = item ?: return
        if (it.progressKey.isNotBlank()) lifecycleScope.launch { app.db.user().deleteProgress(it.progressKey) }
        if (it.kind == "episode" && PlaySession.index < PlaySession.items.size - 1) {
            toast("Sledeća epizoda")
            startItem(PlaySession.index + 1)
        } else if (it.kind != "live") {
            finish()
        }
    }

    private fun saveProgress() {
        val it = item ?: return
        val p = player ?: return
        if (it.progressKey.isBlank() || it.kind == "live" || it.kind == "catchup") return
        val pos = p.currentPosition
        val dur = p.duration.takeIf { d -> d > 0 } ?: 0L
        if (pos < 10_000) return
        val entity = ProgressEntity(
            key = it.progressKey,
            kind = it.kind,
            title = it.title,
            poster = it.poster,
            url = it.url,
            positionMs = pos,
            durationMs = dur,
            updatedAt = System.currentTimeMillis(),
            seriesKey = it.seriesKey,
            playlistId = it.playlistId,
        )
        app.appScope.launch { app.db.user().saveProgress(entity) }
    }

    // ---------------- kanali uživo ----------------

    private fun zap(delta: Int) {
        val n = PlaySession.items.size
        if (n <= 1) return
        startItem(((PlaySession.index + delta) % n + n) % n)
    }

    private fun playChannelBy(number: Int?, name: String?) {
        lifecycleScope.launch {
            val idx = PlaySession.items.indexOfFirst { i ->
                i.channel != null && ((number != null && i.channel.num == number) || (name != null && i.channel.name.equals(name, true)))
            }
            if (idx >= 0) {
                startItem(idx)
                return@launch
            }
            // kanal nije u trenutnoj grupi — potraži ga u celoj listi
            val pid = app.prefs.activePlaylistId
            val ch = when {
                number != null -> app.db.content().channelByNumber(pid, number)
                name != null -> app.db.content().channelByName(pid, "%$name%")
                else -> null
            }
            if (ch != null) {
                PlaySession.items = PlaySession.items + PlayItem(kind = "live", title = ch.name, channel = ch, poster = ch.logo, playlistId = ch.playlistId)
                startItem(PlaySession.items.size - 1)
            } else toast("Kanal nije pronađen")
        }
    }

    private fun showBanner() {
        val ch = item?.channel ?: return
        banner = true
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            if (ch.epgKey.isNotBlank()) {
                val progs = app.db.epg().programs(ch.epgKey, now, now + 6 * 3600_000L)
                bannerProgram = progs.firstOrNull { it.start <= now }
                nextProgram = progs.firstOrNull { it.start > now }
            } else {
                bannerProgram = null
                nextProgram = null
            }
        }
        bannerJob?.cancel()
        bannerJob = lifecycleScope.launch {
            delay(5000)
            banner = false
        }
    }

    private fun onDigit(d: Int) {
        digits = (digits + d).takeLast(4)
        digitJob?.cancel()
        digitJob = lifecycleScope.launch {
            delay(1800)
            val num = digits.toIntOrNull()
            digits = ""
            if (num != null) playChannelBy(num, null)
        }
    }

    private fun showMessage(text: String, durationSec: Int) {
        message = text
        if (durationSec > 0) lifecycleScope.launch {
            delay(durationSec * 1000L)
            if (message == text) message = null
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val it = item
        if (event.action == KeyEvent.ACTION_DOWN && overlay.isEmpty() && message == null) {
            val live = it?.kind == "live"
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_CAPTIONS -> {
                    overlay = "tracks"; return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP -> if (live) { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> if (live) { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (live) { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (live) { zap(1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (live) { overlay = "channels"; return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (live) { showBanner(); return true }
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> if (live) { onDigit(event.keyCode - KeyEvent.KEYCODE_0); return true }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && message != null && (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
            message = null
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------- spoljni plejer i PiP ----------------

    private fun openExternal(url: String, preferVlc: Boolean): Boolean {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*").putExtra("title", item?.title ?: "")
        if (preferVlc) {
            val vlc = Intent(i).setPackage("org.videolan.vlc")
            if (runCatching { startActivity(vlc); true }.getOrDefault(false)) return true
        }
        return runCatching { startActivity(Intent.createChooser(i, "Otvori u plejeru")); true }.getOrDefault(false)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isTv && Build.VERSION.SDK_INT >= 26 && player?.isPlaying == true) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerView?.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) overlay = ""
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        val inPip = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode
        if (!inPip) player?.pause()
    }

    override fun onStart() {
        super.onStart()
        if (player?.playbackState == Player.STATE_READY) player?.play()
    }

    override fun onDestroy() {
        saveProgress()
        progressJob?.cancel()
        player?.release()
        player = null
        app.sync.screen = "home"
        app.sync.nowPlaying = ""
        super.onDestroy()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ---------------- prikaz ----------------

    @Composable
    private fun PlayerScreen() {
        val it = item
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).also { v -> playerView = v; configureView(v) } },
                modifier = Modifier.fillMaxSize(),
            )
            if (it != null && it.kind == "live") {
                // na telefonu: dodir ekrana otvara listu kanala preko dugmeta u gornjem uglu
                if (!isTv) Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Kanali") { overlay = "channels" }
                    ActionButton("Zvuk i prevod") { overlay = "tracks" }
                }
                if (banner || digits.isNotEmpty()) ChannelBanner()
            } else if (it != null && !isTv) {
                Row(Modifier.align(Alignment.TopEnd).padding(12.dp)) { ActionButton("Zvuk i prevod") { overlay = "tracks" } }
            }
            errorText?.let { e ->
                Box(Modifier.align(Alignment.Center).padding(32.dp).background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(20.dp)) {
                    Text(e, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                }
            }
            message?.let { m ->
                Box(Modifier.align(Alignment.TopCenter).padding(24.dp).background(Color(0xEE16233F), RoundedCornerShape(12.dp)).padding(20.dp)) {
                    Column {
                        Text(m, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("Pritisnite OK za zatvaranje", color = Color(0xFF9AA9C7), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            when (overlay) {
                "channels" -> ChannelOverlay()
                "tracks" -> TracksOverlay()
                "subtitle_url" -> SubtitleUrlOverlay()
            }
        }
        BackHandler(enabled = overlay.isNotEmpty()) { overlay = "" }
    }

    @Composable
    private fun androidx.compose.foundation.layout.BoxScope.ChannelBanner() {
        val ch = item?.channel ?: return
        val f = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xDD0F1A30)).padding(horizontal = 32.dp, vertical = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ChannelLogo(ch.logo, ch.name, 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(if (digits.isNotEmpty()) "Kanal $digits…" else "${ch.num}  ${ch.name}", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    bannerProgram?.let { p ->
                        Text("${f.format(Date(p.start))}–${f.format(Date(p.stop))}  ${p.title}", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                        val now = System.currentTimeMillis()
                        val frac = ((now - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f)
                        androidx.compose.material3.LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    }
                    nextProgram?.let { p -> Text("Zatim: ${f.format(Date(p.start))} ${p.title}", color = Color(0xFF9AA9C7), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }

    @Composable
    private fun ChannelOverlay() {
        val items = PlaySession.items
        val state = rememberLazyListState(initialFirstVisibleItemIndex = (PlaySession.index - 3).coerceAtLeast(0))
        val first = remember { FocusRequester() }
        Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
            Column(Modifier.fillMaxHeight().width(420.dp).background(Color(0xF00F1A30)).padding(12.dp)) {
                Text("Kanali", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(8.dp))
                LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(items) { i, pi ->
                        val ch = pi.channel
                        ListRow(
                            title = "${ch?.num ?: ""}  ${pi.title}",
                            selected = i == PlaySession.index,
                            focusRequester = if (i == PlaySession.index) first else null,
                            leading = { ChannelLogo(ch?.logo ?: "", pi.title, 36.dp) },
                        ) {
                            overlay = ""
                            if (i != PlaySession.index) startItem(i)
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { delay(80); first.requestFocus() } }
    }

    @Composable
    private fun TracksOverlay() {
        val p = player ?: return
        @Suppress("UNUSED_VARIABLE") val v = tracksVersion
        val groups = p.currentTracks.groups
        val audio = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val text = groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val textDisabled = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val first = remember { FocusRequester() }
        val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                addSubtitle(SubtitleSource(uri.toString(), "Moj prevod", "", Downloads.subtitleMime(uri.lastPathSegment ?: "")))
                overlay = ""
            }
        }
        Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
            LazyColumn(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(440.dp).background(Color(0xF00F1A30)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item { Text("Zvuk", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(8.dp)) }
                if (audio.isEmpty()) item { Text("Samo jedan zvučni zapis", color = Color(0xFF9AA9C7), modifier = Modifier.padding(8.dp)) }
                audio.forEachIndexed { gi, g ->
                    for (ti in 0 until g.length) {
                        val fmt = g.getTrackFormat(ti)
                        item {
                            ListRow(
                                title = fmt.label ?: fmt.language?.let { Locale(it).displayLanguage } ?: "Zapis ${gi + 1}",
                                subtitle = listOfNotNull(fmt.sampleMimeType?.substringAfter('/'), if (fmt.channelCount > 0) "${fmt.channelCount} kan." else null).joinToString(" · "),
                                selected = g.isTrackSelected(ti),
                                focusRequester = if (gi == 0 && ti == 0) first else null,
                            ) {
                                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, ti))
                                    .build()
                                overlay = ""
                            }
                        }
                    }
                }
                item { Text("Prevod", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp)) }
                item {
                    ListRow("Isključen", selected = textDisabled, focusRequester = if (audio.isEmpty()) first else null) {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                        overlay = ""
                    }
                }
                text.forEach { g ->
                    for (ti in 0 until g.length) {
                        val fmt = g.getTrackFormat(ti)
                        item {
                            ListRow(
                                title = fmt.label ?: fmt.language?.let { Locale(it).displayLanguage } ?: "Prevod",
                                selected = !textDisabled && g.isTrackSelected(ti),
                            ) {
                                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, ti))
                                    .build()
                                overlay = ""
                            }
                        }
                    }
                }
                if (item?.kind != "live") {
                    item { ListRow("Dodaj prevod sa adrese (URL)") { overlay = "subtitle_url" } }
                    if (!isTv) item { ListRow("Dodaj prevod iz fajla (SRT, VTT)") { pickFile.launch(arrayOf("*/*")) } }
                }
                item { Text("Slika", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp)) }
                item {
                    val modes = listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT to "Uklopi", AspectRatioFrameLayout.RESIZE_MODE_FILL to "Popuni", AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Uvećaj")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(8.dp)) {
                        modes.forEach { (mode, label) -> ActionButton(label, primary = playerView?.resizeMode == mode) { playerView?.resizeMode = mode; overlay = "" } }
                    }
                }
                item {
                    ListRow("Otvori u drugom plejeru") {
                        overlay = ""
                        openExternal(currentUrl(), preferVlc = false)
                    }
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { delay(80); first.requestFocus() } }
    }

    @Composable
    private fun SubtitleUrlOverlay() {
        var url by remember { mutableStateOf("") }
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
            Column(Modifier.width(560.dp).background(Color(0xFF16233F), RoundedCornerShape(16.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Prevod sa adrese", style = MaterialTheme.typography.titleLarge, color = Color.White)
                OutlinedTextField(value = url, onValueChange = { v -> url = v }, label = { Text("https://…/prevod.srt") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Dodaj", primary = true) {
                        if (url.startsWith("http")) addSubtitle(SubtitleSource(url.trim(), "Prevod", "", Downloads.subtitleMime(url)))
                        overlay = ""
                    }
                    ActionButton("Odustani") { overlay = "" }
                }
            }
        }
    }

    private fun addSubtitle(s: SubtitleSource) {
        val p = player ?: return
        extraSubs.add(s)
        val pos = p.currentPosition
        prepare(currentUrl(), pos)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        toast("Prevod je dodat")
    }

    companion object {
        fun fmtTime(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }
    }
}
