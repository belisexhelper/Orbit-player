package com.orbitplayer.core

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.orbitplayer.App
import com.orbitplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Runs a command the panel sent to this device. Each case returns ok + an optional result payload
 * that goes back to the panel (so an admin sees "izvršeno" / "neuspešno" and any detail).
 * Commands arrive over the live WebSocket and, as a fallback, in the heartbeat reply.
 */
object Commands {
    private val main = Handler(Looper.getMainLooper())

    fun execute(ctx: Context, type: String, payload: JSONObject): Pair<Boolean, JSONObject?> {
        val app = ctx.applicationContext
        return runCatching {
            when (type) {
                "reload_config" -> { DeviceAgent.refreshConfigNow(); true to null }

                "refresh_playlists" -> {
                    (app as? App)?.let { a -> a.appScope.launch(Dispatchers.IO) { runCatching { a.repo.syncAll(force = true) } } }
                    true to null
                }

                "show_message" -> {
                    val text = payload.optString("text").ifBlank { "Orbit" }
                    main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
                    true to null
                }

                "open_app" -> {
                    val pkg = payload.optString("package")
                    val ok = pkg.isNotBlank() && launchOnMain(app, pkg)
                    ok to JSONObject().put("package", pkg)
                }

                "go_home" -> { goHome(app); true to null }

                "restart_app" -> { restartApp(app); true to null }

                "clear_cache" -> { val n = clearCache(app); true to JSONObject().put("clearedBytes", n) }

                "check_update" -> {
                    // Update from the management server (operator-controlled rollout), not GitHub.
                    (app as? App)?.appScope?.launch(Dispatchers.IO) {
                        runCatching { ServerUpdater.checkAndApply(app, force = true) }
                    }
                    true to msg("Provera ažuriranja pokrenuta")
                }

                "run_diagnostics" -> true to diagnostics(app)

                "reboot" ->
                    if (Kiosk.reboot(app)) true to null
                    else false to msg("Restart uređaja zahteva Device Owner (kiosk nivo 3)")

                "exit_kiosk" -> {
                    val mins = payload.optInt("minutes", 10).coerceIn(1, 120)
                    Kiosk.pause(mins)
                    true to msg("Kiosk privremeno isključen na $mins min")
                }

                "install_app" -> {
                    val url = payload.optString("url")
                    if (url.isBlank()) false to msg("Nedostaje link APK-a")
                    else {
                        (app as? App)?.appScope?.launch(Dispatchers.IO) {
                            AppInstaller.download(app, url)?.let { AppInstaller.install(app, it) }
                        }
                        true to msg("Preuzimanje i instalacija su pokrenuti")
                    }
                }

                "uninstall_app" -> {
                    val pkg = payload.optString("package")
                    if (pkg.isBlank()) false to msg("Nedostaje naziv paketa")
                    else AppInstaller.uninstall(app, pkg) to JSONObject().put("package", pkg)
                }

                "unpair" -> { unpair(app); true to null }

                else -> false to msg("Nepoznata komanda: $type")
            }
        }.getOrElse { false to msg(it.message ?: it.javaClass.simpleName) }
    }

    private fun msg(m: String) = JSONObject().put("message", m)

    /** Launches another app; activity starts must run from the main thread. */
    private fun launchOnMain(app: Context, pkg: String): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        main.post { ok = Launcher.launch(app, pkg); latch.countDown() }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return ok
    }

    private fun goHome(app: Context) = main.post {
        runCatching {
            // Our own home: the launcher if it is enabled, otherwise the normal app screen.
            val target = if (Launcher.enabled(app)) com.orbitplayer.ui.LauncherActivity::class.java
            else com.orbitplayer.ui.MainActivity::class.java
            app.startActivity(Intent(app, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }

    private fun restartApp(app: Context) = main.post {
        runCatching {
            val i = app.packageManager.getLaunchIntentForPackage(app.packageName)
                ?: Intent(app, com.orbitplayer.ui.MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            app.startActivity(i)
            main.postDelayed({ Runtime.getRuntime().exit(0) }, 300)
        }
    }

    /** Empties the cache directory; returns how many bytes were freed. */
    private fun clearCache(app: Context): Long {
        var freed = 0L
        fun wipe(f: java.io.File) {
            if (f.isDirectory) f.listFiles()?.forEach { wipe(it) }
            freed += f.length()
            runCatching { f.delete() }
        }
        runCatching { app.cacheDir?.listFiles()?.forEach { wipe(it) } }
        runCatching { app.externalCacheDir?.listFiles()?.forEach { wipe(it) } }
        return freed
    }

    private fun diagnostics(app: Context): JSONObject {
        val (totalMem, freeMem) = DeviceInfo.memoryMb(app)
        return JSONObject()
            .put("appVersionName", BuildConfig.VERSION_NAME)
            .put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("android", android.os.Build.VERSION.RELEASE)
            .put("sdkInt", android.os.Build.VERSION.SDK_INT)
            .put("model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("isTv", DeviceInfo.isTv(app))
            .put("network", DeviceInfo.networkType(app))
            .put("freeStorageMb", DeviceInfo.freeStorageMb(app))
            .put("totalMemMb", totalMem)
            .put("freeMemMb", freeMem)
            .put("isDefaultHome", Launcher.isDefaultHome(app))
            .put("launcherEnabled", Launcher.enabled(app))
            .put("kioskLevel", Kiosk.level(app))
            .put("isDeviceOwner", Kiosk.isDeviceOwner(app))
            .put("deviceId", DeviceInfo.deviceId(app))
    }

    /** Detaches the device: drops its management token and launcher config so it is unmanaged again. */
    private fun unpair(app: Context) {
        ServerClient.unpair(app)
        Launcher.clearConfig(app)
        main.post { runCatching { Toast.makeText(app, "Uređaj je odjavljen", Toast.LENGTH_LONG).show() } }
    }
}
