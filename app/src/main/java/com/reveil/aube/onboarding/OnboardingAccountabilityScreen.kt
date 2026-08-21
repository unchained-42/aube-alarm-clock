package com.reveil.aube.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reveil.aube.R

/**
 * Purely informational — the feature is opt-in and off by default (no contacts configured),
 * so there's nothing here to block onboarding on. [onSetUpContacts] is a detour, not a fork:
 * it leaves this same screen visible underneath so [onContinue] still moves forward either way.
 */
@Composable
fun OnboardingAccountabilityScreen(onSetUpContacts: () -> Unit, onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp)
    ) {
        Spacer(Modifier.height(48.dp))
        Text(
            stringResource(R.string.onboarding_accountability_headline),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.onboarding_accountability_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_accountability_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))

        OutlinedButton(onClick = onSetUpContacts, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_accountability_setup_button))
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_continue))
        }
        Spacer(Modifier.height(32.dp))
    }
}
