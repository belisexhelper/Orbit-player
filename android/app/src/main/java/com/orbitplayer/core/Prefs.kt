package com.orbitplayer.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Lokalno pamćenje podešavanja i stanja aplikacije. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = "") = sp.getString(key, def) ?: def
    private fun put(key: String, value: String?) = sp.edit().putString(key, value).apply()

    /** "deviceId.token" — dobija se pri registraciji na serveru */
    var deviceAuth: String
        get() = str("device_auth")
        set(v) = put("device_auth", v)

    val deviceId: String get() = deviceAuth.substringBefore('.', "")

    /** Poslednja konfiguracija sa servera (JSON), da aplikacija radi i bez interneta */
    var serverConfig: String
        get() = str("server_config")
        set(v) = put("server_config", v)

    /** Podešavanja koja je korisnik promenio na samom uređaju (imaju prednost nad serverom) */
    var localSettings: String
        get() = str("local_settings", "{}")
        set(v) = put("local_settings", v)

    var lastChannelKey: String
        get() = str("last_channel")
        set(v) = put("last_channel", v)

    var activePlaylistId: String
        get() = str("active_playlist")
        set(v) = put("active_playlist", v)

    var liveFormat: String
        get() = str("live_format", "ts")
        set(v) = put("live_format", v)

    /** Korisnik je isključio launcher na uređaju (ako nije zaključan iz panela) */
    var launcherUserDisabled: Boolean
        get() = sp.getBoolean("launcher_user_disabled", false)
        set(v) = sp.edit().putBoolean("launcher_user_disabled", v).apply()

    /** Do kada je kiosk privremeno isključen (servisni PIN ili komanda iz panela) */
    var kioskSuspendedUntil: Long
        get() = sp.getLong("kiosk_suspended_until", 0)
        set(v) = sp.edit().putLong("kiosk_suspended_until", v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0)
        set(v) = sp.edit().putLong("last_update_check", v).apply()

    var hiddenGroups: Set<String>
        get() = sp.getStringSet("hidden_groups", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("hidden_groups", v).apply()

    var lastSyncAt: Long
        get() = sp.getLong("last_sync_at", 0)
        set(v) = sp.edit().putLong("last_sync_at", v).apply()

    fun localSettingsJson(): JSONObject = runCatching { JSONObject(localSettings) }.getOrElse { JSONObject() }

    fun setLocalSetting(group: String, key: String, value: Any?) {
        val root = localSettingsJson()
        val g = root.optJSONObject(group) ?: JSONObject()
        if (value == null) g.remove(key) else g.put(key, value)
        if (g.length() == 0) root.remove(group) else root.put(group, g)
        localSettings = root.toString()
    }

    fun clearAll() {
        sp.edit().clear().apply()
    }
}
