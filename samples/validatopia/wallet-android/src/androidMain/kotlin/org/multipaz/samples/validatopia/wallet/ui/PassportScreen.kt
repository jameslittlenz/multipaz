package org.multipaz.samples.validatopia.wallet.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import org.multipaz.compose.camera.Camera
import org.multipaz.compose.camera.CameraCaptureResolution
import org.multipaz.compose.camera.CameraSelection
import org.multipaz.compose.permissions.rememberCameraPermissionState
import org.multipaz.idv.lds.Lds
import org.multipaz.idv.mrz.Mrz
import org.multipaz.samples.validatopia.shared.idv.IdvFlags
import org.multipaz.samples.validatopia.shared.idv.IdvRejectedException
import org.multipaz.samples.validatopia.shared.idv.LivenessAction
import org.multipaz.samples.validatopia.shared.idv.LivenessChallenge
import org.multipaz.samples.validatopia.shared.idv.PassportAccessKey
import org.multipaz.samples.validatopia.shared.idv.PassportChipRead
import org.multipaz.samples.validatopia.shared.idv.PassportChipReport
import org.multipaz.samples.validatopia.shared.trust.ValidatopiaTrust
import org.multipaz.samples.validatopia.shared.ui.SectionHeading
import org.multipaz.samples.validatopia.shared.ui.ValidatopiaScaffold
import org.multipaz.samples.validatopia.wallet.BuildConfig
import org.multipaz.samples.validatopia.wallet.WalletModel
import org.multipaz.samples.validatopia.wallet.passport.CameraAnalysis
import org.multipaz.samples.validatopia.wallet.passport.ChipReadStep
import org.multipaz.samples.validatopia.wallet.passport.PassportChipException
import org.multipaz.samples.validatopia.wallet.passport.PassportChipReader
import org.multipaz.util.Logger
import java.io.File
import kotlin.time.Clock

private const val TAG = "PassportScreen"

// What counts as a possible MRZ line in the debug readout.
private const val MRZ_LIKE_LENGTH = 20
private const val MRZ_LIKE_MARKS = 5

private sealed class PassportStep {
    data object Introduction : PassportStep()
    data object PassportDetails : PassportStep()
    data class ReadChip(val key: PassportAccessKey) : PassportStep()
    data class ChipRead(val key: PassportAccessKey, val read: PassportChipRead) : PassportStep()
    data class Liveness(val read: PassportChipRead) : PassportStep()
    data class Submit(val read: PassportChipRead, val selfie: ByteArray) : PassportStep()
}

/**
 * Getting a Photo ID from a passport: read the MRZ (camera or typed), read the chip, check
 * liveness and take a selfie, then send the chip data and selfie to the issuer, which checks the
 * chip's signature and matches the selfie against the chip's photo.
 */
@Composable
fun PassportScreen(model: WalletModel, onBack: () -> Unit) {
    var step by remember { mutableStateOf<PassportStep>(PassportStep.Introduction) }
    ValidatopiaScaffold(title = "Verify with passport", onBack = onBack) {
        when (val current = step) {
            PassportStep.Introduction -> Introduction(onContinue = { step = PassportStep.PassportDetails })
            PassportStep.PassportDetails -> PassportDetails(onKey = { step = PassportStep.ReadChip(it) })
            is PassportStep.ReadChip -> ReadChip(
                key = current.key,
                onRead = { step = PassportStep.ChipRead(current.key, it) },
                onChangeDetails = { step = PassportStep.PassportDetails },
            )
            is PassportStep.ChipRead -> ChipRead(
                key = current.key,
                read = current.read,
                onContinue = { step = PassportStep.Liveness(current.read) },
                onStartAgain = { step = PassportStep.PassportDetails },
            )
            is PassportStep.Liveness -> Liveness(onSelfie = { step = PassportStep.Submit(current.read, it) })
            is PassportStep.Submit -> Submit(
                model = model,
                read = current.read,
                selfie = current.selfie,
                onRetakeSelfie = { step = PassportStep.Liveness(current.read) },
            )
        }
    }
}

