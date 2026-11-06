package com.orbitplayer.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.orbitplayer.BuildConfig
import com.orbitplayer.kiosk.InstalledApp
import com.orbitplayer.ui.MainActivity
import com.orbitplayer.ui.MainViewModel
import com.orbitplayer.ui.Screen
import com.orbitplayer.ui.ScreenHeader
import com.orbitplayer.ui.components.ActionButton
import com.orbitplayer.ui.components.ListRow
import com.orbitplayer.ui.components.SectionTitle
import com.orbitplayer.ui.components.Tile
import com.orbitplayer.ui.theme.LocalIsTv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
private fun Value(text: String) = Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)

private fun <T> cycle(list: List<T>, cur: T): T = list[(list.indexOf(cur) + 1).mod(list.size)]

@Composable
fun SettingsScreen(vm: MainViewModel, activity: MainActivity) {
    val s by vm.settings.collectAsState()
    val launcher by vm.launcher.collectAsState()
    val adultUnlocked by vm.adultUnlocked.collectAsState()
    val tv = LocalIsTv.current
    val context = LocalContext.current
    var askPin by remember { mutableStateOf(false) }
    val set: (String, String, Any?) -> Unit = { g, k, v -> vm.app.repo.setLocalSetting(g, k, v) }
    val launcherEnabled = BuildConfig.SERVER_ENABLED && launcher.enabled
    var liveFormat by remember { mutableStateOf(vm.app.prefs.liveFormat) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = if (tv) 24.dp else 8.dp, vertical = 8.dp)) {
        item { ScreenHeader("Podešavanja", vm) }

        item { SectionTitle("Liste", Modifier.padding(horizontal = 14.dp)) }
        item { ListRow("Moje liste", subtitle = "Pregled, osvežavanje i brisanje lista") { vm.open(Screen.Playlists) } }
        if (BuildConfig.SERVER_ENABLED) {
            item { ListRow("Dodaj listu preko telefona", subtitle = "Kod od 8 cifara i QR kod") { vm.open(Screen.Pairing) } }
            item { ListRow("Imam kod za aktivaciju", subtitle = "Kod koji ste dobili od prodavca") { vm.open(Screen.Activation) } }
        }
        item { ListRow("Dodaj listu ručno") { vm.open(Screen.AddPlaylist) } }
        item { ListRow("Osveži liste i TV vodič sada") { vm.syncNow(); Toast.makeText(context, "Osvežavanje je pokrenuto", Toast.LENGTH_SHORT).show() } }

        item { SectionTitle("Plejer", Modifier.padding(horizontal = 14.dp)) }
        item {
            ListRow("Plejer", trailing = { Value(if (s.playerEngine == "vlc") "VLC (spoljni)" else "Ugrađeni (ExoPlayer)") }) {
                set("player", "engine", if (s.playerEngine == "vlc") "exo" else "vlc")
            }
        }
        item {
            ListRow("Bafer", subtitle = "Veći bafer = manje zastajkivanja, sporije pokretanje", trailing = { Value("${s.bufferMs / 1000} s") }) {
                set("player", "bufferMs", cycle(listOf(5000, 10000, 15000, 30000, 60000), s.bufferMs.let { b -> listOf(5000, 10000, 15000, 30000, 60000).minByOrNull { kotlin.math.abs(it - b) }!! }))
            }
        }
        item { ListRow("Hardversko dekodiranje", subtitle = "Isključite ako slika zastajkuje ili je crna", trailing = { Value(if (s.hardwareDecoding) "uključeno" else "isključeno") }) { set("player", "hardwareDecoding", !s.hardwareDecoding) } }
        item { ListRow("Automatski ponovo poveži", trailing = { Value(if (s.autoReconnect) "da" else "ne") }) { set("player", "autoReconnect", !s.autoReconnect) } }
        item {
            val labels = mapOf("fit" to "Uklopi", "fill" to "Popuni", "zoom" to "Uvećaj")
            ListRow("Odnos slike", trailing = { Value(labels[s.aspect] ?: s.aspect) }) { set("player", "aspect", cycle(listOf("fit", "fill", "zoom"), s.aspect.takeIf { it in labels } ?: "fit")) }
        }
        item {
            ListRow("Format kanala (Xtream)", subtitle = "Ako kanali ne rade, probajte drugi format", trailing = { Value(if (liveFormat == "m3u8") "HLS (m3u8)" else "MPEG-TS") }) {
                liveFormat = if (liveFormat == "m3u8") "ts" else "m3u8"
                vm.app.prefs.liveFormat = liveFormat
            }
        }

        item { SectionTitle("Prevodi", Modifier.padding(horizontal = 14.dp)) }
        item { ListRow("Prevodi uključeni automatski", trailing = { Value(if (s.subtitlesByDefault) "da" else "ne") }) { set("subtitles", "enabledByDefault", !s.subtitlesByDefault) } }
        item {
            val langs = listOf("sr", "hr", "bs", "en", "de")
            ListRow("Željeni jezik prevoda", trailing = { Value(s.subtitleLanguage) }) { set("subtitles", "preferredLanguage", cycle(langs, s.subtitleLanguage.takeIf { it in langs } ?: "sr")) }
        }
        item {
            val sizes = mapOf("small" to "mala", "normal" to "normalna", "large" to "velika")
            ListRow("Veličina prevoda", trailing = { Value(sizes[s.subtitleSize] ?: s.subtitleSize) }) { set("subtitles", "size", cycle(listOf("small", "normal", "large"), s.subtitleSize.takeIf { it in sizes } ?: "normal")) }
        }
        item { ListRow("Tamna pozadina iza prevoda", trailing = { Value(if (s.subtitleBackground) "da" else "ne") }) { set("subtitles", "background", !s.subtitleBackground) } }

        item { SectionTitle("Izgled i zaštita", Modifier.padding(horizontal = 14.dp)) }
        if (!tv) item { ListRow("Tema", trailing = { Value(if (s.theme == "light") "svetla" else "tamna") }) { set("ui", "theme", if (s.theme == "light") "dark" else "light") } }
        item {
            ListRow(if (adultUnlocked) "Sakrij grupe za odrasle" else "Prikaži grupe za odrasle", subtitle = if (s.parentalPin.isNotBlank()) "Zaštićeno PIN-om" else "") {
                if (adultUnlocked) vm.adultUnlocked.value = false
                else if (s.parentalPin.isNotBlank()) askPin = true
                else vm.adultUnlocked.value = true
            }
        }
        item { ListRow("Prikaži sakrivene grupe", subtitle = "Grupe sakrivene dugim pritiskom u listi kanala") { vm.showAllGroups() } }

        if (launcherEnabled) {
            item { SectionTitle("Početni ekran (launcher)", Modifier.padding(horizontal = 14.dp)) }
            item { ListRow("Stanje", subtitle = launcherStatusText(vm.app.kiosk.levelName())) { } }
            if (!launcher.locked) {
                item {
                    val off = vm.app.prefs.launcherUserDisabled
                    ListRow("Koristi aplikaciju kao početni ekran", trailing = { Value(if (off) "ne" else "da") }) { vm.app.kiosk.setUserDisabled(!off) }
                }
            }
            if (vm.app.kiosk.launcherActive() && !vm.app.kiosk.isDefaultHome()) {
                item {
                    ListRow("Postavi kao podrazumevani početni ekran", subtitle = "Na Google TV-u ovo često zahteva pripremu preko ADB-a (uputstvo kod prodavca)") {
                        if (!vm.app.kiosk.openHomeChooser(activity)) Toast.makeText(context, "Uređaj ne dozvoljava promenu početnog ekrana iz aplikacije", Toast.LENGTH_LONG).show()
                    }
                }
            }
            if (!launcher.locked) item { ListRow("Sve aplikacije") { vm.open(Screen.Apps) } }
        }

        item { SectionTitle("Pomoć", Modifier.padding(horizontal = 14.dp)) }
        if (s.showSpeedtest) {
            item { ListRow("Test brzine interneta") { vm.open(Screen.SpeedTest) } }
            item { ListRow("Dijagnostika uređaja i veze") { vm.open(Screen.Diagnostics) } }
        }
        item { ListRow("O aplikaciji i ažuriranje", subtitle = "Verzija ${BuildConfig.VERSION_NAME}") { vm.open(Screen.About) } }
        if (s.supportContact.isNotBlank()) item { ListRow("Podrška", subtitle = s.supportContact) { } }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (askPin) {
        var pin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askPin = false },
            title = { Text("Roditeljski PIN") },
            text = {
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pin == s.parentalPin) {
                        vm.adultUnlocked.value = true
                        askPin = false
                    } else Toast.makeText(context, "Pogrešan PIN", Toast.LENGTH_SHORT).show()
                }) { Text("Otključaj") }
            },
            dismissButton = { TextButton(onClick = { askPin = false }) { Text("Odustani") } },
        )
    }
}

