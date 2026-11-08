package com.orbitplayer.ui.common

import com.orbitplayer.ui.components.OrbitButton
import com.orbitplayer.ui.components.ProgressLine
import com.orbitplayer.core.Updater
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.orbitplayer.BuildConfig
import com.orbitplayer.core.AppSettings
import com.orbitplayer.core.Fmt
import com.orbitplayer.data.db.PlaylistEntity
import com.orbitplayer.ui.AppViewModel
import com.orbitplayer.ui.Route
import com.orbitplayer.ui.components.ActionsDialog
import com.orbitplayer.ui.components.ChoiceDialog
import com.orbitplayer.ui.components.ConfirmDialog
import com.orbitplayer.ui.components.Focusable
import com.orbitplayer.ui.components.ListRow
import com.orbitplayer.ui.components.MenuAction
import com.orbitplayer.ui.components.OrbitDialog
import com.orbitplayer.ui.components.PinDialog
import com.orbitplayer.ui.components.SectionHeader
import com.orbitplayer.ui.components.SettingRow
import com.orbitplayer.ui.components.TextInputDialog
import com.orbitplayer.ui.components.ToggleRow
import com.orbitplayer.ui.theme.LocalIsTv
import com.orbitplayer.ui.theme.Orbit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SettingsSection(val id: String, val title: String, val icon: ImageVector, val tvOnly: Boolean = false)

val SECTIONS = listOf(
    SettingsSection("playlists", "Playlists", Icons.Filled.PlaylistPlay),
    SettingsSection("guide", "TV guide", Icons.Filled.DateRange),
    SettingsSection("appearance", "Appearance", Icons.Filled.Palette),
    SettingsSection("player", "Player", Icons.Filled.PlayCircle),
    SettingsSection("subtitles", "Subtitles", Icons.Filled.ClosedCaption),
    SettingsSection("parental", "Parental control", Icons.Filled.FamilyRestroom),
    SettingsSection("startup", "Start-up", Icons.Filled.PowerSettingsNew, tvOnly = true),
    SettingsSection("backup", "Backup & restore", Icons.Filled.Backup),
    SettingsSection("tools", "Speed test & diagnostics", Icons.Filled.Build),
    SettingsSection("about", "Updates & about", Icons.Filled.SystemUpdate),
)

/** TV: sections on the left, options on the right. Phone: grid of sections. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val tv = LocalIsTv.current
    val c = Orbit.colors
    val sections = SECTIONS.filter { tv || !it.tvOnly }
    if (tv) {
        var current by remember { mutableStateOf(sections.first().id) }
        val first = remember { FocusRequester() }
        com.orbitplayer.ui.tv.TvEntryPoint(first)
        Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 24.dp, top = 18.dp)) {
            Column(Modifier.width(280.dp).fillMaxHeight()) {
                Text("Settings", Modifier.padding(start = 14.dp, bottom = 10.dp), color = c.text, style = MaterialTheme.typography.headlineSmall)
                LazyColumn(Modifier.focusRestorer().focusGroup()) {
                    items(sections, key = { it.id }) { s ->
                        ListRow(
                            s.title,
                            selected = s.id == current,
                            leading = { Icon(s.icon, null, Modifier.size(22.dp), tint = if (s.id == current) c.accent else c.textMuted) },
                            onFocus = { current = s.id },
                            focusRequester = if (s.id == current) first else null,
                        ) { current = s.id }
                    }
                }
            }
            Spacer(Modifier.width(20.dp))
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .focusProperties { exit = { d -> if (d == FocusDirection.Left) first else FocusRequester.Cancel } }
                    .focusRestorer()
                    .focusGroup(),
            ) { SectionContent(vm, current) }
        }
        LaunchedEffect(Unit) {
            delay(150)
            runCatching { first.requestFocus() }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Text("Settings", Modifier.padding(16.dp), color = c.text, style = MaterialTheme.typography.headlineSmall)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sections, key = { it.id }) { s ->
                    Focusable(onClick = { vm.open(Route.SettingsSection(s.id)) }, modifier = Modifier.aspectRatio(1f), color = c.surface) { _ ->
                        Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(s.icon, null, Modifier.size(34.dp), tint = c.accent)
                            Spacer(Modifier.height(8.dp))
                            Text(s.title, color = c.text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

/** One section on its own page (phone, or opened directly) */
@Composable
fun SettingsSectionScreen(vm: AppViewModel, id: String) {
    val tv = LocalIsTv.current
    Column(Modifier.fillMaxSize().padding(start = if (tv) 20.dp else 0.dp, top = if (tv) 18.dp else 0.dp)) {
        PageTitle(SECTIONS.firstOrNull { it.id == id }?.title ?: "Settings", vm)
        SectionContent(vm, id)
    }
}

