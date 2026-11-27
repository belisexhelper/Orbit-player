package com.orbitplayer.core

import android.content.Context
import com.orbitplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps the app updated from the MANAGEMENT server (not GitHub), so the operator controls rollout
 * from the panel. Checks /api/device/update, downloads the APK with the device's auth, verifies the
 * SHA-256, then installs — silently when the device is Device Owner, otherwise via the system prompt.
 * The server pulls the newest build from GitHub on its own, so this keeps the fleet up to date end to end.
 */
object ServerUpdater {
    private const val CHECK_INTERVAL = 20L * 60 * 60 * 1000 // ~daily

    suspend fun checkAndApply(ctx: Context, force: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!ServerClient.isRegistered(ctx)) return@withContext false
        val sp = ctx.getSharedPreferences("orbit", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - sp.getLong("srv_update_check", 0) < CHECK_INTERVAL) return@withContext false
        sp.edit().putLong("srv_update_check", now).apply()

        val res = ServerClient.checkUpdate(ctx) ?: return@withContext false
        if (!res.optBoolean("available")) return@withContext false
        val rel = res.optJSONObject("release") ?: return@withContext false
        if (rel.optInt("versionCode") <= BuildConfig.VERSION_CODE) return@withContext false
        val url = rel.optString("url")
        if (url.isBlank()) return@withContext false

        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "orbit-${rel.optInt("versionCode")}.apk")
        if (!ServerClient.downloadUpdate(ctx, url, file)) return@withContext false

        val want = rel.optString("sha256")
        if (want.isNotBlank() && !sha256(file).equals(want, ignoreCase = true)) {
            file.delete()
            AppLog.w("Server update sha256 mismatch, discarded")
            return@withContext false
        }
        AppInstaller.install(ctx, file)
        true
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
