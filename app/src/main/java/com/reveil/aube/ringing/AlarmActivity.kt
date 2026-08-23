package com.reveil.aube.ringing

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.reveil.aube.R
import com.reveil.aube.alarm.AlarmScheduler
import com.reveil.aube.qr.DEFAULT_QR_PAYLOAD
import com.reveil.aube.routine.PostWakeReminderScheduler
import com.reveil.aube.settings.LocaleHelper
import com.reveil.aube.settings.SettingsRepository
import com.reveil.aube.ui.theme.AubeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class AlarmActivity : ComponentActivity() {

    private lateinit var settingsRepository: SettingsRepository

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository(applicationContext)

        showOverLockScreen()
        enableEdgeToEdge()

        val dawnStart = intent.getLongExtra(EXTRA_DAWN_START_MILLIS, System.currentTimeMillis())
        val dawnEnd = intent.getLongExtra(EXTRA_DAWN_END_MILLIS, System.currentTimeMillis())

        // Registers the window with the service right away (before anything else needs it)
        // so that if this Activity's task later gets closed from a recents/"active apps"
        // list, the service's onTaskRemoved has the right values to relaunch with.
        sendRingingCommand(null) {
            putExtra(EXTRA_DAWN_START_MILLIS, dawnStart)
            putExtra(EXTRA_DAWN_END_MILLIS, dawnEnd)
        }

        // Screen pinning: standard Android API, no root/device-owner/factory-reset needed.
        // While pinned, Home and Recents/"active apps" are inert (the OS shows an "unpin to
        // leave" hint instead of actually leaving), which is what closing this from the
        // active-apps list defeated last time. It isn't absolute — a deliberate long-press
        // back+recents (or the equivalent gesture) still unpins — but it closes the casual
        // "swipe it away" path. Released only in handleDismissed(), on a real scan.
        try {
            startLockTask()
        } catch (_: Exception) {
            // Best-effort hardening — must never be the reason the alarm screen fails to show.
        }

        setContent {
            AubeTheme {
                AlarmScreen(
                    dawnStartMillis = dawnStart,
                    dawnEndMillis = dawnEnd,
                    settingsRepository = settingsRepository,
                    onSetBrightness = ::setScreenBrightness,
                    onStartSound = { uri, volume ->
                        sendRingingCommand(AlarmRingingService.ACTION_START_SOUND) {
                            putExtra(AlarmRingingService.EXTRA_URI, uri)
                            putExtra(AlarmRingingService.EXTRA_VOLUME, volume)
                        }
                    },
                    onSetVolume = { volume ->
                        sendRingingCommand(AlarmRingingService.ACTION_SET_VOLUME) {
                            putExtra(AlarmRingingService.EXTRA_VOLUME, volume)
                        }
                    },
                    onVibrate = { sendRingingCommand(AlarmRingingService.ACTION_START_VIBRATION) },
                    onStopSound = { sendRingingCommand(AlarmRingingService.ACTION_STOP_SOUND) },
                    onDismissed = ::handleDismissed
                )
            }
        }
    }

    // Tells the service whether the real screen is actually what's on top right now, so its
    // full-screen overlay (see AlarmOverlayController) only shows when this Activity isn't
    // visible — anywhere else the user manages to navigate to (home, recents, Settings)
    // while the alarm is still active.
    override fun onStart() {
        super.onStart()
        sendRingingCommand(AlarmRingingService.ACTION_ACTIVITY_VISIBLE)
    }

    override fun onStop() {
        super.onStop()
        // isFinishing guards the legitimate-dismiss path: handleDismissed() calls finish(),
        // which triggers this same onStop() — without the guard, that unconditionally told
        // the service to show the overlay right after telling it to stop everything, so the
        // alarm looked "stopped" for a moment and then the overlay popped back up saying it
        // was still ringing (it wasn't — nothing was left to show), and its own "return to
        // the alarm" button then launched a brand new ringing episode from scratch.
        if (!isFinishing) {
            sendRingingCommand(AlarmRingingService.ACTION_ACTIVITY_HIDDEN)
        }
    }

    /**
     * The alarm's sound and vibration live in [AlarmRingingService], not here — an
     * Activity-owned MediaPlayer dies the instant this Activity does (closed from a recents/
     * "active apps" list, or killed in the background), silencing the alarm with no dismiss
     * check at all. Routing every command through the service means this Activity being
     * destroyed, by any means other than [handleDismissed] sending ACTION_DISMISS, no longer
     * has any power to stop the alarm.
     */
    private fun sendRingingCommand(action: String?, build: Intent.() -> Unit = {}) {
        val serviceIntent = Intent(this, AlarmRingingService::class.java).apply {
            this.action = action
            build()
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    // Sound plays from the moment this screen appears (quietly, climbing with the dawn
    // light), not just once it's fully ringing — so the hardware volume keys must be locked
    // out for the whole screen, not only once phase reaches RINGING. Otherwise the dawn
    // ramp can just be muted away before it ever amounts to anything. The power button can't
    // be intercepted this way (Android reserves it at the system level), so that limitation
    // is real and stays.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in VOLUME_KEYS) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showOverLockScreen() {
        // setShowWhenLocked alone draws this Activity directly on top of the lock screen,
        // no authentication needed — the standard, documented way alarm/incoming-call
        // screens do this. requestDismissKeyguard() used to also be called here, but that
        // actively asks the system to *unlock* the device: on a phone with a PIN/pattern/
        // password set, that's exactly what was showing the pattern-unlock prompt instead
        // of the dawn screen — it was demanding real authentication before revealing
        // anything, the opposite of what an alarm that must be visible without unlocking
        // needs.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setScreenBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    private fun handleDismissed() {
        // Set first, synchronously, before anything else here — including finish() below.
        // finish() tears down this singleInstance/excludeFromRecents activity's own sole
        // task, which triggers AlarmRingingService.onTaskRemoved(); that used to race the
        // ACTION_DISMISS command sent just below (delivered asynchronously, via
        // startForegroundService) and sometimes win, relaunching a brand-new ring right after
        // this legitimate one — confirmed on a real device via logcat. A plain static write is
        // visible to onTaskRemoved the instant it runs, with no intent round-trip to lose the
        // race against. Deliberately a separate flag from the service's own instance-level
        // `dismissed` — see onTaskRemoved's doc for why reusing that one silenced the real
        // dismiss command instead of just the stale ones it was meant to guard against.
        AlarmRingingService.dismissRequested = true
        // The one legitimate way the service ever stops ringing — see
        // AlarmRingingService.ACTION_DISMISS. This Activity finishing, by any other route,
        // must not be able to do this.
        sendRingingCommand(AlarmRingingService.ACTION_DISMISS)
        try {
            stopLockTask()
        } catch (_: IllegalArgumentException) {
            // Wasn't pinned (startLockTask failed earlier, or already unpinned) — fine.
        }
        dismissAlarmNotification(applicationContext)
        lifecycleScope.launch {
            // Clears the flag BootReceiver checks — a reboot from here on is a normal
            // reboot again, not a way to make the alarm quietly disappear.
            settingsRepository.setAlarmRinging(false)
            settingsRepository.setLastHandledDate(LocalDate.now())
            val settings = settingsRepository.settings.first()
            PostWakeReminderScheduler(applicationContext).scheduleRoutine(settings.reminders)
            AlarmScheduler(applicationContext).scheduleNext(settings)
        }
        finish()
    }

    private companion object {
        val VOLUME_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE
        )
    }
}

