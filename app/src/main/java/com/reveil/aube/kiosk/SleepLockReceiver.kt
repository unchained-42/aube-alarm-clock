package com.reveil.aube.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.reveil.aube.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "AubeSleepLockRx"

/** Target of [SleepLock]'s bedtime alarm: re-evaluates and puts the night screen up if due. */
class SleepLockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SleepLock.ACTION_START) return
        Log.i(TAG, "bedtime alarm fired")
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository(appContext).settings.first()
                SleepLock.ensure(appContext, settings)
            } catch (e: Exception) {
                Log.w(TAG, "ensure failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
