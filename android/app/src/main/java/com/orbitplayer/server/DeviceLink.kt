package com.orbitplayer.server

import android.content.Context
import android.content.Intent
import android.os.Build
import com.orbitplayer.App
import com.orbitplayer.core.AppLog
import com.orbitplayer.core.JsonUtil.str
import com.orbitplayer.core.Net
import com.orbitplayer.diag.Diagnostics
import com.orbitplayer.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Collections

/**
 * Stalna WebSocket veza sa serverom: preko nje stižu komande iz admin panela u realnom vremenu.
 * Ako veza padne, ponovo se povezuje sa sve dužim razmakom (2 s do 60 s).
 */
class DeviceLink(private val app: App) {
    @Volatile private var socket: WebSocket? = null
    @Volatile var isConnected: Boolean = false
        private set
    @Volatile private var wanted = false
    private var reconnectJob: Job? = null
    private var backoff = 2_000L
    private val handled = Collections.synchronizedSet(LinkedHashSet<String>())

    fun connect() {
        if (!app.serverApi.isRegistered) return
        wanted = true
        if (socket != null) return
        val req = Request.Builder()
            .url(app.serverApi.wsUrl())
            .header("Authorization", "Device ${app.prefs.deviceAuth}")
            .build()
        socket = Net.api.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                backoff = 2_000L
                app.sync.setConnected(true)
                webSocket.send(JSONObject().put("type", "status").put("status", app.sync.statusJson()).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.str("type")) {
                    "command" -> msg.optJSONObject("command")?.let { handleCommand(it) }
                    "config_changed" -> app.appScope.launch(Dispatchers.IO) { runCatching { app.sync.refreshConfig() } }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(code)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(response?.code ?: 0)
        })
    }

    private fun dropped(code: Int) {
        socket = null
        isConnected = false
        app.sync.setConnected(false)
        if (code == 4401 || code == 401) {
            // token više ne važi — ServerSync će se ponovo registrovati
            return
        }
        if (!wanted) return
        reconnectJob?.cancel()
        reconnectJob = app.appScope.launch {
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
            connect()
        }
    }

    fun disconnect() {
        wanted = false
        reconnectJob?.cancel()
        socket?.close(1000, "bye")
        socket = null
        isConnected = false
    }

    fun sendStatus(status: JSONObject) {
        socket?.send(JSONObject().put("type", "status").put("status", status).toString())
    }

    private fun sendResult(id: String, ok: Boolean, result: Any?) {
        if (id.startsWith("00000000")) return
        val payload = JSONObject().put("type", "command_result").put("id", id).put("ok", ok).put("result", result ?: JSONObject.NULL)
        val sent = socket?.send(payload.toString()) == true
        if (!sent) app.appScope.launch(Dispatchers.IO) { runCatching { app.serverApi.commandResult(id, ok, result) } }
    }

    /** Izvršava komandu iz admin panela */
    fun handleCommand(cmd: JSONObject) {
        val id = cmd.str("id")
        if (id.isNotBlank() && !handled.add(id)) return // već izvršeno (stiglo i preko WS i preko heartbeat-a)
        while (handled.size > 200) handled.remove(handled.first())
        val payload = cmd.optJSONObject("payload") ?: JSONObject()
        val type = cmd.str("type")
        app.appScope.launch(Dispatchers.IO) {
            try {
                val result: Any? = execute(type, payload)
                sendResult(id, true, result)
            } catch (e: Exception) {
                AppLog.e("Komanda $type nije izvršena", e, "command")
                sendResult(id, false, JSONObject().put("error", e.message ?: "greška"))
            }
        }
    }

    private suspend fun execute(type: String, p: JSONObject): Any? = when (type) {
        "reload_config" -> {
            app.sync.refreshConfig(); null
        }
        "refresh_playlists" -> {
            app.repo.syncAll(force = true); null
        }
        "show_message" -> {
            app.sync.emit(UiEvent.Message(p.str("text"), p.optInt("durationSec", 15))); null
        }
        "play_channel" -> {
            app.sync.emit(UiEvent.PlayChannel(if (p.has("number")) p.optInt("number") else null, p.str("name").ifBlank { null })); null
        }
        "open_app" -> {
            app.sync.emit(UiEvent.OpenApp(p.str("package"))); null
        }
        "go_home" -> {
            bringToFront(app)
            app.sync.emit(UiEvent.GoHome); null
        }
        "restart_app" -> {
            delay(500)
            restartApp(app); null
        }
        "reboot" -> {
            if (!app.kiosk.reboot()) throw IllegalStateException("Restart uređaja radi samo u kiosk nivou 3 (Device Owner)")
            null
        }
        "clear_cache" -> {
            app.cacheDir.deleteRecursively()
            JSONObject().put("cleared", true)
        }
        "check_update" -> app.updater.checkAndInstall(userInitiated = false)
        "run_diagnostics" -> Diagnostics.run(app, includeSpeedTest = p.optBoolean("speedtest", true))
        "unpair" -> {
            app.sync.unpair(); null
        }
        "exit_kiosk" -> {
            app.kiosk.suspendFor(30 * 60_000L)
            JSONObject().put("suspendedMinutes", 30)
        }
        else -> throw IllegalArgumentException("Nepoznata komanda: $type")
    }

    companion object {
        fun bringToFront(context: Context) {
            val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            runCatching { context.startActivity(i) }
        }

        fun restartApp(context: Context) {
            val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(i)
            if (Build.VERSION.SDK_INT >= 21) Runtime.getRuntime().exit(0)
        }
    }
}
