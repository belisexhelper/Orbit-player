package com.orbitplayer.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.orbitplayer.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Updates straight from the app's GitHub releases: every build is published to the
 * "latest-build" release as orbit-player-<version>-<build>.apk. A higher build number = an update.
 */
class Updater(private val context: Context, private val scope: CoroutineScope) {
    companion object {
        const val REPO = "belisexhelper/Orbit-player"
        private const val RELEASE_API = "https://api.github.com/repos/$REPO/releases/tags/latest-build"
        private val APK_NAME = Regex("""orbit-player-(.+)-(\d+)\.apk""")
        private const val CHECK_EVERY_MS = 12 * 3_600_000L
    }

    data class Release(val versionName: String, val versionCode: Int, val url: String, val size: Long, val notes: String, val date: String)

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data object UpToDate : State()
        data class Available(val release: Release) : State()
        data class Downloading(val release: Release, val progress: Float) : State()
        data class Ready(val release: Release, val file: File) : State()
        data class Error(val message: String, val release: Release? = null) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private var downloadJob: Job? = null
    private val dir get() = File(context.cacheDir, "updates").apply { mkdirs() }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val currentBuild: Int get() = BuildConfig.VERSION_CODE

    /** GitHub sometimes answers 502/503/504 or drops the connection for a moment: try up to 3 times */
    private class Temporary(msg: String) : Exception(msg)
    /** the file is gone: a newer build replaced it on GitHub after the check */
    private class Gone : Exception("replaced by a newer build")

    private suspend fun <T> retrying(extraTries: Int = 2, block: () -> T): T {
        var wait = 2_000L
        repeat(extraTries) {
            try {
                return block()
            } catch (t: Temporary) {
                AppLog.w("update: ${t.message}, trying again")
            } catch (t: java.io.IOException) {
                AppLog.w("update: ${t.message}, trying again")
            }
            kotlinx.coroutines.delay(wait)
            wait = (wait * 2).coerceAtMost(8_000L)
        }
        return block()
    }

    /** Asks GitHub for the newest build. Returns it when it is newer than the installed one. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        if (_state.value is State.Downloading) return@withContext null
        _state.value = State.Checking
        try {
            val req = Request.Builder().url(RELEASE_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "OrbitPlayer-Updater")
                .build()
            val body = retrying {
                client.newCall(req).execute().use { r ->
                    when {
                        r.code == 404 -> error("No published build yet")
                        r.code == 403 -> error("GitHub is busy, try again in a few minutes")
                        r.code >= 500 -> throw Temporary("GitHub answered HTTP ${r.code}")
                        !r.isSuccessful -> error("GitHub answered HTTP ${r.code}")
                    }
                    r.body?.string() ?: error("Empty answer")
                }
            }
            prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
            val o = JSONObject(body)
            val assets = o.optJSONArray("assets")
            var release: Release? = null
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                val m = APK_NAME.matchEntire(a.optString("name")) ?: continue
                if ((m.groupValues[2].toIntOrNull() ?: 0) <= (release?.versionCode ?: -1)) continue
                release = Release(
                    versionName = m.groupValues[1],
                    versionCode = m.groupValues[2].toInt(),
                    url = a.optString("browser_download_url"),
                    size = a.optLong("size"),
                    notes = o.optString("body").trim(),
                    date = o.optString("published_at").take(10),
                )
            }
            val r = release ?: error("The release has no APK")
            if (r.versionCode > currentBuild) {
                val ready = apkFile(r)
                _state.value = if (ready.exists() && ready.length() == r.size) State.Ready(r, ready) else State.Available(r)
                r
            } else {
                _state.value = State.UpToDate
                null
            }
        } catch (t: Throwable) {
            AppLog.w("update check failed: ${t.message}")
            _state.value = State.Error(t.message ?: "Could not check for updates")
            null
        }
    }

    /** Quiet check at start-up, at most every 12 hours */
    suspend fun checkIfDue(): Release? {
        val last = prefs.getLong("lastCheck", 0)
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) return null
        val r = check()
        if (r == null && _state.value is State.Error) _state.value = State.Idle // don't show start-up errors
        return r
    }

    private fun apkFile(r: Release) = File(dir, "orbit-player-${r.versionCode}.apk")

    fun download(first: Release) {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch(Dispatchers.IO) {
          var r = first
          for (attempt in 0..1) {
            val target = apkFile(r)
            val part = File(dir, target.name + ".part")
            try {
                dir.listFiles()?.filter { it.name != part.name }?.forEach { it.delete() }
                _state.value = State.Downloading(r, 0f)
                val req = Request.Builder().url(r.url).header("User-Agent", "OrbitPlayer-Updater").build()
                retrying(extraTries = 5) { client.newCall(req).execute().use { res ->
                    if (res.code >= 500) throw Temporary("Download failed (HTTP ${res.code})")
                    if (res.code == 404) throw Gone()
                    if (!res.isSuccessful) error("Download failed (HTTP ${res.code})")
                    val body = res.body ?: error("Empty download")
                    val total = body.contentLength().takeIf { it > 0 } ?: r.size
                    var done = 0L
                    var lastShown = 0L
                    part.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(128 * 1024)
                            while (isActive) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (done - lastShown > 256 * 1024) {
                                    lastShown = done
                                    _state.value = State.Downloading(r, if (total > 0) done.toFloat() / total else 0f)
                                }
                            }
                        }
                    }
                    if (r.size > 0 && done != r.size) throw Temporary("Download incomplete")
                } }
                // must be our own app and really newer
                val info = context.packageManager.getPackageArchiveInfo(part.path, 0)
                if (info == null || info.packageName != context.packageName) error("The downloaded file is not a valid Orbit Player update")
                target.delete()
                if (!part.renameTo(target)) error("Could not save the update")
                _state.value = State.Ready(r, target)
                return@launch
            } catch (g: Gone) {
                part.delete()
                // a newer build was published meanwhile: check again and download that one
                _state.value = State.Checking // (check() does nothing while the state says downloading)
                val newer = if (attempt == 0) check() else null
                if (newer == null || newer.versionCode == r.versionCode) {
                    _state.value = State.Error("The update was replaced on GitHub, check again", r)
                    return@launch
                }
                r = newer
            } catch (t: Throwable) {
                part.delete()
                _state.value = State.Error(t.message ?: "Download failed", r)
                return@launch
            }
          }
        }
    }

    fun cancel() {
        downloadJob?.cancel()
        _state.value = State.Idle
    }

    /** Whether Android currently lets this app install updates ("Install unknown apps") */
    fun installAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the Android screen where installing apps from Orbit Player is allowed */
    fun openInstallPermission(activity: Context) {
        try {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
        } catch (_: Throwable) {
            try {
                activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } catch (_: Throwable) {
            }
        }
    }

    /** True when Android lets this app install APKs; otherwise opens the screen to allow it */
    fun canInstall(activity: Context): Boolean {
        if (installAllowed(activity)) return true
        try {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } catch (_: Throwable) {
            }
        }
        return false
    }

    /** Hands the APK to the Android installer (the user confirms the update) */
    fun install(activity: Context, file: File): Boolean {
        if (!canInstall(activity)) return false
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            activity.startActivity(intent)
            true
        } catch (t: Throwable) {
            _state.value = State.Error("This device has no installer for the update: ${t.message}")
            false
        }
    }
}
