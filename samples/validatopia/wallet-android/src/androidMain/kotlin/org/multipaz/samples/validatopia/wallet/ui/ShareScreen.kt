package org.multipaz.samples.validatopia.wallet.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import org.multipaz.compose.camera.CameraCaptureResolution
import org.multipaz.compose.camera.CameraSelection
import org.multipaz.compose.permissions.rememberBluetoothEnabledState
import org.multipaz.compose.permissions.rememberBluetoothPermissionState
import org.multipaz.compose.permissions.rememberCameraPermissionState
import org.multipaz.compose.presentment.MdocProximityQrPresentment
import org.multipaz.compose.presentment.MdocProximityQrSettings
import org.multipaz.compose.prompt.PresentmentActivity
import org.multipaz.compose.qrcode.QrCodeScanner
import org.multipaz.compose.qrcode.generateQrCode
import org.multipaz.digitalcredentials.DigitalCredentials
import org.multipaz.digitalcredentials.getDefault
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.samples.validatopia.shared.transport.ValidatopiaTransport
import org.multipaz.samples.validatopia.shared.ui.ValidatopiaScaffold
import org.multipaz.samples.validatopia.wallet.R
import org.multipaz.samples.validatopia.wallet.WalletModel
import org.multipaz.samples.validatopia.wallet.WalletNfcService
import org.multipaz.samples.validatopia.wallet.WalletUriSchemePresentmentActivity

/**
 * The one place to share from, in one of two panels:
 * - In person: a QR code for the verifier to scan (then the two phones connect over Bluetooth), or
 *   a tap on the verifier's NFC reader, which [WalletNfcService] answers. The holder picks in the
 *   consent sheet from the documents that answer the request.
 * - Online: scanning a website's OpenID4VP request code, which [OnlinePanel] hands to
 *   [WalletUriSchemePresentmentActivity]. Websites can also ask through the W3C Digital
 *   Credentials API, without a code.
 *
 * The consent sheet names the verifier when its request is signed by a trusted certificate. There's
 * no time limit on any step.
 */
@Composable
fun ShareScreen(model: WalletModel, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(ShareTab.IN_PERSON) }
    ValidatopiaScaffold(title = "Share", onBack = onBack) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ShareTab.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ShareTab.entries.size),
                ) {
                    Text(entry.label)
                }
            }
        }
        // Only the selected panel is composed, so the camera runs only on the Online tab and the
        // Bluetooth code only on the In person tab.
        when (tab) {
            ShareTab.IN_PERSON -> InPersonPanel(model, onBack)
            ShareTab.ONLINE -> OnlinePanel()
        }
    }
}

private enum class ShareTab(val label: String) {
    IN_PERSON("In person"),
    ONLINE("Online"),
}

