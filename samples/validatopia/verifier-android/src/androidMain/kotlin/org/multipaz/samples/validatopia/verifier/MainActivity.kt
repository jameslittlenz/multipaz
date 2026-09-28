package org.multipaz.samples.validatopia.verifier

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.multipaz.context.initializeApplication
import org.multipaz.samples.validatopia.shared.branding.ValidatopiaTheme
import org.multipaz.samples.validatopia.shared.ui.ValidatopiaLaunchScreen
import org.multipaz.samples.validatopia.shared.usecase.PhotoIdUseCase
import org.multipaz.samples.validatopia.verifier.ui.VerifierApp

class MainActivity : FragmentActivity() {
    /**
     * Debug builds only: a QR payload handed in by intent, standing in for the camera so two
     * emulators (which can't see each other's screens) can run the real BLE flow:
     * `adb shell am start -n org.multipaz.samples.validatopia.verifier/.MainActivity
     *  --es debug_qr "mdoc:…" --es debug_use_case CROSS_BORDER`
     */
    private val debugQr = MutableStateFlow<DebugQr?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeApplication(applicationContext)
        enableEdgeToEdge()
        handleDebugIntent(intent)
        setContent {
            ValidatopiaTheme {
                val model by produceState<VerifierModel?>(initialValue = null) {
                    value = VerifierModel.get()
                }
                model?.let { VerifierApp(it, debugQr) } ?: ValidatopiaLaunchScreen(appName = "Verify")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    private fun handleDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) {
            return
        }
        val qr = intent?.getStringExtra("debug_qr") ?: return
        val useCase = intent.getStringExtra("debug_use_case")
            ?.let { name -> PhotoIdUseCase.entries.firstOrNull { it.name == name } }
            ?: PhotoIdUseCase.CROSS_BORDER
        debugQr.value = DebugQr(useCase, qr)
    }
}

/** A QR payload injected by a debug intent; see [MainActivity]. */
data class DebugQr(val useCase: PhotoIdUseCase, val qr: String)
