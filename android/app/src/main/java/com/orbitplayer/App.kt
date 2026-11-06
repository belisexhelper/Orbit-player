package com.orbitplayer

import android.app.Application
import com.orbitplayer.core.AppLog
import com.orbitplayer.core.Net
import com.orbitplayer.core.Prefs
import com.orbitplayer.data.ContentRepository
import com.orbitplayer.data.db.AppDatabase
import com.orbitplayer.download.Downloads
import com.orbitplayer.kiosk.KioskManager
import com.orbitplayer.server.DeviceLink
import com.orbitplayer.server.ServerApi
import com.orbitplayer.server.ServerSync
import com.orbitplayer.server.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Ulazna tačka aplikacije. Drži sve deljene objekte (jednostavan „ručni DI”),
 * da ne bi zavisili od dodatnih biblioteka.
 */
class App : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var prefs: Prefs; private set
    lateinit var db: AppDatabase; private set
    lateinit var repo: ContentRepository; private set
    lateinit var serverApi: ServerApi; private set
    lateinit var sync: ServerSync; private set
    lateinit var link: DeviceLink; private set
    lateinit var kiosk: KioskManager; private set
    lateinit var updater: Updater; private set
    lateinit var downloads: Downloads; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLog.install(this)
        prefs = Prefs(this)
        db = AppDatabase.create(this)
        repo = ContentRepository(this, db, prefs)
        serverApi = ServerApi(this, prefs, Net.api)
        kiosk = KioskManager(this, prefs)
        updater = Updater(this, prefs, serverApi)
        downloads = Downloads(this, db)
        sync = ServerSync(this)
        link = DeviceLink(this)
        if (BuildConfig.SERVER_ENABLED) {
            sync.start()
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
