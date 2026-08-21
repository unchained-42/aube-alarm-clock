package com.reveil.aube.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reveil.aube.R
import com.reveil.aube.settings.SettingsRepository
import com.reveil.aube.ui.theme.AubeType
import kotlinx.coroutines.launch

private const val MIN_MINUTES = 240 // 4h
private const val MAX_MINUTES = 600 // 10h
private const val STEP_MINUTES = 30
private const val DEFAULT_MINUTES = 480 // 8h

@Composable
fun OnboardingSleepDurationScreen(settingsRepository: SettingsRepository, onContinue: () -> Unit) {
    val scope = rememberCoroutineScope()
    var minutes by remember { mutableIntStateOf(DEFAULT_MINUTES) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        Spacer(Modifier.height(48.dp))
        Text(
            stringResource(R.string.onboarding_sleep_headline),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.onboarding_sleep_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(40.dp))

        Text(
            formatDuration(minutes),
            style = AubeType.heroTime.copy(fontSize = 48.sp, lineHeight = 52.sp),
            color = MaterialTheme.colorScheme.primary
        )
        Slider(
            value = minutes.toFloat(),
            onValueChange = { value ->
                minutes = (value / STEP_MINUTES).toInt() * STEP_MINUTES
            },
            valueRange = MIN_MINUTES.toFloat()..MAX_MINUTES.toFloat(),
            steps = (MAX_MINUTES - MIN_MINUTES) / STEP_MINUTES - 1,
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
        )

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                scope.launch {
                    settingsRepository.setTargetSleepMinutes(minutes)
                    onContinue()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_continue))
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun formatDuration(minutes: Int): String {
    val hours = (minutes / 60).toString()
    val rem = minutes % 60
    return if (rem == 0) {
        stringResource(R.string.duration_hours, hours)
    } else {
        stringResource(R.string.duration_hours_minutes, hours, rem.toString())
    }
}
