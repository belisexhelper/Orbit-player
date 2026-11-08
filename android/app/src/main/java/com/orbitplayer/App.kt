package com.orbitplayer

import kotlinx.coroutines.launch
import okhttp3.ResponseBody.Companion.toResponseBody
import android.app.Application
import com.orbitplayer.core.Store
import com.orbitplayer.data.ContentRepository
import com.orbitplayer.data.Downloads
import com.orbitplayer.data.db.AppDatabase
import com.orbitplayer.playback.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the shared objects of the app (simple manual dependency injection). */
class App : Application(), coil.ImageLoaderFactory {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var store: Store; private set
    lateinit var db: AppDatabase; private set
    lateinit var repo: ContentRepository; private set
    lateinit var playback: PlaybackManager; private set
    lateinit var downloads: Downloads; private set
    lateinit var updater: com.orbitplayer.core.Updater; private set

    override fun onCreate() {
        super.onCreate()
        // Keep the reason of a crash; it is shown on the next start so it can be reported
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                java.io.File(filesDir, "last-crash.txt").writeText(
                    "Orbit Player ${BuildConfig.VERSION_NAME} build ${BuildConfig.VERSION_CODE} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}\n" +
                        "Thread: ${t.name}\n\n" + e.stackTraceToString().take(6000),
                )
            }
            previous?.uncaughtException(t, e)
        }
        store = Store(this)
        db = AppDatabase.create(this)
        repo = ContentRepository(this, db, store)
        playback = PlaybackManager(this, store, repo)
        downloads = Downloads(this, db)
        updater = com.orbitplayer.core.Updater(this, appScope)
        appScope.launch { com.orbitplayer.data.LogoFinder.loadSaved(this@App) }
    }

    /**
     * Images: a deleted imgur picture comes back as imgur's "image no longer available" placeholder
     * (a redirect to removed.png). Treat it as missing, so the channel shows its initials instead.
     */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .okHttpClient {
            okhttp3.OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val res = chain.proceed(chain.request())
                    if (res.request.url.encodedPath.endsWith("/removed.png")) {
                        res.close()
                        okhttp3.Response.Builder()
                            .request(res.request).protocol(res.protocol).code(404).message("Removed image")
                            .body(ByteArray(0).toResponseBody(null))
                            .build()
                    } else res
                }
                .build()
        }
        .crossfade(true)
        .build()
}

/** The saved crash report from the last run (read once) */
fun App.takeLastCrash(): String? {
    val f = java.io.File(filesDir, "last-crash.txt")
    if (!f.exists()) return null
    val text = runCatching { f.readText() }.getOrNull()
    f.delete()
    return text
}