/** Sharing with a verifier in front of the holder: a QR code to scan, or a tap on their reader. */
@Composable
private fun InPersonPanel(model: WalletModel, onBack: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val blePermissionState = rememberBluetoothPermissionState()
    val bleEnabledState = rememberBluetoothEnabledState()
    val nfc = rememberNfcState()

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        when {
            !blePermissionState.isGranted -> {
                TapOnlyOption(nfc)
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
                TapOnlyOption(nfc)
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
                // Nothing preselected: the consent sheet offers every document that answers.
                preselectedDocuments = emptyList(),
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
                        // Both ways at once: the code works while the phone also answers NFC taps.
                        val tapToo = nfc == NfcState.ON
                        ShareOptionHeading(
                            icons = listOfNotNull(
                                rememberVectorPainter(Icons.Filled.QrCode),
                                painterResource(R.drawable.contactless_24).takeIf { nfc != NfcState.UNAVAILABLE },
                            ),
                            text = if (tapToo) "Show this code or tap to share" else "Show this code",
                        )
                        Text(
                            text = if (tapToo) {
                                "Ask the verifier to scan this code with Validatopia Verify, or hold the back of " +
                                    "your phone near their NFC reader."
                            } else {
                                "Ask the verifier to scan it with Validatopia Verify."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
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
                        if (nfc == NfcState.OFF) {
                            NfcOffNotice()
                        }
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

/** The link schemes of online sharing requests that [WalletUriSchemePresentmentActivity] handles. */
private val ONLINE_REQUEST_SCHEMES = setOf("openid4vp", "haip-vp", "mdoc")

/**
 * The scheme of a cross-device W3C Digital Credentials API request: a FIDO CTAP 2.2 hybrid code,
 * which Google Play Services answers by connecting to the browser (over the internet, checking
 * the two devices are near each other by Bluetooth) and then asking Credential Manager, which
 * starts [org.multipaz.samples.validatopia.wallet.WalletCredentialManagerPresentmentActivity].
 */
private const val HYBRID_SCHEME = "fido"

/**
 * Sharing with a website by scanning the code it shows: an OpenID4VP request, which opens the
 * consent sheet in [WalletUriSchemePresentmentActivity] the same as the website's link would on
 * this phone, or a cross-device Digital Credentials API request ([HYBRID_SCHEME]), handed to the
 * platform. Also explains that websites on this phone can ask through the API without a code.
 */
@Composable
private fun OnlinePanel() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val cameraPermissionState = rememberCameraPermissionState()
    // The code last handed on, so coming back to this screen doesn't open it again.
    var openedCode by rememberSaveable { mutableStateOf<String?>(null) }
    // Why the code in view can't be used, if it can't.
    var problem by remember { mutableStateOf<String?>(null) }
    val digitalCredentialsAvailable by produceState(initialValue = false) {
        value = DigitalCredentials.getDefault().registerAvailable
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ShareOptionHeading(icons = listOf(painterResource(R.drawable.computer_24)), text = "Scan a website's code")
        Text(
            text = "Point the camera at the code the website shows. You'll see what's being asked for, and " +
                "who's asking, before anything is shared.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (cameraPermissionState.isGranted) {
            QrCodeScanner(
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)),
                cameraSelection = CameraSelection.DEFAULT_BACK_CAMERA,
                captureResolution = CameraCaptureResolution.MEDIUM,
                showCameraPreview = true,
                onCodeScanned = { code ->
                    val scheme = code?.substringBefore(':')?.lowercase()
                    when {
                        code == null -> problem = null
                        scheme != HYBRID_SCHEME && scheme !in ONLINE_REQUEST_SCHEMES -> problem = NOT_A_REQUEST
                        code != openedCode -> {
                            openedCode = code
                            problem = openOnlineRequest(context, code, isHybrid = scheme == HYBRID_SCHEME)
                        }
                    }
                },
            )
            problem?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        } else {
            Button(onClick = { coroutineScope.launch { cameraPermissionState.launchPermissionRequest() } }) {
                Text("Allow camera")
            }
        }
        if (digitalCredentialsAvailable) {
            HorizontalDivider()
            Text(
                text = "Some websites ask for your ID without a code. Your phone then asks which wallet to " +
                    "use: choose Validatopia Wallet.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val NOT_A_REQUEST =
    "That code isn't a request to share. Scan the code on the website's page for sharing a digital ID."

/**
 * Opens the online sharing request in [code]: a cross-device Digital Credentials API request
 * ([isHybrid]) with the platform, otherwise in [WalletUriSchemePresentmentActivity].
 *
 * @return why it couldn't be opened, or `null` if it was.
 */
private fun openOnlineRequest(context: Context, code: String, isHybrid: Boolean): String? {
    val intent = Intent(Intent.ACTION_VIEW, code.toUri())
    if (!isHybrid) {
        context.startActivity(intent.setClass(context, WalletUriSchemePresentmentActivity::class.java))
        return null
    }
    return try {
        context.startActivity(intent)
        null
    } catch (_: ActivityNotFoundException) {
        // UI boundary: without Google Play Services nothing on the phone answers hybrid codes.
        "This phone can't answer that website's code. Open the website on this phone instead."
    }
}

/** Share methods' icons, side by side, over their name, read by TalkBack as one heading. */
@Composable
private fun ShareOptionHeading(icons: List<Painter>, text: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (icon in icons) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp))
            }
        }
        Text(text = text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

private enum class NfcState { UNAVAILABLE, OFF, ON }

/** Whether this phone can answer NFC taps, checked again when the holder comes back from Settings. */
@Composable
private fun rememberNfcState(): NfcState {
    val context = LocalContext.current
    val adapter = remember { NfcAdapter.getDefaultAdapter(context) }
    var enabled by remember { mutableStateOf(adapter?.isEnabled == true) }
    LifecycleResumeEffect(adapter) {
        enabled = adapter?.isEnabled == true
        onPauseOrDispose {}
    }
    return when {
        adapter == null -> NfcState.UNAVAILABLE
        enabled -> NfcState.ON
        else -> NfcState.OFF
    }
}

/**
 * Sharing by tap alone, while the QR code can't be shown (Bluetooth is off or not allowed): the
 * phone still answers NFC taps, through [WalletNfcService].
 */
@Composable
private fun TapOnlyOption(nfc: NfcState) {
    when (nfc) {
        NfcState.UNAVAILABLE -> {}
        NfcState.OFF -> NfcOffNotice()
        NfcState.ON -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ShareOptionHeading(icons = listOf(painterResource(R.drawable.contactless_24)), text = "Tap to share")
            Text(
                text = "Hold the back of your phone near the verifier's NFC reader.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            HorizontalDivider()
        }
    }
}

/** NFC is turned off: says tapping won't work until it's on, with the way to Settings. */
@Composable
private fun NfcOffNotice() {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "NFC is off. Turn it on to share by tapping the verifier's reader.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) {
            Text("Open NFC settings")
        }
    }
}
