package com.orbitplayer.diag

import android.os.Build
import com.orbitplayer.App
import com.orbitplayer.BuildConfig
import com.orbitplayer.core.DeviceInfo
import com.orbitplayer.core.Net
import com.orbitplayer.data.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/** Jedna provera u dijagnostici, za prikaz u aplikaciji */
data class DiagItem(val title: String, val value: String, val ok: Boolean?)

/**
 * Dijagnostika uređaja i veze: memorija, mreža, dostupnost našeg i IPTV servera, DNS, brzina.
 * Rezultat vidi korisnik u aplikaciji i admin u panelu (komanda „Pokreni dijagnostiku”).
 */
object Diagnostics {
    private val quick: OkHttpClient = Net.iptv.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun items(app: App, onProgress: (String) -> Unit = {}): List<DiagItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<DiagItem>()
        val (total, avail) = DeviceInfo.memory(app)
        out += DiagItem("Uređaj", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})", null)
        out += DiagItem("Aplikacija", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${if (BuildConfig.SERVER_ENABLED) "Pro" else "Lite"}", null)
        out += DiagItem("Radna memorija", "$avail MB slobodno od $total MB", avail > 200)
        val free = DeviceInfo.freeStorageMb(app)
        out += DiagItem("Prostor za preuzimanja", "$free MB slobodno", free > 1024)
        val net = DeviceInfo.networkType(app)
        out += DiagItem("Mreža", net, net != "nema mreže")

        onProgress("Proveravam DNS…")
        val dnsMs = timed { InetAddress.getByName("google.com") }
        out += DiagItem("DNS", dnsMs?.let { "odgovara za $it ms" } ?: "ne radi", dnsMs != null && dnsMs < 500)

        onProgress("Proveravam internet…")
        val inetMs = timed { quick.newCall(Request.Builder().url("https://www.google.com/generate_204").build()).execute().close() }
        out += DiagItem("Internet", inetMs?.let { "radi ($it ms)" } ?: "nema pristupa", inetMs != null)

        if (BuildConfig.SERVER_ENABLED) {
            onProgress("Proveravam naš server…")
            val srvMs = timed { quick.newCall(Request.Builder().url(app.serverApi.baseUrl + "/api/health").build()).execute().use { if (!it.isSuccessful) error("HTTP ${it.code}") } }
            out += DiagItem("Server aplikacije", srvMs?.let { "dostupan ($it ms)" } ?: "nedostupan", srvMs != null)
            out += DiagItem("Veza uživo sa panelom", if (app.link.isConnected) "povezano" else "nije povezano", app.link.isConnected)
            out += DiagItem("Kiosk nivo", app.kiosk.levelName(), null)
        }

        for (p in app.db.playlists().all()) {
            onProgress("Proveravam ${p.name}…")
            val target = if (p.type == "xtream") p.server else p.url
            val host = runCatching { URI(target).host }.getOrNull() ?: ""
            if (p.type == "xtream") {
                var detail = ""
                val ms = timed {
                    val acc = XtreamClient(p.server, p.username, p.password).account()
                    val ui = acc.optJSONObject("user_info")
                    val exp = ui?.optString("exp_date")?.toLongOrNull()
                    detail = buildString {
                        append("status: ${ui?.optString("status") ?: "?"}")
                        append(", veze ${ui?.optString("active_cons") ?: "?"}/${ui?.optString("max_connections") ?: "?"}")
                        if (exp != null && exp > 0) append(", ističe ${java.text.DateFormat.getDateInstance().format(java.util.Date(exp * 1000))}")
                    }
                }
                out += DiagItem("Lista „${p.name}” ($host)", ms?.let { "odgovara za $it ms, $detail" } ?: "server ne odgovara ili su podaci pogrešni", ms != null && ms < 2000)
            } else {
                val ms = timed { quick.newCall(Request.Builder().url(p.url).head().build()).execute().close() }
                out += DiagItem("Lista „${p.name}” ($host)", ms?.let { "odgovara za $it ms" } ?: "nedostupna", ms != null)
            }
            if (p.lastError.isNotBlank()) out += DiagItem("Poslednja greška liste", p.lastError, false)
        }
        out
    }

    /** Rezultat za admin panel */
    suspend fun run(app: App, includeSpeedTest: Boolean): JSONObject {
        val list = items(app)
        val json = JSONObject()
        val checks = JSONArray()
        list.forEach { checks.put(JSONObject().put("title", it.title).put("value", it.value).put("ok", it.ok ?: JSONObject.NULL)) }
        json.put("checks", checks)
        if (includeSpeedTest) {
            val st = SpeedTest.run(durationMs = 8_000) {}
            json.put("speedtest", JSONObject().put("downloadMbps", st.downloadMbps).put("pingMs", st.pingMs).put("jitterMs", st.jitterMs).put("error", st.error))
        }
        val apps = JSONArray()
        runCatching { app.kiosk.allLaunchableApps() }.getOrDefault(emptyList()).forEach { apps.put(JSONObject().put("package", it.pkg).put("label", it.label)) }
        json.put("installedApps", apps)
        return json
    }

    private inline fun timed(block: () -> Unit): Long? {
        val t = System.nanoTime()
        return try {
            block()
            (System.nanoTime() - t) / 1_000_000
        } catch (e: Exception) {
            null
        }
    }
}
