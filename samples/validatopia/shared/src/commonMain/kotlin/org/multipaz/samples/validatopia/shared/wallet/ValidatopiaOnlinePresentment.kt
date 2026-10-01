package org.multipaz.samples.validatopia.shared.wallet

import io.ktor.client.engine.HttpClientEngineFactory
import kotlinx.coroutines.CancellationException
import org.multipaz.presentment.PresentmentSource
import org.multipaz.presentment.uriSchemePresentment

/**
 * Online presentment for Validatopia Wallet on iOS, which calls this from Swift. (On Android the
 * SDK's `UriSchemePresentmentActivity` does the same.)
 */
object ValidatopiaOnlinePresentment {
    /**
     * Answers an online sharing request link (OpenID4VP, or ISO/IEC 18013-7 Annex A) with
     * [uriSchemePresentment], asking the holder's consent through the global prompt model.
     *
     * [uriSchemePresentment] declares only some of what it can throw, and from Swift any other
     * exception (a network failure, a malformed request, a dismissed prompt) would terminate the
     * app. Declaring [Exception] here hands every failure to Swift as an error instead.
     *
     * @param source the source of truth for what to present.
     * @param uri the request link, scanned or opened by a website. Nothing is known about who sent
     *   it, so no app ID or origin is given.
     * @param httpClientEngineFactory the platform's HTTP engine.
     * @return the URI to continue in the browser, or `null` if there's none.
     * @throws org.multipaz.presentment.PresentmentCanceledException if the holder declined.
     * @throws Exception if the request couldn't be answered.
     */
    @Throws(Exception::class, CancellationException::class)
    suspend fun present(
        source: PresentmentSource,
        uri: String,
        httpClientEngineFactory: HttpClientEngineFactory<*>,
    ): String? = uriSchemePresentment(
        source = source,
        uri = uri,
        appId = null,
        origin = null,
        httpClientEngineFactory = httpClientEngineFactory,
    )
}
