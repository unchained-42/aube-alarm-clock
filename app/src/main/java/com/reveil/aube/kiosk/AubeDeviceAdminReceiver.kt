package com.reveil.aube.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "AubeDeviceAdmin"

/**
 * The component `adb shell dpm set-device-owner` points at. Being device owner is what
 * unlocks every policy in [KioskPolicy] — none of it is available to a plain app, root or
 * not. Nothing interesting happens in the callbacks themselves: the policies are (re)applied
 * on every process start too, so a missed callback never leaves the device half-hardened.
 */
class AubeDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin enabled, deviceOwner=${KioskPolicy.isDeviceOwner(context)}")
        KioskPolicy.applyHardening(context)
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        Log.i(TAG, "lock task entering: $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        Log.i(TAG, "lock task exiting")
    }
}
