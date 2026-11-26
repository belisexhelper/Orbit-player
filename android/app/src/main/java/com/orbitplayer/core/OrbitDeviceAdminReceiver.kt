package com.orbitplayer.core

import android.app.admin.DeviceAdminReceiver

/**
 * Device admin / Device Owner receiver. Present so the app can be promoted to Device Owner
 * (kiosk tier 3) with:
 *   adb shell dpm set-device-owner com.orbitplayer/com.orbitplayer.core.OrbitDeviceAdminReceiver
 * on a freshly set-up device (no accounts added yet). No code is needed here; being a registered
 * admin component is enough for DevicePolicyManager to grant the owner APIs.
 */
class OrbitDeviceAdminReceiver : DeviceAdminReceiver()
