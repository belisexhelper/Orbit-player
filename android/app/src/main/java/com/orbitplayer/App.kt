package com.orbitplayer

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
class App : Application() {
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
    }
}

/** The saved crash report from the last run (read once) */
fun App.takeLastCrash(): String? {
    val f = java.io.File(filesDir, "last-crash.txt")
    if (!f.exists()) return null
    val text = runCatching { f.readText() }.getOrNull()
    f.delete()
    return text
}
