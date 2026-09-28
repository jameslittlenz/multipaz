package org.multipaz.samples.validatopia.verifier.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.multipaz.samples.validatopia.shared.branding.LocalValidatopiaStatusColors
import org.multipaz.samples.validatopia.shared.result.CheckOutcome
import org.multipaz.samples.validatopia.shared.result.TrustPanel
import org.multipaz.samples.validatopia.shared.ui.SectionHeading

/** How an outcome is shown: always an icon and a word, with colour only as reinforcement. */
private data class OutcomeStyle(val icon: ImageVector, val word: String, val color: Color)

@Composable
private fun CheckOutcome.style(): OutcomeStyle {
    val colors = LocalValidatopiaStatusColors.current
    return when (this) {
        CheckOutcome.PASSED -> OutcomeStyle(Icons.Filled.CheckCircle, "Passed", colors.success)
        CheckOutcome.WARNING -> OutcomeStyle(Icons.Filled.Warning, "Test only", colors.warning)
        CheckOutcome.FAILED -> OutcomeStyle(Icons.Filled.Error, "Failed", colors.error)
        CheckOutcome.UNKNOWN -> OutcomeStyle(Icons.AutoMirrored.Filled.Help, "Unknown", colors.neutral)
    }
}

/** An outcome badge: icon plus word, e.g. "✓ Passed". */
@Composable
fun OutcomeBadge(outcome: CheckOutcome, modifier: Modifier = Modifier) {
    val style = outcome.style()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        Icon(style.icon, contentDescription = null, tint = style.color, modifier = Modifier.size(24.dp))
        Text(style.word, color = style.color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}

/** A trust panel ("Credential issuer" or "Passport issuer (CSCA)") with one badge per check. */
@Composable
fun TrustPanelCard(panel: TrustPanel, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                SectionHeading(panel.title, modifier = Modifier.weight(1f))
                OutcomeBadge(panel.overall)
            }
            for ((index, check) in panel.checks.withIndex()) {
                if (index > 0) {
                    HorizontalDivider()
                }
                // Read as one item by TalkBack: "Issuer, Test only, Validatopia Photo ID issuer (TEST…)".
                Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            check.label,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        OutcomeBadge(check.outcome)
                    }
                    Text(check.detail, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
