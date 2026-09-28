package org.multipaz.samples.validatopia.wallet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.multipaz.compose.prompt.PromptDialogs
import org.multipaz.compose.provisioning.ProvisioningBottomSheet
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.samples.validatopia.wallet.WalletModel

/** The wallet's screens. Kept as strings so the back stack survives configuration changes. */
private object Routes {
    const val HOME = "home"
    const val ADD = "add"
    const val SETTINGS = "settings"
    const val DOCUMENT = "document/"
    const val PRESENT = "present/"
    const val DETAILS = "details/"
    const val PORTRAIT = "portrait/"
}

@Composable
fun WalletApp(model: WalletModel) {
    val coroutineScope = rememberCoroutineScope()
    val consentAccepted by model.consentAccepted.collectAsState()
    if (!consentAccepted) {
        WelcomeScreen(onAccept = { coroutineScope.launch { model.acceptConsent() } })
        return
    }

    var backStack by rememberSaveable { mutableStateOf(listOf(Routes.HOME)) }
    fun push(route: String) {
        backStack = backStack + route
    }
    fun pop() {
        backStack = backStack.dropLast(1)
    }
    fun resetTo(route: String) {
        backStack = if (route == Routes.HOME) listOf(Routes.HOME) else listOf(Routes.HOME, route)
    }
    BackHandler(enabled = backStack.size > 1) { pop() }

    // The bottom sheet awaits these; they're rebuilt whenever the issuer changes.
    val issuerUrl by model.issuerUrl.collectAsState()
    val backend = remember(issuerUrl) { CompletableDeferred<OpenID4VCIBackend>() }
    val clientPreferences = remember(issuerUrl) { CompletableDeferred<OpenID4VCIClientPreferences>() }
    LaunchedEffect(issuerUrl) {
        try {
            backend.complete(model.getBackend())
            clientPreferences.complete(model.getClientPreferences())
        } catch (e: Exception) {
            // UI boundary: the bottom sheet shows the failure when it awaits these.
            if (e is CancellationException) throw e
            backend.completeExceptionally(e)
            clientPreferences.completeExceptionally(e)
        }
    }

    PromptDialogs(promptModel = model.promptModel)
    ProvisioningBottomSheet(
        provisioningModel = model.provisioningModel,
        // Test-identity offers are pre-authorized, so there's never a browser redirect to wait for.
        waitForRedirectLinkInvocation = { null },
        clientPreferences = clientPreferences,
        backend = backend,
        onFinishedProvisioning = { document, isNewlyIssued ->
            if (document != null && isNewlyIssued) {
                resetTo(Routes.DOCUMENT + document.identifier)
            }
        },
    )

    val route = backStack.last()
    when {
        route == Routes.HOME -> HomeScreen(
            model = model,
            onAddPhotoId = { push(Routes.ADD) },
            onOpenDocument = { push(Routes.DOCUMENT + it) },
            onOpenSettings = { push(Routes.SETTINGS) },
        )
        route == Routes.ADD -> AddPhotoIdScreen(model = model, onBack = ::pop)
        route == Routes.SETTINGS -> SettingsScreen(model = model, onBack = ::pop)
        route.startsWith(Routes.DOCUMENT) -> DocumentScreen(
            model = model,
            documentId = route.removePrefix(Routes.DOCUMENT),
            onBack = ::pop,
            onShowQr = { push(Routes.PRESENT + it) },
            onViewDetails = { push(Routes.DETAILS + it) },
        )
        route.startsWith(Routes.DETAILS) -> MyDetailsScreen(
            model = model,
            documentId = route.removePrefix(Routes.DETAILS),
            onBack = ::pop,
            onViewPortrait = { push(Routes.PORTRAIT + it) },
            onDeleted = { resetTo(Routes.HOME) },
        )
        route.startsWith(Routes.PORTRAIT) -> PortraitScreen(
            model = model,
            documentId = route.removePrefix(Routes.PORTRAIT),
            onBack = ::pop,
        )
        route.startsWith(Routes.PRESENT) -> PresentQrScreen(
            model = model,
            documentId = route.removePrefix(Routes.PRESENT),
            onBack = ::pop,
        )
    }
}
