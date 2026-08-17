package com.reveil.aube.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reveil.aube.R
import com.reveil.aube.settings.SettingsRepository
import com.reveil.aube.settings.WakeWindow
import com.reveil.aube.ui.WakeWindowEditor
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun OnboardingWakeTimeScreen(settingsRepository: SettingsRepository, onContinue: () -> Unit) {
    val scope = rememberCoroutineScope()
    var weekdayWindow by remember { mutableStateOf(WakeWindow(7 * 60, 7 * 60 + 30)) }
    var weekendWindow by remember { mutableStateOf(WakeWindow(8 * 60 + 30, 9 * 60 + 15)) }
    var useSeparateWeekend by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        // Scrolls on its own, independent of the button pinned below it — with the weekend
        // editor also showing, this can overflow a small screen with no way to reach the
        // button or the rest of the content.
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(48.dp))
            Text(
                stringResource(R.string.onboarding_wake_headline),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.onboarding_wake_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(40.dp))

            Text(stringResource(R.string.label_weekday), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            WakeWindowEditor(window = weekdayWindow, onChange = { weekdayWindow = it })

            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.label_separate_weekend), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Switch(checked = useSeparateWeekend, onCheckedChange = { useSeparateWeekend = it })
            }
            if (useSeparateWeekend) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.label_weekend), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                WakeWindowEditor(window = weekendWindow, onChange = { weekendWindow = it })
            }
        }

        Button(
            onClick = {
                scope.launch {
                    settingsRepository.setWeekdayWindow(weekdayWindow)
                    settingsRepository.setUseSeparateWeekend(useSeparateWeekend)
                    if (useSeparateWeekend) settingsRepository.setWeekendWindow(weekendWindow)
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
