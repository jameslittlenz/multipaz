package org.multipaz.samples.validatopia.verifier.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import org.multipaz.compose.prompt.PromptDialogs
import org.multipaz.samples.validatopia.shared.result.PhotoIdVerification
import org.multipaz.samples.validatopia.shared.usecase.PhotoIdUseCase
import org.multipaz.samples.validatopia.verifier.DebugQr
import org.multipaz.samples.validatopia.verifier.VerifierModel

private sealed class Screen {
    data object Home : Screen()
    data object Trust : Screen()
    data class Read(val useCase: PhotoIdUseCase) : Screen()
    class Result(val result: PhotoIdVerification) : Screen()
}

@Composable
fun VerifierApp(model: VerifierModel, debugQr: MutableStateFlow<DebugQr?>) {
    // The activity handles configuration changes itself, so plain remember keeps results across
    // rotation and font-scale changes; results are deliberately never persisted.
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    BackHandler(enabled = screen != Screen.Home) {
        screen = when (val current = screen) {
            is Screen.Result -> Screen.Read(current.result.useCase)
            else -> Screen.Home
        }
    }
    PromptDialogs(promptModel = model.promptModel)

    val pendingDebugQr by debugQr.collectAsState()
    LaunchedEffect(pendingDebugQr) {
        pendingDebugQr?.let { screen = Screen.Read(it.useCase) }
    }

    when (val current = screen) {
        Screen.Home -> HomeScreen(
            onUseCase = { screen = Screen.Read(it) },
            onOpenTrust = { screen = Screen.Trust },
        )
        Screen.Trust -> TrustScreen(onBack = { screen = Screen.Home })
        is Screen.Read -> ReadScreen(
            model = model,
            useCase = current.useCase,
            injectedQr = pendingDebugQr?.takeIf { it.useCase == current.useCase }?.qr,
            onInjectedQrConsumed = { debugQr.value = null },
            onBack = { screen = Screen.Home },
            onResult = { screen = Screen.Result(it) },
        )
        is Screen.Result -> ResultScreen(
            result = current.result,
            onCheckAnother = { screen = Screen.Read(current.result.useCase) },
            onDone = { screen = Screen.Home },
        )
    }
}
