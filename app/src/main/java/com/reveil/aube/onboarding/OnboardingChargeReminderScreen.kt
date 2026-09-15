package com.reveil.aube.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reveil.aube.R
import com.reveil.aube.charge.ChargeGuardPolicy
import com.reveil.aube.settings.SettingsRepository
import com.reveil.aube.ui.formatMinutes
import com.reveil.aube.ui.showTimePicker
import com.reveil.aube.ui.theme.AubeType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * "When should we remind you to plug the phone in?" — asked here, once, awake, rather than
 * derived from bedtime: see [com.reveil.aube.settings.AlarmSettings.chargeReminderMinute].
 * The suggested default is an hour before the bedtime implied by the wake time and sleep
 * duration chosen on the two previous screens.
 */
@Composable
fun OnboardingChargeReminderScreen(settingsRepository: SettingsRepository, onContinue: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var minute by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        val settings = settingsRepository.settings.first()
        val bedtime = settings.weekdayWindow.latestMinute - settings.targetSleepMinutes
        minute = ((bedtime - ChargeGuardPolicy.DEFAULT_REMINDER_LEAD_MINUTES) % (24 * 60) + 24 * 60) % (24 * 60)
    }
    val current = minute ?: return

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        Spacer(Modifier.height(48.dp))
        Text(
            stringResource(R.string.onboarding_charge_headline),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.onboarding_charge_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(40.dp))

        Text(
            formatMinutes(current),
            style = AubeType.heroTime.copy(fontSize = 64.sp, lineHeight = 68.sp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { showTimePicker(context, current) { minute = it } }
        )
        Text(
            stringResource(R.string.onboarding_charge_tap_to_change),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_charge_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                scope.launch {
                    settingsRepository.setChargeReminderMinute(current)
                    onContinue()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_continue))
        }
        TextButton(
            onClick = {
                scope.launch {
                    settingsRepository.setChargeReminderMinute(null)
                    onContinue()
                }
            },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(stringResource(R.string.onboarding_charge_skip), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(32.dp))
    }
}
