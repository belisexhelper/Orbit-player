package com.orbitplayer.core

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import com.orbitplayer.BuildConfig
import org.json.JSONObject

object DeviceInfo {
    /** TV uređaj (daljinski upravljač) ili telefon/tablet (dodir) */
    fun isTv(context: Context): Boolean {
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        if (pm.hasSystemFeature("amazon.hardware.fire_tv")) return true
        // TV boksovi bez ekrana na dodir
        return !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    fun isTablet(context: Context): Boolean = context.resources.configuration.smallestScreenWidthDp >= 600

    fun registrationJson(context: Context): JSONObject = JSONObject()
        .put("flavor", if (BuildConfig.SERVER_ENABLED) "pro" else "lite")
        .put("isTv", isTv(context))
        .put("manufacturer", Build.MANUFACTURER ?: "")
        .put("model", Build.MODEL ?: "")
        .put("androidVersion", Build.VERSION.RELEASE ?: "")
        .put("sdkInt", Build.VERSION.SDK_INT)
        .put("appVersionName", BuildConfig.VERSION_NAME)
        .put("appVersionCode", BuildConfig.VERSION_CODE)

    fun freeStorageMb(context: Context): Long {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return runCatching { StatFs(dir.path).availableBytes / (1024 * 1024) }.getOrDefault(-1)
    }

    fun memory(context: Context): Pair<Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return (mi.totalMem / (1024 * 1024)) to (mi.availMem / (1024 * 1024))
    }

    @Suppress("DEPRECATION")
    fun networkType(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= 23) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "nema mreže"
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet (kabl)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobilni internet"
                else -> "ostalo"
            }
        }
        val ni = cm.activeNetworkInfo ?: return "nema mreže"
        return ni.typeName ?: "?"
    }
}
