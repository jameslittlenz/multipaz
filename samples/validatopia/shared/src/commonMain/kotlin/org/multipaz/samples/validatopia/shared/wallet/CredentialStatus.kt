package org.multipaz.samples.validatopia.shared.wallet

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import org.multipaz.credential.Credential
import org.multipaz.revocation.CachingRevocationChecker
import org.multipaz.revocation.RevocationCheckResult
import org.multipaz.revocation.RevocationCheckState
import org.multipaz.revocation.check
import org.multipaz.samples.validatopia.shared.trust.ValidatopiaTrust
import org.multipaz.storage.Storage
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A wallet document's status as its holder sees it, from its validity period and, once checked,
 * the issuer's revocation list. Revocation outranks the dates: a revoked credential shows as
 * revoked even if it has also expired.
 */
enum class CredentialStatus {
    VALID,
    NOT_YET_VALID,
    EXPIRED,

    /** The issuer has suspended it; it may be reinstated. */
    SUSPENDED,

    /** The issuer has revoked it, for good. */
    REVOKED;

    companion object {
        /**
         * The status of a credential valid from [validFrom] to [validUntil], at [at].
         *
         * @param revocation the issuer's answer, or `null` if it hasn't been (or couldn't be)
         *   checked, in which case only the dates count.
         */
        fun of(
            validFrom: Instant,
            validUntil: Instant,
            revocation: RevocationCheckState?,
            at: Instant = Clock.System.now(),
        ): CredentialStatus = when {
            revocation == RevocationCheckState.INVALID -> REVOKED
            revocation == RevocationCheckState.SUSPENDED -> SUSPENDED
            at > validUntil -> EXPIRED
            at < validFrom -> NOT_YET_VALID
            else -> VALID
        }
    }
}

/**
 * Checks wallet documents against their issuer's revocation list (an IETF token status list the
 * credential points to). The list is fetched afresh for every check, so a revocation or suspension
 * made in the issuer's admin site shows up straight away. Like Validatopia Verify, it accepts a
 * status list it can't verify against the bundled TEST trust anchors.
 *
 * @param storage where the underlying checker keeps its cache.
 * @param httpClientEngine the platform's HTTP engine, for fetching status lists.
 */
class CredentialStatusChecker(storage: Storage, httpClientEngine: HttpClientEngineFactory<*>) {
    private val checker = CachingRevocationChecker(
        storage = storage,
        httpClient = HttpClient(httpClientEngine) { install(HttpTimeout) },
    )
    private val issuerTrustManager = ValidatopiaTrust.createIssuerTrustManager()

    /**
     * The issuer's current answer for [credential]. Never throws for a failed check, which is
     * [RevocationCheckState.UNKNOWN] with the reason in [RevocationCheckResult.error], so it's
     * safe to call from Swift.
     */
    @Throws(CancellationException::class)
    suspend fun check(credential: Credential): RevocationCheckResult =
        try {
            checker.check(
                credential = credential,
                trustManager = issuerTrustManager,
                onlyTrusted = false,
                bypassCache = true,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            RevocationCheckResult(state = RevocationCheckState.UNKNOWN, isTrusted = false, error = e)
        }
}
