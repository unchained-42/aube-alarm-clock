package com.reveil.aube.ringing

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.reveil.aube.NotifChannels
import com.reveil.aube.R

private const val FULL_SCREEN_NOTIF_ID = 42
const val EXTRA_DAWN_START_MILLIS = "extra_dawn_start_millis"
const val EXTRA_DAWN_END_MILLIS = "extra_dawn_end_millis"

/**
 * Wakes the device and shows [AlarmActivity]. Uses both a full-screen-intent notification
 * (the documented, reliable way to reach the lock screen on modern Android) and a direct
 * activity launch as a belt-and-suspenders fallback for OEMs that behave inconsistently.
 *
 * [dawnStartMillis]/[dawnEndMillis] describe the screen's warm-to-white light ramp: the
 * loud alarm sound and forced dismiss only kick in once [dawnEndMillis] is reached, so a
 * ramp already in the past (start == end) means "ring immediately, no light phase".
 */
fun launchAlarm(context: Context, dawnStartMillis: Long, dawnEndMillis: Long) {
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
        .build()

    if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        NotificationManagerCompat.from(context).notify(FULL_SCREEN_NOTIF_ID, notification)
    }

    context.startActivity(fullScreenIntent)
}

fun dismissAlarmNotification(context: Context) {
    NotificationManagerCompat.from(context).cancel(FULL_SCREEN_NOTIF_ID)
}
