package com.orbitplayer.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.orbitplayer.BuildConfig
import com.orbitplayer.core.JsonUtil.str
import com.orbitplayer.ui.MainViewModel
import com.orbitplayer.ui.Screen
import com.orbitplayer.ui.ScreenHeader
import com.orbitplayer.ui.components.ActionButton
import com.orbitplayer.ui.components.CodeDisplay
import com.orbitplayer.ui.components.NumericKeypad
import com.orbitplayer.ui.components.QrCode
import com.orbitplayer.ui.theme.LocalIsTv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Prvo pokretanje: nema nijedne liste */
@Composable
fun OnboardingScreen(vm: MainViewModel, onSkipWait: () -> Unit) {
    val link by vm.link.collectAsState()
    val tv = LocalIsTv.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (tv) 48.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Dobro došli u ${BuildConfig.APP_NAME}", style = MaterialTheme.typography.headlineMedium)
        if (BuildConfig.SERVER_ENABLED) {
            if (link.error.isNotBlank() && !link.registered) {
                Text("Nema veze sa serverom. Proverite internet. Listu možete uneti i ručno.", color = MaterialTheme.colorScheme.error)
            }
            PairingContent(vm)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("Imam kod za aktivaciju") { vm.open(Screen.Activation) }
                ActionButton("Unesi listu ručno") { vm.open(Screen.AddPlaylist) }
                ActionButton("Dijagnostika") { vm.open(Screen.Diagnostics) }
            }
        } else {
            Text("Aplikacija je plejer: dodajte listu koju ste dobili od svog provajdera (Xtream nalog ili M3U adresa).", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ActionButton("Dodaj listu", primary = true) { vm.open(Screen.AddPlaylist) }
        }
    }
    LaunchedEffect(Unit) { onSkipWait() }
}

/** Kod od 8 cifara i QR kod za povezivanje preko sajta (smer A) */
@Composable
fun PairingContent(vm: MainViewModel) {
    val tv = LocalIsTv.current
    val link by vm.link.collectAsState()
    var code by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(link.registered) {
        if (!link.registered) return@LaunchedEffect
        while (true) {
            try {
                val r = withContext(Dispatchers.IO) { vm.app.serverApi.pairingCode() }
                code = r.str("code")
                url = r.str("linkUrl")
                error = ""
            } catch (e: Exception) {
                error = e.message ?: "Kod nije dostupan"
            }
            delay(if (error.isBlank()) 13 * 60_000L else 15_000L) // kod važi 15 minuta
        }
    }
    Box(Modifier.fillMaxWidth().background(Color(0xFF1C2433), RoundedCornerShape(18.dp)).padding(24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Povežite listu preko telefona", style = MaterialTheme.typography.titleLarge, color = Color.White)
                val host = BuildConfig.SERVER_URL.removePrefix("https://").removePrefix("http://").trimEnd('/')
                Text("Na telefonu otvorite $host/link (ili skenirajte QR kod) i unesite ovaj kod:", color = Color(0xFFC9D2E3))
                when {
                    code.isNotBlank() -> CodeDisplay(code)
                    error.isNotBlank() -> Text(error, color = MaterialTheme.colorScheme.error)
                    else -> CircularProgressIndicator()
                }
                Text("Lista se pojavljuje na televizoru čim je sačuvate na telefonu.", color = Color(0xFF9AA9C7), style = MaterialTheme.typography.bodySmall)
            }
            if (url.isNotBlank() && (tv || androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp > 500)) {
                QrCode(url, if (tv) 180.dp else 140.dp)
            }
        }
    }
}

@Composable
fun PairingScreen(vm: MainViewModel) {
    val playlists by vm.playlists.collectAsState()
    val start = remember { playlists.size }
    LaunchedEffect(playlists.size) { if (playlists.size > start) vm.back() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Dodaj listu preko telefona", vm)
        Box(Modifier.padding(horizontal = 24.dp)) { PairingContent(vm) }
    }
}

/** Unos koda za aktivaciju koji je poslao admin (smer B) */
@Composable
fun ActivationScreen(vm: MainViewModel) {
    val tv = LocalIsTv.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }

    fun submit() {
        if (code.length != 8 || busy) return
        busy = true
        error = ""
        scope.launch {
            try {
                val cfg = withContext(Dispatchers.IO) { vm.app.serverApi.activate(code) }
                vm.app.sync.applyConfig(cfg)
                vm.root(Screen.Home)
            } catch (e: Exception) {
                error = e.message ?: "Aktivacija nije uspela"
                code = ""
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Kod za aktivaciju", vm)
        Column(Modifier.padding(horizontal = if (tv) 32.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Unesite kod od 8 cifara koji ste dobili od prodavca ili podrške.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tv) {
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumericKeypad(
                        onDigit = { d -> if (code.length < 8) code += d; if (code.length == 8) submit() },
                        onDelete = { code = code.dropLast(1) },
                        firstFocus = first,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CodeDisplay(code)
                        if (busy) CircularProgressIndicator()
                        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                    }
                }
                LaunchedEffect(Unit) { runCatching { delay(100); first.requestFocus() } }
            } else {
                CodeDisplay(code)
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { it.isDigit() }.take(8); if (code.length == 8) submit() },
                    label = { Text("Kod") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
                if (busy) CircularProgressIndicator()
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                ActionButton("Aktiviraj", primary = true, enabled = code.length == 8 && !busy) { submit() }
            }
        }
    }
}

/** Servisni PIN za izlaz iz kiosk moda (Meni 5 puta) */
@Composable
fun PinDialog(vm: MainViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!ok) {
                Text("Servisni PIN", style = MaterialTheme.typography.titleLarge)
                Text("•".repeat(pin.length).ifBlank { " " }, style = MaterialTheme.typography.headlineMedium)
                NumericKeypad(
                    onDigit = { d ->
                        pin = (pin + d).take(8)
                        if (pin.length >= 4 && vm.app.kiosk.verifyPin(pin)) ok = true
                    },
                    onDelete = { pin = pin.dropLast(1) },
                    firstFocus = first,
                )
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                ActionButton("Zatvori") { onClose() }
                LaunchedEffect(Unit) { runCatching { delay(100); first.requestFocus() } }
            } else {
                Text("Servisni pristup", style = MaterialTheme.typography.titleLarge)
                ActionButton("Isključi kiosk na 30 minuta", primary = true) {
                    vm.app.kiosk.suspendFor(30 * 60_000L)
                    (context as? Activity)?.let { vm.app.kiosk.leaveKiosk(it) }
                    onClose()
                }
                ActionButton("Otvori podešavanja uređaja") {
                    vm.app.kiosk.suspendFor(30 * 60_000L)
                    (context as? Activity)?.let { vm.app.kiosk.leaveKiosk(it); vm.app.kiosk.openSystemSettings(it) }
                    onClose()
                }
                if (vm.app.kiosk.isDeviceOwner()) {
                    ActionButton("Ukloni Device Owner (trajno)") {
                        (context as? Activity)?.let { vm.app.kiosk.leaveKiosk(it) }
                        vm.app.kiosk.removeDeviceOwner()
                        onClose()
                    }
                }
                ActionButton("Zatvori") { onClose() }
            }
        }
    }
    Spacer(Modifier.height(0.dp).width(0.dp))
}
