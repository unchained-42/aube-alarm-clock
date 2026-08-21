package com.reveil.aube.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.reveil.aube.R
import com.reveil.aube.alarm.AlarmScheduler
import com.reveil.aube.permissions.missingBlockingChecks
import com.reveil.aube.settings.AlarmSettings
import com.reveil.aube.settings.SettingsRepository
import com.reveil.aube.settings.WakeWindow
import com.reveil.aube.ringing.AlarmRingingService
import com.reveil.aube.ui.theme.AubeType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(
    settingsRepository: SettingsRepository,
    onOpenSettings: () -> Unit,
    onOpenWhyStrict: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsRepository.settings.collectAsState(initial = null)
    val current = settings ?: return

    // Re-checks whenever you come back to Home — most reliability checks are granted in a
    // system settings screen outside the app, so there's no in-app callback for them.
    var checksTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) checksTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val checksIncomplete = remember(checksTick) { missingBlockingChecks(context).isNotEmpty() }

    // Ticks on its own so the lock below engages/releases automatically as the clock crosses
    // the threshold, without needing Home to be reopened — same pattern as countdownText.
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = ZonedDateTime.now()
            delay(60_000L)
        }
    }
    val alarmActive = current.alarmEnabled && !checksIncomplete
    val nextLatest = remember(current, checksIncomplete, now) {
        if (alarmActive) AlarmScheduler(context).previewNextWindow(current).second else null
    }
    // Once inside the target-sleep-duration window before the hard deadline, editing the
    // schedule or turning the alarm off is exactly the half-asleep decision this whole app
    // exists to prevent — see AlarmSettings.targetSleepMinutes.
    val sleepLockActive = remember(nextLatest, current.targetSleepMinutes, now) {
        nextLatest != null && run {
            val lockStart = nextLatest.minusMinutes(current.targetSleepMinutes.toLong())
            !now.isBefore(lockStart) && now.isBefore(nextLatest)
        }
    }

    LaunchedEffect(current, checksIncomplete) {
        // A ring/dawn ramp already in progress owns its own lifecycle end to end — touching
        // the schedule here would restart SleepTrackingService mid-cycle (any settings read,
        // even an unrelated one, recomposes this effect) and re-fire the whole launch
        // sequence: a second notification and sound on top of the one already ringing.
        // Checked live against the Service itself, not a persisted flag: a persisted "ring in
        // progress" flag can outlive the ring it described (a crash, a reinstall, a
        // force-stop all skip the real dismiss that would clear it) and then never gets
        // cleared by anything — which silently disables scheduling forever, worse than the
        // bug this guard exists to prevent.
        if (AlarmRingingService.isActive) return@LaunchedEffect
        val scheduler = AlarmScheduler(context)
        // Never schedule on an unreliable setup, even if alarmEnabled was somehow left on
        // from before the checks were introduced — the alarm simply won't go off silently
        // broken.
        if (current.alarmEnabled && !checksIncomplete) scheduler.scheduleNext(current) else scheduler.cancelAll()
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.app_name), style = AubeType.eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            HeroNextAlarm(
                settings = current,
                blocked = checksIncomplete,
                locked = sleepLockActive,
                onToggle = { scope.launch { settingsRepository.setAlarmEnabled(it) } },
                onBlocked = onOpenSettings,
                onEditWindow = { date, newWindow ->
                    scope.launch { settingsRepository.setOneTimeOverride(date, newWindow) }
                },
                onClearOverride = { scope.launch { settingsRepository.clearOneTimeOverride() } }
            )
            if (checksIncomplete) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.home_checks_incomplete),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onOpenSettings)
                )
            }
            if (sleepLockActive) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.home_sleep_lock_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(32.dp))
        }

        item {
            Text(stringResource(R.string.label_weekday), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            WakeWindowEditor(
                window = current.weekdayWindow,
                onChange = { scope.launch { settingsRepository.setWeekdayWindow(it) } },
                enabled = !sleepLockActive
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }

        item {
            SettingsSwitchRow(
                label = stringResource(R.string.label_separate_weekend),
                checked = current.useSeparateWeekend,
                onCheckedChange = { scope.launch { settingsRepository.setUseSeparateWeekend(it) } }
            )
            if (current.useSeparateWeekend) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.label_weekend), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                WakeWindowEditor(
                    window = current.weekendWindow,
                    onChange = { scope.launch { settingsRepository.setWeekendWindow(it) } },
                    enabled = !sleepLockActive
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }

        item {
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.home_why_strict_link),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onOpenWhyStrict)
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HeroNextAlarm(
    settings: AlarmSettings,
    blocked: Boolean,
    locked: Boolean,
    onToggle: (Boolean) -> Unit,
    onBlocked: () -> Unit,
    onEditWindow: (date: java.time.LocalDate, newWindow: WakeWindow) -> Unit,
    onClearOverride: () -> Unit
) {
    val context = LocalContext.current
    val alarmActive = settings.alarmEnabled && !blocked
    val window = remember(settings, blocked) {
        if (alarmActive) AlarmScheduler(context).previewNextWindow(settings) else null
    }
    val targetDate = window?.first?.toLocalDate()
    val isException = targetDate != null && targetDate == settings.oneTimeOverrideDate
    // The currently effective window for that date — already resolved to the override if
    // one applies, otherwise the recurring Semaine/Week-end schedule — used as the time
    // picker's starting point and to compute the "dès X si sommeil léger" advance.
    val effectiveWindow = if (window != null) {
        WakeWindow(
            earliestMinute = window.first.hour * 60 + window.first.minute,
            latestMinute = window.second.hour * 60 + window.second.minute
        )
    } else null

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (window != null) dayLabel(window.first) else stringResource(R.string.home_disabled),
                    style = AubeType.eyebrow,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isException) {
                    Text(
                        text = stringResource(R.string.home_exception_suffix),
                        style = AubeType.eyebrow,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            if (window != null && effectiveWindow != null) {
                val fmt = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
                Text(
                    text = window.second.format(fmt),
                    style = AubeType.heroTime.copy(fontSize = 56.sp, lineHeight = 60.sp),
                    // Same accent color as every other tappable time on Home — one visual
                    // rule to learn ("orange time = tappable"), applied everywhere instead
                    // of a one-off hint on the number people instinctively tap first.
                    color = MaterialTheme.colorScheme.primary,
                    modifier = if (locked) Modifier else Modifier.clickable {
                        showTimePicker(context, effectiveWindow.latestMinute) { picked ->
                            // Editing this time only ever creates a just-this-date exception
                            // — it never touches the recurring Semaine/Week-end schedule. It's
                            // also an exact, deliberate pin: reusing the recurring window's old
                            // "earliest" here used to leave a stale light-sleep window (e.g.
                            // still "dès 07:00" under a manually-set 15:30), which makes no
                            // sense for a one-off override — so earliest and latest match.
                            onEditWindow(targetDate!!, WakeWindow(earliestMinute = picked, latestMinute = picked))
                        }
                    }
                )
                if (window.first != window.second) {
                    Text(
                        text = stringResource(R.string.home_early_wake_hint, window.first.format(fmt)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = countdownText(window.second),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isException) {
                    Text(
                        text = stringResource(R.string.home_revert_override),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(onClick = onClearOverride)
                    )
                }
            } else {
                Text(
                    text = "—",
                    style = AubeType.heroTime.copy(fontSize = 56.sp, lineHeight = 60.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = alarmActive,
            enabled = !locked,
            onCheckedChange = { checked ->
                if (checked && blocked) onBlocked() else onToggle(checked)
            },
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
        )
    }
}

/**
 * "Rings in Xh Ymin" below the big time, ticking on its own so it stays right without
 * needing Home to be reopened. [target] is the hard deadline (`window.second`), the actual
 * moment the safety-net alarm is scheduled for — not the light-sleep earliest bound, which
 * may never trigger at all.
 */
@Composable
private fun countdownText(target: ZonedDateTime): String {
    var now by remember(target) { mutableStateOf(ZonedDateTime.now(target.zone)) }
    LaunchedEffect(target) {
        while (true) {
            now = ZonedDateTime.now(target.zone)
            delay(30_000L)
        }
    }
    // Built with plain Int.toString(), not a resource's %d — that can render native digit
    // systems (Arabic-Indic, Devanagari) for some locales, which %s sidesteps entirely.
    val totalMinutes = Duration.between(now, target).toMinutes().coerceAtLeast(0)
    val hours = (totalMinutes / 60).toString()
    val minutes = (totalMinutes % 60).toString()
    return if (totalMinutes >= 60) {
        stringResource(R.string.home_countdown_hours_minutes, hours, minutes)
    } else {
        stringResource(R.string.home_countdown_minutes, minutes)
    }
}

@Composable
private fun dayLabel(dateTime: ZonedDateTime): String {
    val today = ZonedDateTime.now(dateTime.zone).toLocalDate()
    val target = dateTime.toLocalDate()
    return when {
        target.isEqual(today) -> stringResource(R.string.day_today)
        target.isEqual(today.plusDays(1)) -> stringResource(R.string.day_tomorrow)
        // Weekday name follows the device's own language/locale, not a fixed one — matches
        // the rest of the app's multi-language support instead of always showing French.
        else -> dateTime.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
            .replaceFirstChar { it.uppercase(Locale.getDefault()) }
    }
}

@Composable
private fun SettingsSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
