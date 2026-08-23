package com.reveil.aube.alarm

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.reveil.aube.R
import com.reveil.aube.settings.AlarmSettings
import com.reveil.aube.settings.SettingsRepository

private const val TAG = "AubeAccountability"

/**
 * Texts a "didn't wake up" message to one configured contact — the one consequence that still
 * applies once the phone is powered off through the deadline. Nothing runs while the device
 * is off, and nothing can force it back on either (see the manufacturer-specific autostart
 * checks in [com.reveil.aube.permissions.PermissionsHelper] for the closest this app gets to
 * fighting the OS on background survival) — so this trades a technical fix for a social one.
 * Requires SEND_SMS, requested explicitly from the accountability contacts screen, same as
 * every other sensitive permission in this app.
 */
object AccountabilityNotifier {
    /**
     * With one contact configured, that person is texted every time — there's no one else to
     * rotate to. With several, the last person notified is excluded from the pick so the same
     * contact isn't the only one who ever finds out, then the rest is random.
     */
    internal fun pickRecipient(contacts: List<String>, lastNotified: String?): String {
        if (contacts.size == 1) return contacts[0]
        val candidates = contacts.filter { it != lastNotified }.ifEmpty { contacts }
        return candidates.random()
    }

    suspend fun notifyMissedWakeup(context: Context, settingsRepository: SettingsRepository, settings: AlarmSettings) {
        Log.i(TAG, "notifyMissedWakeup called, ${settings.emergencyContacts.size} contact(s) configured")
        if (settings.emergencyContacts.isEmpty()) {
            Log.i(TAG, "skipped: no contacts configured")
            return
        }
        // Only reflects the standard Android permission grant — MIUI (and some other OEMs)
        // layer their own separate, hidden app-ops toggle for SMS underneath it, which this
        // check has no way to see. A grant here is necessary, not sufficient: the send below
        // can still be silently dropped by that OEM layer with no exception and no signal
        // back to this method, which is exactly why the sentIntent below exists — it's the
        // only place that can actually observe that kind of silent failure.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "skipped: SEND_SMS not granted")
            return
        }

        val body = settings.accountabilityMessage?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.accountability_default_message)
        // The signature is appended here, at send time, never stored as part of the editable
        // text — so there's no way to type it away in the settings screen.
        val signature = context.getString(R.string.accountability_sms_signature, context.getString(R.string.app_name))
        val message = "$body\n$signature"

        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        if (smsManager == null) {
            Log.w(TAG, "skipped: no SmsManager available")
            return
        }

        val recipient = pickRecipient(settings.emergencyContacts, settings.lastNotifiedContact)
        runCatching {
            val parts = smsManager.divideMessage(message)
            val sentIntents = ArrayList<PendingIntent>()
            for (partIndex in parts.indices) {
                sentIntents.add(
                    PendingIntent.getBroadcast(
                        context,
                        partIndex,
                        Intent(ACTION_SMS_SENT).setPackage(context.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
            Log.i(TAG, "sending ${parts.size} part(s) to selected contact")
            smsManager.sendMultipartTextMessage(recipient, null, parts, sentIntents, null)
        }.onFailure { e ->
            Log.e(TAG, "sendMultipartTextMessage threw", e)
        }
        settingsRepository.setLastNotifiedContact(recipient)
    }
}
