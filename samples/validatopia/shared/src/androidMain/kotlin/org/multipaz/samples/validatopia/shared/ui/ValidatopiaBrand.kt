package org.multipaz.samples.validatopia.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.multipaz.samples.validatopia.shared.R

/**
 * "Powered by" plus the VALID8 Advisory logo: the full-colour logo on light backgrounds, the white
 * version on navy.
 *
 * @param onNavy whether the background is Validatopia navy; defaults to the dark theme, whose
 *   background is navy.
 * @param logoMaxWidth the logo's maximum width.
 */
@Composable
fun PoweredByValid8(
    modifier: Modifier = Modifier,
    onNavy: Boolean = isSystemInDarkTheme(),
    logoMaxWidth: Dp = 160.dp,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        // Read as one phrase by TalkBack: "Powered by VALID8 Advisory".
        modifier = modifier.semantics(mergeDescendants = true) {},
    ) {
        Text(
            text = "Powered by",
            style = MaterialTheme.typography.labelMedium,
            color = if (onNavy) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Image(
            painter = painterResource(
                if (onNavy) R.drawable.valid8_advisory_logo_white else R.drawable.valid8_advisory_logo
            ),
            contentDescription = "VALID8 Advisory",
            contentScale = ContentScale.Fit,
            modifier = Modifier.widthIn(max = logoMaxWidth).fillMaxWidth(),
        )
    }
}

/**
 * The Validatopia brand block: "Validatopia", the app's name, then [PoweredByValid8] underneath.
 *
 * @param appName e.g. "Wallet" or "Verify".
 */
@Composable
fun ValidatopiaBrandHeader(appName: String, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.semantics(mergeDescendants = true) { heading() },
        ) {
            Text(
                text = "Validatopia",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = appName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PoweredByValid8()
    }
}

/** Full-screen launch screen shown while the app initialises; no time limit, it goes when ready. */
@Composable
fun ValidatopiaLaunchScreen(appName: String) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(24.dp),
        ) {
            ValidatopiaBrandHeader(appName = appName)
            Spacer(Modifier.height(32.dp))
            CircularProgressIndicator()
        }
    }
}
