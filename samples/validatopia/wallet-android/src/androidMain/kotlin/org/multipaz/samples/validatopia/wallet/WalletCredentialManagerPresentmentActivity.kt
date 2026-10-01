package org.multipaz.samples.validatopia.wallet

import org.multipaz.compose.digitalcredentials.CredentialManagerPresentmentActivity

/**
 * Online presentment through the W3C Digital Credentials API: Android Credential Manager starts
 * this when a website or app calls `navigator.credentials.get()` and the holder picks one of the
 * documents [WalletModel.startDigitalCredentialsExport] registered.
 */
class WalletCredentialManagerPresentmentActivity : CredentialManagerPresentmentActivity() {
    override suspend fun getSettings(): Settings = Settings(
        source = WalletModel.get(this).presentmentSource,
        // Browsers trusted to report the website's origin: the list Google Password Manager uses,
        // from https://gstatic.com/gpm-passkeys-privileged-apps/apps.json.
        privilegedAllowList = assets.open("privilegedUserAgents.json").use { it.readBytes().decodeToString() },
    )
}
