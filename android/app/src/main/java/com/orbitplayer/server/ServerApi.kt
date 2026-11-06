package com.orbitplayer.server

import android.content.Context
import com.orbitplayer.BuildConfig
import com.orbitplayer.core.DeviceInfo
import com.orbitplayer.core.Prefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Greška sa servera, sa porukom za korisnika */
class ServerException(val status: Int, val code: String, message: String) : IOException(message)

/** Pozivi ka našem serveru. Sve metode su blokirajuće — pozivati sa Dispatchers.IO. */
class ServerApi(private val context: Context, private val prefs: Prefs, private val http: OkHttpClient) {
    val baseUrl: String get() = BuildConfig.SERVER_URL.trimEnd('/')
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    val isRegistered: Boolean get() = prefs.deviceAuth.isNotBlank()

    private fun request(method: String, path: String, body: JSONObject? = null, auth: Boolean = true): JSONObject {
        val b = Request.Builder().url(baseUrl + path)
        if (auth) b.header("Authorization", "Device ${prefs.deviceAuth}")
        val rb = body?.toString()?.toRequestBody(jsonType)
        when (method) {
            "GET" -> b.get()
            else -> b.method(method, rb ?: "{}".toRequestBody(jsonType))
        }
        http.newCall(b.build()).execute().use { res ->
            val text = res.body?.string() ?: ""
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!res.isSuccessful) {
                throw ServerException(res.code, json.optString("error", "error"), json.optString("message", "Greška servera (${res.code})"))
            }
            return json
        }
    }

    /** Registruje uređaj (jednom) i pamti pristupni token */
    fun register(): String {
        val r = request("POST", "/api/device/register", DeviceInfo.registrationJson(context), auth = false)
        prefs.deviceAuth = r.getString("auth")
        return prefs.deviceAuth
    }

    fun sendInfo() = request("POST", "/api/device/info", DeviceInfo.registrationJson(context))

    fun config(): JSONObject = request("GET", "/api/device/config")

    fun pairingCode(): JSONObject = request("POST", "/api/device/pairing-code")

    fun activate(code: String): JSONObject = request("POST", "/api/device/activate", JSONObject().put("code", code))

    fun heartbeat(status: JSONObject): JSONObject = request(
        "POST", "/api/device/heartbeat",
        JSONObject().put("status", status).put("appVersionName", BuildConfig.VERSION_NAME).put("appVersionCode", BuildConfig.VERSION_CODE),
    )

    fun commandResult(id: String, ok: Boolean, result: Any?) {
        request("POST", "/api/device/commands/$id/result", JSONObject().put("ok", ok).put("result", result ?: JSONObject.NULL))
    }

    fun sendLogs(entries: List<JSONObject>) {
        if (entries.isEmpty()) return
        request("POST", "/api/device/logs", JSONObject().put("entries", JSONArray(entries)))
    }

    fun checkUpdate(channel: String): JSONObject = request("GET", "/api/device/update?channel=$channel")

    /** Preuzima fajl sa našeg servera (npr. novu verziju) i proverava SHA-256 */
    fun download(url: String, target: File, expectedSha256: String, onProgress: (Long, Long) -> Unit) {
        val req = Request.Builder().url(url).header("Authorization", "Device ${prefs.deviceAuth}").build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Preuzimanje nije uspelo (HTTP ${res.code})")
            val body = res.body ?: throw IOException("Prazan odgovor")
            val total = body.contentLength()
            val md = MessageDigest.getInstance("SHA-256")
            target.parentFile?.mkdirs()
            target.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256.isNotBlank() && !hex.equals(expectedSha256, ignoreCase = true)) {
                target.delete()
                throw IOException("Preuzeti fajl je oštećen (kontrolni zbir se ne slaže)")
            }
        }
    }

    fun wsUrl(): String = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/device/ws"
}
