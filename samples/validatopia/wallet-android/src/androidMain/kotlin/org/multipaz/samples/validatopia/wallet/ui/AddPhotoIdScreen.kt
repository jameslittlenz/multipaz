package org.multipaz.samples.validatopia.wallet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.multipaz.samples.validatopia.shared.idv.IdvUnavailableException
import org.multipaz.samples.validatopia.shared.idv.Persona
import org.multipaz.samples.validatopia.shared.ui.SectionHeading
import org.multipaz.samples.validatopia.shared.ui.ValidatopiaScaffold
import org.multipaz.samples.validatopia.wallet.BuildConfig
import org.multipaz.samples.validatopia.wallet.WalletModel
import org.multipaz.util.Logger

private const val TAG = "AddPhotoIdScreen"

private sealed class PersonasState {
    data object Loading : PersonasState()
    data object Unavailable : PersonasState()
    data class Failed(val message: String) : PersonasState()
    data class Loaded(val personas: List<Persona>) : PersonasState()
}

/**
 * Getting a Photo ID, and with it a Driver Licence, Gym Membership and Age Verification: from a
 * passport ([onVerifyWithPassport]), or from a test identity. The passport option only appears
 * while the issuer accepts passports.
 */
@Composable
fun AddPhotoIdScreen(model: WalletModel, onBack: () -> Unit, onVerifyWithPassport: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    // Whether the issuer accepts passports: null while asking. Asking starts a passport session,
    // which the issuer lets expire unused.
    val passportAvailable by produceState<Boolean?>(initialValue = null) {
        value = try {
            model.createIdvClient().startPassportSession()
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e !is IdvUnavailableException) Logger.w(TAG, "Checking for passport issuance failed", e)
            false
        }
    }
    var state by remember { mutableStateOf<PersonasState>(PersonasState.Loading) }
    var reloadCount by remember { mutableIntStateOf(0) }
    var requestingPersona by remember { mutableStateOf<Persona?>(null) }
    var requestError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadCount) {
        state = PersonasState.Loading
        state = try {
            PersonasState.Loaded(model.createIdvClient().listPersonas())
        } catch (e: IdvUnavailableException) {
            PersonasState.Unavailable
        } catch (e: Exception) {
            // UI boundary: anything from the network or the wallet back-end is shown, with a retry.
            if (e is CancellationException) throw e
            Logger.e(TAG, "Listing test identities failed", e)
            PersonasState.Failed(e.message ?: e.toString())
        }
    }

    fun requestPhotoId(persona: Persona) {
        requestingPersona = persona
        requestError = null
        coroutineScope.launch {
            try {
                model.issueDocuments(model.createIdvClient().requestPersonaOffers(persona.id))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "Requesting a Photo ID for ${persona.id} failed", e)
                requestError = e.message ?: e.toString()
            } finally {
                requestingPersona = null
            }
        }
    }

    ValidatopiaScaffold(title = "Get a Photo ID", onBack = onBack) {
        if (BuildConfig.DEBUG || passportAvailable == true) {
            SectionHeading("Verify with your passport")
            Text(
                text = "Scan your passport, read its chip with your phone and take a selfie. Works with " +
                    "passports that have the chip symbol on the cover.",
                style = MaterialTheme.typography.bodyLarge,
            )
            when (passportAvailable) {
                null -> ProgressRow("Checking the issuer…")
                true -> Button(onClick = onVerifyWithPassport, modifier = Modifier.fillMaxWidth()) {
                    Text("Verify with passport")
                }
                false -> {
                    // Debug builds only: the chip can still be read, to record a passport profile.
                    Text(
                        text = "This issuer isn't accepting passports at the moment. You can still read a " +
                            "passport's chip to see its details.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    OutlinedButton(onClick = onVerifyWithPassport, modifier = Modifier.fillMaxWidth()) {
                        Text("Read a passport chip")
                    }
                }
            }
        }

        SectionHeading("Use a test identity")
        Text(
            text = "Choose a test identity. The Validatopia issuer creates a Photo ID with that person's " +
                "details, backed by synthetic passport data, along with a Driver Licence, Gym Membership and " +
                "Age Verification.",
            style = MaterialTheme.typography.bodyLarge,
        )

        when (val current = state) {
            PersonasState.Loading -> ProgressRow("Loading test identities…")
            PersonasState.Unavailable -> Text(
                text = "This issuer doesn't offer test identities at the moment.",
                style = MaterialTheme.typography.bodyLarge,
            )
            is PersonasState.Failed -> {
                Text(
                    text = "Couldn't reach the issuer: ${current.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Button(onClick = { reloadCount++ }) { Text("Try again") }
            }
            is PersonasState.Loaded -> {
                if (current.personas.isEmpty()) {
                    Text("The issuer has no test identities set up.", style = MaterialTheme.typography.bodyLarge)
                }
                for (persona in current.personas) {
                    val name = "${persona.givenName} ${persona.familyName}"
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = { Text("Test identity") },
                        leadingContent = { Icon(Icons.Filled.Person, contentDescription = null) },
                        trailingContent = {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth().clickable(
                            enabled = requestingPersona == null,
                            role = Role.Button,
                            onClickLabel = "Get a Photo ID for $name",
                            onClick = { requestPhotoId(persona) },
                        ),
                    )
                    HorizontalDivider()
                }
            }
        }

        requestingPersona?.let { ProgressRow("Asking the issuer for ${it.givenName}'s Photo ID…") }
        requestError?.let {
            Text(
                text = "The issuer couldn't create the Photo ID: $it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
internal fun ProgressRow(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
