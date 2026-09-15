package com.reveil.aube.charge

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.reveil.aube.NotifChannels
import com.reveil.aube.R
import com.reveil.aube.ringing.AlarmSoundPlayer
import com.reveil.aube.ringing.AlarmVibrator

/**
 * One "plug me in" chirp: the alarm sound at full volume (plus vibration) for a few seconds,
 * then gone. A short-lived foreground service rather than playback from the receiver — a
 * BroadcastReceiver's process can be reclaimed the moment onReceive returns, mid-chirp — and
 * on the alarm stream, so it cuts through Do Not Disturb like the alarm itself does.
 */
class ChargeNagService : Service() {

    private val soundPlayer by lazy { AlarmSoundPlayer(this) }
    private val vibrator by lazy { AlarmVibrator(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aube:ChargeNag")
            .apply { acquire(CHIRP_MS + 5_000L) }
        soundPlayer.start(intent?.getStringExtra(EXTRA_URI), 1f)
        if (intent?.getBooleanExtra(EXTRA_VIBRATE, true) != false) vibrator.start()
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, CHIRP_MS)
        // A restart with a null Intent (process killed mid-chirp) would just chirp again with
        // the default sound; there's no window to recover, so don't come back.
        return START_NOT_STICKY
    }

    private fun startForeground() {
        val notification: Notification = NotificationCompat.Builder(this, NotifChannels.CHARGE)
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle(getString(R.string.charge_notif_title))
            .setContentText(getString(R.string.charge_chirp_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        soundPlayer.stop()
        vibrator.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_VIBRATE = "extra_vibrate"
        private const val NOTIF_ID = 46
        // Long enough to be unmistakably an alarm and not a notification ping, short enough
        // that a 1-minute cadence is a nag, not a second alarm.
        const val CHIRP_MS = 8_000L
    }
}
