package org.multipaz.idv

actual fun setUpBouncyCastleIfNeeded() {
    // No-op: iOS has no BouncyCastle provider to register; unsupported curves are skipped instead.
}
