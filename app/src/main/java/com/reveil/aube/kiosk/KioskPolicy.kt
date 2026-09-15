package com.reveil.aube.kiosk

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log

private const val TAG = "AubeKioskPolicy"

/**
 * Everything the app is allowed to lock down once it is the device owner ("hardcore mode").
 *
 * The problem this exists for: the power button. A long press opens the system's power menu
 * and "Power off" ends any alarm, whatever the app does — Android reserves that key at the
 * system level, and [com.reveil.aube.ringing.AlarmActivity]'s key interception never sees
 * it. The only sanctioned way for an app to disable that menu is lock task mode *as device
 * owner*: with the package allowlisted and [DevicePolicyManager.LOCK_TASK_FEATURE_NONE],
 * SystemUI hides the power menu, status bar, Home, Recents and keyguard for as long as the
 * alarm screen is pinned, and the unpin gesture stops working — only the app's own
 * `stopLockTask()` (called after a real scan) leaves.
 *
 * Device owner is a one-time enrollment done over adb on a phone with no accounts and no
 * secondary users — see [HardcoreModeScreen][com.reveil.aube.settings.HardcoreModeScreen]
 * for the exact command. Every method here is a no-op when the app isn't device owner, so
 * the rest of the app never needs to know which mode it's running in.
 *
 * What it deliberately does NOT do: disable USB debugging. adb is the recovery path if
 * anything goes wrong (a crash loop while pinned, a lost QR card), and [release] is only
 * reachable from the in-app settings, so keeping adb open is what makes this reversible.
 */
object KioskPolicy {

    fun admin(context: Context): ComponentName =
        ComponentName(context, AubeDeviceAdminReceiver::class.java)

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)

    fun isDeviceOwner(context: Context): Boolean =
        try {
            dpm(context).isDeviceOwnerApp(context.packageName)
        } catch (_: Exception) {
            false
        }

    /**
     * Idempotent; safe (and cheap) to call on every process start. Every call is wrapped
     * individually so one policy an OEM refuses doesn't stop the ones after it from landing.
     */
    fun applyHardening(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        val pkg = context.packageName

        // The core of it: makes startLockTask() enter a real (un-unpinnable) lock task, with
        // every SystemUI affordance off — including the long-press-power menu.
        attempt("setLockTaskPackages") { dpm.setLockTaskPackages(admin, arrayOf(pkg)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // The default when this is never called is LOCK_TASK_FEATURE_GLOBAL_ACTIONS —
            // i.e. the power menu stays available. It has to be set to NONE explicitly.
            attempt("setLockTaskFeatures") {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
        }

        // Closes the ways around a ring that don't go through the alarm screen at all.
        val restrictions = buildList {
            add(UserManager.DISALLOW_SAFE_BOOT)          // safe mode disables third-party apps
            add(UserManager.DISALLOW_FACTORY_RESET)      // Settings > Reset
            add(UserManager.DISALLOW_APPS_CONTROL)       // Force stop / Clear data / Uninstall in Settings
            add(UserManager.DISALLOW_UNINSTALL_APPS)
            add(UserManager.DISALLOW_ADD_USER)           // a second user has none of these policies
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(UserManager.DISALLOW_CONFIG_DATE_TIME) // moving the clock past the alarm
            }
        }
        restrictions.forEach { r -> attempt("addUserRestriction($r)") { dpm.addUserRestriction(admin, r) } }
        attempt("setUninstallBlocked") { dpm.setUninstallBlocked(admin, pkg, true) }

        // Belt and braces with DISALLOW_CONFIG_DATE_TIME: the clock follows the network.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            attempt("setAutoTimeEnabled") { dpm.setAutoTimeEnabled(admin, true) }
        } else {
            @Suppress("DEPRECATION")
            attempt("setAutoTimeRequired") { dpm.setAutoTimeRequired(admin, true) }
        }

        // A phone left charging overnight never turns its screen off, so the dawn ramp is
        // actually visible. 7 = AC | USB | WIRELESS.
        attempt("STAY_ON_WHILE_PLUGGED_IN") {
            dpm.setGlobalSetting(
                admin,
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                (BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB or
                    BatteryManager.BATTERY_PLUGGED_WIRELESS).toString()
            )
        }

        // A forced reboot (power held ~10 s, hardware-level, unblockable) is caught by
        // BootReceiver, which resumes the ring — but only once BOOT_COMPLETED fires, and on a
        // phone with a PIN that waits for the first unlock. No keyguard means the ring
        // resumes on its own. Only takes effect when no PIN/pattern is set; a dedicated alarm
        // phone shouldn't have one.
        attempt("setKeyguardDisabled") { dpm.setKeyguardDisabled(admin, true) }

        // The runtime permissions the alarm can't work without, granted for good so a
        // mis-tap on a system dialog can never leave the scan screen without a camera.
        val runtimePermissions = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        runtimePermissions.forEach { permission ->
            attempt("grant($permission)") {
                dpm.setPermissionGrantState(
                    admin, pkg, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            }
        }
        Log.i(TAG, "hardening applied")
    }

    /**
     * Undoes everything [applyHardening] set, then gives up device ownership entirely.
     * Deliberately not reachable while an alarm is ringing — that's the whole point — but
     * it has to exist: the only other way out is a factory reset.
     */
    fun release(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        val pkg = context.packageName

        listOf(
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_APPS_CONTROL,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_CONFIG_DATE_TIME
        ).forEach { r -> attempt("clearUserRestriction($r)") { dpm.clearUserRestriction(admin, r) } }
        attempt("setUninstallBlocked(false)") { dpm.setUninstallBlocked(admin, pkg, false) }
        attempt("setKeyguardDisabled(false)") { dpm.setKeyguardDisabled(admin, false) }
        attempt("setLockTaskPackages(empty)") { dpm.setLockTaskPackages(admin, emptyArray()) }
        attempt("STAY_ON_WHILE_PLUGGED_IN(0)") {
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "0")
        }
        // Last — nothing above works once this has run.
        attempt("clearDeviceOwnerApp") { dpm.clearDeviceOwnerApp(pkg) }
        Log.i(TAG, "device ownership released")
    }

    private inline fun attempt(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // Never fatal — a refused policy is logged so a device where one consistently
            // fails is diagnosable from a bug report, but the rest still applies.
            Log.w(TAG, "$what failed", e)
        }
    }
}
