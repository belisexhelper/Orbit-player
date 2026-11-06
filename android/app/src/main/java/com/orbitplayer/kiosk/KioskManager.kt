package com.orbitplayer.kiosk

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import com.orbitplayer.App
import com.orbitplayer.BuildConfig
import com.orbitplayer.core.AppLog
import com.orbitplayer.core.JsonUtil.objects
import com.orbitplayer.core.JsonUtil.str
import com.orbitplayer.core.Prefs
import com.orbitplayer.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.MessageDigest

data class AllowedApp(val pkg: String, val label: String)

data class LauncherConfig(
    val enabled: Boolean = false,
    val locked: Boolean = false,
    val kioskLevel: String = "auto",
    val allowedApps: List<AllowedApp> = emptyList(),
    val bootTarget: String = "",
    val blockSettings: Boolean = false,
    val showSystemSettingsTile: Boolean = true,
    val blockInstallUninstall: Boolean = false,
    val hideStatusBar: Boolean = false,
    val exitPinHash: String = "",
    val title: String = "",
    val logoUrl: String = "",
    val backgroundUrl: String = "",
    val accent: String = "",
    val welcomeText: String = "",
) {
    companion object {
        fun from(o: JSONObject): LauncherConfig {
            val b = o.optJSONObject("branding") ?: JSONObject()
            return LauncherConfig(
                enabled = o.optBoolean("enabled"),
                locked = o.optBoolean("locked"),
                kioskLevel = o.optString("kioskLevel", "auto"),
                allowedApps = (o.optJSONArray("allowedApps")?.objects() ?: emptyList()).map { AllowedApp(it.str("package"), it.str("label")) }.filter { it.pkg.isNotBlank() },
                bootTarget = o.str("bootTarget"),
                blockSettings = o.optBoolean("blockSettings"),
                showSystemSettingsTile = o.optBoolean("showSystemSettingsTile", true),
                blockInstallUninstall = o.optBoolean("blockInstallUninstall"),
                hideStatusBar = o.optBoolean("hideStatusBar"),
                exitPinHash = o.str("exitPinHash"),
                title = b.str("title"),
                logoUrl = b.str("logoUrl"),
                backgroundUrl = b.str("backgroundUrl"),
                accent = b.str("accent"),
                welcomeText = b.str("welcomeText"),
            )
        }
    }
}

/** Aplikacija instalirana na uređaju (za pločice na početnom ekranu) */
data class InstalledApp(val pkg: String, val label: String, val icon: Drawable?, val launch: Intent)

/**
 * Launcher i kiosk mod u tri nivoa:
 *  1 — aplikacija je izabrana kao početni ekran (HOME)
 *  2 — isto, ali je sistemski launcher isključen preko ADB-a (vidi docs/KIOSK.md)
 *  3 — aplikacija je Device Owner: Lock Task, blokada podešavanja, instalacije i statusne trake
 */
class KioskManager(private val context: Context, private val prefs: Prefs) {
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(context, AdminReceiver::class.java)
    private val homeAlias = ComponentName(context.packageName, "com.orbitplayer.HomeAlias")

    private val _config = MutableStateFlow(loadCached())
    val config: StateFlow<LauncherConfig> = _config.asStateFlow()

    private val _suspended = MutableStateFlow(prefs.kioskSuspendedUntil > System.currentTimeMillis())
    val suspended: StateFlow<Boolean> = _suspended.asStateFlow()

    private fun loadCached(): LauncherConfig {
        val cfg = runCatching { JSONObject(prefs.serverConfig) }.getOrNull()?.optJSONObject("launcher") ?: return LauncherConfig()
        return LauncherConfig.from(cfg)
    }

