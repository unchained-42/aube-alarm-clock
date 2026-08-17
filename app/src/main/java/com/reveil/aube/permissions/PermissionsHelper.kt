package com.reveil.aube.permissions

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.reveil.aube.R

/** Small wrapper around the handful of system checks/intents an alarm app needs to be reliable. */
object PermissionsHelper {

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java)
        return manager.canScheduleExactAlarms()
    }

    fun exactAlarmSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))

    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.canUseFullScreenIntent()
    }

    fun fullScreenIntentSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.parse("package:${context.packageName}")
        )

    /**
     * "Display over other apps" — what lets [com.reveil.aube.ringing.AlarmOverlayController]
     * keep a blocking screen on top of the launcher, recents, or Settings for as long as the
     * alarm hasn't been dismissed. A real, verifiable, non-root permission.
     */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    /**
     * Several Android manufacturers layer their own background-restriction system on top of
     * stock Android's — an "autostart"/"protected apps"/"sleeping apps" list that stock
     * Android has no API to check or request. Without it, the OEM's own battery manager can
     * silently kill the tracking service overnight or block the alarm screen from actually
     * showing over the lock screen — the most likely explanation for an alarm that rings (or
     * doesn't) without the dawn screen ever appearing. Class names come from what each OEM
     * actually ships (per widely-documented reports, e.g. dontkillmyapp.com) and change
     * across firmware versions, so every candidate is verified resolvable before use —
     * pointing at a screen that doesn't exist on this exact device would be worse than not
     * showing the check at all.
     */
    fun oemAutostartIntent(context: Context): Intent? {
        val candidate = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi" -> Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            "huawei", "honor" -> Intent().setClassName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
            "oppo", "realme" -> Intent().setClassName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            )
            "vivo" -> Intent().setClassName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
            "oneplus" -> Intent().setClassName(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            )
            "asus" -> Intent().setClassName(
                "com.asus.mobilemanager",
                "com.asus.mobilemanager.autostart.AutoStartActivity"
            )
            "samsung" -> Intent().setClassName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
            else -> null
        } ?: return null
        return if (context.packageManager.resolveActivity(candidate, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            candidate
        } else {
            null
        }
    }
}

data class ReliabilityCheck(val label: String, val hint: String? = null, val intent: (Context) -> Intent)

/**
 * The checks that gate turning the alarm on. Every one of these has a real Android API that
 * reports true/false, so "granted" is a fact, not a guess — which is exactly why the MIUI
 * autostart/pop-up permission below is deliberately NOT in this list: Xiaomi exposes no API
 * to read that state back. Gating on something that can never be confirmed true would mean
 * the alarm could never be turned on no matter what the user does — worse than not gating
 * on it at all.
 */
fun missingBlockingChecks(context: Context): List<ReliabilityCheck> {
    val list = mutableListOf<ReliabilityCheck>()
    if (!PermissionsHelper.canScheduleExactAlarms(context)) {
        list += ReliabilityCheck(context.getString(R.string.check_exact_alarms)) { PermissionsHelper.exactAlarmSettingsIntent(it) }
    }
    if (!PermissionsHelper.notificationsEnabled(context)) {
        list += ReliabilityCheck(context.getString(R.string.check_notifications)) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName)
        }
    }
    if (!PermissionsHelper.isIgnoringBatteryOptimizations(context)) {
        // The system screen this opens varies a lot by phone (a plain Allow/Deny dialog on
        // some, a multi-option battery menu on others like MIUI) — the label on the option
        // to pick varies too, so this points at the goal rather than an exact wording.
        list += ReliabilityCheck(
            context.getString(R.string.check_battery),
            hint = context.getString(R.string.check_battery_hint)
        ) { PermissionsHelper.batteryOptimizationIntent(it) }
    }
    if (!PermissionsHelper.canUseFullScreenIntent(context)) {
        list += ReliabilityCheck(context.getString(R.string.check_fullscreen)) { PermissionsHelper.fullScreenIntentSettingsIntent(it) }
    }
    if (!PermissionsHelper.canDrawOverlays(context)) {
        list += ReliabilityCheck(
            context.getString(R.string.check_overlay),
            hint = context.getString(R.string.check_overlay_hint)
        ) { PermissionsHelper.overlaySettingsIntent(it) }
    }
    return list
}

/**
 * Shown as a strong recommendation, never as a gate — see [missingBlockingChecks] for why.
 * Null whenever this manufacturer has no known, resolvable autostart screen — covers both
 * OEMs without this kind of restriction and ones this app doesn't recognize.
 */
fun oemAutostartAdvisoryCheck(context: Context): ReliabilityCheck? {
    val intent = PermissionsHelper.oemAutostartIntent(context) ?: return null
    return ReliabilityCheck(
        context.getString(R.string.check_oem_autostart),
        hint = context.getString(R.string.check_oem_autostart_hint)
    ) { intent }
}
