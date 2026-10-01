package org.multipaz.samples.validatopia.wallet

import io.ktor.client.engine.android.Android
import org.multipaz.compose.presentment.UriSchemePresentmentActivity

/**
 * Online presentment with OpenID4VP (and ISO/IEC 18013-7 Annex A) request links: `openid4vp://`,
 * `haip-vp://` and `mdoc://`. They arrive from a verifier's website or app on this phone, or from
 * the code scanned on the Share screen's Online tab. The consent sheet names the verifier when its
 * request is signed by a trusted certificate and otherwise shows it as unverified.
 */
class WalletUriSchemePresentmentActivity : UriSchemePresentmentActivity() {
    override suspend fun getSettings(): Settings = Settings(
        source = WalletModel.get(this).presentmentSource,
        httpClientEngineFactory = Android,
    )
}
