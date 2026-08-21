package com.reveil.aube.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.reveil.aube.R
import kotlinx.coroutines.launch

/**
 * [onContinue] is only passed from the onboarding flow, where this screen is a detour off a
 * "Set up contacts" button rather than a destination of its own — reachable from Settings any
 * time, the back arrow there already does the right thing. Onboarding has no equivalent path
 * back to its own "Continue", so without a button here the only way forward was backing out
 * first, whether or not a contact was ever added.
 */
@Composable
fun AccountabilityContactsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit,
    onContinue: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsRepository.settings.collectAsState(initial = null)
    val current = settings ?: return

    var hasSmsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasSmsPermission = granted
    }

    val pickContactLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        // Querying this exact URI doesn't need READ_CONTACTS: the system Contacts app grants
        // temporary read access to just the one entry the user picked, as part of the result.
        context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val number = cursor.getString(0)?.trim()
                if (!number.isNullOrEmpty() && number !in current.emergencyContacts) {
                    scope.launch { settingsRepository.setEmergencyContacts(current.emergencyContacts + number) }
                }
            }
        }
    }

    var newNumber by remember { mutableStateOf("") }
    var messageText by remember(current.accountabilityMessage) {
        mutableStateOf(current.accountabilityMessage ?: "")
    }
    val signaturePreview = stringResource(R.string.accountability_sms_signature, stringResource(R.string.app_name))

    Column(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.weight(1f),
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
                Text(stringResource(R.string.settings_accountability_label), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.accountability_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            if (!hasSmsPermission) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.accountability_permission_missing),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    TextButton(onClick = { permissionLauncher.launch(Manifest.permission.SEND_SMS) }) {
                        Text(stringResource(R.string.action_allow))
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(20.dp))
            }
        }

        items(current.emergencyContacts, key = { it }) { number ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(number, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                IconButton(onClick = {
                    scope.launch { settingsRepository.setEmergencyContacts(current.emergencyContacts - number) }
                }) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = stringResource(R.string.cd_delete_contact),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }

        item {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                    pickContactLauncher.launch(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Contacts, contentDescription = null, modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.accountability_pick_contact_button))
            }

            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.accountability_manual_entry_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newNumber,
                    onValueChange = { newNumber = it },
                    placeholder = { Text(stringResource(R.string.accountability_add_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    val trimmed = newNumber.trim()
                    if (trimmed.isNotEmpty() && trimmed !in current.emergencyContacts) {
                        scope.launch { settingsRepository.setEmergencyContacts(current.emergencyContacts + trimmed) }
                    }
                    newNumber = ""
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.accountability_add_button))
                }
            }

            Spacer(Modifier.height(28.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = messageText,
                onValueChange = { text ->
                    messageText = text
                    scope.launch { settingsRepository.setAccountabilityMessage(text) }
                },
                label = { Text(stringResource(R.string.accountability_message_label)) },
                placeholder = { Text(stringResource(R.string.accountability_message_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Spacer(Modifier.height(6.dp))
            // The signature isn't part of this field's stored value — see
            // AccountabilityNotifier — so this is shown, not editable, as a plain fact of
            // what actually gets sent.
            Text(
                stringResource(R.string.accountability_signature_note, signaturePreview),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
    if (onContinue != null) {
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text(
                if (current.emergencyContacts.isEmpty()) stringResource(R.string.action_skip)
                else stringResource(R.string.action_continue)
            )
        }
    }
    }
}