/** Title row with a back button on phones */
@Composable
fun PageTitle(title: String, vm: AppViewModel) {
    val tv = LocalIsTv.current
    Row(Modifier.fillMaxWidth().padding(horizontal = if (tv) 14.dp else 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!tv) {
            com.orbitplayer.ui.components.IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Back") { vm.back() }
        }
        Text(title, color = Orbit.colors.text, style = MaterialTheme.typography.headlineSmall)
    }
}

private fun onOff(b: Boolean) = if (b) "On" else "Off"

@Composable
fun SectionContent(vm: AppViewModel, id: String) {
    val s by vm.settings.collectAsState()
    val tv = LocalIsTv.current
    var choice by remember { mutableStateOf<ChoiceSpec?>(null) }
    val set: ((AppSettings) -> AppSettings) -> Unit = { vm.store.update(it) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 4.dp, end = 8.dp, bottom = 32.dp)) {
        when (id) {
            "playlists" -> item { PlaylistsSection(vm) }
            "guide" -> item { GuideSection(vm, s) { choice = it } }
            "appearance" -> {
                item {
                    val themes = buildList {
                        add("slate" to "Slate (default)")
                        add("oled" to "Black (OLED)")
                        if (!tv) add("light" to "Light")
                    }
                    SettingRow("Theme", themes.firstOrNull { it.first == s.theme }?.second ?: "Slate") {
                        choice = ChoiceSpec("Theme", themes, s.theme) { v -> set { it.copy(theme = v) } }
                    }
                }
                item {
                    val sizes = listOf("0.9" to "Small", "1.0" to "Medium", "1.15" to "Large", "1.3" to "Extra large")
                    val cur = sizes.minByOrNull { kotlin.math.abs(it.first.toFloat() - s.fontScale) }!!.first
                    SettingRow("Text size", sizes.first { it.first == cur }.second) {
                        choice = ChoiceSpec("Text size", sizes, cur) { v -> set { it.copy(fontScale = v.toFloat()) } }
                    }
                }
                if (tv) item { ToggleRow("Live preview", s.livePreview, "Plays the highlighted channel in the top window of Live TV") { v -> set { it.copy(livePreview = v) } } }
                item { ToggleRow("Show channel numbers", s.showChannelNumbers) { v -> set { it.copy(showChannelNumbers = v) } } }
                item { ToggleRow("Hide country in channel names", s.cleanChannelNames, "Shows “RTS 1” instead of “RS: RTS 1 FHD”. Off shows the full name from the playlist.") { v -> set { it.copy(cleanChannelNames = v) } } }
                item {
                    SettingRow("Channel info banner", "${s.bannerSeconds} s") {
                        choice = ChoiceSpec("Banner on channel change", listOf(2, 3, 4, 6, 8, 10).map { "$it" to "$it seconds" }, "${s.bannerSeconds}") { v -> set { it.copy(bannerSeconds = v.toInt()) } }
                    }
                }
            }
            "player" -> {
                item {
                    val opts = listOf("auto" to "Automatic (ExoPlayer, VLC when needed)", "exo" to "ExoPlayer", "vlc" to "VLC")
                    SettingRow("Player engine", opts.first { it.first == s.playerEngine }.second.substringBefore(" ("), "Automatic switches to VLC when a stream can't be played") {
                        choice = ChoiceSpec("Player engine", opts, s.playerEngine) { v -> set { it.copy(playerEngine = v) } }
                    }
                }
                item {
                    val opts = listOf("small" to "Small (fast start)", "medium" to "Medium", "large" to "Large", "xlarge" to "Very large (unstable lines)")
                    SettingRow("Buffer size", opts.first { it.first == s.buffer }.second.substringBefore(" (")) {
                        choice = ChoiceSpec("Buffer size", opts, s.buffer) { v -> set { it.copy(buffer = v) } }
                    }
                }
                item {
                    SettingRow("Video decoder", if (s.hardwareDecoding) "Hardware" else "Software", "Switch if the picture stutters, freezes or stays black") {
                        choice = ChoiceSpec("Video decoder", listOf("hw" to "Hardware (recommended)", "sw" to "Software"), if (s.hardwareDecoding) "hw" else "sw") { v -> set { it.copy(hardwareDecoding = v == "hw") } }
                    }
                }
                item {
                    SettingRow("Live stream format", if (s.streamFormat == "m3u8") "HLS" else "MPEG-TS", "For Xtream Codes playlists") {
                        choice = ChoiceSpec("Live stream format", listOf("ts" to "MPEG-TS (default)", "m3u8" to "HLS (m3u8)"), s.streamFormat) { v -> set { it.copy(streamFormat = v) } }
                    }
                }
                item { ToggleRow("Reconnect automatically", s.autoReconnect) { v -> set { it.copy(autoReconnect = v) } } }
                item {
                    val opts = listOf("fit" to "Fit to screen", "fill" to "Stretch to fill", "zoom" to "Zoom (crop)")
                    SettingRow("Default picture", opts.first { it.first == s.aspect }.second) {
                        choice = ChoiceSpec("Default picture", opts, s.aspect) { v -> set { it.copy(aspect = v) } }
                    }
                }
                item { ToggleRow("Play next episode automatically", s.autoPlayNext) { v -> set { it.copy(autoPlayNext = v) } } }
                item { Text("Buffer and decoder changes apply to the next stream you start.", Modifier.padding(14.dp), color = Orbit.colors.textMuted, style = MaterialTheme.typography.bodySmall) }
            }
            "subtitles" -> {
                item { ToggleRow("Show subtitles automatically", s.subtitlesByDefault) { v -> set { it.copy(subtitlesByDefault = v) } } }
                item {
                    val langs = listOf("en" to "English", "sr" to "Serbian", "hr" to "Croatian", "bs" to "Bosnian", "sl" to "Slovenian", "mk" to "Macedonian", "de" to "German", "fr" to "French", "es" to "Spanish", "it" to "Italian", "tr" to "Turkish", "ar" to "Arabic")
                    SettingRow("Preferred language", langs.firstOrNull { it.first == s.subtitleLanguage }?.second ?: s.subtitleLanguage) {
                        choice = ChoiceSpec("Preferred subtitle language", langs, s.subtitleLanguage) { v -> set { it.copy(subtitleLanguage = v) } }
                    }
                }
                item {
                    val opts = listOf("small" to "Small", "normal" to "Normal", "large" to "Large")
                    SettingRow("Subtitle size", opts.first { it.first == s.subtitleSize }.second) {
                        choice = ChoiceSpec("Subtitle size", opts, s.subtitleSize) { v -> set { it.copy(subtitleSize = v) } }
                    }
                }
                item { ToggleRow("Dark background behind subtitles", s.subtitleBackground) { v -> set { it.copy(subtitleBackground = v) } } }
            }
            "parental" -> item { ParentalSection(vm, s) }
            "startup" -> {
                item { ToggleRow("Start on the last channel", s.startOnLastChannel, "Opening the app continues the channel you watched last") { v -> set { it.copy(startOnLastChannel = v) } } }
                item { ToggleRow("Start with the device", s.autostartOnBoot, "Some devices don't allow apps to start on boot") { v -> set { it.copy(autostartOnBoot = v) } } }
                item { ToggleRow("Ask before exiting", s.confirmExit) { v -> set { it.copy(confirmExit = v) } } }
            }
            "backup" -> item { BackupSection(vm) }
            "tools" -> {
                item { SettingRow("Internet speed test") { vm.open(Route.SpeedTest) } }
                item { SettingRow("Diagnostics", subtitle = "Device, network and playlist servers") { vm.open(Route.Diagnostics) } }
                item {
                    SettingRow("Clear image cache") {
                        vm.app.cacheDir.deleteRecursively()
                        vm.toast("Cache cleared")
                    }
                }
            }
            "about" -> {
                item { UpdateCard(vm) }
                item { ToggleRow("Check for updates automatically", s.autoUpdateCheck, "At start-up, at most twice a day") { v -> set { it.copy(autoUpdateCheck = v) } } }
                item { AboutText(tv) }
            }
        }
    }
    choice?.let { ch -> ChoiceDialog(ch.title, ch.options, ch.selected, { choice = null }) { ch.onPick(it) } }
}

