package org.multipaz.samples.validatopia.wallet.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.multipaz.compose.permissions.rememberBluetoothEnabledState
import org.multipaz.compose.permissions.rememberBluetoothPermissionState
import org.multipaz.compose.presentment.MdocProximityQrPresentment
import org.multipaz.compose.presentment.MdocProximityQrSettings
import org.multipaz.compose.prompt.PresentmentActivity
import org.multipaz.compose.qrcode.generateQrCode
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.samples.validatopia.shared.transport.ValidatopiaTransport
import org.multipaz.samples.validatopia.shared.ui.ValidatopiaScaffold
import org.multipaz.samples.validatopia.wallet.WalletModel

/**
 * Presents a document by QR code: the verifier scans the code, then the two phones connect over
 * Bluetooth. The consent sheet (in `PresentmentActivity`) names the verifier when its request is
 * signed by a trusted reader certificate. There's no time limit on any step.
 */
@Composable
fun PresentQrScreen(model: WalletModel, documentId: String, onBack: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val blePermissionState = rememberBluetoothPermissionState()
    val bleEnabledState = rememberBluetoothEnabledState()
    val documentInfos by model.documentModel.documentInfos.collectAsState()
    val document = documentInfos.firstOrNull { it.document.identifier == documentId }?.document

    ValidatopiaScaffold(title = "Share with QR code", onBack = onBack) {
        when {
            document == null -> Text("This document is no longer in the wallet.")
            !blePermissionState.isGranted -> {
                Text(
                    text = "Sharing by QR code uses Bluetooth to connect to the verifier's phone. Allow " +
                        "Validatopia Wallet to find and connect to nearby devices.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = { coroutineScope.launch { blePermissionState.launchPermissionRequest() } }) {
                    Text("Allow nearby devices")
                }
            }
            !bleEnabledState.isEnabled -> {
                Text(
                    text = "Bluetooth is off. Turn it on to share by QR code.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = { coroutineScope.launch { bleEnabledState.enable() } }) {
                    Text("Turn on Bluetooth")
                }
            }
            else -> MdocProximityQrPresentment(
                modifier = Modifier.fillMaxWidth(),
                source = model.presentmentSource,
                promptModel = PresentmentActivity.promptModel,
                preselectedDocuments = listOf(document),
                prepareSettings = { generateQrCode ->
                    // Show the code straight away; there's nothing to configure.
                    LaunchedEffect(Unit) {
                        generateQrCode(
                            MdocProximityQrSettings(
                                availableConnectionMethods = ValidatopiaTransport.bleConnectionMethods(),
                                createTransportOptions = ValidatopiaTransport.options,
                            )
                        )
                    }
                },
                showQrCode = { uri, reset ->
                    val qrCode = remember(uri) { generateQrCode(uri) }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Ask the verifier to scan this code with Validatopia Verify.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Image(
                            bitmap = qrCode,
                            contentDescription = "QR code for sharing your document. Show it to the verifier.",
                            contentScale = ContentScale.FillWidth,
                            // A light quiet zone keeps it scannable in dark theme too.
                            modifier = Modifier
                                .widthIn(max = 360.dp)
                                .fillMaxWidth()
                                .background(Color.White)
                                .padding(16.dp),
                        )
                        OutlinedButton(onClick = { reset(); onBack() }) { Text("Cancel") }
                    }
                },
                showTransacting = { reset ->
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ProgressRow("Connected. Check your screen to review the request.")
                        OutlinedButton(onClick = { reset(); onBack() }) { Text("Cancel") }
                    }
                },
                showCompleted = { error, reset ->
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = when (error) {
                                null -> "Shared. The verifier has your answer."
                                is PresentmentCanceledException -> "Nothing was shared."
                                else -> "Sharing didn't complete: ${error.message ?: error.toString()}"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        Button(onClick = { reset(); onBack() }) { Text("Done") }
                    }
                },
            )
        }
    }
}
