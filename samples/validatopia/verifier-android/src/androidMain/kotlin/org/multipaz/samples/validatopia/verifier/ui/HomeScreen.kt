package org.multipaz.samples.validatopia.verifier.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.multipaz.samples.validatopia.shared.ui.SectionHeading
import org.multipaz.samples.validatopia.shared.ui.ValidatopiaBrandHeader
import org.multipaz.samples.validatopia.shared.ui.ValidatopiaScaffold
import org.multipaz.samples.validatopia.shared.usecase.PhotoIdUseCase

/** Picks the use case, which fixes exactly what the request will ask for. */
@Composable
fun HomeScreen(onUseCase: (PhotoIdUseCase) -> Unit, onOpenTrust: () -> Unit) {
    ValidatopiaScaffold(
        title = "Validatopia Verify",
        actions = {
            IconButton(onClick = onOpenTrust) {
                Icon(Icons.Filled.VerifiedUser, contentDescription = "Trusted issuers")
            }
        },
    ) {
        ValidatopiaBrandHeader(appName = "Verify")
        SectionHeading("What are you checking?")
        for (useCase in PhotoIdUseCase.entries) {
            Card(
                onClick = { onUseCase(useCase) },
                modifier = Modifier.fillMaxWidth().semantics {
                    role = Role.Button
                    onClick(label = "Start") { onUseCase(useCase); true }
                },
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(useCase.title, style = MaterialTheme.typography.titleMedium)
                    Text(useCase.purpose, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Asks for: " + useCase.requested.joinToString { it.element.displayName },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** The requested elements, marking the ones the verifier intends to keep. */
@Composable
fun RequestedElementsList(useCase: PhotoIdUseCase) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (requested in useCase.requested) {
            val name = requested.element.displayName
            Text(
                text = if (requested.intentToRetain) "• $name (kept on file)" else "• $name",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
