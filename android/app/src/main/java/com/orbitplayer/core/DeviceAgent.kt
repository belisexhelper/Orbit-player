package com.orbitplayer.core

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Keeps the device talking to the management server: registers once, pulls its config and applies it
 * (launcher tiles from the panel), holds a live WebSocket for instant changes/commands, and sends a
 * heartbeat so the panel shows it online. All overt — the device shows its ID on screen.
 */
object DeviceAgent {
    @Volatile private var started = false
    @Volatile private var ws: WebSocket? = null
    @Volatile private var scope: CoroutineScope? = null

    /** What the device is doing now (shown in the panel): launcher | app | player | home */
    @Volatile var foreground: String = "home"
        private set

    fun start(context: Context, appScope: CoroutineScope) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope = appScope
        appScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    if (!ServerClient.ensureRegistered(app)) { delay(30_000); continue }
                    ServerClient.postInfo(app)
                    applyConfig(app, ServerClient.getConfig(app))
                    // If the server forgot this device (e.g. its data was reset), the token was
                    // dropped on a 401 above — fall back to the outer loop to register again.
                    if (!ServerClient.isRegistered(app)) continue
                    connectWs(app)
                    while (isActive && ServerClient.isRegistered(app)) {
                        delay(60_000)
                        ServerClient.heartbeat(app, statusJson(app))
                        if (!ServerClient.isRegistered(app)) break // token dropped: re-register
                        if (ws == null) connectWs(app) // socket dropped: bring it back
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // network hiccup: wait and try again
                }
                delay(15_000)
            }
        }
    }

    private fun statusJson(c: Context): JSONObject = JSONObject()
        .put("code", DeviceInfo.deviceId(c))
        .put("foreground", foreground)
        .put("home", Launcher.isDefaultHome(c))

    private fun connectWs(app: Context) {
        val url = ServerClient.wsUrl(app) ?: return
        ws?.cancel()
        ws = Net.api.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "status").put("status", statusJson(app)).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("type")) {
                    "config_changed" -> scope?.launch(Dispatchers.IO) { applyConfig(app, ServerClient.getConfig(app)) }
                    "command" -> handleCommand(app, msg, webSocket)
                    "ping" -> webSocket.send(JSONObject().put("type", "pong").toString())
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ws = null }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ws = null }
        })
    }

    private fun handleCommand(app: Context, msg: JSONObject, webSocket: WebSocket) {
        val command = msg.optJSONObject("command") ?: msg
        val type = command.optString("type").ifBlank { command.optString("cmd") }
        when (type) {
            "refresh_config", "config_changed" ->
                scope?.launch(Dispatchers.IO) { applyConfig(app, ServerClient.getConfig(app)) }
            // other commands (launch, reboot, update…) arrive in a later phase
        }
        val id = command.optString("id")
        if (id.isNotBlank()) webSocket.send(JSONObject().put("type", "command_result").put("id", id).put("ok", true).toString())
    }

    private fun applyConfig(c: Context, cfg: JSONObject?) {
        cfg ?: return
        Launcher.applyServerConfig(c, cfg.optJSONObject("launcher"))
    }

    /** Called by the UI as the user moves around, so the panel shows what's on screen. */
    fun reportForeground(where: String) {
        foreground = where
        runCatching { ws?.send(JSONObject().put("type", "status").put("status", JSONObject().put("foreground", where)).toString()) }
    }
}
