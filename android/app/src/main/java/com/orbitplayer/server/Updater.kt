package com.orbitplayer.server

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.orbitplayer.App
import com.orbitplayer.BuildConfig
import com.orbitplayer.core.AppLog
import com.orbitplayer.core.JsonUtil.str
import com.orbitplayer.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class UpdateState(
    val checking: Boolean = false,
    val available: Boolean = false,
    val versionName: String = "",
    val changelog: String = "",
    val mandatory: Boolean = false,
    val progress: Float = -1f,
    val message: String = "",
    val needsPermission: Boolean = false,
)

/**
 * Samoažuriranje (samo Pro verzija): proverava server, preuzima APK, proverava kontrolni zbir
 * i instalira. Na uređaju u kiosk nivou 3 instalacija je tiha, inače Android traži potvrdu.
 */
class Updater(private val context: Context, private val prefs: Prefs, private val api: ServerApi) {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var pending: JSONObject? = null

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= 26) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(i) }
        }
    }

    /** Proverava novu verziju; vraća opis za admin panel. */
    suspend fun check(): JSONObject? = withContext(Dispatchers.IO) {
        if (!BuildConfig.SERVER_ENABLED || !api.isRegistered) return@withContext null
        _state.value = _state.value.copy(checking = true, message = "")
        try {
            val channel = App.instance.repo.settings.value.updateChannel
            val r = api.checkUpdate(channel)
            prefs.lastUpdateCheck = System.currentTimeMillis()
            if (!r.optBoolean("available")) {
                pending = null
                _state.value = UpdateState(message = "Imate najnoviju verziju (${BuildConfig.VERSION_NAME}).")
                return@withContext null
            }
            val rel = r.getJSONObject("release")
            pending = rel
            _state.value = UpdateState(
                available = true,
                versionName = rel.str("versionName"),
                changelog = rel.str("changelog"),
                mandatory = rel.optBoolean("mandatory"),
            )
            rel
        } catch (e: Exception) {
            _state.value = UpdateState(message = "Provera nije uspela: ${e.message}")
            null
        }
    }

    /**
     * Proveri i instaliraj. Bez korisnika (komanda/pozadina) instalira se samo tiho (kiosk nivo 3)
     * ili ako je ažuriranje obavezno — inače se samo prikaže obaveštenje.
     */
    suspend fun checkAndInstall(userInitiated: Boolean): JSONObject = withContext(Dispatchers.IO) {
        val rel = check() ?: return@withContext JSONObject().put("available", false).put("current", BuildConfig.VERSION_NAME)
        val silent = App.instance.kiosk.isDeviceOwner()
        if (userInitiated || silent || rel.optBoolean("mandatory")) install()
        JSONObject().put("available", true).put("version", rel.str("versionName")).put("installing", userInitiated || silent)
    }

    suspend fun install() = withContext(Dispatchers.IO) {
        val rel = pending ?: check() ?: return@withContext
        val silent = App.instance.kiosk.isDeviceOwner()
        if (!silent && !canInstall()) {
            _state.value = _state.value.copy(needsPermission = true, message = "Dozvolite instalaciju aplikacija iz ovog izvora, pa pokušajte ponovo.")
            return@withContext
        }
        val file = File(context.cacheDir, "updates/update.apk")
        try {
            api.download(rel.str("url"), file, rel.str("sha256")) { done, total ->
                if (total > 0) _state.value = _state.value.copy(progress = done.toFloat() / total, message = "Preuzimam novu verziju…")
            }
            _state.value = _state.value.copy(progress = 1f, message = "Instaliram…")
            installApk(file)
        } catch (e: Exception) {
            AppLog.e("Ažuriranje nije uspelo", e, "update")
            _state.value = _state.value.copy(progress = -1f, message = "Ažuriranje nije uspelo: ${e.message}")
        }
    }

    private fun installApk(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    input.copyTo(out, 64 * 1024)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
            val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pi.intentSender)
        }
    }

    fun onInstallStatus(status: Int, message: String?) {
        if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            _state.value = _state.value.copy(progress = -1f, message = "Instalacija nije uspela: ${message ?: status}")
            AppLog.w("Instalacija nove verzije nije uspela: $status $message")
        }
    }
}

/** Prima rezultat instalacije; ako Android traži potvrdu korisnika, otvara taj ekran. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
        }
        App.instance.updater.onInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
