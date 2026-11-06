package com.orbitplayer.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Efektivna podešavanja: podrazumevane vrednosti ← server (globalno/korisnik/uređaj) ← lokalne izmene.
 * Isti ključevi kao DEFAULT_SETTINGS u backend/src/lib/settings.ts.
 */
data class AppSettings(
    val playerEngine: String = "exo",
    val bufferMs: Int = 15000,
    val hardwareDecoding: Boolean = true,
    val autoReconnect: Boolean = true,
    val aspect: String = "fit",
    val rememberAudioTrack: Boolean = true,
    val subtitlesByDefault: Boolean = false,
    val subtitleLanguage: String = "sr",
    val subtitleSize: String = "normal",
    val subtitleBackground: Boolean = true,
    val theme: String = "dark",
    val accent: String = "#3D8BFF",
    val language: String = "sr",
    val showLive: Boolean = true,
    val showMovies: Boolean = true,
    val showSeries: Boolean = true,
    val showDownloads: Boolean = true,
    val showSpeedtest: Boolean = true,
    val parentalPin: String = "",
    val hideAdultGroups: Boolean = true,
    val adultKeywords: List<String> = listOf("xxx", "adult", "18+", "for adults"),
    val playlistRefreshHours: Int = 24,
    val epgRefreshHours: Int = 12,
    val epgDaysBack: Int = 2,
    val autostartOnBoot: Boolean = false,
    val startScreen: String = "home",
    val updateChannel: String = "stable",
    val heartbeatSec: Int = 60,
    val supportMessage: String = "",
    val supportContact: String = "",
) {
    companion object {
        fun from(json: JSONObject): AppSettings {
            val p = json.optJSONObject("player") ?: JSONObject()
            val s = json.optJSONObject("subtitles") ?: JSONObject()
            val u = json.optJSONObject("ui") ?: JSONObject()
            val pa = json.optJSONObject("parental") ?: JSONObject()
            val d = json.optJSONObject("data") ?: JSONObject()
            val a = json.optJSONObject("app") ?: JSONObject()
            val su = json.optJSONObject("support") ?: JSONObject()
            val def = AppSettings()
            return AppSettings(
                playerEngine = p.optString("engine", def.playerEngine),
                bufferMs = p.optInt("bufferMs", def.bufferMs).coerceIn(2000, 120000),
                hardwareDecoding = p.optBoolean("hardwareDecoding", def.hardwareDecoding),
                autoReconnect = p.optBoolean("autoReconnect", def.autoReconnect),
                aspect = p.optString("aspect", def.aspect),
                rememberAudioTrack = p.optBoolean("rememberAudioTrack", def.rememberAudioTrack),
                subtitlesByDefault = s.optBoolean("enabledByDefault", def.subtitlesByDefault),
                subtitleLanguage = s.optString("preferredLanguage", def.subtitleLanguage),
                subtitleSize = s.optString("size", def.subtitleSize),
                subtitleBackground = s.optBoolean("background", def.subtitleBackground),
                theme = u.optString("theme", def.theme),
                accent = u.optString("accent", def.accent),
                language = u.optString("language", def.language),
                showLive = u.optBoolean("showLive", true),
                showMovies = u.optBoolean("showMovies", true),
                showSeries = u.optBoolean("showSeries", true),
                showDownloads = u.optBoolean("showDownloads", true),
                showSpeedtest = u.optBoolean("showSpeedtest", true),
                parentalPin = pa.optString("pin", ""),
                hideAdultGroups = pa.optBoolean("hideAdultGroups", true),
                adultKeywords = pa.optString("adultKeywords", "xxx,adult,18+,for adults")
                    .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                playlistRefreshHours = d.optInt("playlistRefreshHours", 24).coerceIn(1, 24 * 14),
                epgRefreshHours = d.optInt("epgRefreshHours", 12).coerceIn(1, 24 * 7),
                epgDaysBack = d.optInt("epgDaysBack", 2).coerceIn(0, 14),
                autostartOnBoot = a.optBoolean("autostartOnBoot", false),
                startScreen = a.optString("startScreen", "home"),
                updateChannel = a.optString("updateChannel", "stable"),
                heartbeatSec = a.optInt("heartbeatSec", 60).coerceIn(15, 3600),
                supportMessage = su.optString("message", ""),
                supportContact = su.optString("contact", ""),
            )
        }
    }
}

object JsonUtil {
    /** Duboko spajanje: vrednosti iz [over] zamenjuju vrednosti iz [base]. */
    fun merge(base: JSONObject, over: JSONObject): JSONObject {
        val out = JSONObject(base.toString())
        val keys = over.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = over.get(k)
            val cur = out.opt(k)
            if (v is JSONObject && cur is JSONObject) out.put(k, merge(cur, v)) else out.put(k, v)
        }
        return out
    }

    fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    /** optString koji vraća "" i za JSON null */
    fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")
}
