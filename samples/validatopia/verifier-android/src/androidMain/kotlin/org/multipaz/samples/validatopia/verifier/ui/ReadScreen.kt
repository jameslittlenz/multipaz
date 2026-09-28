package org.multipaz.samples.validatopia.verifier.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Simple
import org.multipaz.compose.camera.CameraCaptureResolution
import org.multipaz.compose.camera.CameraSelection
import org.multipaz.compose.permissions.rememberBluetoothEnabledState
import org.multipaz.compose.permissions.rememberBluetoothPermissionState
import org.multipaz.compose.permissions.rememberCameraPermissionState
import org.multipaz.compose.qrcode.QrCodeScanner
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.nfc.MdocHandoverType
import org.multipaz.mdoc.nfc.MdocReaderNfcHandoverOptions
import org.multipaz.mdoc.nfc.scanMdocReader
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.nfc.NfcTagReader
import org.multipaz.samples.validatopia.shared.reader.PhotoIdReadResult
import org.multipaz.samples.validatopia.shared.reader.PhotoIdReader
import org.multipaz.samples.validatopia.shared.result.PhotoIdVerification
import org.multipaz.samples.validatopia.shared.ui.SectionHeading
import org.multipaz.samples.validatopia.shared.ui.ValidatopiaScaffold
import org.multipaz.samples.validatopia.shared.usecase.PhotoIdUseCase
import org.multipaz.samples.validatopia.verifier.VerifierModel
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.fromBase64Url

private const val TAG = "ReadScreen"
private val TRANSPORT_OPTIONS = MdocTransportOptions(bleUseL2CAP = false, bleUseL2CAPInEngagement = true)

private sealed class ReadState {
    data object Choose : ReadState()
    data object ScanningQr : ReadState()
    data class Working(val message: String) : ReadState()
    data class Failed(val message: String) : ReadState()
}

/**
 * Engagement for one use case: scan the wallet's QR code, or tap the wallet phone (this phone acts
 * as the NFC reader). Both then connect over Bluetooth. Nothing here times out; the holder takes
 * as long as they need to decide, and the verifier can cancel.
 */
