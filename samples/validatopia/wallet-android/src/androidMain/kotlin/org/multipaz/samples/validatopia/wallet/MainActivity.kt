package org.multipaz.samples.validatopia.wallet

import android.content.ComponentName
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import org.multipaz.samples.validatopia.shared.branding.ValidatopiaTheme
import org.multipaz.samples.validatopia.shared.ui.ValidatopiaLaunchScreen
import org.multipaz.samples.validatopia.wallet.ui.WalletApp
import org.multipaz.util.Logger

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ValidatopiaTheme {
                val model by produceState<WalletModel?>(initialValue = null) {
                    value = WalletModel.get(applicationContext)
                }
                model?.let { WalletApp(it) } ?: ValidatopiaLaunchScreen(appName = "Wallet")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // While the wallet is in the foreground, it wins NFC taps over any other wallet app.
        NfcAdapter.getDefaultAdapter(this)?.let { adapter ->
            val cardEmulation = CardEmulation.getInstance(adapter)
            val service = ComponentName(this, WalletNfcService::class.java)
            if (!cardEmulation.setPreferredService(this, service)) {
                Logger.w(TAG, "CardEmulation.setPreferredService() returned false")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.let { adapter ->
            CardEmulation.getInstance(adapter).unsetPreferredService(this)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
