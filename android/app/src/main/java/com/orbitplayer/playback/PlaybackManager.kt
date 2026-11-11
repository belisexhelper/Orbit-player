package com.orbitplayer.playback

import kotlinx.coroutines.withContext
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
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
import com.orbitplayer.core.AppLog
import com.orbitplayer.core.AppSettings
import com.orbitplayer.core.Net
import com.orbitplayer.core.Store
import com.orbitplayer.data.ContentRepository
import com.orbitplayer.data.Downloads
import com.orbitplayer.data.db.ChannelEntity
import com.orbitplayer.data.db.ProgressEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SubtitleSource(val uri: String, val label: String, val language: String = "", val mime: String = "")

/** What is playing */
data class PlayItem(
    val kind: String, // live | vod | episode | catchup | local
    val title: String,
    val url: String = "", // live: computed from the channel
    val poster: String = "",
    val channel: ChannelEntity? = null,
    val progressKey: String = "",
    val seriesKey: String = "",
    val playlistId: String = "",
    val subtitles: List<SubtitleSource> = emptyList(),
    val subtitle: String = "", // second line (programme, S01E02 …)
    /** catch-up: the programme's time span, and where in it to start */
    val catchupStart: Long = 0,
    val catchupEnd: Long = 0,
    val catchupOffset: Long = 0,
) {
    val isLive get() = kind == "live"
    val seekable get() = kind != "live"
    val isCatchup get() = kind == "catchup" && catchupEnd > catchupStart
}

data class TrackOption(val groupIndex: Int, val trackIndex: Int, val label: String, val detail: String, val selected: Boolean)

/**
 * One player for the whole app: the preview on TV, the mini player on phones and full screen
 * all show the same ExoPlayer, so moving between them never restarts the stream.
 */