@Composable
private fun AboutText(tv: Boolean) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Orbit Player ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = Orbit.colors.text, style = MaterialTheme.typography.titleMedium)
                    Text("Orbit Player is a media player. It does not provide or include any channels, movies or other content. Add the playlist you received from your provider.", color = Orbit.colors.textMuted, style = MaterialTheme.typography.bodyMedium)
                    Text("Interface: ${if (tv) "TV (remote control)" else "Phone / tablet (touch)"}", color = Orbit.colors.textMuted, style = MaterialTheme.typography.bodySmall)
                }
}

/** Check for a newer build on GitHub, download it and hand it to the Android installer */
@Composable
private fun UpdateCard(vm: AppViewModel) {
    val c = Orbit.colors
    val updater = vm.app.updater
    val state by updater.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).background(c.surface, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Installed: ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}", color = c.text, style = MaterialTheme.typography.titleMedium)
        when (val st = state) {
            Updater.State.Idle -> Text("Updates come directly from the Orbit Player releases on GitHub.", color = c.textMuted)
            Updater.State.Checking -> Text("Checking for updates…", color = c.textMuted)
            Updater.State.UpToDate -> Text("You have the latest version.", color = c.success)
            is Updater.State.Available -> ReleaseInfo(st.release)
            is Updater.State.Downloading -> {
                ReleaseInfo(st.release)
                Text("Downloading… ${(st.progress * 100).toInt()}%", color = c.text)
                ProgressLine(st.progress, Modifier.fillMaxWidth(), height = 6.dp)
            }
            is Updater.State.Ready -> {
                ReleaseInfo(st.release)
                Text("Downloaded. Press Install and confirm in the Android window. If Android asks, allow installing apps from Orbit Player, then press Install again.", color = c.textMuted, style = MaterialTheme.typography.bodySmall)
            }
            is Updater.State.Error -> Text(st.message, color = c.live)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (val st = state) {
                is Updater.State.Available -> OrbitButton("Download and install (${Fmt.bytes(st.release.size)})", icon = Icons.Filled.Download, primary = true, focusRequester = first) { updater.download(st.release) }
                is Updater.State.Downloading -> OrbitButton("Cancel", focusRequester = first) { updater.cancel() }
                is Updater.State.Ready -> OrbitButton("Install", icon = Icons.Filled.SystemUpdate, primary = true, focusRequester = first) {
                    vm.closePlayer(stop = true)
                    updater.install(context, st.file)
                }
                is Updater.State.Error -> OrbitButton(if (st.release != null) "Try again" else "Check again", icon = Icons.Filled.Refresh, primary = true, focusRequester = first) {
                    if (st.release != null) updater.download(st.release) else scope.launch { updater.check() }
                }
                Updater.State.Checking -> OrbitButton("Checking…", enabled = false, focusRequester = first) {}
                else -> OrbitButton("Check for updates", icon = Icons.Filled.Refresh, primary = true, focusRequester = first) { scope.launch { updater.check() } }
            }
        }
    }
}

