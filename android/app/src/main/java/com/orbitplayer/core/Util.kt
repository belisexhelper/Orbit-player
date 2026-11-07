package com.orbitplayer.core

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.orbitplayer.App
import com.orbitplayer.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val TAG = "Orbit"
    fun d(msg: String) = Log.d(TAG, msg)
    fun w(msg: String, e: Throwable? = null) = Log.w(TAG, msg, e)
    fun e(msg: String, e: Throwable? = null) = Log.e(TAG, msg, e)
}

object DeviceInfo {
    /** TV (remote control) or phone/tablet (touch) */
    fun isTv(context: Context): Boolean {
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        if (pm.hasSystemFeature("amazon.hardware.fire_tv")) return true
        return !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    fun freeStorageMb(context: Context): Long {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return runCatching { StatFs(dir.path).availableBytes / (1024 * 1024) }.getOrDefault(-1)
    }

    fun memoryMb(context: Context): Pair<Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return (mi.totalMem / (1024 * 1024)) to (mi.availMem / (1024 * 1024))
    }

    @Suppress("DEPRECATION")
    fun networkType(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= 23) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "No connection"
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                else -> "Other"
            }
        }
        return cm.activeNetworkInfo?.typeName ?: "No connection"
    }
}

/** Removes provider clutter like "UK:", "|EN|", "FHD" from channel names (setting "Clean channel names"). */
object ChannelNames {
    private val prefix = Regex("""^\s*(\[[^\]]{1,12}]|\|[^|]{1,12}\||[A-Z]{2,4}\s*[|:\-]+)\s*""")
    private val suffix = Regex("""\s*[\[(]?\b(UHD|FHD|HD|SD|4K|HEVC|H265|1080p?|720p?|50FPS|60FPS)\b[\])]?\s*$""", RegexOption.IGNORE_CASE)

    fun clean(name: String): String {
        var n = name.trim()
        repeat(2) { n = prefix.replace(n, "") }
        repeat(2) { n = suffix.replace(n, "") }
        return n.trim().ifBlank { name.trim() }
    }
}

object Fmt {
    private val time = ThreadLocal<SimpleDateFormat>()
    fun time(ms: Long): String {
        val f = time.get() ?: SimpleDateFormat("HH:mm", Locale.getDefault()).also { time.set(it) }
        return f.format(Date(ms))
    }

    fun date(ms: Long): String = SimpleDateFormat("EEE d MMM", Locale.ENGLISH).format(Date(ms))

    fun longDate(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(Date(ms))

    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    fun minutes(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    fun bytes(b: Long): String = when {
        b <= 0 -> "?"
        b >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", b / 1073741824.0)
        else -> String.format(Locale.US, "%.0f MB", b / 1048576.0)
    }
}

/** Starts the app after the device boots (setting "Start on boot"). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as App
        if (!app.store.settings.value.autostartOnBoot) return
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }.onFailure { AppLog.w("Start on boot is not allowed on this device", it) }
    }
}