@Composable
private fun Introduction(onContinue: () -> Unit) {
    SectionHeading("What happens")
    BodyText(
        "You'll scan your passport's photo page, hold your phone against the passport to read its chip, " +
            "and take a selfie. The Validatopia issuer then checks the chip's data is genuine and that your " +
            "selfie matches the photo on the chip."
    )
    SectionHeading("Your biometric data")
    BodyText(
        "Your selfie and the chip's photo are sent to the Validatopia issuer to compare your face. The issuer " +
            "deletes the selfie as soon as it's compared. It keeps an encrypted copy of the chip's data, including " +
            "its photo, to renew your Photo ID, for a limited time (30 days unless its administrator changes it), " +
            "and deletes it if your Photo ID is revoked."
    )
    SectionHeading("The liveness check")
    BodyText(
        "To show you're present, you'll blink or turn your head in front of the camera, whichever you prefer. " +
            "This check runs on your phone. It makes it harder to use someone else's photo, but it can't rule it out."
    )
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Agree and continue") }
}

@Composable
private fun PassportDetails(onKey: (PassportAccessKey) -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val cameraPermissionState = rememberCameraPermissionState()
    var typing by remember { mutableStateOf(false) }

    SectionHeading("Scan your passport's photo page")
    BodyText(
        "Point the camera at the two lines of letters, numbers and < signs at the bottom of the photo page, " +
            "close enough that they fill the width of the frame, in good light without glare. They unlock the " +
            "passport's chip."
    )
    if (!typing && cameraPermissionState.isGranted) {
        var found by remember { mutableStateOf(false) }
        var frameCount by remember { mutableIntStateOf(0) }
        // Debug builds only: the MRZ-like lines the camera last read, to diagnose scanning.
        var seen by remember { mutableStateOf("") }
        Camera(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(16.dp)),
            cameraSelection = CameraSelection.DEFAULT_BACK_CAMERA,
            captureResolution = CameraCaptureResolution.HIGH,
            showCameraPreview = true,
            onFrameCaptured = { frame ->
                if (!found) {
                    // Alternate plain and black-and-white frames: each suits different lighting.
                    val binarize = frameCount++ % 2 == 1
                    val text = try {
                        CameraAnalysis.recognizeText(frame, binarize)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Logger.w(TAG, "Text recognition failed", e)
                        ""
                    }
                    if (BuildConfig.DEBUG) {
                        val mrzLike = text.lines().map { it.filterNot(Char::isWhitespace) }
                            .filter { it.length >= MRZ_LIKE_LENGTH && it.count { c -> c == '<' || c.isDigit() } >= MRZ_LIKE_MARKS }
                        if (mrzLike.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                seen = (if (binarize) "Black and white:\n" else "Plain:\n") + mrzLike.joinToString("\n")
                            }
                        }
                    }
                    PassportAccessKey.fromOcrText(text)?.let { key ->
                        found = true
                        withContext(Dispatchers.Main) { onKey(key) }
                    }
                }
            },
        )
        if (BuildConfig.DEBUG && seen.isNotEmpty()) {
            Text(
                text = "The camera reads (debug builds only):\n$seen",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
        OutlinedButton(onClick = { typing = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Type the details instead")
        }
    } else if (!typing) {
        Button(onClick = { coroutineScope.launch { cameraPermissionState.launchPermissionRequest() } }) {
            Text("Allow camera")
        }
        OutlinedButton(onClick = { typing = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Type the details instead")
        }
    } else {
        ManualEntry(onKey = onKey)
    }
}

@Composable
private fun ManualEntry(onKey: (PassportAccessKey) -> Unit) {
    var number by remember { mutableStateOf("") }
    var birth by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    SectionHeading("Type your passport details")
    OutlinedTextField(
        value = number,
        onValueChange = { number = it },
        label = { Text("Passport number") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth(),
    )
    DateField(value = birth, onValueChange = { birth = it }, label = "Date of birth")
    DateField(value = expiry, onValueChange = { expiry = it }, label = "Expiry date")
    error?.let { ErrorText(it) }
    Button(
        onClick = {
            val birthDate = parseDate(birth)
            val expiryDate = parseDate(expiry)
            error = when {
                birthDate == null -> "Enter the full date of birth: day, month and year"
                expiryDate == null -> "Enter the full expiry date: day, month and year"
                else -> try {
                    onKey(PassportAccessKey.fromManualEntry(number, birthDate, expiryDate))
                    null
                } catch (e: IllegalArgumentException) {
                    e.message
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Continue") }
}

/** A date typed as digits only, DDMMYYYY, shown with the slashes filled in. */
@Composable
private fun DateField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(DATE_DIGITS)) },
        label = { Text(label) },
        placeholder = { Text("DD/MM/YYYY") },
        supportingText = { Text("Day, month and year, for example 01/02/1990") },
        singleLine = true,
        visualTransformation = DateSlashes,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val DATE_DIGITS = 8

/** Shows up to eight digits as DD/MM/YYYY, adding each slash once the digits before it are typed. */
private object DateSlashes : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val shown = buildString {
            for ((index, digit) in digits.withIndex()) {
                if (index == 2 || index == 4) append('/')
                append(digit)
            }
        }
        val offsets = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = offset + when {
                offset > 4 -> 2
                offset > 2 -> 1
                else -> 0
            }

            override fun transformedToOriginal(offset: Int): Int = (offset - when {
                offset > 5 -> 2
                offset > 2 -> 1
                else -> 0
            }).coerceIn(0, digits.length)
        }
        return TransformedText(AnnotatedString(shown), offsets)
    }
}

/** Parses the eight digits DDMMYYYY that [DateField] holds. */
private fun parseDate(digits: String): LocalDate? {
    if (digits.length != DATE_DIGITS) return null
    return try {
        LocalDate(digits.substring(4, 8).toInt(), digits.substring(2, 4).toInt(), digits.substring(0, 2).toInt())
    } catch (e: IllegalArgumentException) {
        null
    }
}

@Composable
private fun ReadChip(key: PassportAccessKey, onRead: (PassportChipRead) -> Unit, onChangeDetails: () -> Unit) {
    val activity = LocalContext.current as Activity
    var progress by remember { mutableStateOf(ChipReadStep.WAITING) }
    var photoFraction by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        try {
            val read = PassportChipReader(activity).read(key) { step, fraction ->
                progress = step
                photoFraction = fraction
            }
            onRead(read)
        } catch (e: PassportChipException) {
            Logger.w(TAG, "Chip read failed", e)
            error = e.message
        } catch (e: Exception) {
            // UI boundary: JMRTD reports some failures as runtime exceptions.
            if (e is CancellationException) throw e
            Logger.e(TAG, "Chip read failed", e)
            error = "Couldn't read the passport chip: ${e.message ?: e.toString()}"
        }
    }

    SectionHeading("Read your passport's chip")
    BodyText(
        "Open the passport at the photo page and place it on a table. Then lay the back of your phone on it " +
            "and keep it still. Finding the chip can take a few tries: slide the phone slowly over the page."
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        if (error == null) {
            Text(progress.description, style = MaterialTheme.typography.bodyLarge)
            if (progress == ChipReadStep.READING_PHOTO) {
                LinearProgressIndicator(progress = { photoFraction }, modifier = Modifier.fillMaxWidth())
            } else if (progress != ChipReadStep.WAITING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        } else {
            ErrorText(error!!)
        }
    }
    if (error != null) {
        Button(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
    }
    OutlinedButton(onClick = onChangeDetails, modifier = Modifier.fillMaxWidth()) { Text("Change passport details") }
}

@Composable
private fun ChipRead(key: PassportAccessKey, read: PassportChipRead, onContinue: () -> Unit, onStartAgain: () -> Unit) {
    val context = LocalContext.current
    val mismatches = remember(read) {
        try {
            key.mismatchesWith(Mrz.parseTd3(Lds.parseDG1(read.dg1)))
        } catch (e: Exception) {
            Logger.w(TAG, "Couldn't parse DG1", e)
            listOf("machine-readable zone")
        }
    }
    val report by produceState<PassportChipReport?>(null, read) {
        value = PassportChipReport.create(read, ValidatopiaTrust.createCscaStore())
    }
    var showDetails by remember { mutableStateOf(false) }

    if (mismatches.isEmpty()) {
        SectionHeading("Passport chip read")
        BodyText("The chip's details match the photo page. Next, the liveness check and your selfie.")
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    } else {
        SectionHeading("The chip doesn't match the photo page")
        ErrorText(
            "The ${mismatches.joinToString(" and ")} on the chip differ from what was scanned from the photo page. " +
                "Scan the photo page again, making sure it's the same passport."
        )
        Button(onClick = onStartAgain, modifier = Modifier.fillMaxWidth()) { Text("Scan again") }
    }

    OutlinedButton(onClick = { showDetails = !showDetails }, modifier = Modifier.fillMaxWidth()) {
        Text(if (showDetails) "Hide chip details" else "Show chip details")
    }
    if (showDetails) {
        SectionHeading("Chip details")
        BodyText(
            "How this passport's chip works, for docs/validatopia/passport-profiles.md. There are no names, " +
                "dates, document numbers or images here."
        )
        report?.let { chipReport ->
            Text(chipReport.toText(), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Passport chip details", chipReport.toText()))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Copy chip details") }
        }
    }
    if (BuildConfig.DEBUG) {
        var saved by remember { mutableStateOf<String?>(null) }
        OutlinedButton(onClick = { saved = saveChipRead(context, read) }, modifier = Modifier.fillMaxWidth()) {
            Text("Save chip files to this phone (debug builds only)")
        }
        saved?.let {
            BodyText(
                "Saved to $it. These files hold your personal data: copy them only to your own computer, " +
                    "and never commit or upload them."
            )
        }
    }
}

/** Debug builds only: saves the chip's files to the app's own storage, for `adb pull`. */
private fun saveChipRead(context: Context, read: PassportChipRead): String {
    val directory = File(context.getExternalFilesDir("chip-reads"), Clock.System.now().toEpochMilliseconds().toString())
    directory.mkdirs()
    File(directory, "EF.SOD").writeBytes(read.sod)
    File(directory, "EF.DG1").writeBytes(read.dg1)
    File(directory, "EF.DG2").writeBytes(read.dg2)
    return directory.absolutePath
}

@Composable
private fun Liveness(onSelfie: (ByteArray) -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val cameraPermissionState = rememberCameraPermissionState()
    val haptics = LocalHapticFeedback.current
    var action by remember { mutableStateOf<LivenessAction?>(null) }

    SectionHeading("Show you're present")
    val chosen = action
    if (chosen == null) {
        BodyText("Choose what you'd like to do in front of the camera. Either works equally well.")
        for (option in LivenessAction.entries) {
            Button(onClick = { action = option }, modifier = Modifier.fillMaxWidth()) {
                Text(if (option == LivenessAction.BLINK) "Blink" else "Turn my head")
            }
        }
        return
    }
    if (!cameraPermissionState.isGranted) {
        Button(onClick = { coroutineScope.launch { cameraPermissionState.launchPermissionRequest() } }) {
            Text("Allow camera")
        }
        return
    }

    val challenge = remember(chosen) { LivenessChallenge(chosen) }
    var instruction by remember(chosen) { mutableStateOf(challenge.instruction) }
    var finished by remember(chosen) { mutableStateOf(false) }
    BodyText("Hold the phone at eye level, in even light, without glasses or a hat if you can.")
    Text(
        text = instruction,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    )
    Camera(
        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(16.dp)),
        cameraSelection = CameraSelection.DEFAULT_FRONT_CAMERA,
        captureResolution = CameraCaptureResolution.HIGH,
        showCameraPreview = true,
        onFrameCaptured = { frame ->
            if (!finished) {
                val faces = try {
                    CameraAnalysis.detectFaces(frame)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.w(TAG, "Face detection failed", e)
                    emptyList()
                }
                val before = challenge.stage
                val keep = challenge.onFrame(faces)
                val selfie = if (keep) CameraAnalysis.uprightJpeg(frame) else null
                withContext(Dispatchers.Main) {
                    if (challenge.stage != before && challenge.stage == LivenessChallenge.Stage.HOLD_STILL) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    instruction = challenge.instruction
                    if (selfie != null) {
                        finished = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSelfie(selfie)
                    }
                }
            }
        },
    )
    OutlinedButton(onClick = { action = null }, modifier = Modifier.fillMaxWidth()) { Text("Choose something else") }
}

@Composable
private fun Submit(model: WalletModel, read: PassportChipRead, selfie: ByteArray, onRetakeSelfie: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(true) }
    var rejection by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(attempt) {
        working = true
        rejection = null
        error = null
        try {
            val idvClient = model.createIdvClient()
            val sessionId = idvClient.startPassportSession()
            model.issueDocuments(idvClient.submitPassportEvidence(sessionId, read, selfie))
        } catch (e: IdvRejectedException) {
            rejection = e.flags
        } catch (e: Exception) {
            // UI boundary: anything from the network or the wallet back-end is shown, with a retry.
            if (e is CancellationException) throw e
            Logger.e(TAG, "Submitting passport evidence failed", e)
            error = e.message ?: e.toString()
        } finally {
            working = false
        }
    }

    when {
        working -> ProgressRow("Checking your passport and selfie…")
        rejection != null -> {
            SectionHeading("The issuer couldn't verify you")
            for (flag in rejection!!.distinct()) {
                ErrorText(IdvFlags.describe(flag))
            }
            Button(onClick = onRetakeSelfie, modifier = Modifier.fillMaxWidth()) { Text("Take another selfie") }
        }
        error != null -> {
            ErrorText("Couldn't reach the issuer: $error")
            Button(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
        }
        else -> BodyText("Verified. Your Photo ID is on its way.")
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
