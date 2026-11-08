package com.orbitplayer.ui.common

import androidx.compose.material.icons.filled.LiveTv
import com.orbitplayer.data.db.ProgramEntity
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Replay
import androidx.compose.ui.focus.onFocusChanged
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent as AKeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import com.orbitplayer.core.Fmt
import com.orbitplayer.data.Downloads
import com.orbitplayer.data.db.ChannelEntity
import com.orbitplayer.playback.PlayItem
import com.orbitplayer.playback.SubtitleSource
import com.orbitplayer.ui.AppViewModel
import com.orbitplayer.ui.NowNext
import com.orbitplayer.ui.components.ChannelLogo
import com.orbitplayer.ui.components.ChoiceDialog
import com.orbitplayer.ui.components.Focusable
import com.orbitplayer.ui.components.IconAction
import com.orbitplayer.ui.components.ListRow
import com.orbitplayer.ui.components.OrbitButton
import com.orbitplayer.ui.components.Pill
import com.orbitplayer.ui.components.ProgressLine
import com.orbitplayer.ui.components.TextInputDialog
import com.orbitplayer.ui.components.isOkKey
import com.orbitplayer.ui.theme.LocalIsTv
import com.orbitplayer.ui.theme.Orbit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Overlay { None, Channels, Options, Panel }
private enum class Picker { None, Audio, Subtitles, Aspect, Sleep, SubtitleUrl }

