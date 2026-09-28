package org.multipaz.samples.validatopia.wallet.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
import org.multipaz.samples.validatopia.shared.ui.PoweredByValid8
import org.multipaz.samples.validatopia.shared.ui.SectionHeading
import org.multipaz.samples.validatopia.shared.ui.ValidatopiaScaffold
import org.multipaz.samples.validatopia.wallet.BuildConfig
import org.multipaz.samples.validatopia.wallet.WalletModel

@Composable
fun SettingsScreen(model: WalletModel, onBack: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val savedUrl by model.issuerUrl.collectAsState()
    var url by remember(savedUrl) { mutableStateOf(savedUrl) }
    var message by remember { mutableStateOf<String?>(null) }
    val valid = url.startsWith("https://") || url.startsWith("http://")

    ValidatopiaScaffold(title = "Settings", onBack = onBack) {
        SectionHeading("Issuer")
        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it.trim()
                message = null
            },
            label = { Text("Issuer URL") },
            supportingText = {
                Text(if (valid) "The Validatopia issuer's address, ending in /openid4vci" else "Must start with https://")
            },
            isError = !valid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = valid && url != savedUrl,
            onClick = {
                coroutineScope.launch {
                    model.setIssuerUrl(url.removeSuffix("/"))
                    message = "Saved."
                }
            },
        ) { Text("Save") }
        message?.let {
            Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }

        SectionHeading("About")
        Text(
            text = "Validatopia Wallet ${BuildConfig.VERSION_NAME}. A demonstration app: every identity in it " +
                "is a test identity.",
            style = MaterialTheme.typography.bodyLarge,
        )
        PoweredByValid8(modifier = Modifier.fillMaxWidth())
        if (BuildConfig.USE_DEV_ATTESTATION) {
            Text(
                text = "Development build: this copy of the app vouches for itself with a public development " +
                    "key, instead of Android key attestation checked by the Validatopia back-end. Only " +
                    "issuers configured to trust that key will accept it.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
