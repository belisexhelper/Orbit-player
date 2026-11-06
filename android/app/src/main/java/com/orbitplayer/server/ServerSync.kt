package com.orbitplayer.server

import com.orbitplayer.App
import com.orbitplayer.BuildConfig
import com.orbitplayer.core.AppLog
import com.orbitplayer.core.DeviceInfo
import com.orbitplayer.core.JsonUtil.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Stanje veze sa serverom, za prikaz u aplikaciji */
data class LinkState(
    val registered: Boolean = false,
    val connected: Boolean = false,
    val blocked: Boolean = false,
    val profileName: String = "",
    val profileExpiresAt: String = "",
    val expired: Boolean = false,
    val deviceName: String = "",
    val error: String = "",
    val configLoaded: Boolean = false,
)

/** Događaji koje server šalje, a prikazuje ih korisnički interfejs */
sealed class UiEvent {
    data class Message(val text: String, val durationSec: Int) : UiEvent()
    data class PlayChannel(val number: Int?, val name: String?) : UiEvent()
    data class OpenApp(val pkg: String) : UiEvent()
    data object GoHome : UiEvent()
    data object Unpaired : UiEvent()
    data class Toast(val text: String) : UiEvent()
}

/**
 * Povezuje uređaj sa serverom: registracija, preuzimanje konfiguracije, javljanje stanja,
 * slanje dnevnika i provera novih verzija.
 */
class ServerSync(private val app: App) {
    private val api get() = app.serverApi
    private val prefs get() = app.prefs

    private val _state = MutableStateFlow(LinkState(registered = app.prefs.deviceAuth.isNotBlank()))
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val configLock = Mutex()
    private var loop: Job? = null
    private var lastConfigVersion = ""

    /** Šta korisnik trenutno radi — šalje se u panel („Stanje uživo”) */
    @Volatile var screen: String = "home"
    @Volatile var nowPlaying: String = ""

    fun emit(e: UiEvent) {
        _events.tryEmit(e)
    }

    fun start() {
        // učitaj poslednju poznatu konfiguraciju odmah, da aplikacija radi i bez interneta
        app.repo.serverConfigJson()?.let { applyStateFrom(it) }
        if (loop?.isActive == true) return
        loop = app.appScope.launch(Dispatchers.IO) {
            var backoff = 5_000L
            while (isActive) {
                try {
                    ensureRegistered()
                    refreshConfig()
                    app.link.connect()
                    backoff = 5_000L
                    break
                } catch (e: ServerException) {
                    _state.update { it.copy(error = e.message ?: "") }
                    if (e.status == 401) prefs.deviceAuth = ""
                } catch (e: Exception) {
                    _state.update { it.copy(error = "Server nije dostupan") }
                    AppLog.d("server: ${e.message}")
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(120_000L)
            }
            // posle uspešnog povezivanja: periodično javljanje i održavanje
            var tick = 0L
            while (isActive) {
                val every = app.repo.settings.value.heartbeatSec * 1000L
                delay(every)
                tick += every
                runCatching { heartbeat() }.onFailure { handleFailure(it) }
                runCatching { uploadLogs() }
                if (!app.link.isConnected) app.link.connect()
                if (tick >= 6 * 3600_000L) {
                    tick = 0
                    runCatching { app.updater.checkAndInstall(userInitiated = false) }
                    runCatching { app.repo.syncAll() }
                }
            }
        }
    }

    private suspend fun ensureRegistered() = withContext(Dispatchers.IO) {
        if (!api.isRegistered) {
            api.register()
            AppLog.i("Uređaj registrovan na serveru")
        } else {
            runCatching { api.sendInfo() }.onFailure { if (it is ServerException && it.status == 401) { prefs.deviceAuth = ""; api.register() } }
        }
        _state.update { it.copy(registered = true, error = "") }
    }

    private fun handleFailure(e: Throwable) {
        if (e is ServerException && e.status == 401) {
            // uređaj je obrisan u panelu — registruj se ponovo
            prefs.deviceAuth = ""
            app.appScope.launch(Dispatchers.IO) { runCatching { ensureRegistered(); refreshConfig() } }
        }
    }

    /** Preuzima konfiguraciju sa servera i primenjuje je (liste, podešavanja, launcher). */
    suspend fun refreshConfig(): JSONObject = withContext(Dispatchers.IO) {
        configLock.withLock {
            val cfg = api.config()
            applyConfig(cfg)
            cfg
        }
    }

    /** Primena konfiguracije (koristi se i posle aktivacije kodom) */
    suspend fun applyConfig(cfg: JSONObject) = withContext(Dispatchers.IO) {
        val version = cfg.str("configVersion")
        prefs.serverConfig = cfg.toString()
        app.repo.refreshSettings()
        applyStateFrom(cfg)
        if (version == lastConfigVersion) return@withContext
        lastConfigVersion = version
        val changed = app.repo.applyServerPlaylists(cfg.optJSONArray("playlists") ?: org.json.JSONArray())
        app.kiosk.applyConfig(cfg.optJSONObject("launcher"))
        for (p in changed) {
            app.repo.syncPlaylist(p.id)
            app.repo.syncEpg(p.id)
        }
    }

    private fun applyStateFrom(cfg: JSONObject) {
        val profile = cfg.optJSONObject("profile")
        val device = cfg.optJSONObject("device")
        _state.update {
            it.copy(
                configLoaded = true,
                blocked = device?.optBoolean("blocked") == true,
                deviceName = device?.str("name") ?: "",
                profileName = profile?.str("name") ?: "",
                profileExpiresAt = profile?.str("expiresAt") ?: "",
                expired = profile?.optBoolean("expired") == true,
            )
        }
    }

    fun setConnected(connected: Boolean) = _state.update { it.copy(connected = connected) }

    fun statusJson(): JSONObject {
        val (totalRam, _) = runCatching { DeviceInfo.memory(app) }.getOrDefault(0L to 0L)
        return JSONObject()
            .put("screen", screen)
            .put("nowPlaying", nowPlaying)
            .put("launcherLevel", app.kiosk.levelName())
            .put("network", DeviceInfo.networkType(app))
            .put("freeStorageMb", DeviceInfo.freeStorageMb(app))
            .put("ramMb", totalRam)
            .put("appVersion", BuildConfig.VERSION_NAME)
    }

    suspend fun heartbeat() = withContext(Dispatchers.IO) {
        val status = statusJson()
        if (app.link.isConnected) {
            app.link.sendStatus(status)
        } else {
            val r = api.heartbeat(status)
            val cmds = r.optJSONArray("commands")
            if (cmds != null) for (i in 0 until cmds.length()) cmds.optJSONObject(i)?.let { app.link.handleCommand(it) }
        }
    }

    suspend fun uploadLogs() = withContext(Dispatchers.IO) {
        val logs = AppLog.drain()
        if (logs.isEmpty()) return@withContext
        try {
            api.sendLogs(logs)
        } catch (e: Exception) {
            AppLog.requeue(logs)
        }
    }

    /** Odjava: briše liste sa servera i lokalne podatke veze, pa se registruje ponovo */
    suspend fun unpair() = withContext(Dispatchers.IO) {
        app.link.disconnect()
        app.repo.clearAllServerData()
        app.kiosk.applyConfig(null)
        prefs.serverConfig = ""
        prefs.deviceAuth = ""
        lastConfigVersion = ""
        app.repo.refreshSettings()
        _state.value = LinkState()
        emit(UiEvent.Unpaired)
        loop?.cancel()
        loop = null
        start()
    }
}