@OptIn(UnstableApi::class)
class PlaybackManager(private val context: Context, private val store: Store, private val repo: ContentRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var built: ExoPlayer? = null
    /** the current item showed a picture already (buffering after that = a stall) */
    private var startedPlaying = false

    private fun noteBuffering(buffering: Boolean) {
        if (!buffering) {
            if (startedPlaying) PlaybackStats.stallEnded()
            startedPlaying = true
        } else if (startedPlaying && _item.value?.isLive == true) {
            PlaybackStats.stallStarted(_item.value?.title ?: "stream")
        }
    }
    private var loadControl: OrbitLoadControl? = null
    private var builtWith: String = ""

    private val _item = MutableStateFlow<PlayItem?>(null)
    val item: StateFlow<PlayItem?> = _item.asStateFlow()

    private val _queue = MutableStateFlow<List<PlayItem>>(emptyList())
    val queue: StateFlow<List<PlayItem>> = _queue.asStateFlow()

    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    /** Increments when tracks change (audio/subtitle lists in the UI re-read) */
    private val _tracksVersion = MutableStateFlow(0)
    val tracksVersion: StateFlow<Int> = _tracksVersion.asStateFlow()

    /** Time when the sleep timer stops playback, 0 = off */
    private val _sleepAt = MutableStateFlow(0L)
    val sleepAt: StateFlow<Long> = _sleepAt.asStateFlow()

    /** A finished episode/movie: the UI closes full screen */
    private val _ended = MutableStateFlow(0)
    val ended: StateFlow<Int> = _ended.asStateFlow()

    private var retries = 0
    private var triedHls = false
    private var extraSubs = mutableListOf<SubtitleSource>()
    private var progressJob: Job? = null

    // live reconnect: attempts since the stream last played, and the watchdog for frozen streams
    private var reconnects = 0
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    /** false while the user (or the app going to the background) has paused */
    private var wantPlay = true
    private var recentJob: Job? = null
    private var sleepJob: Job? = null
    private var previousChannelIndex = -1
    private var lastUrl = ""

    /** catch-up: offset (ms from the programme start) where the current archive stream begins */
    private var catchupOffset = 0L
    private var catchupJob: Job? = null
    private var lastForceHls = false

    /** Which engine plays the current item: "exo" or "vlc". The picture view follows this. */
    private val _engine = MutableStateFlow("exo")
    val engine: StateFlow<String> = _engine.asStateFlow()
    private val usingVlc get() = _engine.value == "vlc"

    /** Short messages for the user (shown as a toast) */
    val notices = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Set when the user switched engines by hand for the current item */
    private var manualEngine: String? = null

    val vlc: VlcEngine by lazy {
        VlcEngine(context, object : VlcEngine.Events {
            override fun onPlaying(playing: Boolean) { if (usingVlc) { _playing.value = playing; PlaybackStats.playing(playing) } }
            override fun onBuffering(buffering: Boolean) { if (usingVlc) { _buffering.value = buffering; noteBuffering(buffering) } }
            override fun onReady() {
                if (!usingVlc) return
                retries = 0
                reconnects = 0
                _error.value = null
            }
            override fun onEnded() { if (usingVlc) onVlcEnded() }
            override fun onError() { if (usingVlc) onVlcError() }
            override fun onTracks() { if (usingVlc) _tracksVersion.value++ }
        })
    }

    val settings: AppSettings get() = store.settings.value

    val player: ExoPlayer
        get() {
            val sig = "${settings.hardwareDecoding}" // buffer settings are read live by OrbitLoadControl
            val p = built
            if (p != null && (sig == builtWith || _item.value != null)) return p
            p?.release()
            return build().also {
                built = it
                builtWith = sig
            }
        }

    private fun build(): ExoPlayer {
        val s = settings
        // the device's decoders first; FFmpeg (software) for audio formats the device can't play
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        if (!s.hardwareDecoding) {
            renderers.setMediaCodecSelector(MediaCodecSelector { mime, secure, tunneling ->
                val all = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
                val sw = all.filter { it.softwareOnly }
                sw.ifEmpty { all }
            })
        }
        val load = OrbitLoadControl(
            settings = { settings },
            isLive = { _item.value?.isLive == true },
            serverKey = { runCatching { Uri.parse(lastUrl).host }.getOrNull() ?: "" },
        ).also { loadControl = it }
        val http = OkHttpDataSource.Factory(Net.iptv).setUserAgent(Net.USER_AGENT)
        // live channel stream: a connection that sends nothing for 4 s counts as stalled and is replaced
        val liveHttp = OkHttpDataSource.Factory(Net.iptv.newBuilder().readTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build())
            .setUserAgent(Net.USER_AGENT)
        val reconnecting = ReconnectingDataSource.Factory(http, liveHttp) { uri ->
            settings.fastReconnect && _item.value?.isLive == true && uri.toString() == lastUrl &&
                !lastForceHls && !lastUrl.contains(".m3u8")
        }
        val dataSource = DefaultDataSource.Factory(context, reconnecting)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        val p = ExoPlayer.Builder(context, renderers)
            .setLoadControl(load)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
            .build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(s.subtitleLanguage)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !s.subtitlesByDefault)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!usingVlc) onError(error)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (usingVlc) return
                _buffering.value = state == Player.STATE_BUFFERING
                if (state == Player.STATE_BUFFERING) noteBuffering(true)
                if (state == Player.STATE_READY) noteBuffering(false)
                if (state == Player.STATE_READY) {
                    retries = 0
                    reconnects = 0
                    _error.value = null
                }
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!usingVlc) { _playing.value = isPlaying; PlaybackStats.playing(isPlaying) }
            }

            override fun onTracksChanged(tracks: Tracks) {
                _tracksVersion.value++
                checkUnsupported(tracks)
            }
        })
        return p
    }

    // ---------------- starting playback ----------------

    fun playQueue(items: List<PlayItem>, index: Int) {
        if (items.isEmpty()) return
        val cur = _item.value
        val target = items[index.coerceIn(0, items.size - 1)]
        _queue.value = items
        // same channel already playing: keep the stream, just update the queue
        if (cur != null && cur.isLive && target.isLive && cur.channel?.key == target.channel?.key && isActive()) {
            _index.value = index
            return
        }
        start(index)
    }

    fun play(item: PlayItem) = playQueue(listOf(item), 0)

    fun start(index: Int) {
        val items = _queue.value
        val next = items.getOrNull(index) ?: return
        saveProgress()
        val prev = _item.value
        if (prev?.isLive == true && next.isLive && prev.channel?.key != next.channel?.key) previousChannelIndex = _index.value
        _index.value = index
        _item.value = next
        startedPlaying = false
        PlaybackStats.streamChanged()
        retries = 0
        reconnects = 0
        reconnectJob?.cancel()
        wantPlay = true
        triedHls = false
        manualEngine = null
        extraSubs = next.subtitles.toMutableList()
        _error.value = null
        catchupOffset = next.catchupOffset
        scope.launch {
            val url = when {
                next.isLive && next.channel != null -> repo.channelUrl(next.channel)
                next.isCatchup && next.channel != null -> repo.catchupUrlAt(next.channel, next.catchupStart + catchupOffset, next.catchupEnd) ?: ""
                else -> next.url
            }
            if (url.isBlank()) {
                _error.value = "This stream has no address"
                return@launch
            }
            val resume = if (next.progressKey.isNotBlank() && next.seekable) repo.db.user().progress(next.progressKey) else null
            val startAt = if (resume != null && !resume.finished && (resume.durationMs <= 0 || resume.positionMs < resume.durationMs - 60_000)) resume.positionMs else 0L
            prepare(url, startAt)
            if (next.isLive && next.channel != null) rememberChannel(next.channel)
        }
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                delay(10_000)
                saveProgress()
            }
        }
        startWatchdog(next)
    }

    private fun rememberChannel(ch: ChannelEntity) {
        store.lastChannelKey = ch.key
        recentJob?.cancel()
        recentJob = scope.launch {
            delay(5_000) // counts as "recent" after 5 s of watching
            store.recentChannels = (listOf(ch.key) + store.recentChannels.filter { it != ch.key }).take(20)
        }
    }

    private fun isActive(): Boolean = if (usingVlc) vlc.hasMedia else (built?.playbackState ?: Player.STATE_IDLE) != Player.STATE_IDLE

    /** Engine for the current item: the user's choice, else the setting ("auto" starts with ExoPlayer) */
    private fun chosenEngine(): String = manualEngine ?: if (settings.playerEngine == "vlc") "vlc" else "exo"

    private fun prepare(url: String, startMs: Long, forceHls: Boolean = false) {
        lastUrl = url
        lastForceHls = forceHls
        if (chosenEngine() == "vlc") {
            built?.stop()
            if (vlc.prepare(url, startMs, extraSubs, settings.hardwareDecoding,
                    if (_item.value?.isLive == true) settings.liveBufferMs else settings.vodBufferMs,
                    vod = _item.value?.isLive != true)) {
                _engine.value = "vlc"
                return
            }
            manualEngine = "exo" // VLC can't load on this device
        }
        if (usingVlc) vlc.stop()
        _engine.value = "exo"
        loadControl?.newStream()
        val p = player
        val b = MediaItem.Builder().setUri(Uri.parse(url))
        if (forceHls || url.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (url.contains(".mpd")) b.setMimeType(MimeTypes.APPLICATION_MPD)
        if (extraSubs.isNotEmpty()) {
            b.setSubtitleConfigurations(extraSubs.map { s ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.uri))
                    .setMimeType(s.mime.ifBlank { Downloads.subtitleMime(s.uri) })
                    .setLanguage(s.language.ifBlank { null })
                    .setLabel(s.label)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            })
        }
        p.setMediaItem(b.build(), startMs)
        p.prepare()
        p.playWhenReady = true
    }

    private fun currentUrl(): String = lastUrl

    /** Switches the current item to the other engine and continues from the same position */
    fun switchEngine(to: String) {
        val it = _item.value ?: return
        val pos = if (it.seekable) positionMs else 0L
        manualEngine = to
        retries = 0
        _error.value = null
        if (lastUrl.isNotBlank()) prepare(lastUrl, pos, lastForceHls)
    }

    /** "auto": ExoPlayer has the stream but can't decode its audio or video → continue in VLC */
    private fun checkUnsupported(tracks: Tracks) {
        if (settings.playerEngine != "auto" || manualEngine != null || usingVlc) return
        fun broken(type: Int): Boolean {
            val groups = tracks.groups.filter { it.type == type }
            return groups.isNotEmpty() && groups.none { it.isSupported }
        }
        if (broken(C.TRACK_TYPE_AUDIO) || broken(C.TRACK_TYPE_VIDEO)) {
            AppLog.w("ExoPlayer can't decode ${_item.value?.title}, switching to VLC")
            fallbackToVlc()
        }
    }

    private fun fallbackToVlc(): Boolean {
        if (settings.playerEngine != "auto" || manualEngine != null || !vlc.available) return false
        val it = _item.value ?: return false
        val pos = if (it.seekable) built?.currentPosition ?: 0L else 0L
        manualEngine = "vlc"
        retries = 0
        _error.value = null
        notices.tryEmit("ExoPlayer can't play this, switched to VLC")
        prepare(lastUrl, pos, lastForceHls)
        return true
    }

    private fun onVlcEnded() {
        val it = _item.value ?: return
        // a live stream that "ends" has dropped: reconnect
        if (it.isLive || it.kind == "catchup" && vlc.durationMs <= 0) onVlcError() else onEnded()
    }

    /**
     * Live stream dropped: get a fresh address for the channel and start it again — the same thing
     * that happens when the user switches channels — with short, growing pauses between attempts.
     */
    private fun reconnectLive() {
        val it = _item.value ?: return
        if (!settings.autoReconnect) {
            _error.value = "Connection lost. Press OK on Try again or switch channels."
            return
        }
        if (reconnectJob?.isActive == true) return
        reconnects++
        PlaybackStats.reconnect(_error.value?.takeIf { e -> !e.startsWith("Connection lost") } ?: "stream frozen or lost")
        _error.value = "Connection lost. Reconnecting… ($reconnects)"
        reconnectJob = scope.launch {
            delay((1000L * reconnects).coerceAtMost(8_000L))
            if (_item.value !== it) return@launch
            val url = if (it.channel != null) repo.channelUrl(it.channel) else lastUrl
            retries = 0
            triedHls = false
            prepare(url.ifBlank { lastUrl }, 0)
        }
    }

    /** Live: if nothing moves (picture nor incoming data) for 15 s + the Live TV buffer while it should play, reconnect */
    private fun startWatchdog(item: PlayItem) {
        watchdogJob?.cancel()
        if (!item.isLive) return
        watchdogJob = scope.launch {
            var lastPos = -1L
            var lastBuffered = -1L
            var lastMove = System.currentTimeMillis()
            while (_item.value === item) {
                delay(2_000)
                val pos = enginePositionMs
                // data still coming in (filling the Live TV buffer before the picture starts) is not frozen
                val buffered = if (usingVlc) 0L else built?.bufferedPosition ?: 0L
                val now = System.currentTimeMillis()
                val wants = wantPlay && (if (usingVlc) vlc.hasMedia else built?.playWhenReady == true)
                if (pos != lastPos || buffered != lastBuffered || !wants) {
                    lastPos = pos
                    lastBuffered = buffered
                    lastMove = now
                } else if (now - lastMove > 15_000L + settings.liveBufferMs && reconnectJob?.isActive != true) {
                    AppLog.w("Live stream frozen, reconnecting ${item.title}")
                    lastMove = now
                    reconnectLive()
                }
            }
        }
    }

    private fun onVlcError() {
        val it = _item.value ?: return
        if (it.isLive) return reconnectLive()
        val maxRetries = if (it.isLive) 1000 else 4
        if (settings.autoReconnect && retries < maxRetries) {
            retries++
            _error.value = "Connection lost. Reconnecting… ($retries)"
            val pos = if (it.isLive) 0L else vlc.positionMs
            scope.launch {
                delay((2000L * retries).coerceAtMost(10_000L))
                if (_item.value === it && usingVlc) prepare(lastUrl, pos, lastForceHls)
            }
        } else {
            _error.value = "The stream can't be played. Check your connection and subscription, or try the other player engine."
        }
    }

    // ---------------- errors and reconnect ----------------

    private fun onError(error: PlaybackException) {
        val p = built ?: return
        val it = _item.value ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            p.seekToDefaultPosition()
            p.prepare()
            return
        }
        val url = currentUrl()
        if (!triedHls && !url.contains(".m3u8") && (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED || error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)) {
            triedHls = true
            prepare(url, p.currentPosition, forceHls = true)
            return
        }
        // Xtream channel: when .ts fails, try .m3u8
        if (it.isLive && it.channel != null && it.channel.url.isBlank() && url.endsWith(".ts") && retries == 1) {
            retries++
            prepare(url.removeSuffix(".ts") + ".m3u8", 0)
            return
        }
        // anything that is not a network problem (container, codec, audio output): try VLC
        val networkProblem = error.errorCode in 2000..2999
        if (!networkProblem && fallbackToVlc()) return
        if (it.isLive) return reconnectLive()
        val maxRetries = 4
        if (settings.autoReconnect && retries < maxRetries) {
            retries++
            _error.value = "Connection lost. Reconnecting… ($retries)"
            scope.launch {
                delay((2000L * retries).coerceAtMost(10_000L))
                prepare(url, if (it.isLive) 0 else p.currentPosition)
            }
        } else {
            _error.value = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused the stream. Check that your subscription is active and the connection limit is not reached."
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the server. Check your internet connection."
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "This device can't decode the stream. Try the VLC engine or the other video decoder in Settings → Player."
                else -> "The stream can't be played (${error.errorCodeName})."
            }
            AppLog.w("Player error ${error.errorCodeName} for ${it.title}")
        }
    }

    private fun onEnded() {
        val it = _item.value ?: return
        if (it.isCatchup) return continueAfterCatchup(it)
        if (it.progressKey.isNotBlank()) markFinished(it)
        if (it.kind == "episode" && settings.autoPlayNext && _index.value < _queue.value.size - 1) {
            start(_index.value + 1)
        } else if (it.isLive) {
            reconnectLive()
        } else {
            _ended.value++
        }
    }

    private fun markFinished(it: PlayItem) {
        val dur = durationMs
        scope.launch(Dispatchers.IO) {
            repo.db.user().saveProgress(
                ProgressEntity(it.progressKey, it.kind, it.title, it.poster, it.url, dur, dur, System.currentTimeMillis(), it.seriesKey, it.playlistId, finished = true),
            )
        }
    }

    fun retry() {
        val url = currentUrl()
        retries = 0
        _error.value = null
        if (url.isNotBlank()) prepare(url, if (_item.value?.seekable == true) positionMs else 0) else start(_index.value)
    }

    // ---------------- controls ----------------

    fun zap(delta: Int) {
        val n = _queue.value.size
        if (n <= 1) return
        start(((_index.value + delta) % n + n) % n)
    }

    /** Back to the channel watched before the current one */
    fun previousChannel(): Boolean {
        val i = previousChannelIndex
        if (i < 0 || i >= _queue.value.size || i == _index.value) return false
        start(i)
        return true
    }

    fun togglePause() {
        if (usingVlc) {
            if (vlc.isPlaying) vlc.pause() else vlc.play()
            wantPlay = vlc.isPlaying.not()
            return
        }
        val p = built ?: return
        wantPlay = !p.playWhenReady
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekBy(ms: Long) {
        val cur = _item.value
        if (cur?.isCatchup == true) {
            // the archive can only start at whole minutes: forward goes to the next minute, back to the previous one
            val target = positionMs + ms
            val minute = 60_000L
            catchupSeek(if (ms > 0) (target + minute - 1) / minute * minute else target / minute * minute)
            return
        }
        if (_item.value?.seekable != true) return
        val dur = durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        seekTo((positionMs + ms).coerceIn(0, dur))
    }

    fun seekTo(ms: Long) {
        if (_item.value?.isCatchup == true) {
            catchupSeek(ms / 60_000L * 60_000L)
            return
        }
        if (usingVlc) vlc.seekTo(ms) else built?.seekTo(ms.coerceAtLeast(0))
    }

    /** Catch-up "seek": asks the server for the archive from a new point of the programme */
    private fun catchupSeek(offsetMs: Long) {
        val it = _item.value ?: return
        val ch = it.channel ?: return
        val maxOffset = (minOf(it.catchupEnd, System.currentTimeMillis()) - it.catchupStart - 60_000L).coerceAtLeast(0)
        val offset = offsetMs.coerceIn(0, maxOffset)
        catchupOffset = offset
        retries = 0
        _error.value = null
        catchupJob?.cancel()
        catchupJob = scope.launch {
            delay(350) // several quick presses = one request
            val url = repo.catchupUrlAt(ch, it.catchupStart + offset, it.catchupEnd) ?: return@launch
            if (_item.value === it) prepare(url, 0)
        }
    }

    /** Catch-up of a programme, from its start (or [offsetMs] into it) */
    fun playCatchup(ch: ChannelEntity, title: String, programTitle: String, start: Long, stop: Long, offsetMs: Long = 0) {
        play(
            PlayItem(
                kind = "catchup", title = title, subtitle = programTitle, poster = ch.logo, channel = ch, playlistId = ch.playlistId,
                catchupStart = start, catchupEnd = stop, catchupOffset = (offsetMs / 60_000L * 60_000L).coerceAtLeast(0),
            ),
        )
    }

    /** End of an archived programme: continue with the next one, or with live TV when that is on now */
    private fun continueAfterCatchup(it: PlayItem) {
        val ch = it.channel ?: return
        scope.launch {
            val now = System.currentTimeMillis()
            val next = if (ch.epgKey.isBlank()) null else withContext(Dispatchers.IO) {
                repo.db.epg().programs(ch.epgKey, it.catchupEnd - 60_000L, it.catchupEnd + 6 * 3600_000L).firstOrNull { p -> p.start >= it.catchupEnd - 60_000L }
            }
            when {
                next != null && next.stop <= now -> playCatchup(ch, it.title, next.title, next.start, next.stop)
                else -> play(PlayItem(kind = "live", title = it.title, channel = ch, poster = ch.logo, playlistId = ch.playlistId))
            }
        }
    }

    fun stop() {
        saveProgress()
        progressJob?.cancel()
        reconnectJob?.cancel()
        watchdogJob?.cancel()
        recentJob?.let { j -> if (!j.isCompleted) j.cancel() }
        built?.stop()
        built?.clearMediaItems()
        if (usingVlc) vlc.stop()
        _engine.value = "exo"
        _item.value = null
        _error.value = null
        _playing.value = false
        _buffering.value = false
        lastUrl = ""
    }

    fun pause() {
        wantPlay = false
        if (usingVlc) vlc.pause() else built?.pause()
    }

    fun resumeIfReady() {
        val cur = _item.value ?: return
        val wasPaused = !wantPlay
        wantPlay = true
        // live TV coming back from the background: start again at the live point
        if (cur.isLive && wasPaused) {
            start(_index.value)
            return
        }
        if (usingVlc) {
            if (vlc.hasMedia && !vlc.isPlaying) vlc.play()
            return
        }
        val p = built ?: return
        if (p.playbackState == Player.STATE_READY) p.play()
    }

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _sleepAt.value = 0
            return
        }
        _sleepAt.value = System.currentTimeMillis() + minutes * 60_000L
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            _sleepAt.value = 0
            pause()
        }
    }

    fun saveProgress() {
        val it = _item.value ?: return
        if (!usingVlc && built == null) return
        if (it.progressKey.isBlank() || !it.seekable || it.kind == "catchup") return
        val pos = positionMs
        val dur = durationMs
        if (pos < 10_000) return
        val finished = dur > 0 && pos > dur - 60_000
        val e = ProgressEntity(it.progressKey, it.kind, it.title, it.poster, it.url, pos, dur, System.currentTimeMillis(), it.seriesKey, it.playlistId, finished)
        scope.launch(Dispatchers.IO) { repo.db.user().saveProgress(e) }
    }

    // ---------------- tracks ----------------

    fun audioTracks(): List<TrackOption> = if (usingVlc) vlc.audioTracks() else tracks(C.TRACK_TYPE_AUDIO)

    fun textTracks(): List<TrackOption> = if (usingVlc) vlc.textTracks() else tracks(C.TRACK_TYPE_TEXT)

    val subtitlesOff: Boolean get() = if (usingVlc) vlc.subtitlesOff else built?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) ?: true

    private fun tracks(type: Int): List<TrackOption> {
        val p = built ?: return emptyList()
        val out = ArrayList<TrackOption>()
        p.currentTracks.groups.forEachIndexed { gi, g ->
            if (g.type != type) return@forEachIndexed
            for (ti in 0 until g.length) {
                val f = g.getTrackFormat(ti)
                val lang = f.language?.takeIf { it != "und" }?.let { java.util.Locale(it).displayLanguage }
                val label = f.label ?: lang ?: if (type == C.TRACK_TYPE_AUDIO) "Track ${out.size + 1}" else "Subtitles ${out.size + 1}"
                val detail = listOfNotNull(
                    f.sampleMimeType?.substringAfter('/')?.uppercase(),
                    if (f.channelCount > 0) "${f.channelCount} ch" else null,
                    if (f.label != null) lang else null,
                ).joinToString(" · ")
                out += TrackOption(gi, ti, label, detail, g.isTrackSelected(ti))
            }
        }
        return out
    }

    fun selectTrack(type: Int, option: TrackOption) {
        if (usingVlc) {
            if (type == C.TRACK_TYPE_AUDIO) vlc.selectAudio(option.groupIndex) else vlc.selectText(option.groupIndex)
            _tracksVersion.value++
            return
        }
        val p = built ?: return
        val group = p.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .build()
    }

    fun disableSubtitles() {
        if (usingVlc) {
            vlc.disableSubtitles()
            _tracksVersion.value++
            return
        }
        val p = built ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        _tracksVersion.value++
    }

    /** Adds an external subtitle (SRT/VTT/ASS) and keeps the position */
    fun addSubtitle(s: SubtitleSource) {
        extraSubs.add(s)
        if (usingVlc) {
            vlc.addSubtitle(s)
            return
        }
        val p = built ?: return
        prepare(currentUrl(), p.currentPosition)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
    }

    /** live: extra cushion the player learned for this server from its stalls */
    val learnedBufferMs: Long get() = if (usingVlc) 0L else loadControl?.learnedExtraMs ?: 0L

    /** How much is loaded ahead of the picture (ExoPlayer), -1 when unknown (VLC) */
    val bufferedAheadMs: Long get() = if (usingVlc) -1L else built?.totalBufferedDuration ?: 0L
    /** VLC's buffering progress in percent */
    val vlcBufferPercent: Int get() = vlc.bufferPercent.toInt()
    /** what the player waits for before the picture starts (the Live TV buffer for channels) */
    /** movies & series: how far ahead the player loads (the setting) */
    val vodTargetMs: Long get() = settings.vodBufferMs.toLong()
    val bufferTargetMs: Long get() = if (_item.value?.isLive == true) loadControl?.liveTargetMs ?: settings.liveBufferMs.toLong() else loadControl?.currentNeedMs ?: 2_500L

    private val enginePositionMs: Long get() = if (usingVlc) vlc.positionMs else built?.currentPosition ?: 0
    val positionMs: Long get() = if (_item.value?.isCatchup == true) catchupOffset + enginePositionMs else enginePositionMs
    val durationMs: Long get() = _item.value?.takeIf { it.isCatchup }?.let { it.catchupEnd - it.catchupStart }
        ?: if (usingVlc) vlc.durationMs else built?.duration?.takeIf { it > 0 } ?: 0
    val bufferedMs: Long get() = if (usingVlc) vlc.positionMs else built?.bufferedPosition ?: 0

    /** Video info for the banner: "1920×1080 · 25 fps" */
    fun videoInfo(): String {
        if (usingVlc) {
            val (w, h) = vlc.videoSize()
            return listOfNotNull(if (w > 0) "${w}×$h" else null, "VLC").joinToString(" · ")
        }
        val f = built?.videoFormat ?: return ""
        val parts = mutableListOf<String>()
        if (f.width > 0) parts += "${f.width}×${f.height}"
        if (f.frameRate > 0) parts += "${f.frameRate.toInt()} fps"
        return parts.joinToString(" · ")
    }

    fun quality(): String {
        val h = (if (usingVlc) vlc.videoSize().second else built?.videoFormat?.height) ?: return ""
        return when {
            h >= 2000 -> "4K"
            h >= 1000 -> "FHD"
            h >= 700 -> "HD"
            h > 0 -> "SD"
            else -> ""
        }
    }
}
