package com.orbitplayer.core

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject

/** One app shown on the launcher home screen. */
data class AppTile(val pkg: String, val label: String)

/**
 * The Orbit launcher: a home screen of tiles. One tile is always Orbit itself (Live TV), the rest are
 * other apps on the device. Which tiles show is chosen here for now; from Phase 4 the panel controls it.
 */
object Launcher {
    private fun prefs(c: Context) = c.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    fun tiles(c: Context): List<AppTile> {
        val raw = prefs(c).getString("launcher_tiles", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { val o = arr.getJSONObject(it); AppTile(o.getString("pkg"), o.optString("label")) }
        }.getOrDefault(emptyList())
    }

    fun setTiles(c: Context, list: List<AppTile>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("pkg", it.pkg).put("label", it.label)) }
        prefs(c).edit().putString("launcher_tiles", arr.toString()).apply()
    }

    /** Title shown on the launcher home screen (from the panel's branding, else "Orbit"). */
    /** The launcher title set in the panel; blank means "show no title". */
    fun title(c: Context): String = prefs(c).getString("launcher_title", "") ?: ""

    /** Tile/icon size multiplier from the panel (0.8 smaller … 1.3 larger). */
    fun iconScale(c: Context): Float = prefs(c).getFloat("launcher_icon_scale", 1f).coerceIn(0.6f, 1.8f)

    /** Accent color (ARGB) chosen in the panel, or null to use the theme's. */
    fun accentColor(c: Context): Int? {
        val hex = prefs(c).getString("launcher_accent", "")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex") }.getOrNull()
    }

    fun bootTarget(c: Context): String = prefs(c).getString("launcher_boot", "") ?: ""
    fun enabled(c: Context): Boolean = prefs(c).getBoolean("launcher_enabled", false)

    /**
     * Applies the launcher config the server sent (part of the device config). The tiles, the title and
     * the kiosk/boot options are all chosen in the panel — never on the device.
     */
    /** Wipes all managed launcher + kiosk state (used when the device is unpaired or has no profile). */
    fun clearConfig(c: Context) {
        prefs(c).edit()
            .remove("launcher_config").remove("launcher_title").remove("launcher_boot").remove("launcher_locked")
            .remove("kiosk_level").remove("kiosk_hide_status").remove("kiosk_block_settings")
            .remove("kiosk_block_install").remove("kiosk_exit_pin_hash").remove("launcher_show_settings_tile")
            .putBoolean("launcher_enabled", false).apply()
        setTiles(c, emptyList())
    }

    fun applyServerConfig(c: Context, launcher: org.json.JSONObject?) {
        val e = prefs(c).edit()
        if (launcher == null) {
            // no launcher profile assigned to this device: clear managed tiles and kiosk
            clearConfig(c)
            return
        }
        val branding = launcher.optJSONObject("branding")
        e.putString("launcher_config", launcher.toString())
            .putBoolean("launcher_enabled", launcher.optBoolean("enabled", false))
            .putBoolean("launcher_locked", launcher.optBoolean("locked", false))
            .putString("launcher_boot", launcher.optString("bootTarget", ""))
            .putString("launcher_title", branding?.optString("title") ?: "")
            .putString("launcher_accent", branding?.optString("accent") ?: "")
            .putFloat("launcher_icon_scale", (branding?.optDouble("iconScale", 1.0) ?: 1.0).toFloat())
            // kiosk settings (enforced by Kiosk, to the extent the device's level allows)
            .putString("kiosk_level", launcher.optString("kioskLevel", "auto"))
            .putBoolean("kiosk_hide_status", launcher.optBoolean("hideStatusBar", false))
            .putBoolean("kiosk_block_settings", launcher.optBoolean("blockSettings", false))
            .putBoolean("kiosk_block_install", launcher.optBoolean("blockInstallUninstall", false))
            .putBoolean("launcher_show_settings_tile", launcher.optBoolean("showSystemSettingsTile", true))
            .apply()
        // Store the exit-PIN hash on its own so a config without it doesn't wipe a set PIN.
        val pinHash = launcher.optString("exitPinHash", "")
        if (pinHash.isNotBlank()) prefs(c).edit().putString("kiosk_exit_pin_hash", pinHash).apply()
        else prefs(c).edit().remove("kiosk_exit_pin_hash").apply()
        val apps = launcher.optJSONArray("allowedApps")
        val tiles = ArrayList<AppTile>()
        if (apps != null) for (i in 0 until apps.length()) {
            val o = apps.optJSONObject(i) ?: continue
            val pkg = o.optString("package")
            if (pkg.isNotBlank()) tiles.add(AppTile(pkg, o.optString("label")))
        }
        setTiles(c, tiles)
    }

    /** All launchable apps on the device (TV and phone launchers), without Orbit itself. */
    fun installedApps(c: Context): List<AppTile> {
        val pm = c.packageManager
        val found = LinkedHashMap<String, AppTile>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == c.packageName) continue
                found.putIfAbsent(pkg, AppTile(pkg, ri.loadLabel(pm).toString()))
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    /** Opens another app; true when it could be started. */
    fun launch(c: Context, pkg: String): Boolean {
        val pm = c.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { c.startActivity(intent); true }.getOrDefault(false)
    }

    fun appIcon(c: Context, pkg: String): Drawable? =
        runCatching { c.packageManager.getApplicationIcon(pkg) }.getOrNull()

    /** True when Orbit is the device's current home app. */
    fun isDefaultHome(c: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = c.packageManager.resolveActivity(intent, 0) ?: return false
        return ri.activityInfo?.packageName == c.packageName
    }

    /** Opens the system screen where the user can make Orbit the home app. */
    fun openHomeChooser(c: Context) {
        runCatching {
            c.startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            // some devices have no home-settings screen: fall back to pressing HOME so the picker appears
            runCatching { c.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
