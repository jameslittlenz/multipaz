package org.multipaz.idv

actual fun setUpBouncyCastleIfNeeded() {
    // No-op: brainpool support on Android is independent of BouncyCastle provider registration.
}