@Composable
private fun ReleaseInfo(r: Updater.Release) {
    val c = Orbit.colors
    Text("New version: ${r.versionName} · build ${r.versionCode}" + if (r.date.isNotBlank()) " · ${r.date}" else "", color = c.accent, style = MaterialTheme.typography.titleSmall)
    val notes = r.notes.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("Automatic build") }.take(10)
    if (notes.isNotEmpty()) Text(notes.joinToString("\n"), color = c.textMuted, style = MaterialTheme.typography.bodySmall)
}

data class ChoiceSpec(val title: String, val options: List<Pair<String, String>>, val selected: String, val onPick: (String) -> Unit)

// ---------------------------------------------------------------- playlists

@Composable
private fun PlaylistsSection(vm: AppViewModel) {
    val c = Orbit.colors
    val playlists by vm.playlists.collectAsState()
    val active by vm.activePlaylist.collectAsState()
    val sync by vm.syncStatus.collectAsState()
    var menu by remember { mutableStateOf<PlaylistEntity?>(null) }
    var delete by remember { mutableStateOf<PlaylistEntity?>(null) }
    var groups by remember { mutableStateOf(false) }
    Column {
        playlists.orEmpty().forEach { p ->
            val st = sync[p.id]
            val info = when {
                st?.running == true -> st.step
                st?.error?.isNotBlank() == true -> st.error
                p.lastError.isNotBlank() -> "Error: ${p.lastError}"
                else -> listOfNotNull(
                    if (p.type == "xtream") "Xtream" else "M3U",
                    "${p.liveCount} channels",
                    if (p.vodCount > 0) "${p.vodCount} movies" else null,
                    if (p.seriesCount > 0) "${p.seriesCount} series" else null,
                    if (p.expiresAt > 0) "expires ${Fmt.longDate(p.expiresAt)}" else null,
                ).joinToString(" · ")
            }
            ListRow(p.name, subtitle = info, selected = p.id == active?.id) { menu = p }
        }
        SettingRow("Add playlist") { vm.open(Route.AddPlaylist()) }
        SettingRow("Refresh all playlists and TV guide") {
            vm.syncNow()
            vm.toast("Refreshing…")
        }
        SettingRow("Show or hide channel groups") { groups = true }
        SettingRow("Show all hidden groups and channels") { vm.showAllHidden() }
        Text("Tip: hold OK (or long-press) on a group or channel to hide it.", Modifier.padding(14.dp), color = c.textMuted, style = MaterialTheme.typography.bodySmall)
    }
    menu?.let { p ->
        ActionsDialog(p.name, listOf(
            MenuAction("Use this playlist") { vm.setActivePlaylist(p.id) },
            MenuAction("Refresh now") { vm.refreshPlaylist(p.id); vm.toast("Refreshing ${p.name}…") },
            MenuAction("Edit") { vm.open(Route.AddPlaylist(p.id)) },
            MenuAction("Delete") { delete = p },
        ), onDismiss = { menu = null })
    }
    delete?.let { p -> ConfirmDialog("Delete playlist?", "“${p.name}” and its channels, movies and series will be removed from this device.", "Delete", { delete = null }) { vm.deletePlaylist(p.id) } }
    if (groups) ManageGroupsDialog(vm) { groups = false }
}

