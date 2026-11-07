package com.orbitplayer.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** All user settings. Stored as one JSON object, so new fields get their default automatically. */
data class AppSettings(
    // Appearance
    val theme: String = "slate", // slate | oled | light (light: phone/tablet only)
    val fontScale: Float = 1.0f, // 0.9 | 1.0 | 1.15 | 1.3
    val livePreview: Boolean = false,
    val showChannelNumbers: Boolean = true,
    val cleanChannelNames: Boolean = true,
    val bannerSeconds: Int = 4,
    // Player
    val buffer: String = "medium", // small | medium | large | xlarge
    val hardwareDecoding: Boolean = true,
    val streamFormat: String = "ts", // ts | m3u8 (Xtream live)
    val autoReconnect: Boolean = true,
    val aspect: String = "fit", // fit | fill | zoom
    val autoPlayNext: Boolean = true,
    // Subtitles
    val subtitlesByDefault: Boolean = false,
    val subtitleLanguage: String = "en",
    val subtitleSize: String = "normal", // small | normal | large
    val subtitleBackground: Boolean = true,
    // Parental
    val parentalPin: String = "",
    val hideAdult: Boolean = true,
    val adultKeywords: String = "xxx,adult,18+,for adults",
    // Data
    val playlistRefreshHours: Int = 24,
    val epgRefreshHours: Int = 12,
    val epgPastDays: Int = 1,
    // Startup
    val startOnLastChannel: Boolean = true,
    val autostartOnBoot: Boolean = false,
    val confirmExit: Boolean = true,
) {
    val bufferMs: Int
        get() = when (buffer) {
            "small" -> 5_000
            "large" -> 30_000
            "xlarge" -> 60_000
            else -> 15_000
        }

    val adultWords: List<String> get() = adultKeywords.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun toJson(): JSONObject = JSONObject()
        .put("theme", theme).put("fontScale", fontScale.toDouble()).put("livePreview", livePreview)
        .put("showChannelNumbers", showChannelNumbers).put("cleanChannelNames", cleanChannelNames).put("bannerSeconds", bannerSeconds)
        .put("buffer", buffer).put("hardwareDecoding", hardwareDecoding).put("streamFormat", streamFormat)
        .put("autoReconnect", autoReconnect).put("aspect", aspect).put("autoPlayNext", autoPlayNext)
        .put("subtitlesByDefault", subtitlesByDefault).put("subtitleLanguage", subtitleLanguage)
        .put("subtitleSize", subtitleSize).put("subtitleBackground", subtitleBackground)
        .put("parentalPin", parentalPin).put("hideAdult", hideAdult).put("adultKeywords", adultKeywords)
        .put("playlistRefreshHours", playlistRefreshHours).put("epgRefreshHours", epgRefreshHours).put("epgPastDays", epgPastDays)
        .put("startOnLastChannel", startOnLastChannel).put("autostartOnBoot", autostartOnBoot).put("confirmExit", confirmExit)

    companion object {
        fun fromJson(o: JSONObject, defaults: AppSettings = AppSettings()): AppSettings = defaults.copy(
            theme = o.optString("theme", defaults.theme),
            fontScale = o.optDouble("fontScale", defaults.fontScale.toDouble()).toFloat().coerceIn(0.8f, 1.5f),
            livePreview = o.optBoolean("livePreview", defaults.livePreview),
            showChannelNumbers = o.optBoolean("showChannelNumbers", defaults.showChannelNumbers),
            cleanChannelNames = o.optBoolean("cleanChannelNames", defaults.cleanChannelNames),
            bannerSeconds = o.optInt("bannerSeconds", defaults.bannerSeconds).coerceIn(2, 15),
            buffer = o.optString("buffer", defaults.buffer),
            hardwareDecoding = o.optBoolean("hardwareDecoding", defaults.hardwareDecoding),
            streamFormat = o.optString("streamFormat", defaults.streamFormat),
            autoReconnect = o.optBoolean("autoReconnect", defaults.autoReconnect),
            aspect = o.optString("aspect", defaults.aspect),
            autoPlayNext = o.optBoolean("autoPlayNext", defaults.autoPlayNext),
            subtitlesByDefault = o.optBoolean("subtitlesByDefault", defaults.subtitlesByDefault),
            subtitleLanguage = o.optString("subtitleLanguage", defaults.subtitleLanguage),
            subtitleSize = o.optString("subtitleSize", defaults.subtitleSize),
            subtitleBackground = o.optBoolean("subtitleBackground", defaults.subtitleBackground),
            parentalPin = o.optString("parentalPin", defaults.parentalPin),
            hideAdult = o.optBoolean("hideAdult", defaults.hideAdult),
            adultKeywords = o.optString("adultKeywords", defaults.adultKeywords),
            playlistRefreshHours = o.optInt("playlistRefreshHours", defaults.playlistRefreshHours).coerceIn(1, 24 * 14),
            epgRefreshHours = o.optInt("epgRefreshHours", defaults.epgRefreshHours).coerceIn(1, 24 * 7),
            epgPastDays = o.optInt("epgPastDays", defaults.epgPastDays).coerceIn(0, 14),
            startOnLastChannel = o.optBoolean("startOnLastChannel", defaults.startOnLastChannel),
            autostartOnBoot = o.optBoolean("autostartOnBoot", defaults.autostartOnBoot),
            confirmExit = o.optBoolean("confirmExit", defaults.confirmExit),
        )
    }
}

