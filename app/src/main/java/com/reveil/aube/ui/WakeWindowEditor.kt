package com.reveil.aube.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reveil.aube.R
import com.reveil.aube.settings.WakeWindow
import com.reveil.aube.ui.theme.AubeType

/**
 * One number you set on purpose — "wake by this time, guaranteed" — and one slider that
 * explains itself in the sentence above it, rather than a second unlabeled time next to the
 * first. Dragging the slider to 0 is exactly "just wake me at that time, nothing smart."
 */
@Composable
fun WakeWindowEditor(window: WakeWindow, onChange: (WakeWindow) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val context = LocalContext.current
    val advance = (window.latestMinute - window.earliestMinute).coerceIn(0, 45)

    Column(modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { base ->
                    if (enabled) {
                        base.clickable {
                            showTimePicker(context, window.latestMinute) { picked ->
                                // Keeps the current "wake up to X min early" gap relative to
                                // the new time, rather than the old absolute earliestMinute —
                                // that used to leave a stale, unrelated "dès 07:00" hanging
                                // around under a target moved somewhere completely different,
                                // e.g. 15:30 in the afternoon.
                                onChange(
                                    window.copy(
                                        latestMinute = picked,
                                        earliestMinute = (picked - advance).coerceAtLeast(0)
                                    )
                                )
                            }
                        }
                    } else {
                        base
                    }
                }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    stringResource(R.string.wake_window_latest_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatMinutes(window.latestMinute),
                    style = AubeType.heroTime.copy(fontSize = 40.sp, lineHeight = 44.sp),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.wake_window_early_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(2.dp))
        Text(
            if (advance == 0) stringResource(R.string.wake_window_early_disabled)
            else stringResource(R.string.wake_window_early_enabled, advance.toString(), formatMinutes(window.latestMinute - advance)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = advance.toFloat(),
            onValueChange = { value ->
                val newAdvance = value.toInt()
                onChange(window.copy(earliestMinute = (window.latestMinute - newAdvance).coerceAtLeast(0)))
            },
            enabled = enabled,
            valueRange = 0f..45f,
            steps = 8,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}
