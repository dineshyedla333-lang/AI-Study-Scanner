package com.aistudyscanner.agent.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aistudyscanner.agent.auth.AuthManager

@Composable
fun LoginScreen(
    onRegistered: () -> Unit,
    vm: LoginViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.ui.collectAsState()

    LaunchedEffect(Unit) { vm.init(context) }

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        vm.handleSignInResult(result.data)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // No Scaffold here, so inset the system bars ourselves — edge-to-edge
            // is enforced at targetSdk 36 and would otherwise hide the title.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Welcome to AI Study Scan Agent",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Sign in with Google and add your mobile number to register. " +
                "This lets us deliver your daily UPSC current-affairs notifications.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        // Step 1 — Google sign-in
        val signedIn = state.signedInEmail != null
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (signedIn)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "1. Google account",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                if (signedIn) {
                    Text(
                        text = "Signed in as ${state.signedInName ?: state.signedInEmail}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.signedInEmail?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            signInLauncher.launch(AuthManager.googleClient(context).signInIntent)
                        },
                        enabled = !state.isWorking && !state.configMissing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Sign in with Google")
                    }
                }
            }
        }

        // Step 2 — mobile number
        OutlinedTextField(
            value = state.phone,
            onValueChange = vm::onPhoneChange,
            label = { Text("2. Mobile number (10 digits)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.configMissing) {
            Text(
                text = "Google sign-in is not configured on this build yet. Enable " +
                    "Google in Firebase Authentication, add the app's SHA-1, then " +
                    "re-download google-services.json and rebuild.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        state.error?.let { err ->
            Text(
                text = err,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = { vm.register(context, onRegistered) },
            enabled = !state.isWorking && signedIn && state.phone.length == 10,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isWorking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Continue")
            }
        }
    }
}
