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

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        db = AppDatabase.create(this)
        repo = ContentRepository(this, db, store)
        playback = PlaybackManager(this, store, repo)
        downloads = Downloads(this, db)
    }
}