@Composable
private fun ManageGroupsDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val cats by vm.liveCategories.collectAsState()
    val hidden by vm.hiddenGroups.collectAsState()
    OrbitDialog(onDismiss, "Channel groups") {
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(cats, key = { it.key }) { cat ->
                ToggleRow(cat.name, cat.key !in hidden) { visible -> vm.setGroupHidden(cat.key, !visible) }
            }
        }
    }
}

@Composable
private fun GuideSection(vm: AppViewModel, s: AppSettings, onChoice: (ChoiceSpec) -> Unit) {
    val scope = rememberCoroutineScope()
    Column {
        SettingRow("Update TV guide every", "${s.epgRefreshHours} h") {
            onChoice(ChoiceSpec("Update TV guide every", listOf(6, 12, 24, 48).map { "$it" to "$it hours" }, "${s.epgRefreshHours}") { v -> vm.store.update { it.copy(epgRefreshHours = v.toInt()) } })
        }
        SettingRow("Update playlists every", "${s.playlistRefreshHours} h") {
            onChoice(ChoiceSpec("Update playlists every", listOf(6, 12, 24, 48, 168).map { "$it" to if (it == 168) "1 week" else "$it hours" }, "${s.playlistRefreshHours}") { v -> vm.store.update { it.copy(playlistRefreshHours = v.toInt()) } })
        }
        SettingRow("Keep past programmes", if (s.epgPastDays == 0) "None" else "${s.epgPastDays} days", "More days are useful for channels with an archive") {
            onChoice(ChoiceSpec("Keep past programmes", listOf(0, 1, 2, 3, 7).map { "$it" to if (it == 0) "None" else if (it == 1) "1 day" else "$it days" }, "${s.epgPastDays}") { v -> vm.store.update { it.copy(epgPastDays = v.toInt()) } })
        }
        SettingRow("Update TV guide now") {
            scope.launch { for (p in vm.repo.db.playlists().all()) vm.repo.syncEpg(p.id) }
            vm.toast("Updating TV guide…")
        }
        SettingRow("Clear TV guide") {
            scope.launch { vm.repo.clearEpg() }
            vm.toast("TV guide cleared")
        }
        Text("The guide address of each playlist is set when you edit the playlist. Xtream playlists load their guide automatically.", Modifier.padding(14.dp), color = Orbit.colors.textMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ParentalSection(vm: AppViewModel, s: AppSettings) {
    var setPin by remember { mutableStateOf(false) }
    var verify by remember { mutableStateOf<(() -> Unit)?>(null) }
    var keywords by remember { mutableStateOf(false) }
    val unlocked by vm.adultUnlocked.collectAsState()
    fun guarded(action: () -> Unit) {
        if (s.parentalPin.isBlank()) action() else verify = action
    }
    Column {
        SettingRow(if (s.parentalPin.isBlank()) "Set PIN" else "Change PIN", if (s.parentalPin.isBlank()) "Not set" else "Set") { guarded { setPin = true } }
        if (s.parentalPin.isNotBlank()) SettingRow("Remove PIN") { guarded { vm.store.update { it.copy(parentalPin = "") }; vm.toast("PIN removed") } }
        ToggleRow("Hide adult groups", s.hideAdult, "Groups whose name contains the words below") { v -> guarded { vm.store.update { it.copy(hideAdult = v) } } }
        SettingRow("Words that mark adult groups", s.adultKeywords) { guarded { keywords = true } }
        if (s.hideAdult) {
            if (unlocked) SettingRow("Hide adult content again") { vm.adultUnlocked.value = false }
            else SettingRow("Show adult content until the app closes") { guarded { vm.adultUnlocked.value = true } }
        }
    }
    if (setPin) TextInputDialog("New PIN (4–8 digits)", "", "PIN", KeyboardType.NumberPassword, password = true, onDismiss = { setPin = false }) { pin ->
        val clean = pin.filter { it.isDigit() }
        if (clean.length in 4..8) {
            vm.store.update { it.copy(parentalPin = clean) }
            vm.toast("PIN saved")
        } else vm.toast("The PIN must have 4 to 8 digits")
    }
    verify?.let { action ->
        PinDialog("Enter PIN", onDismiss = { verify = null }) { pin ->
            val ok = pin == s.parentalPin
            if (ok) action()
            ok
        }
    }
    if (keywords) TextInputDialog("Words (comma separated)", s.adultKeywords, "xxx, adult, 18+", onDismiss = { keywords = false }) { v -> vm.store.update { it.copy(adultKeywords = v) } }
}

@Composable
private fun BackupSection(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching {
                val text = vm.repo.exportBackup()
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }.onSuccess { vm.toast("Backup saved") }.onFailure { vm.toast("Backup failed: ${it.message}") }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                vm.repo.importBackup(text)
            }.onSuccess { n ->
                vm.activePlaylistId.value = vm.store.activePlaylistId
                vm.hiddenGroups.value = vm.store.hiddenGroups
                vm.hiddenChannels.value = vm.store.hiddenChannels
                vm.toast("Restored $n playlists. Loading…")
                vm.syncNow()
            }.onFailure { vm.toast("Restore failed: ${it.message}") }
        }
    }
    Column {
        SettingRow("Save backup to a file", subtitle = "Playlists, favorites, watch progress, hidden groups and settings") {
            runCatching { export.launch("orbit-backup-${java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())}.json") }
                .onFailure { vm.toast("This device has no file manager for saving files") }
        }
        SettingRow("Restore from a backup file") {
            runCatching { import.launch(arrayOf("application/json", "text/plain", "*/*")) }
                .onFailure { vm.toast("This device has no file manager for opening files") }
        }
        Text("The backup contains your playlist passwords. Keep the file private.", Modifier.padding(14.dp), color = Orbit.colors.textMuted, style = MaterialTheme.typography.bodySmall)
    }
}
