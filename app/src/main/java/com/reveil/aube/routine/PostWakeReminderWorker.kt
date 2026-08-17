package com.reveil.aube.routine

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.reveil.aube.NotifChannels
import com.reveil.aube.R

/**
 * Posts one gentle, single reminder — not a guided checklist, per your preference for
 * light nudges over a forced sequence. Delay and message are whatever was configured in
 * Settings > Routine du matin.
 */
class PostWakeReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val message = inputData.getString(KEY_MESSAGE) ?: return Result.failure()

        val notification = NotificationCompat.Builder(applicationContext, NotifChannels.ROUTINE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        if (NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            NotificationManagerCompat.from(applicationContext).notify(id.hashCode(), notification)
        }
        return Result.success()
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_MESSAGE = "message"
    }
}
