package com.orbitplayer.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * A controllable message bar shown at the top of the screen, driven from the panel.
 * Unlike the quick toast, it stays exactly as long as told (seconds, days, or until cleared) and is
 * colored by severity. One banner at a time; it survives app restarts (stored in prefs).
 */
object Banner {
    data class Data(val text: String, val severity: String, val expiresAt: Long) // expiresAt 0 = until cleared

    private val _state = MutableStateFlow<Data?>(null)
    val state: StateFlow<Data?> = _state.asStateFlow()

    private fun prefs(c: Context) = c.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    /** Loads a saved banner on app start (if it hasn't expired). */
    fun load(c: Context) {
        val raw = prefs(c).getString("banner", null) ?: return
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val exp = o.optLong("expiresAt", 0)
        if (exp != 0L && System.currentTimeMillis() > exp) { clear(c); return }
        _state.value = Data(o.optString("text"), o.optString("severity", "info"), exp)
    }

    /** Shows a banner. [durationSec] <= 0 keeps it until explicitly cleared. */
    fun show(c: Context, text: String, severity: String, durationSec: Long) {
        if (text.isBlank()) { clear(c); return }
        val exp = if (durationSec > 0) System.currentTimeMillis() + durationSec * 1000 else 0L
        val sev = if (severity in listOf("info", "warn", "urgent")) severity else "info"
        val data = Data(text.take(300), sev, exp)
        _state.value = data
        prefs(c).edit().putString(
            "banner",
            JSONObject().put("text", data.text).put("severity", data.severity).put("expiresAt", exp).toString(),
        ).apply()
    }

    fun clear(c: Context) {
        _state.value = null
        prefs(c).edit().remove("banner").apply()
    }

    /** Called by the UI tick to drop an expired banner. */
    fun pruneExpired(c: Context) {
        val d = _state.value ?: return
        if (d.expiresAt != 0L && System.currentTimeMillis() > d.expiresAt) clear(c)
    }
}