@Composable
fun ReadScreen(
    model: VerifierModel,
    useCase: PhotoIdUseCase,
    onBack: () -> Unit,
    onResult: (PhotoIdVerification) -> Unit,
    injectedQr: String? = null,
    onInjectedQrConsumed: () -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope { model.promptModel }
    var state by remember { mutableStateOf<ReadState>(ReadState.Choose) }
    var job by remember { mutableStateOf<Job?>(null) }
    val blePermissionState = rememberBluetoothPermissionState()
    val bleEnabledState = rememberBluetoothEnabledState()
    val cameraPermissionState = rememberCameraPermissionState()
    val nfcReader = remember { NfcTagReader.getReaders().firstOrNull() }

    fun run(initialMessage: String, block: suspend () -> PhotoIdReadResult?) {
        state = ReadState.Working(initialMessage)
        job = coroutineScope.launch {
            try {
                val read = block()
                if (read == null) {
                    state = ReadState.Choose
                    return@launch
                }
                state = ReadState.Working("Checking the response…")
                onResult(model.verifier.verify(useCase, read.deviceResponse, read.sessionTranscript))
            } catch (e: Exception) {
                // UI boundary: engagement, transport and verification errors are all shown, with a retry.
                if (e is CancellationException) throw e
                Logger.e(TAG, "Reading a Photo ID failed", e)
                state = ReadState.Failed(e.message ?: e.toString())
            }
        }
    }

    fun readQr(qrCode: String) {
        val encodedDeviceEngagement = ByteString(qrCode.removePrefix("mdoc:").fromBase64Url())
        run("Connecting to the wallet…") {
            val transport = PhotoIdReader.createTransportForQrEngagement(encodedDeviceEngagement, TRANSPORT_OPTIONS)
            state = ReadState.Working("Waiting for the holder to review the request on their phone…")
            PhotoIdReader.read(useCase, encodedDeviceEngagement, Simple.NULL, transport, model.readerKey)
        }
    }

    fun readNfc(reader: NfcTagReader) {
        run("Hold the wallet phone against the back of this phone.") {
            val uuid = UUID.randomUUID()
            reader.scanMdocReader(
                message = "Hold the wallet phone against the back of this phone.",
                options = TRANSPORT_OPTIONS,
                handoverOptions = MdocReaderNfcHandoverOptions(),
                selectConnectionMethod = { it.firstOrNull() },
                negotiatedHandoverConnectionMethods = listOf(
                    MdocConnectionMethodBle(
                        supportsPeripheralServerMode = false,
                        supportsCentralClientMode = true,
                        peripheralServerModeUuid = null,
                        centralClientModeUuid = uuid,
                    ),
                    MdocConnectionMethodBle(
                        supportsPeripheralServerMode = true,
                        supportsCentralClientMode = false,
                        peripheralServerModeUuid = uuid,
                        centralClientModeUuid = null,
                    ),
                ),
            ) { scan ->
                state = ReadState.Working("Waiting for the holder to review the request on their phone…")
                PhotoIdReader.read(
                    useCase = useCase,
                    encodedDeviceEngagement = scan.encodedDeviceEngagement,
                    handover = scan.handover,
                    transport = scan.transport,
                    readerKey = model.readerKey,
                    insertSequenceNumbers = scan.type == MdocHandoverType.V2_HANDOVER,
                )
            }
        }
    }

    // Debug builds: a QR payload handed in by intent instead of the camera (see MainActivity).
    LaunchedEffect(injectedQr) {
        if (injectedQr != null && state == ReadState.Choose) {
            onInjectedQrConsumed()
            readQr(injectedQr)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        state = ReadState.Choose
    }

    ValidatopiaScaffold(title = useCase.title, onBack = { cancel(); onBack() }) {
        Text(useCase.purpose, style = MaterialTheme.typography.bodyLarge)

        when {
            !blePermissionState.isGranted -> {
                Text(
                    text = "Validatopia Verify connects to the wallet over Bluetooth. Allow it to find and " +
                        "connect to nearby devices.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = { coroutineScope.launch { blePermissionState.launchPermissionRequest() } }) {
                    Text("Allow nearby devices")
                }
                return@ValidatopiaScaffold
            }
            !bleEnabledState.isEnabled -> {
                Text("Bluetooth is off. Turn it on to read a Photo ID.", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { coroutineScope.launch { bleEnabledState.enable() } }) {
                    Text("Turn on Bluetooth")
                }
                return@ValidatopiaScaffold
            }
        }

        when (val current = state) {
            ReadState.Choose, is ReadState.Failed -> {
                if (current is ReadState.Failed) {
                    Text(
                        text = "That didn't work: ${current.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                SectionHeading("How is the holder sharing?")
                Button(
                    onClick = {
                        if (cameraPermissionState.isGranted) {
                            state = ReadState.ScanningQr
                        } else {
                            coroutineScope.launch {
                                cameraPermissionState.launchPermissionRequest()
                                if (cameraPermissionState.isGranted) {
                                    state = ReadState.ScanningQr
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan their QR code")
                }
                if (nfcReader != null) {
                    Button(onClick = { readNfc(nfcReader) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Nfc, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Tap their phone")
                    }
                } else {
                    Text(
                        text = "This phone can't read NFC, so ask the holder to show a QR code.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                SectionHeading("This request asks for")
                RequestedElementsList(useCase)
            }
            ReadState.ScanningQr -> {
                Text(
                    "Point the camera at the QR code on the holder's phone.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                QrCodeScanner(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    cameraSelection = CameraSelection.DEFAULT_BACK_CAMERA,
                    captureResolution = CameraCaptureResolution.MEDIUM,
                    showCameraPreview = true,
                    onCodeScanned = { code ->
                        if (code != null && code.startsWith("mdoc:") && state == ReadState.ScanningQr) {
                            readQr(code)
                        }
                    },
                )
                OutlinedButton(onClick = ::cancel) { Text("Cancel") }
            }
            is ReadState.Working -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                ) {
                    CircularProgressIndicator()
                    Text(current.message, style = MaterialTheme.typography.bodyLarge)
                }
                OutlinedButton(onClick = ::cancel) { Text("Cancel") }
            }
        }
    }
}
