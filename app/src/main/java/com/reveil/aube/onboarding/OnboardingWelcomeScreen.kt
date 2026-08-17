package com.reveil.aube.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reveil.aube.R

@Composable
fun OnboardingWelcomeScreen(onContinue: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        // Scrolls on its own, independent of the button pinned below it — some languages'
        // translations run noticeably longer than French/English and can overflow a small
        // screen with these four feature rows.
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(48.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.onboarding_welcome_headline),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.onboarding_welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(48.dp))

            FeatureRow(
                icon = Icons.Filled.WbSunny,
                title = stringResource(R.string.onboarding_feature_light_title),
                description = stringResource(R.string.onboarding_feature_light_desc)
            )
            FeatureRow(
                icon = Icons.Filled.Bedtime,
                title = stringResource(R.string.onboarding_feature_sleep_title),
                description = stringResource(R.string.onboarding_feature_sleep_desc)
            )
            FeatureRow(
                icon = Icons.Filled.QrCodeScanner,
                title = stringResource(R.string.onboarding_feature_qr_title),
                description = stringResource(R.string.onboarding_feature_qr_desc)
            )
            FeatureRow(
                icon = Icons.Filled.WaterDrop,
                title = stringResource(R.string.onboarding_feature_routine_title),
                description = stringResource(R.string.onboarding_feature_routine_desc)
            )
        }

        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_start))
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, description: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
