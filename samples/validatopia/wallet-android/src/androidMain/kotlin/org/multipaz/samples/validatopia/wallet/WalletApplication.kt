package org.multipaz.samples.validatopia.wallet

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import org.multipaz.compose.branding.Branding
import org.multipaz.context.initializeApplication
import org.multipaz.document.Document
import org.multipaz.samples.validatopia.shared.branding.ValidatopiaCardArt
import org.multipaz.samples.validatopia.shared.branding.ValidatopiaTheme

class WalletApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initializeApplication(applicationContext)
        // PresentmentActivity (consent sheet, QR and NFC presentment) renders with Branding.Current.
        Branding.setCurrent(WalletBranding(getString(R.string.app_name), DocumentCardArt(applicationContext)))
    }
}

private class WalletBranding(
    override val appName: String,
    private val cardArt: DocumentCardArt,
) : Branding by Branding.Default {
    override val theme: @Composable (content: @Composable () -> Unit) -> Unit = { content ->
        ValidatopiaTheme(content = content)
    }

    // The issuer supplies no card art, so every document is drawn here, in its type's colors.
    override suspend fun renderFallbackCardArt(document: Document): ImageBitmap =
        cardArt.image(ValidatopiaCardArt.styleFor(document), ValidatopiaCardArt.holderShortName(document))
}
