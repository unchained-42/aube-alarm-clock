package com.reveil.aube.ringing

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.reveil.aube.R

/**
 * A full-screen window drawn on top of literally everything else — launcher, recents,
 * Settings, any other app — for as long as the alarm is ringing and [AlarmActivity] isn't
 * the thing actually on screen. This is the real mechanism behind other alarm apps' "can't
 * escape it" feel: none of them can block Settings' own "Force stop" or "Clear data" any
 * more than this app can (no regular app can, root or not) — they just make getting there,
 * or doing anything else at all, genuinely painful instead of a stray swipe away. Requires
 * "Display over other apps" (SYSTEM_ALERT_WINDOW), a real one-time permission grant with no
 * root or device-owner enrollment involved.
 *
 * Plain Android views rather than Compose: hosting a ComposeView here would need a manually
 * wired LifecycleOwner/ViewModelStoreOwner/SavedStateRegistryOwner, since there's no
 * Activity/Fragment providing one for a raw WindowManager-added view. Not worth the
 * complexity for a screen this simple.
 */
private const val TAG = "AubeAlarmOverlay"

class AlarmOverlayController(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var overlayView: View? = null
    private var dawnStartMillis = 0L
    private var dawnEndMillis = 0L

    fun show(dawnStartMillis: Long, dawnEndMillis: Long) {
        this.dawnStartMillis = dawnStartMillis
        this.dawnEndMillis = dawnEndMillis
        if (overlayView != null || !Settings.canDrawOverlays(context)) return

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#14161F"))
            setPadding(96, 96, 96, 96)
        }
        root.addView(TextView(context).apply {
            text = context.getString(R.string.overlay_still_ringing_title)
            setTextColor(Color.parseColor("#EDE8DC"))
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        root.addView(TextView(context).apply {
            text = context.getString(R.string.overlay_still_ringing_subtitle)
            setTextColor(Color.parseColor("#938D7C"))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 56)
        })
        root.addView(Button(context).apply {
            text = context.getString(R.string.overlay_return_button)
            setBackgroundColor(Color.parseColor("#E08A4F"))
            setTextColor(Color.parseColor("#14161F"))
            setOnClickListener { bringActivityToFront() }
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        )

        try {
            windowManager.addView(root, params)
            overlayView = root
        } catch (e: Exception) {
            // Permission revoked mid-flight, or an OEM restriction — never fatal, but worth a
            // log: this is the fallback screen for "the user navigated away from the real
            // alarm activity", so silently failing here means nothing at all blocks the way
            // back to the launcher/recents on that device.
            Log.w(TAG, "failed to add ringing overlay window", e)
        }
    }

    fun hide() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
                // Already gone — fine.
            }
        }
        overlayView = null
    }

    private fun bringActivityToFront() {
        // Without these extras, AlarmActivity falls back to System.currentTimeMillis() for
        // both — two calls nanoseconds apart, so dawnEnd <= dawnStart nearly every time,
        // which reads as "no time left" and launches a brand new safety-net ringing episode
        // from scratch instead of just bringing the real one back.
        val intent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra(EXTRA_DAWN_START_MILLIS, dawnStartMillis)
            putExtra(EXTRA_DAWN_END_MILLIS, dawnEndMillis)
        }
        context.startActivity(intent)
    }
}
