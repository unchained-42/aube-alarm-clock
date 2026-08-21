package com.reveil.aube.alarm

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log

private const val TAG = "AubeAccountability"
const val ACTION_SMS_SENT = "com.reveil.aube.action.SMS_SENT"

/**
 * The only way to actually know whether [AccountabilityNotifier]'s send call reached the
 * carrier: `SmsManager` reports success/failure asynchronously, through the result code
 * delivered to whatever this `sentIntent` targets, not through a return value at the call
 * site. Manifest-registered (not a receiver tied to the caller's own lifetime) because
 * [BootReceiver]'s `goAsync()` window can end before the carrier actually responds.
 */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (resultCode) {
            Activity.RESULT_OK -> "sent"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
            SmsManager.RESULT_ERROR_NULL_PDU -> "null PDU"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "limit exceeded"
            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "FDN check failure"
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "short code not allowed"
            SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "short code never allowed"
            else -> "unknown error code $resultCode"
        }
        Log.i(TAG, "SMS part result: $reason")
    }
}
