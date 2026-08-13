package com.example.ui.screens
import com.example.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField

import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import android.widget.Toast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.VaultItemEntity
import com.example.ui.MainViewModel
import com.example.ui.appendPinDigit
import com.example.ui.changeVaultPin
import com.example.ui.clearPinDigit
import com.example.ui.lockVault
import com.example.ui.onBiometricError
import com.example.ui.onBiometricSuccess
import com.example.ui.theme.BhagwaOrange
import com.example.ui.theme.CosmicBlue
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.SoftGold
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@Composable
fun VaultScreen(
    viewModel: MainViewModel,
    isUnlocked: Boolean,
    enteredPin: String,
    pinError: String?,
    vaultItems: List<VaultItemEntity>
) {
    val context = LocalContext.current
    val activity = remember(context) { context as? FragmentActivity }
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    
    val isBiometricAvailable = remember(context) {
        val biometricManager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or 
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    var biometricEnabled by rememberSaveable { mutableStateOf(true) }

    val showBiometricPrompt = remember(activity, executor, viewModel, isBiometricAvailable, biometricEnabled) {
        {
            if (activity != null && isBiometricAvailable && biometricEnabled) {
                val biometricPrompt = BiometricPrompt(
                    activity,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && 
                                errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                                viewModel.onBiometricError(errString.toString())
                            }
                        }

                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            viewModel.onBiometricSuccess()
                        }

                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            viewModel.onBiometricError("Authentication failed")
                        }
                    }
                )

                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Vault")
                    .setSubtitle("Authenticate using your biometric credential")
                    .setNegativeButtonText("Use PIN")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or 
                            BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    .build()

                biometricPrompt.authenticate(promptInfo)
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(isUnlocked, isBiometricAvailable, biometricEnabled) {
        if (!isUnlocked && isBiometricAvailable && biometricEnabled) {
            showBiometricPrompt()
        }
    }
    var autoLockTimer by rememberSaveable { mutableStateOf("1 minute") }
    var showChangePinDialog by rememberSaveable { mutableStateOf(false) }
    var changePinOld by rememberSaveable { mutableStateOf("") }
    var changePinNew by rememberSaveable { mutableStateOf("") }
    var changePinConfirm by rememberSaveable { mutableStateOf("") }
    var changePinError by rememberSaveable { mutableStateOf<String?>(null) }
    if (showChangePinDialog) {
        AlertDialog(
            onDismissRequest = {
                showChangePinDialog = false
                changePinOld = ""
                changePinNew = ""
                changePinConfirm = ""
                changePinError = null
            },
            title = { Text(stringResource(R.string.change_master_pin)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = changePinOld, onValueChange = { changePinOld = it }, label = { Text(stringResource(R.string.current_pin)) }, singleLine = true)
                    OutlinedTextField(value = changePinNew, onValueChange = { changePinNew = it }, label = { Text(stringResource(R.string.new_4_digit_pin)) }, singleLine = true)
                    OutlinedTextField(value = changePinConfirm, onValueChange = { changePinConfirm = it }, label = { Text(stringResource(R.string.confirm_new_pin)) }, singleLine = true)
                    if (changePinError != null) Text(text = changePinError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (changePinNew != changePinConfirm) changePinError = "New PIN and confirmation do not match."
                    else if (changePinNew.length != 4 || !changePinNew.all { it.isDigit() }) changePinError = "New PIN must be exactly 4 digits."
                    else {
                        val success = viewModel.changeVaultPin(changePinOld, changePinNew)
                        if (success) {
                            showChangePinDialog = false
                            changePinOld = ""; changePinNew = ""; changePinConfirm = ""; changePinError = null
                        } else changePinError = "Failed to update PIN. Check current PIN."
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = BhagwaOrange)) { Text(stringResource(R.string.change)) }
            },
            dismissButton = {
                TextButton(onClick = { showChangePinDialog = false; changePinOld = ""; changePinNew = ""; changePinConfirm = ""; changePinError = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
    if (!isUnlocked) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(BhagwaOrange.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(imageVector = Icons.Default.Lock, contentDescription = stringResource(R.string.vault_locked), tint = BhagwaOrange, modifier = Modifier.size(40.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Secure Encrypted Vault", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text(text = "Enter 4-Digit Master PIN", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(4) { index -> Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(if (index < enteredPin.length) BhagwaOrange else MaterialTheme.colorScheme.surfaceVariant)) }
            }
            if (pinError != null) { Spacer(modifier = Modifier.height(12.dp)); Text(text = pinError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(modifier = Modifier.height(32.dp))
            val keypadDigits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "BIO", "0", "DEL")
            LazyVerticalGrid(columns = GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.width(280.dp)) {
                items(keypadDigits) { digit ->
                    when (digit) {
                        "BIO" -> if (isBiometricAvailable && biometricEnabled) IconButton(onClick = { showBiometricPrompt() }, modifier = Modifier.size(64.dp).clip(CircleShape).background(EmeraldGreen.copy(alpha = 0.15f))) { Icon(Icons.Default.Fingerprint, contentDescription = stringResource(R.string.biometric), tint = EmeraldGreen) } else Box(modifier = Modifier.size(64.dp))
                        "DEL" -> IconButton(onClick = { viewModel.clearPinDigit() }, modifier = Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) { Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.onSurface) }
                        else -> Surface(onClick = { viewModel.appendPinDigit(digit) }, modifier = Modifier.size(64.dp).testTag("pin_key_$digit"), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) { Box(contentAlignment = Alignment.Center) { Text(text = digit, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) } }
                    }
                }
            }
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Box(modifier = Modifier.fillMaxWidth().background(Brush.linearGradient(colors = listOf(CosmicBlue, MaterialTheme.colorScheme.surfaceVariant))).padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.LockOpen, contentDescription = stringResource(R.string.unlocked), tint = EmeraldGreen); Spacer(modifier = Modifier.width(8.dp)); Text(text = "Encrypted Vault Unlocked", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                                Text(text = "AES-256 Android Keystore Cipher Active", fontSize = 12.sp, color = SoftGold)
                            }
                            Button(onClick = { viewModel.lockVault() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.lock_vault)) }
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "Vault Security Options", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = BhagwaOrange)
                        Spacer(modifier = Modifier.height(12.dp))
                        if (isBiometricAvailable) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column { Text(text = stringResource(R.string.biometric_unlock), fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text(text = stringResource(R.string.use_fingerprint_or_face_id_to_), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Switch(checked = biometricEnabled, onCheckedChange = { biometricEnabled = it }, colors = SwitchDefaults.colors(checkedThumbColor = BhagwaOrange))
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column { Text(text = stringResource(R.string.auto_lock_timer), fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text(text = stringResource(R.string.locks_automatically_when_inact), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text(text = autoLockTimer, color = BhagwaOrange, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.clickable { autoLockTimer = if (autoLockTimer == "1 minute") "5 minutes" else "1 minute" })
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column { Text(text = stringResource(R.string.change_master_pin), fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text(text = stringResource(R.string.update_your_4_digit_security_p), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            OutlinedButton(onClick = { showChangePinDialog = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = BhagwaOrange), modifier = Modifier.testTag("change_pin_button")) { Text(stringResource(R.string.change), fontSize = 12.sp) }
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f))) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(imageVector = Icons.Default.Shield, contentDescription = "Best-Effort Overwrite Disclaimer", tint = BhagwaOrange, modifier = Modifier.size(24.dp))
                        Column {
                            Text(text = "Best-Effort Source Overwrite", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = "When encrypting a file to the Vault, the original source is overwritten matching its exact file size (3-pass random/zeros data) before deletion.\n\nDisclaimer: Modern flash/SSD storage utilizes physical Wear-Leveling controllers. Software-level overwriting is performed on a best-effort basis and does not guarantee absolute block-level physical erasure.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
                        }
                    }
                }
            }
            item { Text(text = "Encrypted Files (${vaultItems.size})", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground) }
            if (vaultItems.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(imageVector = Icons.Default.Shield, contentDescription = stringResource(R.string.vault_empty), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = "No encrypted files in vault.\nUse File Manager menu to encrypt sensitive files.", textAlign = TextAlign.Center, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                items(vaultItems, key = { it.id }) { item ->
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(EmeraldGreen.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Security, contentDescription = stringResource(R.string.encrypted), tint = EmeraldGreen) }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column { Text(text = item.originalName, fontWeight = FontWeight.Bold, fontSize = 14.sp); Text(text = "${item.encryptedName} • ${formatFileSize(item.sizeBytes)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            OutlinedButton(onClick = { viewModel.unlockFromVault(item) }) { Text(stringResource(R.string.decrypt), fontSize = 12.sp, color = EmeraldGreen) }
                        }
                    }
                }
            }
        }
    }
}
