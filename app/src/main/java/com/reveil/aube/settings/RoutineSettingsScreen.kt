package com.reveil.aube.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.reveil.aube.R
import kotlinx.coroutines.launch

@Composable
fun RoutineSettingsScreen(settingsRepository: SettingsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by settingsRepository.settings.collectAsState(initial = null)
    val current = settings ?: return

    fun persist(updated: List<CustomReminder>) {
        scope.launch { settingsRepository.setReminders(updated) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(stringResource(R.string.settings_routine_label), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.routine_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
        }

        items(current.reminders, key = { it.id }) { reminder ->
            ReminderEditor(
                reminder = reminder,
                onChange = { updated ->
                    persist(current.reminders.map { if (it.id == reminder.id) updated else it })
                },
                onDelete = {
                    persist(current.reminders.filterNot { it.id == reminder.id })
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }

        item {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = {
                    val new = CustomReminder(
                        id = SettingsRepository.newReminderId(),
                        enabled = true,
                        delayMinutes = 10,
                        message = ""
                    )
                    persist(current.reminders + new)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.routine_add_button))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReminderEditor(reminder: CustomReminder, onChange: (CustomReminder) -> Unit, onDelete: () -> Unit) {
    var delayText by remember(reminder.id, reminder.delayMinutes) { mutableStateOf(reminder.delayMinutes.toString()) }
    // Keyed only on reminder.id, not reminder.message: the message field's value used to be
    // bound directly to reminder.message, which round-trips through an async DataStore write
    // on every keystroke (onChange -> persist -> Flow re-emits -> recomposition). That async
    // gap made typing feel broken — a stale emission could land mid-edit and overwrite what
    // was just typed, or reset the cursor to the end. Buffering locally like delayText already
    // does keeps what's on screen authoritative while still persisting in the background.
    var messageText by remember(reminder.id) { mutableStateOf(reminder.message) }

    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(checked = reminder.enabled, onCheckedChange = { onChange(reminder.copy(enabled = it)) })
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = stringResource(R.string.cd_delete_reminder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.routine_delay_label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = delayText,
                onValueChange = { text ->
                    if (text.length <= 3 && text.all { it.isDigit() }) {
                        delayText = text
                        text.toIntOrNull()?.let { minutes ->
                            if (minutes in 0..180) onChange(reminder.copy(delayMinutes = minutes))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.width(84.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.unit_minutes_short), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = messageText,
            onValueChange = { text ->
                messageText = text
                onChange(reminder.copy(message = text))
            },
            label = { Text(stringResource(R.string.routine_message_label)) },
            placeholder = { Text(stringResource(R.string.routine_message_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
    }
}
