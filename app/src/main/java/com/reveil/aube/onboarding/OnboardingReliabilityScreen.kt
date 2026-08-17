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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.reveil.aube.R
import com.reveil.aube.permissions.ReliabilityCheck
import com.reveil.aube.permissions.oemAutostartAdvisoryCheck
import com.reveil.aube.permissions.missingBlockingChecks
import com.reveil.aube.settings.SettingsRepository
import kotlinx.coroutines.launch

@Composable
fun OnboardingReliabilityScreen(settingsRepository: SettingsRepository, onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var checksTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) checksTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val missingChecks = remember(checksTick) { missingBlockingChecks(context) }
    val oemCheck = remember { oemAutostartAdvisoryCheck(context) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        // Scrolls on its own, independent of the button pinned below it — a long check list
        // (several missing permissions plus the OEM autostart row) can otherwise overflow a
        // small screen with no way to reach the button or see the rest of the list.
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(48.dp))
            Text(
                stringResource(R.string.onboarding_reliability_headline),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.onboarding_reliability_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))

            if (missingChecks.isEmpty() && oemCheck == null) {
                Text(
                    stringResource(R.string.onboarding_reliability_all_ok),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                missingChecks.forEach { check -> ReliabilityRow(check, context) }
                if (oemCheck != null) ReliabilityRow(oemCheck, context)
            }
        }

        Button(
            onClick = {
                scope.launch {
                    settingsRepository.setOnboardingCompleted(true)
                    // If every check is already satisfied, there's nothing left to decide —
                    // turn the alarm on so landing on Home is genuinely "done", not one more tap.
                    if (missingBlockingChecks(context).isEmpty()) {
                        settingsRepository.setAlarmEnabled(true)
                    }
                    onFinished()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (missingChecks.isEmpty()) stringResource(R.string.action_finish) else stringResource(R.string.onboarding_reliability_continue_partial))
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ReliabilityRow(check: ReliabilityCheck, context: android.content.Context) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(check.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            if (check.hint != null) {
                Text(check.hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = { context.startActivity(check.intent(context)) }) {
            Text(stringResource(R.string.action_allow))
        }
    }
}