fun launcherStatusText(level: String) = when (level) {
    "device_owner" -> "Pravi kiosk (nivo 3): uređaj je zaključan"
    "default_home" -> "Aplikacija je početni ekran uređaja"
    "not_default" -> "Uključeno, ali aplikacija još nije podrazumevani početni ekran"
    else -> "Isključeno"
}

@Composable
fun PlaylistsScreen(vm: MainViewModel) {
    val playlists by vm.playlists.collectAsState()
    val status by vm.syncStatus.collectAsState()
    val active by vm.activePlaylistId.collectAsState()
    val scope = rememberCoroutineScope()
    var remove by remember { mutableStateOf<com.orbitplayer.data.db.PlaylistEntity?>(null) }
    val f = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
        item {
            ScreenHeader("Moje liste", vm) { ActionButton("Dodaj") { vm.open(Screen.AddPlaylist) } }
        }
        items(playlists, key = { it.id }) { p ->
            val st = status[p.id]
            Column {
                ListRow(
                    title = p.name,
                    selected = p.id == active,
                    subtitle = when {
                        st?.running == true -> st.step
                        st?.error?.isNotBlank() == true -> st.error
                        p.lastError.isNotBlank() -> "Greška: ${p.lastError}"
                        p.lastSyncAt > 0 -> "${if (p.type == "xtream") "Xtream" else "M3U"} · osveženo ${f.format(Date(p.lastSyncAt))}${if (p.source == "server") " · sa servera" else ""}"
                        else -> "Još nije učitano"
                    },
                ) { vm.setActivePlaylist(p.id) }
                if (st?.running == true) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 14.dp))
                Row(Modifier.padding(start = 14.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.id != active) ActionButton("Koristi ovu listu") { vm.setActivePlaylist(p.id) }
                    ActionButton("Osveži") { scope.launch { vm.app.repo.syncPlaylist(p.id); vm.app.repo.syncEpg(p.id) } }
                    if (p.source == "local") ActionButton("Obriši") { remove = p }
                }
            }
        }
    }
    remove?.let { p ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("Obrisati listu?") },
            text = { Text("Lista „${p.name}” biće uklonjena sa ovog uređaja.") },
            confirmButton = { TextButton(onClick = { remove = null; scope.launch { vm.app.repo.removePlaylist(p.id); vm.activePlaylistId.value = vm.app.prefs.activePlaylistId } }) { Text("Obriši") } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Odustani") } },
        )
    }
}

