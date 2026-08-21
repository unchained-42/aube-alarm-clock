package com.reveil.aube.ringing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.reveil.aube.R
import kotlin.random.Random

/**
 * The anti-snooze dismiss step: scanning a barcode you've placed somewhere across the room
 * demands real motor planning, which is much harder to do on autopilot than tapping a
 * button in bed. Every install always has a working code — the built-in Aube one by default,
 * see [com.reveil.aube.settings.AlarmSettings.effectiveQrPayload] — so there is no
 * "unconfigured" state to fall back out of the scan for; a weaker hold-to-dismiss fallback
 * used to exist here for that case and was itself a bypass once the default made "no code"
 * unreachable in practice.
 *
 * [EmergencyChallengeScreen] is the one deliberate exception — for sleeping away from the
 * printed code with the phone still in hand. It's not meant to be easy: retyping ~30 random
 * mixed-case/digit/symbol characters correctly, half-asleep, on a phone keyboard that keeps
 * switching layers, is real, sustained effort — not a loophole, a genuine last resort.
 */
@Composable
fun QrDismissScreen(expectedPayload: String, onDismissed: () -> Unit) {
    var useEmergencyChallenge by remember { mutableStateOf(false) }

    if (useEmergencyChallenge) {
        EmergencyChallengeScreen(
            onDismissed = onDismissed,
            onBackToScan = { useEmergencyChallenge = false }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        var mismatchMessage by remember { mutableStateOf<String?>(null) }
        // Resolved during composition (main thread) — the analyzer callback below runs on a
        // background executor thread, where calling stringResource() directly isn't safe.
        val wrongCodeMessage = stringResource(R.string.qr_dismiss_wrong_code)
        val defaultInstruction = stringResource(R.string.qr_dismiss_instruction)
        BarcodeCameraView(
            modifier = Modifier.fillMaxSize(),
            onBarcodeDetected = { value ->
                if (value == expectedPayload) {
                    onDismissed()
                } else {
                    mismatchMessage = wrongCodeMessage
                }
            }
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = mismatchMessage ?: defaultInstruction,
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                    .padding(16.dp)
            )
            TextButton(onClick = { useEmergencyChallenge = true }) {
                Text(stringResource(R.string.qr_dismiss_no_code_link), color = Color.White.copy(alpha = 0.75f))
            }
        }
    }
}

private const val CHALLENGE_LENGTH = 45
private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val DIGITS = "0123456789"
private const val SYMBOLS = "!@#\$%^&*-_=+?"

/** Guarantees at least one of each category, not just a uniform draw from the combined pool —
 * matches "lowercase, uppercase, digit, symbol" rather than leaving it to chance. */
private fun generateChallenge(): String {
    val all = LOWER + UPPER + DIGITS + SYMBOLS
    val required = listOf(LOWER, UPPER, DIGITS, SYMBOLS).map { it[Random.nextInt(it.length)] }
    val rest = (required.size until CHALLENGE_LENGTH).map { all[Random.nextInt(all.length)] }
    return (required + rest).shuffled().joinToString("")
}

@Composable
private fun EmergencyChallengeScreen(onDismissed: () -> Unit, onBackToScan: () -> Unit) {
    var challenge by remember { mutableStateOf(generateChallenge()) }
    var input by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.emergency_title),
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Text(
                stringResource(R.string.emergency_subtitle),
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )
            Text(
                text = challenge,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .padding(16.dp)
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = input,
                // Checked character by character as you type, not on submit: a single wrong
                // character anywhere restarts the whole thing with a fresh, longer code. A
                // submit-then-check model lets you fix typos before they count, which turns
                // "retype this exactly" into "retype this eventually" — the opposite of the
                // point. A pasted string that doesn't match from the very first character
                // hits the same restart, so pasting can't shortcut it either.
                onValueChange = { newValue ->
                    if (newValue.length <= challenge.length && challenge.startsWith(newValue)) {
                        input = newValue
                        showError = false
                        if (newValue.length == challenge.length) {
                            onDismissed()
                        }
                    } else {
                        showError = true
                        challenge = generateChallenge()
                        input = ""
                    }
                },
                singleLine = false,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false
                ),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.4f),
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )
            if (showError) {
                Text(
                    stringResource(R.string.emergency_error),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onBackToScan) {
                Text(stringResource(R.string.emergency_back_to_scan), color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}
