package com.reveil.aube.charge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AubeChargeGuardRx"

/**
 * The charge guard's own next-check alarm lands here — its only trigger besides boot, app
 * start and the watchdog. Not the system's plug/unplug or battery-low broadcasts: Android
 * won't deliver those to a manifest receiver (see [ChargeGuard]), so there's no point
 * declaring them.
 */
class ChargeGuardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        Log.i(TAG, "onReceive: ${intent.action}")
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ChargeGuard.evaluate(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "evaluate failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_CHECK = "com.reveil.aube.action.CHARGE_GUARD_CHECK"
        private val HANDLED_ACTIONS = setOf(ACTION_CHECK)
    }
}