@Composable
fun AddPlaylistScreen(vm: MainViewModel) {
    val tv = LocalIsTv.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf("xtream") }
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Nova lista", vm)
        Column(Modifier.padding(horizontal = if (tv) 32.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (BuildConfig.SERVER_ENABLED && tv) {
                Text("Kucanje daljinskim je sporo — lakše je preko telefona.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                ActionButton("Dodaj preko telefona (kod)", primary = true) { vm.open(Screen.Pairing) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Xtream Codes", primary = type == "xtream") { type = "xtream" }
                ActionButton("M3U link", primary = type == "m3u") { type = "m3u" }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Naziv liste") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (type == "xtream") {
                OutlinedTextField(server, { server = it }, label = { Text("Adresa servera (http://server:port)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(user, { user = it }, label = { Text("Korisničko ime") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text("Lozinka") }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
            } else {
                OutlinedTextField(url, { url = it }, label = { Text("M3U adresa") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            }
            OutlinedTextField(epg, { epg = it }, label = { Text("EPG adresa (nije obavezno)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            ActionButton(if (busy) "Proveravam…" else "Sačuvaj i učitaj", primary = true, enabled = !busy) {
                busy = true
                error = ""
                scope.launch {
                    try {
                        val p = vm.app.repo.addLocalPlaylist(name, type, server, user, pass, url, epg)
                        vm.setActivePlaylist(p.id)
                        vm.root(Screen.Home)
                        vm.app.appScope.launch { vm.app.repo.syncPlaylist(p.id); vm.app.repo.syncEpg(p.id) }
                    } catch (e: Exception) {
                        error = e.message ?: "Lista nije sačuvana"
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }
}

@Composable
fun AppsScreen(vm: MainViewModel, activity: MainActivity) {
    val tv = LocalIsTv.current
    val apps by produceState(emptyList<InstalledApp>()) { value = withContext(Dispatchers.IO) { vm.app.kiosk.allLaunchableApps() } }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Aplikacije", vm)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (tv) 170.dp else 120.dp),
            contentPadding = PaddingValues(horizontal = if (tv) 32.dp else 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(apps, key = { it.pkg }) { a -> Tile(a.label, drawable = a.icon) { runCatching { activity.startActivity(a.launch) } } }
        }
    }
}

@Composable
fun AboutScreen(vm: MainViewModel) {
    val update by vm.update.collectAsState()
    val link by vm.link.collectAsState()
    val scope = rememberCoroutineScope()
    val tv = LocalIsTv.current
    var confirmUnpair by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("O aplikaciji", vm)
        Column(Modifier.padding(horizontal = if (tv) 32.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${BuildConfig.APP_NAME} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${if (BuildConfig.SERVER_ENABLED) "Pro" else "Lite"}", style = MaterialTheme.typography.titleMedium)
            Text("Aplikacija je medijski plejer i ne sadrži kanale ni sadržaj. Liste dobijate od svog provajdera.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (BuildConfig.SERVER_ENABLED) {
                Spacer(Modifier.height(8.dp))
                SectionTitle("Uređaj")
                Text("ID uređaja: ${vm.app.prefs.deviceId.take(8).ifBlank { "nije registrovan" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Veza sa panelom: ${if (link.connected) "povezano" else "nije povezano"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (link.profileName.isNotBlank()) Text("Nalog: ${link.profileName}${if (link.expired) " (isteklo)" else ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SectionTitle("Ažuriranje")
                if (update.available) {
                    Text("Dostupna je verzija ${update.versionName}", style = MaterialTheme.typography.titleMedium)
                    if (update.changelog.isNotBlank()) Text(update.changelog, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (update.message.isNotBlank()) Text(update.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (update.progress in 0f..1f) LinearProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (update.available) ActionButton("Instaliraj", primary = true) { scope.launch { vm.app.updater.install() } }
                    else ActionButton(if (update.checking) "Proveravam…" else "Proveri ažuriranje") { scope.launch { vm.app.updater.check() } }
                    if (update.needsPermission) ActionButton("Dozvoli instalaciju") { vm.app.updater.openInstallPermissionSettings() }
                }
                Spacer(Modifier.height(16.dp))
                ActionButton("Odjavi uređaj i obriši liste") { confirmUnpair = true }
            }
        }
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Odjaviti uređaj?") },
            text = { Text("Liste sa servera biće obrisane sa ovog uređaja. Moći ćete ponovo da ga povežete kodom.") },
            confirmButton = { TextButton(onClick = { confirmUnpair = false; scope.launch { vm.app.sync.unpair() } }) { Text("Odjavi") } },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Odustani") } },
        )
    }
    Spacer(Modifier.width(0.dp))
}
