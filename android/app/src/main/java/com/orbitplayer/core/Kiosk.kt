package com.orbitplayer.core

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.UserManager
import java.lang.ref.WeakReference

/**
 * Kiosk / lock-down in three tiers, as strong as the device allows:
 *  - level 1 (ordinary): Orbit is the home screen; "lock" uses screen pinning (user can still leave).
 *  - level 3 (Device Owner or system app): true kiosk — lock task, no status bar, no install/uninstall,
 *    forced home, no safe-boot/factory-reset escape. Set up once with
 *    `adb shell dpm set-device-owner com.orbitplayer/com.orbitplayer.core.OrbitDeviceAdminReceiver`.
 * Everything is driven by the launcher config pushed from the panel (enabled/locked/kioskLevel/…).
 */
object Kiosk {
    @Volatile private var current: WeakReference<Activity>? = null
    @Volatile private var pausedUntil = 0L // service access: don't re-lock until this time

    private fun dpm(c: Context) = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(c: Context) = ComponentName(c, OrbitDeviceAdminReceiver::class.java)

    fun isDeviceOwner(c: Context): Boolean =
        runCatching { dpm(c).isDeviceOwnerApp(c.packageName) }.getOrDefault(false)

    private fun isSystemApp(c: Context): Boolean {
        val f = c.applicationInfo.flags
        return (f and ApplicationInfo.FLAG_SYSTEM) != 0 || (f and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    /** 3 = full kiosk possible (Device Owner / system), 1 = ordinary. */
    fun level(c: Context): Int = if (isDeviceOwner(c) || isSystemApp(c)) 3 else 1

    /** Status token the panel understands: off | not_default | default_home | device_owner. */
    fun statusLevel(c: Context): String = when {
        !Launcher.enabled(c) -> "off"
        isDeviceOwner(c) -> "device_owner"
        Launcher.isDefaultHome(c) -> "default_home"
        else -> "not_default"
    }

    fun levelLabel(c: Context): String = when {
        isDeviceOwner(c) -> "3 — Device Owner (pravi kiosk)"
        isSystemApp(c) -> "3 — sistemska aplikacija"
        else -> "1 — obični launcher"
    }

    /** The adb command an installer runs to promote this app to Device Owner. */
    fun setupCommand(c: Context) =
        "adb shell dpm set-device-owner ${c.packageName}/com.orbitplayer.core.OrbitDeviceAdminReceiver"

    private data class Cfg(
        val enabled: Boolean, val locked: Boolean, val wantedLevel: String,
        val hideStatusBar: Boolean, val blockSettings: Boolean, val blockInstallUninstall: Boolean,
    )

    private fun cfg(c: Context): Cfg {
        val p = c.getSharedPreferences("orbit", Context.MODE_PRIVATE)
        return Cfg(
            enabled = p.getBoolean("launcher_enabled", false),
            locked = p.getBoolean("launcher_locked", false),
            wantedLevel = p.getString("kiosk_level", "auto") ?: "auto",
            hideStatusBar = p.getBoolean("kiosk_hide_status", false),
            blockSettings = p.getBoolean("kiosk_block_settings", false),
            blockInstallUninstall = p.getBoolean("kiosk_block_install", false),
        )
    }

    /** Enforce the kiosk for [activity] (call from the launcher's onResume). */
    fun apply(activity: Activity) {
        current = WeakReference(activity)
        val c = activity.applicationContext
        val cfg = cfg(c)
        val owner = isDeviceOwner(c)

        if (owner) applyOwner(activity, cfg)

        val shouldLock = cfg.enabled && cfg.locked && System.currentTimeMillis() >= pausedUntil &&
            cfg.wantedLevel != "none"
        if (shouldLock) startLock(activity) else stopLock(activity)
    }

    /** Owner-only hardening: forced home, lock-task allowlist, status bar, restrictions. */
    private fun applyOwner(activity: Activity, cfg: Cfg) {
        val c = activity.applicationContext
        val dpm = dpm(c); val admin = admin(c)
        runCatching {
            if (cfg.enabled) {
                // Force Orbit as home so it can't be swapped out (the only way to become the launcher
                // on Google TV, which blocks third-party launchers through normal settings).
                val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
                }
                dpm.addPersistentPreferredActivity(admin, filter, ComponentName(c, "com.orbitplayer.ui.LauncherActivity"))
            } else {
                // Launcher turned off in the panel: hand home back to the system launcher.
                dpm.clearPackagePersistentPreferredActivities(admin, c.packageName)
            }
        }
        runCatching {
            val pkgs = (listOf(c.packageName) + Launcher.tiles(c).map { it.pkg }).distinct().toTypedArray()
            dpm.setLockTaskPackages(admin, pkgs)
        }
        runCatching { dpm.setStatusBarDisabled(admin, cfg.enabled && cfg.locked && cfg.hideStatusBar) }
        setRestriction(dpm, admin, UserManager.DISALLOW_INSTALL_APPS, cfg.blockInstallUninstall)
        setRestriction(dpm, admin, UserManager.DISALLOW_UNINSTALL_APPS, cfg.blockInstallUninstall)
        // A locked kiosk must not be escapable via safe mode or factory reset.
        val hardened = cfg.enabled && cfg.locked
        setRestriction(dpm, admin, UserManager.DISALLOW_SAFE_BOOT, hardened)
        setRestriction(dpm, admin, UserManager.DISALLOW_FACTORY_RESET, hardened)
        // Hide the system Settings app when asked (reliable way to block it at tier 3).
        runCatching { dpm.setApplicationHidden(admin, "com.android.settings", hardened && cfg.blockSettings) }
        runCatching { dpm.setApplicationHidden(admin, "com.android.tv.settings", hardened && cfg.blockSettings) }
    }

    private fun setRestriction(dpm: DevicePolicyManager, admin: ComponentName, key: String, on: Boolean) {
        runCatching { if (on) dpm.addUserRestriction(admin, key) else dpm.clearUserRestriction(admin, key) }
    }

    private fun startLock(activity: Activity) {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val state = if (Build.VERSION.SDK_INT >= 23) am.lockTaskModeState else
            @Suppress("DEPRECATION") if (am.isInLockTaskMode) ActivityManager.LOCK_TASK_MODE_LOCKED else ActivityManager.LOCK_TASK_MODE_NONE
        if (state == ActivityManager.LOCK_TASK_MODE_NONE) {
            // Owner: locks silently. Ordinary: enters screen pinning (user can still exit).
            runCatching { activity.startLockTask() }
        }
    }

    fun stopLock(activity: Activity) {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val state = if (Build.VERSION.SDK_INT >= 23) am.lockTaskModeState else ActivityManager.LOCK_TASK_MODE_NONE
        if (state != ActivityManager.LOCK_TASK_MODE_NONE) runCatching { activity.stopLockTask() }
    }

    /** Temporarily leave the kiosk (service access); re-locks after [minutes] or on next config reload. */
    fun pause(minutes: Int = 10) {
        pausedUntil = System.currentTimeMillis() + minutes * 60_000L
        current?.get()?.let { a -> a.runOnUiThread { stopLock(a) } }
    }

    fun resume() { pausedUntil = 0L; current?.get()?.let { a -> a.runOnUiThread { apply(a) } } }

    /** Checks a service PIN against the hash pushed from the panel. */
    fun verifyExitPin(c: Context, pin: String): Boolean {
        val hash = c.getSharedPreferences("orbit", Context.MODE_PRIVATE).getString("kiosk_exit_pin_hash", null)
            ?: return false
        return sha256("launcher-pin:${pin.trim()}") == hash
    }

    fun hasExitPin(c: Context): Boolean =
        c.getSharedPreferences("orbit", Context.MODE_PRIVATE).getString("kiosk_exit_pin_hash", null) != null

    /** Reboots the device (Device Owner only). Returns false when not permitted. */
    fun reboot(c: Context): Boolean {
        if (!isDeviceOwner(c)) return false
        return runCatching { dpm(c).reboot(admin(c)); true }.getOrDefault(false)
    }

    private fun sha256(s: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }
}
