package com.orbitplayer.core

import android.content.Context
import com.orbitplayer.App
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
    @Volatile private var appContext: Context? = null

    /** What the device is doing now (shown in the panel): launcher | app | player | home */
    @Volatile var foreground: String = "home"
        private set

    fun start(context: Context, appScope: CoroutineScope) {
        if (started) return
        started = true
        val app = context.applicationContext
        appContext = app
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
                    // Check the server for a newer build on startup/reconnect (throttled to ~daily inside).
                    runCatching { ServerUpdater.checkAndApply(app, force = false) }
                    connectWs(app)
                    while (isActive && ServerClient.isRegistered(app)) {
                        delay(60_000)
                        val hb = ServerClient.heartbeat(app, statusJson(app).put("localPlaylists", localPlaylists(app)))
                        if (!ServerClient.isRegistered(app)) break // token dropped: re-register
                        runHeartbeatCommands(app, hb) // fallback channel when the socket is down
                        runCatching { ServerUpdater.checkAndApply(app, force = false) } // ~daily, throttled
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

    /** The playlists currently on the device (name/type/host, no credentials), so the panel can see them. */
    private suspend fun localPlaylists(c: Context): org.json.JSONArray {
        val arr = org.json.JSONArray()
        val app = c.applicationContext as? App ?: return arr
        runCatching {
            // ids of playlists that were pushed from the panel, to mark them as managed vs local
            val managed = runCatching {
                JSONObject(c.getSharedPreferences("orbit", Context.MODE_PRIVATE).getString("srv_playlists", "{}") ?: "{}")
            }.getOrNull()?.let { m -> m.keys().asSequence().map { m.optString(it) }.toHashSet() } ?: hashSetOf()
            for (p in app.repo.db.playlists().all()) {
                val host = if (p.type == "xtream") p.server else runCatching { android.net.Uri.parse(p.url).host ?: "" }.getOrDefault("")
                arr.put(
                    JSONObject().put("name", p.name).put("type", p.type).put("host", host)
                        .put("source", if (managed.contains(p.id)) "panel" else "local"),
                )
            }
        }
        return arr
    }

    private fun statusJson(c: Context): JSONObject = JSONObject()
        .put("code", DeviceInfo.deviceId(c))
        .put("foreground", foreground)
        .put("home", Launcher.isDefaultHome(c))
        .put("launcherLevel", Kiosk.statusLevel(c))
        .put("deviceOwner", Kiosk.isDeviceOwner(c))

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

    /** A command arriving live over the socket: run it and report the result back over the socket. */
    private fun handleCommand(app: Context, msg: JSONObject, webSocket: WebSocket) {
        val command = msg.optJSONObject("command") ?: msg
        val type = command.optString("type").ifBlank { command.optString("cmd") }
        val id = command.optString("id")
        scope?.launch(Dispatchers.IO) {
            val (ok, result) = Commands.execute(app, type, command.optJSONObject("payload") ?: JSONObject())
            if (id.isNotBlank()) {
                val out = JSONObject().put("type", "command_result").put("id", id).put("ok", ok)
                if (result != null) out.put("result", result)
                runCatching { webSocket.send(out.toString()) }
            }
        }
    }

    /** Commands returned in the heartbeat reply (used when the socket isn't connected). */
    private fun runHeartbeatCommands(app: Context, hb: JSONObject?) {
        val cmds = hb?.optJSONArray("commands") ?: return
        for (i in 0 until cmds.length()) {
            val command = cmds.optJSONObject(i) ?: continue
            val type = command.optString("type")
            val id = command.optString("id")
            val (ok, result) = Commands.execute(app, type, command.optJSONObject("payload") ?: JSONObject())
            if (id.isNotBlank()) ServerClient.reportCommandResult(app, id, ok, result)
        }
    }

    /** Re-pull and apply the server config now (used by the reload_config command). */
    fun refreshConfigNow() {
        val c = scope ?: return
        val ctx = appContext ?: return
        c.launch(Dispatchers.IO) { applyConfig(ctx, ServerClient.getConfig(ctx)) }
    }

    private suspend fun applyConfig(c: Context, cfg: JSONObject?) {
        cfg ?: return
        Launcher.applyServerConfig(c, cfg.optJSONObject("launcher"))
        // App settings pushed from the panel (theme, player, subtitles, which sections show, …).
        cfg.optJSONObject("settings")?.let { runCatching { (c.applicationContext as? App)?.store?.applyServerSettings(it) } }
        // Playlists pushed from the panel (via the 9-digit connect / the user's profile).
        val app = c.applicationContext as? App ?: return
        val changed = runCatching { app.repo.applyServerPlaylists(cfg.optJSONArray("playlists")) }.getOrDefault(false)
        if (changed) runCatching { app.repo.syncAll(force = false) }
    }

    /** Called by the UI as the user moves around, so the panel shows what's on screen. */
    fun reportForeground(where: String) {
        foreground = where
        runCatching { ws?.send(JSONObject().put("type", "status").put("status", JSONObject().put("foreground", where)).toString()) }
    }
}
