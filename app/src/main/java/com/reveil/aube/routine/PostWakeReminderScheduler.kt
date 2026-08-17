package com.reveil.aube.routine

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.reveil.aube.settings.CustomReminder
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class PostWakeReminderScheduler(private val context: Context) {

    fun scheduleRoutine(reminders: List<CustomReminder>) {
        val workManager = WorkManager.getInstance(context)
        val today = LocalDate.now().toString()
        reminders.forEach { reminder ->
            if (reminder.enabled) enqueue(workManager, reminder, today)
        }
    }

    private fun enqueue(workManager: WorkManager, reminder: CustomReminder, today: String) {
        val request = OneTimeWorkRequestBuilder<PostWakeReminderWorker>()
            .setInitialDelay(reminder.delayMinutes.toLong(), TimeUnit.MINUTES)
            .setInputData(
                Data.Builder()
                    .putString(PostWakeReminderWorker.KEY_ID, reminder.id)
                    .putString(PostWakeReminderWorker.KEY_MESSAGE, reminder.message)
                    .build()
            )
            .build()
        // Unique per reminder+day: if dismiss ever fires more than once in the same day (a
        // second "already awake" tap, a retry after a crash...), this replaces the pending
        // reminder instead of stacking a duplicate set of notifications behind it.
        workManager.enqueueUniqueWork("reminder_${reminder.id}_$today", ExistingWorkPolicy.REPLACE, request)
    }
}
