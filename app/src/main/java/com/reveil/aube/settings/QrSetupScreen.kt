package com.reveil.aube.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.reveil.aube.R
import com.reveil.aube.qr.DEFAULT_QR_PAYLOAD
import com.reveil.aube.qr.printQrCard
import com.reveil.aube.qr.rememberQrCardBitmap
import com.reveil.aube.ringing.BarcodeCameraView
import kotlinx.coroutines.launch

@Composable
fun QrSetupScreen(settingsRepository: SettingsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by settingsRepository.settings.collectAsState(initial = null)
    var scanning by remember { mutableStateOf(false) }
    val current = settings ?: return
    val cardBitmap = rememberQrCardBitmap(DEFAULT_QR_PAYLOAD)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(stringResource(R.string.settings_qr_label), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        }

        if (scanning) {
            Column(modifier = Modifier.weight(1f)) {
                BarcodeCameraView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onBarcodeDetected = { value ->
                        scanning = false
                        scope.launch { settingsRepository.setQrPayload(value) }
                    }
                )
                Text(
                    stringResource(R.string.qr_setup_scan_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(20.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                Text(
                    stringResource(R.string.qr_setup_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1400f / 1900f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (cardBitmap != null) {
                        Image(
                            bitmap = cardBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.cd_qr_card),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = { cardBitmap?.let { printQrCard(context, it) } },
                    enabled = cardBitmap != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.qr_setup_print_button))
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    if (current.usingDefaultQrCode) stringResource(R.string.qr_setup_active_default)
                    else stringResource(R.string.qr_setup_active_custom),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (current.usingDefaultQrCode) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.qr_setup_default_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!current.usingDefaultQrCode) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { scope.launch { settingsRepository.setQrPayload(null) } }) {
                        Text(stringResource(R.string.qr_setup_reset_default))
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.qr_setup_use_other))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
