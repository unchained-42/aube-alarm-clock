package com.reveil.aube

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings
import com.reveil.aube.settings.LocaleHelper

/** Channel ids referenced across the app. */
object NotifChannels {
    const val TRACKING = "tracking"
    const val ALARM = "alarm"
    const val ROUTINE = "routine"
}

class AubeApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val tracking = NotificationChannel(
            NotifChannels.TRACKING,
            getString(R.string.notif_channel_tracking_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.notif_channel_tracking_desc)
            setShowBadge(false)
        }

        val alarmSound: Uri = Settings.System.DEFAULT_ALARM_ALERT_URI
        val alarm = NotificationChannel(
            NotifChannels.ALARM,
            getString(R.string.notif_channel_alarm_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notif_channel_alarm_desc)
            setSound(
                alarmSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setBypassDnd(true)
            enableVibration(true)
            lockscreenVisibility = NotificationManager.IMPORTANCE_HIGH
        }

        val routine = NotificationChannel(
            NotifChannels.ROUTINE,
            getString(R.string.notif_channel_routine_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notif_channel_routine_desc)
        }

        manager.createNotificationChannels(listOf(tracking, alarm, routine))
    }
}
