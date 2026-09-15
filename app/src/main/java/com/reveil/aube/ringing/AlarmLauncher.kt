package com.reveil.aube.ringing

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.reveil.aube.NotifChannels
import com.reveil.aube.R

private const val FULL_SCREEN_NOTIF_ID = 42
const val EXTRA_DAWN_START_MILLIS = "extra_dawn_start_millis"
const val EXTRA_DAWN_END_MILLIS = "extra_dawn_end_millis"
/** Set when a ring is being resumed after the device went down mid-ring — see [resumeAlarmAfterBoot]. */
const val EXTRA_RESUMED_AFTER_REBOOT = "extra_resumed_after_reboot"

/**
 * Wakes the device and shows [AlarmActivity]. Uses both a full-screen-intent notification
 * (the documented, reliable way to reach the lock screen on modern Android) and, by default,
 * a direct activity launch as a belt-and-suspenders fallback for OEMs that behave
 * inconsistently.
 *
 * [dawnStartMillis]/[dawnEndMillis] describe the screen's warm-to-white light ramp: the
 * loud alarm sound and forced dismiss only kick in once [dawnEndMillis] is reached, so a
 * ramp already in the past (start == end) means "ring immediately, no light phase".
 *
 * [directLaunch] exists for [com.reveil.aube.alarm.BootReceiver], the one caller that runs
 * this moments after boot: `startActivity()` there raced the lock screen's own window still
 * initializing and lost, an "Input dispatching timed out... Waited 8000ms for
 * FocusEvent(hasFocus=false)" ANR confirmed via the actual trace — not a guess. Skipping the
 * direct call there and leaning on the notification's full-screen intent alone sidesteps that
 * race: it's the OS's own documented mechanism for this exact "reach the lock screen safely"
 * job, and it waits for the system to actually be ready rather than forcing the window now.
 */
fun launchAlarm(context: Context, dawnStartMillis: Long, dawnEndMillis: Long, directLaunch: Boolean = true) {
    val fullScreenIntent = Intent(context, AlarmActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_NO_USER_ACTION or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(EXTRA_DAWN_START_MILLIS, dawnStartMillis)
        putExtra(EXTRA_DAWN_END_MILLIS, dawnEndMillis)
    }
    val fullScreenPendingIntent = PendingIntent.getActivity(
        context, 0, fullScreenIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, NotifChannels.ALARM)
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(context.getString(R.string.alarm_ringing_message))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setFullScreenIntent(fullScreenPendingIntent, true)
        .setContentIntent(fullScreenPendingIntent)
        .setAutoCancel(true)
        .setOngoing(true)
        // Defense in depth: if launchAlarm ever gets called a second time for an already-
        // ringing alarm (a stray reschedule restarting the tracking service mid-cycle, say),
        // re-posting this same notification ID should silently update it, not re-alert with
        // sound/vibration/heads-up as if it were new.
        .setOnlyAlertOnce(true)
        .build()

    if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        NotificationManagerCompat.from(context).notify(FULL_SCREEN_NOTIF_ID, notification)
    }

    if (directLaunch) {
        context.startActivity(fullScreenIntent)
    }
}

/**
 * The boot-time counterpart of [launchAlarm], for a ring the device went down in the middle
 * of. The one thing that can't be blocked on any phone is the hardware forced reboot (power
 * held ~10 s, handled by the power-management chip before the OS is involved), so the answer
 * is to make it pointless: sound comes back from the service itself, right now, in the first
 * seconds after boot — not from a screen that may never appear. A full-screen notification
 * alone (the previous approach) was confirmed on a real device to leave nothing but a silent
 * "Time to get up" in the tray when the OEM declined to show it right after boot. The
 * service then brings the pinned screen back a few seconds later, once the system has
 * settled — see [AlarmRingingService.ACTION_RESUME_AFTER_BOOT] — and, because the ring is
 * a resumption, the one-time mute is already spent (see [EXTRA_RESUMED_AFTER_REBOOT]).
 */
fun resumeAlarmAfterBoot(context: Context, musicUri: String?, vibrationEnabled: Boolean) {
    val now = System.currentTimeMillis()
    launchAlarm(context, dawnStartMillis = now, dawnEndMillis = now, directLaunch = false)
    val serviceIntent = Intent(context, AlarmRingingService::class.java).apply {
        action = AlarmRingingService.ACTION_RESUME_AFTER_BOOT
        putExtra(EXTRA_DAWN_START_MILLIS, now)
        putExtra(EXTRA_DAWN_END_MILLIS, now)
        putExtra(AlarmRingingService.EXTRA_URI, musicUri)
        putExtra(AlarmRingingService.EXTRA_VIBRATE, vibrationEnabled)
    }
    ContextCompat.startForegroundService(context, serviceIntent)
}

fun dismissAlarmNotification(context: Context) {
    NotificationManagerCompat.from(context).cancel(FULL_SCREEN_NOTIF_ID)
}
