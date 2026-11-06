package com.orbitplayer.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.orbitplayer.App
import com.orbitplayer.BuildConfig
import com.orbitplayer.core.DeviceInfo
import com.orbitplayer.player.PlayItem
import com.orbitplayer.player.PlaySession
import com.orbitplayer.server.UiEvent
import com.orbitplayer.ui.theme.LocalIsTv
import com.orbitplayer.ui.theme.OrbitTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val app get() = application as App
    private var isTv = false

    /** Poruka iz admin panela */
    var serverMessage by mutableStateOf<String?>(null)
    /** Dijalog za servisni PIN (izlaz iz kiosk moda) */
    var pinDialog by mutableStateOf(false)

    private var menuPresses = 0
    private var menuFirstAt = 0L
    private var bootHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTv = DeviceInfo.isTv(this)
        if (!isTv) enableEdgeToEdge()
        vm.startup()
        setContent {
            val s by vm.settings.collectAsState()
            CompositionLocalProvider(LocalIsTv provides isTv) {
                OrbitTheme(light = s.theme == "light", accentHex = vm.launcher.collectAsState().value.accent.ifBlank { s.accent }, isTv = isTv) {
                    AppRoot(vm, this)
                }
            }
        }
        lifecycleScope.launch {
            app.sync.events.collect { e -> handleEvent(e) }
        }
        handleBootTarget(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // pritisak na Home dok je aplikacija launcher: vrati na početni ekran
        if (intent.hasCategory(Intent.CATEGORY_HOME)) vm.root(Screen.Home)
    }

    override fun onResume() {
        super.onResume()
        app.sync.screen = vm.current.javaClass.simpleName.lowercase()
        app.kiosk.isSuspended()
        app.kiosk.applyComponents()
        app.kiosk.enterKiosk(this)
        lifecycleScope.launch { app.downloads.refresh() }
    }

    private fun handleEvent(e: UiEvent) {
        when (e) {
            is UiEvent.Message -> {
                serverMessage = e.text
                if (e.durationSec > 0) lifecycleScope.launch {
                    kotlinx.coroutines.delay(e.durationSec * 1000L)
                    if (serverMessage == e.text) serverMessage = null
                }
            }
            is UiEvent.PlayChannel -> lifecycleScope.launch {
                val pid = app.prefs.activePlaylistId
                val ch = when {
                    e.number != null -> app.db.content().channelByNumber(pid, e.number)
                    e.name != null -> app.db.content().channelByName(pid, "%${e.name}%")
                    else -> null
                }
                if (ch != null) vm.playChannel(vm.live.value.channels.ifEmpty { listOf(ch) }, ch)
            }
            is UiEvent.OpenApp -> openApp(e.pkg)
            is UiEvent.GoHome -> vm.root(Screen.Home)
            is UiEvent.Unpaired -> vm.root(Screen.Home)
            is UiEvent.Toast -> Toast.makeText(this, e.text, Toast.LENGTH_LONG).show()
        }
    }

    fun openApp(pkg: String) {
        val pm = packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        if (i == null) {
            Toast.makeText(this, "Aplikacija nije instalirana", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Pri paljenju ili prvom otvaranju launchera: otvori ono što je admin podesio */
    private fun handleBootTarget(intent: Intent?) {
        if (bootHandled) return
        bootHandled = true
        val fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true
        val asHome = intent?.hasCategory(Intent.CATEGORY_HOME) == true
        val launcherTarget = if (app.kiosk.launcherActive()) app.kiosk.config.value.bootTarget else ""
        val target = when {
            launcherTarget.isNotBlank() && (fromBoot || asHome) -> launcherTarget
            vm.settings.value.startScreen == "live" -> "live"
            vm.settings.value.startScreen == "last_channel" -> "last_channel"
            else -> ""
        }
        when (target) {
            "" -> Unit
            "live" -> vm.open(Screen.Live)
            "last_channel" -> lifecycleScope.launch {
                val ch = app.db.content().channel(app.prefs.lastChannelKey)
                if (ch != null) {
                    val list = app.db.content().channels(ch.playlistId)
                    vm.playChannel(list, ch)
                } else vm.open(Screen.Live)
            }
            else -> openApp(target)
        }
    }

    fun playLastChannel() = lifecycleScope.launch {
        val ch = app.db.content().channel(app.prefs.lastChannelKey) ?: return@launch
        PlaySession.start(this@MainActivity, listOf(PlayItem(kind = "live", title = ch.name, channel = ch, poster = ch.logo, playlistId = ch.playlistId)), 0)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Servisni pristup u kiosk modu: taster Meni 5 puta u 3 sekunde
        if (BuildConfig.SERVER_ENABLED && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU && app.kiosk.launcherActive()) {
            val now = System.currentTimeMillis()
            if (now - menuFirstAt > 3000) {
                menuFirstAt = now
                menuPresses = 0
            }
            menuPresses++
            if (menuPresses >= 5) {
                menuPresses = 0
                pinDialog = true
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pinDialog) {
            pinDialog = false; return
        }
        if (serverMessage != null) {
            serverMessage = null; return
        }
        if (vm.back()) return
        // kao launcher, „nazad” sa početnog ekrana ne izlazi iz aplikacije
        if (app.kiosk.launcherActive()) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_FROM_BOOT = "from_boot"
    }
}