/** Full-screen player for TV and phone */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(vm: AppViewModel, inPip: Boolean = false, onEnterPip: () -> Unit = {}) {
    val tv = LocalIsTv.current
    val pb = vm.playback
    val item by pb.item.collectAsState()
    val index by pb.index.collectAsState()
    val error by pb.error.collectAsState()
    val buffering by pb.buffering.collectAsState()
    val playing by pb.playing.collectAsState()
    val ended by pb.ended.collectAsState()
    val settings by vm.settings.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var aspect by remember { mutableStateOf(settings.aspect) }
    // short note in the middle of the screen after a quick seek ("+10 s")
    var seekNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(seekNote) {
        if (seekNote != null) {
            delay(900)
            seekNote = null
        }
    }
    val engine by pb.engine.collectAsState()
    var overlay by remember { mutableStateOf(Overlay.None) }
    var picker by remember { mutableStateOf(Picker.None) }
    var controls by remember { mutableStateOf(false) } // phone controls / TV VOD controls
    var banner by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }
    var locked by remember { mutableStateOf(false) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var gestureLabel by remember { mutableStateOf("") }
    val root = remember { FocusRequester() }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            pb.addSubtitle(SubtitleSource(uri.toString(), "My subtitles", "", Downloads.subtitleMime(uri.lastPathSegment ?: "")))
        }
    }

    fun touch() {
        lastInput = System.currentTimeMillis()
    }

    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val channel = item?.channel
    val nowNext by produceState<NowNext?>(null, channel?.key, now / 60_000) {
        value = channel?.let { vm.loadNowNext(listOf(it.epgKey))[it.epgKey] }
    }

    // banner on every channel change
    LaunchedEffect(item, index) {
        if (overlay == Overlay.Panel) overlay = Overlay.None
        if (item?.isLive == true || item?.kind == "catchup") {
            banner = true
            touch()
        }
        if (item?.seekable == true && !tv) controls = true
    }
    // auto hide (TV 5 s, phone 3.5 s; banner uses the setting; not while paused or a menu is open)
    LaunchedEffect(lastInput, banner, controls, playing, overlay) {
        val wait = if (banner && !controls) settings.bannerSeconds * 1000L else if (tv) 5000L else 3500L
        delay(wait)
        if (overlay == Overlay.None && (playing || item?.isLive == true)) {
            banner = false
            controls = false
        }
    }
    // number entry commits after 2 s
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(2000)
        commitNumber(vm, digits)
        digits = ""
    }
    LaunchedEffect(ended) { if (ended > 0) vm.closePlayer(stop = true) }
    // The player must own the remote: if focus ends up anywhere else (e.g. the list behind it), take it back
    var playerHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(overlay, picker, controls, playerHasFocus) {
        if (overlay == Overlay.None && picker == Picker.None && !(controls && tv && item?.seekable == true)) {
            delay(if (playerHasFocus) 50 else 150)
            runCatching { root.requestFocus() }
        } else if (!playerHasFocus && picker == Picker.None) {
            delay(300)
            if (!playerHasFocus) runCatching { root.requestFocus() }
        }
    }

    BackHandler(enabled = !inPip) {
        when {
            digits.isNotEmpty() -> digits = ""
            overlay != Overlay.None -> overlay = Overlay.None
            controls && tv -> controls = false
            banner && tv -> banner = false
            else -> vm.leavePlayer(tv)
        }
    }

    val cur = item
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onFocusChanged { playerHasFocus = it.hasFocus }
            .focusProperties { exit = { d -> com.orbitplayer.ui.components.arrowExit(d) } }
            .focusGroup(),
    ) {
        PlayerSurface(pb, settings, aspect, Modifier.fillMaxSize())
        if (inPip) return@Box

        // Key handling and touch layer
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(root)
                .onPreviewKeyEvent { e ->
                    if (overlay != Overlay.None || picker != Picker.None) return@onPreviewKeyEvent false
                    if (controls && tv && cur?.seekable == true && e.key != Key.Back) {
                        // VOD controls are visible: buttons handle the keys
                        touch()
                        return@onPreviewKeyEvent false
                    }
                    handleKey(e.type, e.key, e.nativeKeyEvent.repeatCount, cur, tv,
                        onZap = { d -> pb.zap(d); banner = true; touch() },
                        onChannels = { overlay = Overlay.Channels },
                        onOptions = { overlay = Overlay.Options },
                        onPrevious = { if (!pb.previousChannel()) { banner = true; touch() } },
                        onBanner = { banner = !banner; touch() },
                        onDigit = { d -> digits = (digits + d).take(4); touch() },
                        onControls = { if (cur?.isCatchup == true) overlay = Overlay.Panel else controls = true; touch() },
                        onPanel = { overlay = Overlay.Panel; banner = false; touch() },
                        onSeek = { ms ->
                            val step = if (cur?.isCatchup == true) 60_000L else 10_000L
                            pb.seekBy(if (ms < 0) -step else step)
                            seekNote = (if (ms < 0) "−" else "+") + if (cur?.isCatchup == true) "1 min" else "10 s"
                            if (cur?.isCatchup == true) banner = true else controls = true
                            touch()
                        },
                        onTogglePause = { pb.togglePause(); if (cur?.isCatchup == true) banner = true else controls = true; touch() },
                        onCommitDigits = { if (digits.isNotEmpty()) { commitNumber(vm, digits); digits = "" } },
                        hasDigits = digits.isNotEmpty(),
                        onRewindLive = {
                            val ch = cur?.channel
                            if (ch?.archive == true) vm.rewindLive(ch, 60_000) else vm.toast("This channel has no archive")
                        },
                    )
                }
                .focusable()
                .then(
                    if (!tv) Modifier
                        .pointerInput(cur?.seekable, locked) {
                            detectTapGestures(
                                onTap = { if (!locked) { controls = !controls; touch() } else { controls = true; touch() } },
                                onDoubleTap = { o ->
                                    if (locked || cur?.seekable != true) return@detectTapGestures
                                    val left = o.x < size.width / 2
                                    pb.seekBy(if (left) -10_000 else 10_000)
                                    gestureLabel = if (left) "−10 s" else "+10 s"
                                },
                            )
                        }
                        .pointerInput(cur?.seekable, locked) {
                            gestureControls(context, this, cur?.seekable == true, { locked }, { label -> gestureLabel = label }) { ms -> pb.seekBy(ms) }
                        }
                    else Modifier,
                ),
        )

        if (buffering && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Orbit.colors.accent)
        seekNote?.let { note ->
            Text(
                note,
                Modifier.align(Alignment.TopCenter).padding(top = 48.dp).background(Color(0xCC101521), RoundedCornerShape(12.dp)).padding(horizontal = 22.dp, vertical = 10.dp),
                color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            )
        }

        error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).padding(32.dp).background(Color(0xE6161B26), RoundedCornerShape(14.dp)).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(msg, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(14.dp))
                OrbitButton("Try again", icon = Icons.Filled.Refresh, primary = true) { pb.retry() }
            }
        }

        if (gestureLabel.isNotEmpty()) {
            LaunchedEffect(gestureLabel) {
                delay(900)
                gestureLabel = ""
            }
            Box(Modifier.align(Alignment.Center).background(Color(0xB3000000), RoundedCornerShape(20.dp)).padding(horizontal = 22.dp, vertical = 12.dp)) {
                Text(gestureLabel, color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
        }

        if (digits.isNotEmpty()) {
            Box(Modifier.align(Alignment.TopEnd).padding(32.dp).background(Color(0xE61E2533), RoundedCornerShape(12.dp)).padding(horizontal = 26.dp, vertical = 12.dp)) {
                Text(digits, color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (cur != null) {
            if (tv) {
                AnimatedVisibility(banner && overlay == Overlay.None && !(controls && cur.seekable), Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
                    InfoBanner(vm, cur, nowNext, pb.quality(), now, favorites)
                }
                if (cur.seekable && controls && overlay == Overlay.None) {
                    TvVodControls(vm, cur, now, onTouch = ::touch, onPicker = { picker = it }, onOptions = { overlay = Overlay.Options })
                }
            } else {
                AnimatedVisibility(controls || banner, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
                    PhoneControls(
                        vm, cur, nowNext, now, playing, locked,
                        onBack = { vm.leavePlayer(false) },
                        onLock = { locked = !locked; touch() },
                        onPip = onEnterPip,
                        onChannels = { overlay = Overlay.Channels },
                        onPicker = { picker = it; touch() },
                        onTouch = ::touch,
                    )
                }
            }
        }

        // EON-style panel: OK on a live channel or catch-up
        if (tv && overlay == Overlay.Panel && cur != null && cur.channel != null) {
            LaunchedEffect(lastInput) {
                delay(12_000)
                if (overlay == Overlay.Panel) overlay = Overlay.None
            }
            TvLivePanel(vm, cur, nowNext, now, onTouch = ::touch, onClose = { overlay = Overlay.None })
        }

        // Side channel list (Left on TV, "Channels" on phone)
        AnimatedVisibility(
            overlay == Overlay.Channels,
            Modifier.align(Alignment.CenterStart),
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
        ) {
            ChannelListPanel(vm, onPick = { ch, list ->
                overlay = Overlay.None
                vm.playChannel(ch, list)
            })
        }

        // Options bar (long press OK)
        if (overlay == Overlay.Options && cur != null) {
            OptionsBar(
                cur, channel != null && channel.key in favorites, aspect, engine,
                onAction = { a ->
                    overlay = Overlay.None
                    when (a) {
                        "audio" -> picker = Picker.Audio
                        "subs" -> picker = Picker.Subtitles
                        "aspect" -> picker = Picker.Aspect
                        "sleep" -> picker = Picker.Sleep
                        "fav" -> channel?.let { vm.toggleFavorite(it.key, "live") }
                        "channels" -> overlay = Overlay.Channels
                        "guide" -> overlay = Overlay.Panel
                        "retry" -> pb.retry()
                        "startover" -> channel?.let { vm.startOver(it) }
                        "engine" -> switchEngine(vm)
                        "external" -> openExternal(context, vm, cur)
                    }
                },
                onClose = { overlay = Overlay.None },
            )
        }
    }

    when (picker) {
        Picker.Audio -> {
            val v by pb.tracksVersion.collectAsState()
            val opts = remember(v) { pb.audioTracks() }
            if (opts.isEmpty()) {
                LaunchedEffect(Unit) { vm.toast("This stream has only one audio track"); picker = Picker.None }
            } else {
                ChoiceDialog("Audio", opts.mapIndexed { i, o -> i.toString() to (o.label + if (o.detail.isNotBlank()) "  ·  ${o.detail}" else "") }, opts.indexOfFirst { it.selected }.toString(), { picker = Picker.None }) { i ->
                    opts.getOrNull(i.toInt())?.let { pb.selectTrack(C.TRACK_TYPE_AUDIO, it) }
                }
            }
        }
        Picker.Subtitles -> {
            val v by pb.tracksVersion.collectAsState()
            val opts = remember(v) { pb.textTracks() }
            val choices = buildList {
                add("off" to "Off")
                opts.forEachIndexed { i, o -> add(i.toString() to o.label) }
                if (cur?.seekable == true) {
                    add("url" to "Add subtitles from address (URL)…")
                    if (!tv) add("file" to "Add subtitles from file (SRT, VTT)…")
                }
            }
            val sel = if (pb.subtitlesOff) "off" else opts.indexOfFirst { it.selected }.takeIf { it >= 0 }?.toString() ?: "off"
            ChoiceDialog("Subtitles", choices, sel, { if (picker == Picker.Subtitles) picker = Picker.None }) { v2 ->
                when (v2) {
                    "off" -> pb.disableSubtitles()
                    "url" -> scope.launch { delay(50); picker = Picker.SubtitleUrl }
                    "file" -> pickFile.launch(arrayOf("*/*"))
                    else -> opts.getOrNull(v2.toInt())?.let { pb.selectTrack(C.TRACK_TYPE_TEXT, it) }
                }
            }
        }
        Picker.SubtitleUrl -> TextInputDialog("Subtitles from address", "", "https://…/subtitles.srt", onDismiss = { picker = Picker.None }) { url ->
            if (url.startsWith("http")) pb.addSubtitle(SubtitleSource(url.trim(), "Subtitles", "", Downloads.subtitleMime(url)))
        }
        Picker.Aspect -> ChoiceDialog("Picture", listOf("fit" to "Fit to screen", "fill" to "Stretch to fill", "zoom" to "Zoom (crop)"), aspect, { picker = Picker.None }) { aspect = it }
        Picker.Sleep -> {
            val sleepAt by pb.sleepAt.collectAsState()
            val curMin = if (sleepAt == 0L) "0" else ""
            ChoiceDialog("Sleep timer", listOf("0" to "Off", "15" to "15 minutes", "30" to "30 minutes", "60" to "1 hour", "90" to "1.5 hours", "120" to "2 hours"), curMin, { picker = Picker.None }) {
                pb.setSleepTimer(it.toInt())
                if (it != "0") vm.toast("Playback stops in ${it} min")
            }
        }
        Picker.None -> Unit
    }
}

private fun commitNumber(vm: AppViewModel, digits: String) {
    val n = digits.toIntOrNull() ?: return
    val ch = vm.channels.value.firstOrNull { it.num == n }
    if (ch == null) {
        vm.toast("Channel $n not found")
        return
    }
    val list = vm.groupChannels.value.takeIf { l -> l.any { it.key == ch.key } } ?: vm.channels.value
    vm.playChannel(ch, list)
}

private fun handleKey(
    type: KeyEventType,
    key: Key,
    repeat: Int,
    item: PlayItem?,
    tv: Boolean,
    onZap: (Int) -> Unit,
    onChannels: () -> Unit,
    onOptions: () -> Unit,
    onPrevious: () -> Unit,
    onBanner: () -> Unit,
    onDigit: (Char) -> Unit,
    onControls: () -> Unit,
    onSeek: (Long) -> Unit,
    onTogglePause: () -> Unit,
    onCommitDigits: () -> Unit,
    hasDigits: Boolean,
    onRewindLive: () -> Unit = {},
    onPanel: () -> Unit = {},
): Boolean {
    if (!tv || item == null) return false
    val live = item.isLive
    val code = key.nativeKeyCode
    // OK: short press on key up, long press while held
    if (isOkKey(key)) {
        if (type == KeyEventType.KeyDown && repeat == 1) onOptions()
        if (type == KeyEventType.KeyUp && repeat == 0) {
            when {
                hasDigits -> onCommitDigits()
                live || item.isCatchup -> onPanel()
                else -> onControls()
            }
        }
        return true
    }
    if (type != KeyEventType.KeyDown) return code in handledCodes
    if (code in AKeyEvent.KEYCODE_0..AKeyEvent.KEYCODE_9 && live) {
        onDigit('0' + (code - AKeyEvent.KEYCODE_0))
        return true
    }
    when (code) {
        AKeyEvent.KEYCODE_DPAD_UP, AKeyEvent.KEYCODE_CHANNEL_UP -> if (live) onZap(1) else if (item.isCatchup) onPanel() else onControls()
        AKeyEvent.KEYCODE_DPAD_DOWN, AKeyEvent.KEYCODE_CHANNEL_DOWN -> if (live) onZap(-1) else if (item.isCatchup) onPanel() else onControls()
        AKeyEvent.KEYCODE_DPAD_LEFT -> if (live) onChannels() else onSeek(-10_000)
        AKeyEvent.KEYCODE_DPAD_RIGHT -> if (live) onPrevious() else onSeek(10_000)
        AKeyEvent.KEYCODE_MEDIA_REWIND -> if (live) onRewindLive() else onSeek(-30_000)
        AKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> onSeek(30_000)
        AKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AKeyEvent.KEYCODE_MEDIA_PLAY, AKeyEvent.KEYCODE_MEDIA_PAUSE, AKeyEvent.KEYCODE_SPACE -> onTogglePause()
        AKeyEvent.KEYCODE_MENU -> onOptions()
        AKeyEvent.KEYCODE_INFO -> if (live) onBanner() else onControls()
        AKeyEvent.KEYCODE_LAST_CHANNEL -> onPrevious()
        AKeyEvent.KEYCODE_GUIDE -> onOptions()
        AKeyEvent.KEYCODE_CAPTIONS -> onOptions()
        else -> return false
    }
    return true
}

private val handledCodes = setOf(
    AKeyEvent.KEYCODE_DPAD_UP, AKeyEvent.KEYCODE_DPAD_DOWN, AKeyEvent.KEYCODE_DPAD_LEFT, AKeyEvent.KEYCODE_DPAD_RIGHT,
    AKeyEvent.KEYCODE_CHANNEL_UP, AKeyEvent.KEYCODE_CHANNEL_DOWN, AKeyEvent.KEYCODE_MENU, AKeyEvent.KEYCODE_INFO,
)

/** Brightness (left half) and volume (right half) by vertical swipe; seek by horizontal swipe */
private suspend fun gestureControls(
    context: Context,
    scope: androidx.compose.ui.input.pointer.PointerInputScope,
    seekable: Boolean,
    isLocked: () -> Boolean,
    onLabel: (String) -> Unit,
    onSeek: (Long) -> Unit,
) {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val activity = context as? Activity
    var mode = 0 // 1 = brightness, 2 = volume, 3 = seek
    var startX = 0f
    var acc = 0f
    var seekMs = 0L
    var startVol = 0
    var startBright = 0.5f
    scope.detectDragGestures(
        onDragStart = { o ->
            mode = 0
            startX = o.x
            acc = 0f
            seekMs = 0
            startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val b = activity?.window?.attributes?.screenBrightness ?: -1f
            startBright = if (b < 0) 0.5f else b
        },
        onDragEnd = {
            if (mode == 3 && seekMs != 0L) onSeek(seekMs)
            mode = 0
        },
        onDrag = { change, drag ->
            if (isLocked()) return@detectDragGestures
            change.consume()
            if (mode == 0) {
                mode = when {
                    kotlin.math.abs(drag.x) > kotlin.math.abs(drag.y) -> if (seekable) 3 else -1
                    startX < scope.size.width / 2 -> 1
                    else -> 2
                }
            }
            when (mode) {
                1 -> {
                    acc -= drag.y
                    val v = (startBright + acc / (scope.size.height * 0.8f)).coerceIn(0.02f, 1f)
                    activity?.window?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = v } }
                    onLabel("Brightness ${(v * 100).toInt()}%")
                }
                2 -> {
                    acc -= drag.y
                    val v = (startVol + (acc / (scope.size.height * 0.8f)) * maxVol).toInt().coerceIn(0, maxVol)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                    onLabel("Volume ${v * 100 / maxVol.coerceAtLeast(1)}%")
                }
                3 -> {
                    acc += drag.x
                    seekMs = (acc / scope.size.width * 120_000).toLong()
                    onLabel((if (seekMs >= 0) "+" else "−") + Fmt.duration(kotlin.math.abs(seekMs)))
                }
            }
        },
    )
}

private fun openExternal(context: Context, vm: AppViewModel, item: PlayItem) {
    vm.launch {
        val url = if (item.isLive && item.channel != null) vm.repo.channelUrl(item.channel) else item.url
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*").putExtra("title", item.title)
        runCatching { context.startActivity(Intent.createChooser(i, "Open with")) }
    }
}

// ---------------------------------------------------------------- overlays

/**
 * TV, OK on a live channel or a catch-up programme (EON style): what is on, a timeline, start over / −1 min /
 * pause / +1 min / LIVE, and a strip of the channel's programmes: past ones (↺) play from the archive.
 */
@Composable
private fun BoxScope.TvLivePanel(vm: AppViewModel, item: PlayItem, nn: NowNext?, now: Long, onTouch: () -> Unit, onClose: () -> Unit) {
    val pb = vm.playback
    val c = Orbit.colors
    val ch = item.channel ?: return
    val playing by pb.playing.collectAsState()
    val catchup = item.isCatchup
    val archive = ch.archive
    val pauseFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { pauseFocus.requestFocus() }
    }
    // the programme being watched and where in it we are
    val progStart = if (catchup) item.catchupStart else nn?.now?.start ?: 0L
    val progStop = if (catchup) item.catchupEnd else nn?.now?.stop ?: 0L
    val progTitle = if (catchup) item.subtitle else nn?.now?.title ?: ""
    val at = if (catchup) item.catchupStart + pb.positionMs else now
    val behind = if (catchup) ((now - at) / 60_000L).coerceAtLeast(0) else 0L

    val days = if (archive) ch.archiveDays.coerceIn(1, 7) else 0
    val programmes by produceState<List<ProgramEntity>>(emptyList(), ch.key) {
        val t = System.currentTimeMillis()
        value = vm.programs(ch, t - maxOf(days, 1) * 86_400_000L, t + 12 * 3_600_000L)
    }
    val stripState = rememberLazyListState()
    LaunchedEffect(programmes.size) {
        val i = programmes.indexOfFirst { it.start <= at && it.stop > at }
        if (i >= 0) stripState.scrollToItem((i - 1).coerceAtLeast(0))
    }
    val channels = vm.groupChannels.value

    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6101521), Color(0xF7101521))))
            .padding(start = 40.dp, end = 40.dp, top = 48.dp, bottom = 22.dp)
            .onPreviewKeyEvent { onTouch(); false },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ChannelLogo(ch.logo, ch.name, 84.dp, 48.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(progTitle.ifBlank { vm.displayName(ch) }, color = Color.White, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (catchup) Pill("ARCHIVE", c.accentAlt, filled = true) else Pill("LIVE", c.live, filled = true)
                    EnginePill(vm)
                }
                Text(
                    vm.displayName(ch) + (if (progStop > progStart) "  ·  ${Fmt.time(progStart)} – ${Fmt.time(progStop)}" else "") +
                        (if (catchup) "  ·  $behind min behind live" else ""),
                    color = c.textMuted, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                )
            }
            Text(Fmt.time(now), color = Color.White, style = MaterialTheme.typography.headlineSmall)
        }
        // timeline of the programme
        if (progStop > progStart) {
            val frac = ((at - progStart).toFloat() / (progStop - progStart)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                // how far live is (catch-up): the rest of the bar is still in the future
                if (catchup) ProgressLine(((now - progStart).toFloat() / (progStop - progStart)).coerceIn(0f, 1f), Modifier.fillMaxWidth(), color = Color.White.copy(alpha = 0.25f), height = 5.dp)
                ProgressLine(frac, Modifier.fillMaxWidth(), height = 5.dp)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(Fmt.time(progStart), color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(Fmt.time(at), color = Color.White, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(Fmt.time(progStop), color = c.textMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (archive) IconAction(Icons.Filled.Replay, "Start over") {
                if (catchup) pb.seekTo(0) else vm.startOver(ch)
            }
            IconAction(Icons.Filled.FastRewind, "−1 min") {
                when {
                    catchup -> pb.seekBy(-60_000)
                    archive -> vm.rewindLive(ch, 60_000)
                    else -> vm.toast("This channel has no archive")
                }
            }
            IconAction(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", focusRequester = pauseFocus) { pb.togglePause() }
            IconAction(Icons.Filled.FastForward, "+1 min", tint = if (catchup) Orbit.colors.text else Orbit.colors.textMuted) {
                if (catchup && behind >= 1) pb.seekBy(60_000) else vm.toast("You are watching live")
            }
            IconAction(Icons.Filled.LiveTv, "LIVE", active = !catchup) {
                if (catchup) vm.playChannel(ch, channels) else onClose()
            }
        }
        Spacer(Modifier.height(10.dp))
        // programmes of the channel
        if (programmes.isEmpty()) {
            Text("No programme information for this channel", color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
        } else LazyRow(state = stripState, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(programmes, key = { it.id * 31 + it.start }) { p ->
                val isNow = p.start <= now && p.stop > now
                val past = p.stop <= now
                val archived = archive && p.start < now && p.start >= now - days * 86_400_000L
                val watching = at >= p.start && at < p.stop
                Focusable(
                    onClick = {
                        when {
                            isNow && catchup -> vm.playChannel(ch, channels)
                            isNow -> onClose()
                            archived -> vm.playCatchup(ch, p)
                            past -> vm.toast("This programme is no longer in the archive")
                            else -> vm.toast("Starts ${Fmt.date(p.start)} at ${Fmt.time(p.start)}")
                        }
                    },
                    onLongClick = { if (isNow && archived) vm.playCatchup(ch, p) },
                    modifier = Modifier.width(240.dp),
                    color = if (watching) c.accent.copy(alpha = 0.22f) else Color(0xCC1E2533),
                    scaleOnFocus = 1.05f,
                ) { f ->
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.title, Modifier.weight(1f), color = if (!past && !isNow) c.textMuted else Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (f || isNow) FontWeight.SemiBold else FontWeight.Normal)
                            when {
                                isNow -> Text("LIVE", Modifier.padding(start = 6.dp).background(c.live, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                archived -> Icon(Icons.Filled.History, "From the archive", Modifier.padding(start = 6.dp).size(16.dp), tint = c.accentAlt)
                            }
                        }
                        val dayStart = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
                        val dayText = when ((Math.floorDiv(p.start - dayStart, 86_400_000L)).toInt()) { 0 -> "Today"; -1 -> "Yesterday"; 1 -> "Tomorrow"; else -> Fmt.date(p.start) }
                        Text("$dayText  //  ${Fmt.time(p.start)} – ${Fmt.time(p.stop)}", color = if (isNow) c.accent else c.textMuted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        if (isNow) ProgressLine(((now - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f), Modifier.fillMaxWidth().padding(top = 5.dp), height = 3.dp)
                    }
                }
            }
        }
        Text("◀ ▶ move  ·  OK play  ·  Back close", Modifier.padding(top = 6.dp), color = c.textMuted, style = MaterialTheme.typography.labelSmall)
    }
}


/** Channel banner (TV): logo, number, name, now/next with progress */
@Composable
private fun InfoBanner(vm: AppViewModel, item: PlayItem, nn: NowNext?, quality: String, now: Long, favorites: Set<String>) {
    val c = Orbit.colors
    val ch = item.channel
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0101521))))
            .padding(start = 40.dp, end = 40.dp, top = 40.dp, bottom = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            if (ch != null) ChannelLogo(ch.logo, ch.name, 96.dp, 56.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (ch != null && vm.settings.value.showChannelNumbers) Text("${ch.num}", color = c.accent, style = MaterialTheme.typography.headlineSmall)
                    Text(item.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (quality.isNotBlank()) Pill(quality, c.accent)
                    EnginePill(vm)
                    if (ch?.archive == true) Pill("ARCHIVE", c.accentAlt)
                    if (item.kind == "catchup") Pill("CATCH-UP", c.accentAlt, filled = true)
                    if (ch != null && ch.key in favorites) Icon(Icons.Filled.Star, null, tint = c.accentAlt, modifier = Modifier.size(20.dp))
                }
                val p = nn?.now
                if (item.kind == "catchup") {
                    Text(item.subtitle, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                } else if (p != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${Fmt.time(p.start)} – ${Fmt.time(p.stop)}", color = c.textMuted, style = MaterialTheme.typography.bodyLarge)
                        Text(p.title, color = Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text("${((p.stop - now) / 60_000).coerceAtLeast(0)} min left", color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                    ProgressLine((now - p.start).toFloat() / (p.stop - p.start), Modifier.fillMaxWidth().padding(vertical = 8.dp), height = 4.dp)
                    nn.next?.let { n -> Text("Next  ${Fmt.time(n.start)}  ${n.title}", color = c.textMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
                } else {
                    Text("No programme information", color = c.textMuted, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.time(now), color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text("OK  programmes  ·  ◀  channels  ·  hold OK  options", color = c.textMuted, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Left panel with groups and channels, available while watching */
@Composable
private fun ChannelListPanel(vm: AppViewModel, onPick: (ChannelEntity, List<ChannelEntity>) -> Unit) {
    val c = Orbit.colors
    val tv = LocalIsTv.current
    val groups by vm.groups.collectAsState()
    val all by vm.channels.collectAsState()
    val playing by vm.playback.item.collectAsState()
    var group by remember { mutableStateOf(vm.selectedGroup.value) }
    var showGroups by remember { mutableStateOf(false) }
    val list = remember(all, group) { vm.channelsOf(all, group) }
    val nowNext by produceState(emptyMap<String, NowNext>(), list) { value = vm.loadNowNext(list.map { it.epgKey }) }
    val playingIdx = list.indexOfFirst { it.key == playing?.channel?.key }.coerceAtLeast(0)
    val state = remember(group) { androidx.compose.foundation.lazy.LazyListState((playingIdx - 4).coerceAtLeast(0)) }
    val groupIdx = groups.indexOfFirst { it.id == group }.coerceAtLeast(0)
    val groupState = remember(showGroups) { androidx.compose.foundation.lazy.LazyListState((groupIdx - 3).coerceAtLeast(0)) }
    val chFocus = remember { FocusRequester() }
    val grFocus = remember { FocusRequester() }
    LaunchedEffect(showGroups, group) {
        // the list may need a frame or two before the target row exists: try a few times
        repeat(6) {
            delay(50)
            if (runCatching { if (showGroups) grFocus.requestFocus() else chFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    Row(Modifier.fillMaxHeight().background(Color(0xF2121826))) {
        if (showGroups || !tv) {
            LazyColumn(Modifier.width(if (tv) 240.dp else 170.dp).fillMaxHeight().padding(8.dp), state = groupState) {
                itemsIndexed(groups, key = { _, g -> g.id }) { _, g ->
                    ListRow(
                        title = g.name,
                        subtitle = "${g.count}",
                        selected = g.id == group,
                        focusRequester = if (g.id == group) grFocus else null,
                        modifier = Modifier.onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { showGroups = false; true } else false
                        },
                    ) {
                        group = g.id
                        showGroups = false
                    }
                }
            }
        }
        Column(Modifier.width(if (tv) 420.dp else 300.dp).fillMaxHeight().padding(8.dp)) {
            Text(groups.firstOrNull { it.id == group }?.name ?: "Channels", Modifier.padding(12.dp), color = c.text, style = MaterialTheme.typography.titleMedium)
            LazyColumn(state = state) {
                itemsIndexed(list, key = { _, ch -> ch.key }) { i, ch ->
                    val p = nowNext[ch.epgKey]?.now
                    ListRow(
                        title = (if (vm.settings.value.showChannelNumbers) "${ch.num}  " else "") + vm.displayName(ch),
                        subtitle = p?.title ?: "",
                        selected = ch.key == playing?.channel?.key,
                        focusRequester = if (i == playingIdx) chFocus else null,
                        leading = { ChannelLogo(ch.logo, ch.name, 52.dp, 32.dp) },
                        modifier = Modifier.onPreviewKeyEvent { e ->
                            if (tv && e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { showGroups = true; true } else false
                        },
                    ) {
                        if (group != vm.selectedGroup.value) vm.selectGroup(group)
                        onPick(ch, list)
                    }
                }
            }
        }
    }
}

/** Long press OK: options bar at the bottom */
@Composable
private fun OptionsBar(item: PlayItem, favorite: Boolean, aspect: String, engine: String, onAction: (String) -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp).background(Color(0xF21E2533), RoundedCornerShape(16.dp)).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (item.isLive) IconAction(Icons.AutoMirrored.Filled.List, "Channels", focusRequester = first) { onAction("channels") }
            IconAction(Icons.Filled.Audiotrack, "Audio", focusRequester = if (!item.isLive) first else null) { onAction("audio") }
            IconAction(Icons.Filled.ClosedCaption, "Subtitles") { onAction("subs") }
            IconAction(Icons.Filled.AspectRatio, when (aspect) { "fill" -> "Stretch"; "zoom" -> "Zoom"; else -> "Fit" }) { onAction("aspect") }
            IconAction(Icons.Filled.Bedtime, "Sleep") { onAction("sleep") }
            if (item.isLive) IconAction(if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder, if (favorite) "Unfavorite" else "Favorite", active = favorite) { onAction("fav") }
            if (item.isLive && item.channel?.archive == true) IconAction(Icons.Filled.Replay, "Start over") { onAction("startover") }
            if (item.isLive) IconAction(Icons.Filled.DateRange, "Programmes") { onAction("guide") }
            IconAction(Icons.Filled.Refresh, "Reload") { onAction("retry") }
            IconAction(Icons.Filled.SwapHoriz, if (engine == "vlc") "Engine: VLC" else "Engine: Exo") { onAction("engine") }
            IconAction(Icons.Filled.OpenInNew, "Other player") { onAction("external") }
        }
    }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
}

/** TV controls for movies and episodes: seek bar + buttons */
@Composable
private fun BoxScope.TvVodControls(vm: AppViewModel, item: PlayItem, now: Long, onTouch: () -> Unit, onPicker: (Picker) -> Unit, onOptions: () -> Unit) {
    val pb = vm.playback
    val c = Orbit.colors
    val playing by pb.playing.collectAsState()
    val queue by pb.queue.collectAsState()
    val index by pb.index.collectAsState()
    val pos = now.let { pb.positionMs }
    val dur = now.let { pb.durationMs }
    val playFocus = remember { FocusRequester() }
    // Timeline: Left/Right move a marker by 1 min (5 min while held); playback jumps there when you stop
    var scrub by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(scrub) {
        val t = scrub ?: return@LaunchedEffect
        delay(800)
        pb.seekTo(t)
        delay(400)
        scrub = null
    }
    val small = if (item.isCatchup) 60_000L else 10_000L
    val smallLabel = if (item.isCatchup) "1 min" else "10 s"
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { playFocus.requestFocus() }
    }
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0101521))))
            .padding(horizontal = 40.dp, vertical = 26.dp)
            .onPreviewKeyEvent { onTouch(); false },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(item.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (pb.quality().isNotBlank()) Pill(pb.quality(), c.accent)
            EnginePill(vm)
        }
        if (item.subtitle.isNotBlank()) Text(item.subtitle, color = c.textMuted, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        Spacer(Modifier.height(14.dp))
        // seek bar: focus it and use left/right
        Focusable(
            onClick = { pb.togglePause() },
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val step = if (e.nativeKeyEvent.repeatCount > 6) 300_000L else 60_000L
                when (e.key) {
                    Key.DirectionLeft -> { scrub = ((scrub ?: pos) - step).coerceAtLeast(0); true }
                    Key.DirectionRight -> { scrub = ((scrub ?: pos) + step).let { if (dur > 0) it.coerceAtMost(dur - 5_000) else it }; true }
                    else -> false
                }
            },
            color = Color.Transparent,
            focusedColor = Color(0x22FFFFFF),
            scaleOnFocus = 1f,
        ) { focused ->
            Column(Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
                val shown = scrub ?: pos
                Box {
                    ProgressLine(if (dur > 0) pos.toFloat() / dur else 0f, Modifier.fillMaxWidth(), height = if (focused) 8.dp else 5.dp)
                    // where playback will jump to
                    if (scrub != null && dur > 0) ProgressLine(shown.toFloat() / dur, Modifier.fillMaxWidth(), color = c.accentAlt.copy(alpha = 0.85f), height = if (focused) 8.dp else 5.dp)
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(Fmt.duration(shown), color = if (scrub != null) c.accentAlt else Color.White, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    if (focused) Text(if (scrub != null) "Jumping to ${Fmt.duration(shown)}…" else "◀ ▶  1 min · hold for 5 min", color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Text(if (dur > 0) "−" + Fmt.duration(dur - shown) else "", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.Filled.Replay10, "−$smallLabel") { pb.seekBy(-small) }
            IconAction(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", focusRequester = playFocus) { pb.togglePause() }
            IconAction(Icons.Filled.Forward10, "+$smallLabel") { pb.seekBy(small) }
            if (item.kind == "episode" && index < queue.size - 1) IconAction(Icons.Filled.SkipNext, "Next episode") { pb.start(index + 1) }
            if (item.isCatchup) item.channel?.let { ch -> IconAction(Icons.Filled.LiveTv, "Back to live") { vm.playChannel(ch, listOf(ch)) } }
            Spacer(Modifier.width(24.dp))
            IconAction(Icons.Filled.Audiotrack, "Audio") { onPicker(Picker.Audio) }
            IconAction(Icons.Filled.ClosedCaption, "Subtitles") { onPicker(Picker.Subtitles) }
            IconAction(Icons.Filled.AspectRatio, "Picture") { onPicker(Picker.Aspect) }
            IconAction(Icons.Filled.Bedtime, "Sleep") { onPicker(Picker.Sleep) }
        }
    }
}

/** Phone / tablet controls: top bar, centre buttons, seek bar, bottom actions */
@Composable
private fun PhoneControls(
    vm: AppViewModel,
    item: PlayItem,
    nn: NowNext?,
    now: Long,
    playing: Boolean,
    locked: Boolean,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onPip: () -> Unit,
    onChannels: () -> Unit,
    onPicker: (Picker) -> Unit,
    onTouch: () -> Unit,
) {
    val pb = vm.playback
    val c = Orbit.colors
    val queue by pb.queue.collectAsState()
    val index by pb.index.collectAsState()
    Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
        if (locked) {
            Box(Modifier.align(Alignment.CenterStart).padding(24.dp)) {
                IconAction(Icons.Filled.Lock, "Unlock", tint = Color.White) { onLock() }
            }
            return@Box
        }
        // top bar
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) { onBack() }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(item.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = if (item.isLive) nn?.now?.title ?: "" else item.subtitle
                if (sub.isNotBlank()) Text(sub, color = Color(0xFFD5DCE8), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (pb.quality().isNotBlank()) Pill(pb.quality(), c.accent)
            Spacer(Modifier.width(6.dp))
            EnginePill(vm)
            IconAction(Icons.Filled.PictureInPictureAlt, "PiP", tint = Color.White) { onPip() }
        }
        // centre
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.isLive) {
                BigButton(Icons.Filled.SkipPrevious) { pb.zap(-1); onTouch() }
                BigButton(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, big = true) { pb.togglePause(); onTouch() }
                BigButton(Icons.Filled.SkipNext) { pb.zap(1); onTouch() }
            } else {
                BigButton(Icons.Filled.Replay10) { pb.seekBy(-10_000); onTouch() }
                BigButton(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, big = true) { pb.togglePause(); onTouch() }
                BigButton(Icons.Filled.Forward10) { pb.seekBy(10_000); onTouch() }
            }
        }
        // bottom
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (item.seekable) {
                val dur = pb.durationMs
                var dragging by remember { mutableStateOf(false) }
                var dragValue by remember { mutableFloatStateOf(0f) }
                val pos = if (dragging) (dragValue * dur).toLong() else now.let { pb.positionMs }
                Slider(
                    value = if (dur > 0) pos.toFloat() / dur else 0f,
                    onValueChange = { dragging = true; dragValue = it; onTouch() },
                    onValueChangeFinished = { pb.seekTo((dragValue * dur).toLong()); dragging = false },
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = Color(0x55FFFFFF)),
                )
                Row {
                    Text(Fmt.duration(pos), color = Color.White, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Text(Fmt.duration(dur), color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            } else nn?.now?.let { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${Fmt.time(p.start)} – ${Fmt.time(p.stop)}", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(10.dp))
                    ProgressLine((now - p.start).toFloat() / (p.stop - p.start), Modifier.weight(1f))
                    nn.next?.let { n -> Text("  Next ${Fmt.time(n.start)} ${n.title}", color = Color(0xFFD5DCE8), style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f)) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (item.isLive) IconAction(Icons.AutoMirrored.Filled.List, "Channels", tint = Color.White) { onChannels() }
                IconAction(Icons.Filled.Audiotrack, "Audio", tint = Color.White) { onPicker(Picker.Audio) }
                IconAction(Icons.Filled.ClosedCaption, "Subtitles", tint = Color.White) { onPicker(Picker.Subtitles) }
                IconAction(Icons.Filled.AspectRatio, "Picture", tint = Color.White) { onPicker(Picker.Aspect) }
                IconAction(Icons.Filled.Bedtime, "Sleep", tint = Color.White) { onPicker(Picker.Sleep) }
                if (item.kind == "episode" && index < queue.size - 1) IconAction(Icons.Filled.SkipNext, "Next", tint = Color.White) { pb.start(index + 1) }
                if (item.isLive && item.channel?.archive == true) IconAction(Icons.Filled.Replay, "Start over", tint = Color.White) { item.channel?.let { vm.startOver(it) } }
                if (item.isCatchup) item.channel?.let { ch -> IconAction(Icons.Filled.LiveTv, "Live", tint = Color.White) { vm.playChannel(ch, listOf(ch), fullscreen = true) } }
                if (item.isLive && item.channel?.archive == true) IconAction(Icons.Filled.History, "Archive", tint = Color.White) {
                    vm.channelViewGuide = true
                    vm.leavePlayer(tv = false)
                }
                val engine by pb.engine.collectAsState()
                IconAction(Icons.Filled.SwapHoriz, if (engine == "vlc") "VLC" else "ExoPlayer", tint = Color.White) { switchEngine(vm) }
                IconAction(Icons.Filled.LockOpen, "Lock", tint = Color.White) { onLock() }
            }
        }
    }
}

@Composable
private fun BigButton(icon: androidx.compose.ui.graphics.vector.ImageVector, big: Boolean = false, onClick: () -> Unit) {
    Focusable(onClick = onClick, shape = RoundedCornerShape(50), color = Color(0x55000000), focusedColor = Color(0x88000000), scaleOnFocus = 1f) { _ ->
        Box(Modifier.size(if (big) 76.dp else 56.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(if (big) 48.dp else 34.dp))
        }
    }
}

/** Continues the current item in the other engine (ExoPlayer ↔ VLC) */
private fun switchEngine(vm: AppViewModel) {
    val to = if (vm.playback.engine.value == "vlc") "exo" else "vlc"
    vm.playback.switchEngine(to)
    vm.toast(if (to == "vlc") "Playing with VLC" else "Playing with ExoPlayer")
}

/** Which engine is playing: EXO (ExoPlayer) or VLC */
@Composable
private fun EnginePill(vm: AppViewModel) {
    val engine by vm.playback.engine.collectAsState()
    Pill(if (engine == "vlc") "VLC" else "EXO", if (engine == "vlc") Orbit.colors.accentAlt else Orbit.colors.textMuted)
}