    fun isDeviceOwner(): Boolean = BuildConfig.SERVER_ENABLED && runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    fun isDefaultHome(): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val r = context.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
        return r?.activityInfo?.packageName == context.packageName
    }

    /** Launcher je uključen u panelu i korisnik ga nije isključio */
    fun launcherActive(): Boolean {
        val c = _config.value
        if (!BuildConfig.SERVER_ENABLED || !c.enabled) return false
        if (!c.locked && prefs.launcherUserDisabled) return false
        return true
    }

    /** Puni kiosk (nivo 3) je aktivan */
    fun kioskActive(): Boolean = launcherActive() && isDeviceOwner() && c().kioskLevel != "launcher" && !isSuspended()

    private fun c() = _config.value

    fun isSuspended(): Boolean {
        val s = prefs.kioskSuspendedUntil > System.currentTimeMillis()
        if (_suspended.value != s) _suspended.value = s
        return s
    }

    fun levelName(): String = when {
        !launcherActive() -> "off"
        kioskActive() -> "device_owner"
        isDefaultHome() -> "default_home"
        else -> "not_default"
    }

    fun applyConfig(json: JSONObject?) {
        _config.value = json?.let { LauncherConfig.from(it) } ?: LauncherConfig()
        applyComponents()
        applyPolicies()
    }

    /** Uključuje/isključuje HOME alias, pa se aplikacija nudi kao početni ekran samo kad treba */
    fun applyComponents() {
        if (!BuildConfig.SERVER_ENABLED) return
        val state = if (launcherActive()) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            if (context.packageManager.getComponentEnabledSetting(homeAlias) != state) {
                context.packageManager.setComponentEnabledSetting(homeAlias, state, PackageManager.DONT_KILL_APP)
            }
        }.onFailure { AppLog.w("Promena HOME komponente nije uspela", it) }
    }

    /** Pravila uređaja (samo kada je aplikacija Device Owner) */
    fun applyPolicies() {
        if (!isDeviceOwner()) return
        val active = kioskActive()
        val cfg = c()
        runCatching {
            // podrazumevani početni ekran
            dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
            if (active) {
                val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                dpm.addPersistentPreferredActivity(admin, filter, homeAlias)
            }
            // dozvoljene aplikacije u Lock Task modu
            val pkgs = (listOf(context.packageName) + cfg.allowedApps.map { it.pkg }).distinct().toTypedArray()
            dpm.setLockTaskPackages(admin, if (active) pkgs else emptyArray<String>())
            if (Build.VERSION.SDK_INT >= 28) {
                dpm.setLockTaskFeatures(admin, if (active) DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS else DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
            // instalacija i brisanje aplikacija
            for (r in listOf(UserManager.DISALLOW_INSTALL_APPS, UserManager.DISALLOW_UNINSTALL_APPS, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)) {
                if (active && cfg.blockInstallUninstall) dpm.addUserRestriction(admin, r) else dpm.clearUserRestriction(admin, r)
            }
            // fabrički reset i dodavanje korisnika uvek blokiramo u kiosk modu
            for (r in listOf(UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_ADD_USER, UserManager.DISALLOW_SAFE_BOOT)) {
                if (active) dpm.addUserRestriction(admin, r) else dpm.clearUserRestriction(admin, r)
            }
            // sistemska podešavanja
            for (pkg in SETTINGS_PACKAGES) {
                if (isInstalled(pkg)) dpm.setApplicationHidden(admin, pkg, active && cfg.blockSettings)
            }
            if (Build.VERSION.SDK_INT >= 23) {
                dpm.setStatusBarDisabled(admin, active && cfg.hideStatusBar)
                dpm.setKeyguardDisabled(admin, active)
            }
        }.onFailure { AppLog.e("Primena kiosk pravila nije uspela", it, "kiosk") }
    }

    private fun isInstalled(pkg: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(pkg, PackageManager.GET_UNINSTALLED_PACKAGES)
        true
    }.getOrDefault(false)

    /** Ulazak u Lock Task (zove MainActivity na pokretanju) */
    fun enterKiosk(activity: Activity) {
        if (!kioskActive()) return
        runCatching {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val already = if (Build.VERSION.SDK_INT >= 23) am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE else {
                @Suppress("DEPRECATION")
                am.isInLockTaskMode
            }
            if (!already) activity.startLockTask()
        }.onFailure { AppLog.w("Lock Task nije pokrenut", it) }
    }

    fun leaveKiosk(activity: Activity) {
        runCatching { activity.stopLockTask() }
    }

    fun verifyPin(pin: String): Boolean {
        val h = c().exitPinHash
        if (h.isBlank()) return false
        val hex = MessageDigest.getInstance("SHA-256").digest("launcher-pin:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
        return hex.equals(h, ignoreCase = true)
    }

    /** Privremeno izlazi iz kiosk moda (servisni pristup) */
    fun suspendFor(ms: Long) {
        prefs.kioskSuspendedUntil = System.currentTimeMillis() + ms
        _suspended.value = true
        applyPolicies()
        App.instance.sync.emit(com.orbitplayer.server.UiEvent.Toast("Kiosk mod je isključen na ${ms / 60000} minuta"))
    }

    fun resumeKiosk() {
        prefs.kioskSuspendedUntil = 0
        _suspended.value = false
        applyPolicies()
    }

    fun setUserDisabled(disabled: Boolean) {
        if (c().locked) return
        prefs.launcherUserDisabled = disabled
        applyComponents()
        applyPolicies()
        _config.value = _config.value.copy() // osveži posmatrače
    }

    fun reboot(): Boolean {
        if (!isDeviceOwner() || Build.VERSION.SDK_INT < 24) return false
        dpm.reboot(admin)
        return true
    }

    /** Uklanja Device Owner (pre vraćanja uređaja ili deinstalacije) */
    fun removeDeviceOwner() {
        if (!isDeviceOwner()) return
        prefs.kioskSuspendedUntil = Long.MAX_VALUE
        applyPolicies()
        @Suppress("DEPRECATION")
        runCatching { dpm.clearDeviceOwnerApp(context.packageName) }
    }

    /** Otvara izbor početnog ekrana (nivo 1) */
    fun openHomeChooser(activity: Activity): Boolean {
        val attempts = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        for (i in attempts) {
            if (runCatching { activity.startActivity(i); true }.getOrDefault(false)) return true
        }
        return false
    }

    /** Aplikacije za pločice na početnom ekranu, redom kako ih je admin poređao */
    fun allowedInstalledApps(): List<InstalledApp> {
        val pm = context.packageManager
        return c().allowedApps.mapNotNull { a ->
            val launch = pm.getLeanbackLaunchIntentForPackage(a.pkg) ?: pm.getLaunchIntentForPackage(a.pkg) ?: return@mapNotNull null
            val info = runCatching { pm.getApplicationInfo(a.pkg, 0) }.getOrNull()
            InstalledApp(a.pkg, a.label.ifBlank { info?.loadLabel(pm)?.toString() ?: a.pkg }, info?.loadIcon(pm), launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Sve aplikacije sa ikonom (za dijagnostiku i kada launcher nije ograničen) */
    fun allLaunchableApps(): List<InstalledApp> {
        val pm = context.packageManager
        val seen = HashSet<String>()
        val out = ArrayList<InstalledApp>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName || !seen.add(pkg)) continue
                val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: continue
                out += InstalledApp(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm), launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    fun openSystemSettings(activity: Activity) {
        runCatching { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    companion object {
        val SETTINGS_PACKAGES = listOf("com.android.settings", "com.android.tv.settings", "com.google.android.tv.settings", "com.amazon.tv.settings.v2")
    }
}

/** Prijemnik za Device Owner. Postavlja se ADB komandom ili QR provisioningom (docs/KIOSK.md). */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        AppLog.i("Device admin uključen", "kiosk")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
    }
}

/** Pokretanje pri paljenju uređaja */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as App
        app.kiosk.applyComponents()
        app.kiosk.applyPolicies()
        val wantStart = app.repo.settings.value.autostartOnBoot || (app.kiosk.launcherActive() && app.kiosk.config.value.bootTarget.isNotBlank())
        if (wantStart && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_FROM_BOOT, true)
            runCatching { context.startActivity(i) }.onFailure { AppLog.w("Pokretanje pri paljenju nije dozvoljeno", it) }
        }
    }
}