private enum class Phase { LIGHT, RINGING }

private const val MUTE_DURATION_SECONDS = 120

@Composable
private fun AlarmScreen(
    dawnStartMillis: Long,
    dawnEndMillis: Long,
    settingsRepository: SettingsRepository,
    onSetBrightness: (Float) -> Unit,
    onStartSound: (String?, Float) -> Unit,
    onSetVolume: (Float) -> Unit,
    onVibrate: () -> Unit,
    onStopSound: () -> Unit,
    onDismissed: () -> Unit
) {
    var progress by remember { mutableStateOf(0f) }
    var phase by remember { mutableStateOf(if (dawnEndMillis <= dawnStartMillis) Phase.RINGING else Phase.LIGHT) }
    // Starts as the guaranteed floor value (never null/blank) so a fast tap into the dismiss
    // screen — before the settings read below finishes — can never land on an "unconfigured"
    // state; it only ever gets replaced by the user's own custom code once that read lands.
    var qrPayload by remember { mutableStateOf(DEFAULT_QR_PAYLOAD) }
    var musicUri by remember { mutableStateOf<String?>(null) }
    var vibrationEnabled by remember { mutableStateOf(true) }
    var showDismiss by remember { mutableStateOf(false) }
    var currentTime by remember { mutableStateOf(LocalTime.now()) }

    // The alarm can be muted exactly once, for two minutes, as a middle ground between
    // "no snooze at all" (what you asked for originally) and being able to silence a
    // sudden blast of sound for a moment. Once the countdown ends it rings again at full
    // volume and stays on — muting is spent, only the QR scan stops it after that.
    var muteUsed by remember { mutableStateOf(false) }
    var muteSecondsLeft by remember { mutableStateOf(0) }
    var isMuting by remember { mutableStateOf(false) }

    // Without this, the system back gesture/button finishes the Activity directly —
    // bypassing onDismissed entirely — which stops the sound in onDestroy() with no code
    // scan at all. Exactly the escape hatch the whole QR-dismiss design exists to close.
    BackHandler(enabled = true) {}

    LaunchedEffect(Unit) {
        val settings = settingsRepository.settings.first()
        qrPayload = settings.effectiveQrPayload
        musicUri = settings.musicUri
        vibrationEnabled = settings.vibrationEnabled
        if (phase == Phase.RINGING) {
            // Safety-net path: there was no time left for a gentle build-up, so there's
            // nothing gentle to do — start at full volume (and vibration) immediately.
            onStartSound(musicUri, 1f)
            if (vibrationEnabled) onVibrate()
        } else {
            // Sound rides the same earliest→latest window as the light from the very start
            // — barely audible now, climbing every tick below as `progress` advances.
            onStartSound(musicUri, dawnVolume(0f))
        }
    }

    LaunchedEffect(dawnStartMillis, dawnEndMillis) {
        val span = (dawnEndMillis - dawnStartMillis).coerceAtLeast(1L)
        while (true) {
            val now = System.currentTimeMillis()
            val elapsed = (now - dawnStartMillis).coerceAtLeast(0L)
            progress = (elapsed.toFloat() / span).coerceIn(0f, 1f)
            currentTime = LocalTime.now()
            onSetBrightness(dawnScreenBrightness(progress))
            // Re-asserted every tick, not just set once — anything that manages to move the
            // alarm stream through a path our hardware-key block doesn't cover (the system's
            // own touch-based volume panel, a brief gap before this Activity has real input
            // focus) gets overwritten within a second, for as long as ringing lasts — not
            // only during the initial ramp.
            if (!isMuting) onSetVolume(if (phase == Phase.LIGHT) dawnVolume(progress) else 1f)
            if (progress >= 1f && phase == Phase.LIGHT) {
                phase = Phase.RINGING
                // Sound is already at (or essentially at) full volume by construction —
                // dawnVolume(1f) == 1f — so only the escalation that hasn't happened yet,
                // vibration, needs to start here. Restarting the player would just cut it.
                if (vibrationEnabled) onVibrate()
            }
            delay(1_000L)
        }
    }

    // Walking to wherever the code is printed, then scanning it, takes real time — and until
    // now the only way to get quiet for that walk was to notice and tap the mute button
    // *before* leaving the ringing screen. Opening the scan screen itself now spends the same
    // one-time, two-minute budget automatically, so the trip to the code doesn't have to
    // happen with the alarm still blaring. A no-op if it was already used (button or a
    // previous scan-screen visit) — it only ever fires once, same as before.
    LaunchedEffect(showDismiss) {
        if (showDismiss && !muteUsed) {
            muteUsed = true
            isMuting = true
            onStopSound()
        }
    }

    LaunchedEffect(isMuting) {
        if (isMuting) {
            var remaining = MUTE_DURATION_SECONDS
            muteSecondsLeft = remaining
            while (remaining > 0) {
                delay(1_000L)
                remaining -= 1
                muteSecondsLeft = remaining
            }
            // Resuming from an intentional mute is always the hard deadline's full volume —
            // a gentle re-ramp would defeat the point of the mute ever ending.
            onStartSound(musicUri, 1f)
            if (vibrationEnabled) onVibrate()
            isMuting = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        DawnBackground(progress = progress, modifier = Modifier.fillMaxSize())

        if (showDismiss) {
            QrDismissScreen(expectedPayload = qrPayload, onDismissed = onDismissed)
        } else {
            // The background ramps through night -> amber -> daylight on an eased curve, so
            // a fixed cutoff on raw progress used to leave white text sitting on an
            // already-bright background for a good stretch of the ramp. This tracks the
            // actual rendered background color instead.
            val textIsDark = dawnTextIsDark(progress)
            val inkColor = Color(0xFF22201B)

            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = currentTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US)),
                    color = if (textIsDark) inkColor else Color.White,
                    style = MaterialTheme.typography.headlineLarge
                )
                Text(
                    text = if (phase == Phase.LIGHT) stringResource(R.string.alarm_light_message) else stringResource(R.string.alarm_ringing_message),
                    color = if (textIsDark) inkColor else Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp, bottom = 40.dp)
                )

                when (phase) {
                    // Dismissing here used to skip the scan entirely — a single low-effort
                    // tap, reachable the instant the dawn ramp starts, that cancelled the
                    // whole alarm (including the RINGING phase that would follow) with no
                    // forcing function at all. Exactly the escape hatch this whole mechanism
                    // exists to close, so it now goes through the same scan as everything
                    // else — being "already awake" doesn't get a shortcut.
                    Phase.LIGHT -> TextButton(onClick = { showDismiss = true }) {
                        Text(
                            stringResource(R.string.alarm_already_awake_button),
                            color = if (textIsDark) inkColor else Color.White
                        )
                    }
                    Phase.RINGING -> {
                        if (isMuting) {
                            // Pre-formatted with Locale.US, then passed as a single %s — not
                            // raw ints — so the mm:ss countdown stays Western digits in every
                            // language instead of picking up native numerals in some locales.
                            val countdown = String.format(Locale.US, "%d:%02d", muteSecondsLeft / 60, muteSecondsLeft % 60)
                            Text(
                                text = stringResource(R.string.alarm_mute_countdown, countdown),
                                color = inkColor,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                        }
                        TextButton(onClick = { showDismiss = true }) {
                            Text(stringResource(R.string.alarm_stop_button), color = inkColor)
                        }
                        if (!muteUsed) {
                            TextButton(onClick = {
                                muteUsed = true
                                isMuting = true
                                onStopSound()
                            }) {
                                Text(
                                    stringResource(R.string.alarm_mute_button, (MUTE_DURATION_SECONDS / 60).toString()),
                                    color = inkColor.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
