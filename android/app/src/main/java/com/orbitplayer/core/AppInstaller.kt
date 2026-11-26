package com.orbitplayer.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import com.orbitplayer.App
import okhttp3.Request
import java.io.File

/**
 * Installs and removes other apps on command from the panel.
 *  - Device Owner (kiosk tier 3): silent, via PackageInstaller — no user interaction.
 *  - Otherwise: the system installer/uninstaller UI opens so the user confirms.
 */
object AppInstaller {

    /** Downloads an APK to the cache; returns the file or null on failure. */
    fun download(ctx: Context, url: String): File? = runCatching {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "remote-${Integer.toHexString(url.hashCode())}.apk")
        val req = Request.Builder().url(url).build()
        Net.api.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body ?: return null
            body.byteStream().use { inp -> file.outputStream().use { out -> inp.copyTo(out) } }
        }
        file.takeIf { it.length() > 0 }
    }.getOrNull()

    fun install(ctx: Context, file: File): Boolean =
        if (Kiosk.isDeviceOwner(ctx)) installSilently(ctx, file)
        else (ctx.applicationContext as? App)?.updater?.install(ctx, file) ?: false

    fun uninstall(ctx: Context, pkg: String): Boolean =
        if (Kiosk.isDeviceOwner(ctx)) uninstallSilently(ctx, pkg)
        else runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT

    private fun installSilently(ctx: Context, file: File): Boolean = runCatching {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { inp ->
                session.openWrite("orbit_apk", 0, file.length()).use { out ->
                    inp.copyTo(out)
                    session.fsync(out)
                }
            }
            val pi = PendingIntent.getBroadcast(ctx, sessionId, Intent("com.orbitplayer.INSTALL_RESULT").setPackage(ctx.packageName), pendingFlags())
            session.commit(pi.intentSender)
        }
        true
    }.getOrDefault(false)

    private fun uninstallSilently(ctx: Context, pkg: String): Boolean = runCatching {
        val installer = ctx.packageManager.packageInstaller
        val pi = PendingIntent.getBroadcast(ctx, pkg.hashCode(), Intent("com.orbitplayer.UNINSTALL_RESULT").setPackage(ctx.packageName), pendingFlags())
        installer.uninstall(pkg, pi.intentSender)
        true
    }.getOrDefault(false)
}
