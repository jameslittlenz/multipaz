package org.multipaz.samples.validatopia.shared

import io.ktor.client.HttpClient

/**
 * Runs [block] against a real Validatopia issuer serving the placeholder personas (`p1` Claudia
 * Hill, NZL; `p2` Richard Smyth, AUS) with the fixed TEST keys and trusting `DevWalletBackend`.
 *
 * [block] gets the issuer's base URL and an HTTP client for it that doesn't follow redirects.
 */
expect fun runWithTestIssuer(block: suspend (issuerUrl: String, httpClient: HttpClient) -> Unit)
