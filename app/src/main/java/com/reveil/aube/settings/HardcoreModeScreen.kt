package com.reveil.aube.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.reveil.aube.R
import com.reveil.aube.kiosk.KioskPolicy
import com.reveil.aube.ringing.AlarmRingingService

/**
 * Status + on/off for the device-owner "hardcore mode" — see [KioskPolicy] for what it does.
 * Turning it ON can't happen from inside the app at all (Android only allows it over adb,
 * on a phone with no accounts), so the inactive state is instructions, not a switch.
 * Turning it OFF is a button here — the one and only way out short of a factory reset —
 * refused while an alarm is ringing, which is the whole point of the mode.
 */
@Composable
fun HardcoreModeScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // Enrollment happens outside the app (adb), so re-check whenever this screen comes back.
    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val active = remember(tick) { KioskPolicy.isDeviceOwner(context) }
    var confirmRelease by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                stringResource(R.string.hardcore_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (active) stringResource(R.string.hardcore_status_active) else stringResource(R.string.hardcore_status_inactive),
            style = MaterialTheme.typography.titleMedium,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        Text(
            stringResource(R.string.hardcore_what_it_does),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        listOf(
            R.string.hardcore_blocks_power_menu,
            R.string.hardcore_blocks_leave_screen,
            R.string.hardcore_blocks_force_stop,
            R.string.hardcore_blocks_safe_mode,
            R.string.hardcore_blocks_clock
        ).forEach { res ->
            Text(
                "•  " + stringResource(res),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.hardcore_limits),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(28.dp))

        if (active) {
            OutlinedButton(onClick = {
                if (AlarmRingingService.isActive) {
                    Toast.makeText(context, R.string.hardcore_release_refused_ringing, Toast.LENGTH_LONG).show()
                } else {
                    confirmRelease = true
                }
            }) {
                Text(stringResource(R.string.hardcore_release_button))
            }
        } else {
            Text(
                stringResource(R.string.hardcore_how_to_enable),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))
            listOf(
                R.string.hardcore_step_accounts,
                R.string.hardcore_step_usb_debug,
                R.string.hardcore_step_command
            ).forEachIndexed { i, res ->
                Text(
                    "${i + 1}.  " + stringResource(res),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "adb shell dpm set-device-owner " + KioskPolicy.admin(context).flattenToShortString(),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp)
            )
        }
    }

    if (confirmRelease) {
        AlertDialog(
            onDismissRequest = { confirmRelease = false },
            title = { Text(stringResource(R.string.hardcore_release_confirm_title)) },
            text = { Text(stringResource(R.string.hardcore_release_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRelease = false
                    // Re-checked at the last moment: a ring could have started while the
                    // dialog was open.
                    if (AlarmRingingService.isActive) {
                        Toast.makeText(context, R.string.hardcore_release_refused_ringing, Toast.LENGTH_LONG).show()
                    } else {
                        KioskPolicy.release(context)
                        tick++
                    }
                }) { Text(stringResource(R.string.hardcore_release_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRelease = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}
