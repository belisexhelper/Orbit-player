package com.orbitplayer.core

import android.content.Context
import android.os.Build
import com.orbitplayer.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Talks to the Orbit management server (the panel's device API at {base}/api/device/...).
 * The device registers once and keeps an id + token; everything else is authenticated with it.
 */
object ServerClient {
    const val DEFAULT_BASE = "https://server.1379000.xyz"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun prefs(c: Context) = c.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    fun baseUrl(c: Context): String =
        (prefs(c).getString("server_url", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE).trimEnd('/')

    fun setBaseUrl(c: Context, url: String) =
        prefs(c).edit().putString("server_url", url.trim().trimEnd('/')).apply()

    fun deviceId(c: Context): String? = prefs(c).getString("srv_device_id", null)
    private fun token(c: Context): String? = prefs(c).getString("srv_token", null)
    fun isRegistered(c: Context): Boolean = deviceId(c) != null && token(c) != null

    /** Last connection result, shown in settings so a problem is visible: null = OK. */
    @Volatile var lastError: String? = null
        private set

    private fun authHeader(c: Context): String? {
        val id = deviceId(c) ?: return null
        val t = token(c) ?: return null
        return "Device $id.$t"
    }

    private fun deviceInfoJson(c: Context): JSONObject = JSONObject()
        .put("flavor", "pro")
        .put("code", DeviceInfo.deviceId(c)) // the 10-digit ID shown on the device
        .put("isTv", DeviceInfo.isTv(c))
        .put("manufacturer", Build.MANUFACTURER ?: "")
        .put("model", Build.MODEL ?: "")
        .put("androidVersion", Build.VERSION.RELEASE ?: "")
        .put("sdkInt", Build.VERSION.SDK_INT)
        .put("appVersionName", BuildConfig.VERSION_NAME)
        .put("appVersionCode", BuildConfig.VERSION_CODE)

    /** Registers this device once; true when a token is held (already or newly obtained). */
    fun ensureRegistered(c: Context): Boolean {
        if (isRegistered(c)) return true
        val req = Request.Builder().url("${baseUrl(c)}/api/device/register")
            .post(deviceInfoJson(c).toString().toRequestBody(JSON)).build()
        return runCatching {
            Net.api.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { lastError = "register HTTP ${r.code}"; return false }
                val o = JSONObject(r.body?.string() ?: run { lastError = "register: empty body"; return false })
                prefs(c).edit()
                    .putString("srv_device_id", o.getString("deviceId"))
                    .putString("srv_token", o.getString("token"))
                    .apply()
                lastError = null
                true
            }
        }.getOrElse { lastError = it.message ?: it.javaClass.simpleName; false }
    }

    fun postInfo(c: Context) {
        val auth = authHeader(c) ?: return
        val req = Request.Builder().url("${baseUrl(c)}/api/device/info")
            .addHeader("Authorization", auth)
            .post(deviceInfoJson(c).toString().toRequestBody(JSON)).build()
        runCatching { Net.api.newCall(req).execute().use { r -> if (r.code == 401) clearAuth(c) } }
    }

    fun getConfig(c: Context): JSONObject? {
        val auth = authHeader(c) ?: return null
        val req = Request.Builder().url("${baseUrl(c)}/api/device/config")
            .addHeader("Authorization", auth).get().build()
        return runCatching {
            Net.api.newCall(req).execute().use { r ->
                if (r.code == 401) { clearAuth(c); return null }
                if (!r.isSuccessful) return null
                JSONObject(r.body?.string() ?: return null)
            }
        }.getOrNull()
    }

    fun heartbeat(c: Context, status: JSONObject): JSONObject? {
        val auth = authHeader(c) ?: return null
        val body = JSONObject()
            .put("appVersionName", BuildConfig.VERSION_NAME)
            .put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("status", status)
        val req = Request.Builder().url("${baseUrl(c)}/api/device/heartbeat")
            .addHeader("Authorization", auth)
            .post(body.toString().toRequestBody(JSON)).build()
        return runCatching {
            Net.api.newCall(req).execute().use { r ->
                if (r.code == 401) { clearAuth(c); return null }
                if (r.isSuccessful) { lastError = null; JSONObject(r.body?.string() ?: "{}") } else null
            }
        }.getOrNull()
    }

    /**
     * Asks the server for a fresh pairing code (8 digits) the customer can use on the site to attach
     * a playlist to this device. Returns {code, formatted, expiresAt, linkUrl} or null.
     */
    fun pairingCode(c: Context): JSONObject? {
        val auth = authHeader(c) ?: return null
        val req = Request.Builder().url("${baseUrl(c)}/api/device/pairing-code")
            .addHeader("Authorization", auth)
            .post("{}".toRequestBody(JSON)).build()
        return runCatching {
            Net.api.newCall(req).execute().use { r ->
                if (r.code == 401) { clearAuth(c); return null }
                if (r.isSuccessful) JSONObject(r.body?.string() ?: return null) else null
            }
        }.getOrNull()
    }

    /** Redeems an activation code the customer was given; returns the new config or null on failure. */
    fun activate(c: Context, code: String): JSONObject? {
        val auth = authHeader(c) ?: return null
        val body = JSONObject().put("code", code.trim())
        val req = Request.Builder().url("${baseUrl(c)}/api/device/activate")
            .addHeader("Authorization", auth)
            .post(body.toString().toRequestBody(JSON)).build()
        return runCatching {
            Net.api.newCall(req).execute().use { r ->
                if (r.code == 401) { clearAuth(c); return null }
                if (r.isSuccessful) JSONObject(r.body?.string() ?: "{}") else null
            }
        }.getOrNull()
    }

    /** wss:// URL for the live command socket, or null when not registered. */
    fun wsUrl(c: Context): String? {
        val id = deviceId(c) ?: return null
        val t = token(c) ?: return null
        val base = baseUrl(c).replaceFirst("http", "ws") // https -> wss, http -> ws
        return "$base/api/device/ws?auth=$id.$t"
    }

    private fun clearAuth(c: Context) =
        prefs(c).edit().remove("srv_device_id").remove("srv_token").apply()
}
