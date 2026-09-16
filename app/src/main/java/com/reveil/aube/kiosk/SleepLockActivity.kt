package com.reveil.aube.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.reveil.aube.settings.LocaleHelper
import com.reveil.aube.settings.SettingsRepository
import com.reveil.aube.ui.theme.AubeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val TAG = "AubeSleepLockActivity"

/**
 * The night screen — see [SleepLock]. Deliberately does almost nothing: a black background,
 * a dim clock, one line of text, pinned in lock task. It does not keep the screen on (the
 * display sleeps as usual) and leaves once the ring takes over or the night is over — checked
 * on every resume, once a minute, and on [SleepLock.ACTION_RECHECK].
 */
class SleepLockActivity : ComponentActivity() {

    private lateinit var settingsRepository: SettingsRepository

    private val recheckReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = leaveIfOver()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(true)
        // As dim as the panel goes: this screen exists to be pinned, not looked at.
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        ContextCompat.registerReceiver(
            this, recheckReceiver, IntentFilter(SleepLock.ACTION_RECHECK), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        KioskPolicy.applyHardening(this)
        try {
            startLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "startLockTask failed", e)
        }

        setContent {
            AubeTheme {
                NightScreen(onTick = ::leaveIfOver)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        leaveIfOver()
    }

    private fun leaveIfOver() {
        lifecycleScope.launch {
            val settings = settingsRepository.settings.first()
            if (!SleepLock.shouldBeLocked(this@SleepLockActivity, settings)) {
                Log.i(TAG, "night over (or ring took over), leaving")
                try {
                    stopLockTask()
                } catch (_: IllegalArgumentException) {
                }
                finish()
            }
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(recheckReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onDestroy()
    }
}

@Composable
private fun NightScreen(onTick: () -> Unit) {
    var time by remember { mutableStateOf(LocalTime.now()) }
    BackHandler(enabled = true) {}
    LaunchedEffect(Unit) {
        while (true) {
            time = LocalTime.now()
            onTick()
            delay(60_000L)
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US)),
            color = Color(0xFF3A3A3A),
            style = MaterialTheme.typography.headlineLarge
        )
        Text(
            text = stringResource(R.string.sleep_lock_body),
            color = Color(0xFF2A2A2A),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}
