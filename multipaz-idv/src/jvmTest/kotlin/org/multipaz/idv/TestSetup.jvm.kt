package org.multipaz.idv

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

actual fun setUpBouncyCastleIfNeeded() {
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    Security.insertProviderAt(BouncyCastleProvider(), 1)
}
