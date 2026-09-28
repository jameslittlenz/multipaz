package org.multipaz.samples.validatopia.verifier

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.prompt.PromptModel
import org.multipaz.revocation.CachingRevocationChecker
import org.multipaz.samples.validatopia.shared.result.PhotoIdVerifier
import org.multipaz.samples.validatopia.shared.trust.ValidatopiaTrust
import org.multipaz.util.Platform

/** The verifier's long-lived state: bundled TEST trust anchors, reader key and revocation cache. */
class VerifierModel private constructor(
    val readerKey: AsymmetricKey.X509Certified,
    val verifier: PhotoIdVerifier,
    val promptModel: PromptModel,
) {
    companion object {
        private val lock = Mutex()
        private var instance: VerifierModel? = null

        /** Returns the model, creating it on first use. */
        suspend fun get(): VerifierModel = lock.withLock {
            instance ?: create().also { instance = it }
        }

        private fun create(): VerifierModel = VerifierModel(
            readerKey = ValidatopiaTrust.readerKey(),
            verifier = PhotoIdVerifier(
                issuerTrustManager = ValidatopiaTrust.createIssuerTrustManager(),
                cscaStore = ValidatopiaTrust.createCscaStore(),
                // PhotoIdVerifier fetches the status list afresh for every check, so a revocation
                // made in the admin site shows up straight away.
                revocationChecker = CachingRevocationChecker(
                    storage = Platform.nonBackedUpStorage,
                    httpClient = HttpClient(Android) { install(HttpTimeout) },
                ),
            ),
            promptModel = AndroidPromptModel.Builder().apply { addCommonDialogs() }.build(),
        )
    }
}
