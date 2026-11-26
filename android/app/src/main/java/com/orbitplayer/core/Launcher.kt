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
    fun title(c: Context): String =
        prefs(c).getString("launcher_title", null)?.takeIf { it.isNotBlank() } ?: "Orbit"

    fun bootTarget(c: Context): String = prefs(c).getString("launcher_boot", "") ?: ""
    fun enabled(c: Context): Boolean = prefs(c).getBoolean("launcher_enabled", false)

    /**
     * Applies the launcher config the server sent (part of the device config). The tiles, the title and
     * the kiosk/boot options are all chosen in the panel — never on the device.
     */
    fun applyServerConfig(c: Context, launcher: org.json.JSONObject?) {
        val e = prefs(c).edit()
        if (launcher == null) {
            // no launcher profile assigned to this device: clear managed tiles
            e.remove("launcher_config").remove("launcher_title").remove("launcher_boot")
                .putBoolean("launcher_enabled", false).apply()
            setTiles(c, emptyList())
            return
        }
        e.putString("launcher_config", launcher.toString())
            .putBoolean("launcher_enabled", launcher.optBoolean("enabled", false))
            .putBoolean("launcher_locked", launcher.optBoolean("locked", false))
            .putString("launcher_boot", launcher.optString("bootTarget", ""))
            .putString("launcher_title", launcher.optJSONObject("branding")?.optString("title") ?: "")
            .apply()
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
