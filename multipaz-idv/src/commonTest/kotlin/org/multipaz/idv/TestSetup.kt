package org.multipaz.idv

/**
 * Registers BouncyCastle on platforms where it's needed for brainpool curve support (the JVM;
 * Android and iOS have their own curve support, or none, independent of this). Every test in this
 * module that touches [org.multipaz.crypto.Crypto] should call this in its `setup()`.
 */
expect fun setUpBouncyCastleIfNeeded()