/** Settings + small pieces of app state, kept in SharedPreferences. */
class Store(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings = runCatching { AppSettings.fromJson(JSONObject(sp.getString("settings", "{}") ?: "{}")) }
        .getOrDefault(AppSettings())

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        sp.edit().putString("settings", next.toJson().toString()).apply()
    }

    fun replaceAll(s: AppSettings) = update { s }

    private fun str(k: String) = sp.getString(k, "") ?: ""
    private fun putStr(k: String, v: String) = sp.edit().putString(k, v).apply()

    var activePlaylistId: String
        get() = str("active_playlist")
        set(v) = putStr("active_playlist", v)

    var lastChannelKey: String
        get() = str("last_channel")
        set(v) = putStr("last_channel", v)

    var lastGroupKey: String
        get() = str("last_group")
        set(v) = putStr("last_group", v)

    /** Most recent first */
    var recentChannels: List<String>
        get() = jsonList("recent_channels")
        set(v) = putJsonList("recent_channels", v.take(20))

    var searchHistory: List<String>
        get() = jsonList("search_history")
        set(v) = putJsonList("search_history", v.take(15))

    var hiddenGroups: Set<String>
        get() = sp.getStringSet("hidden_groups", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("hidden_groups", v).apply()

    var hiddenChannels: Set<String>
        get() = sp.getStringSet("hidden_channels", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("hidden_channels", v).apply()

    var lockedGroups: Set<String>
        get() = sp.getStringSet("locked_groups", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("locked_groups", v).apply()

    private fun jsonList(k: String): List<String> = runCatching {
        val a = JSONArray(sp.getString(k, "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun putJsonList(k: String, v: List<String>) = putStr(k, JSONArray(v).toString())

    /** For backup */
    fun exportState(): JSONObject = JSONObject()
        .put("settings", _settings.value.toJson())
        .put("hiddenGroups", JSONArray(hiddenGroups.toList()))
        .put("hiddenChannels", JSONArray(hiddenChannels.toList()))
        .put("lockedGroups", JSONArray(lockedGroups.toList()))

    fun importState(o: JSONObject) {
        o.optJSONObject("settings")?.let { replaceAll(AppSettings.fromJson(it)) }
        fun set(a: JSONArray?) = a?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
        set(o.optJSONArray("hiddenGroups"))?.let { hiddenGroups = it }
        set(o.optJSONArray("hiddenChannels"))?.let { hiddenChannels = it }
        set(o.optJSONArray("lockedGroups"))?.let { lockedGroups = it }
    }
}
