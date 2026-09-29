package org.multipaz.samples.validatopia.shared.wallet

import io.ktor.client.engine.HttpClientEngineFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.multipaz.crypto.Algorithm
import org.multipaz.document.DocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.presentment.PresentmentSource
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.provisioning.DocumentProvisioningSettings
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackendStub
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.rpc.client.RpcAuthorizedDeviceClient
import org.multipaz.rpc.handler.RpcAuthClientSession
import org.multipaz.rpc.handler.RpcExceptionMap
import org.multipaz.samples.validatopia.shared.trust.ValidatopiaTrust
import org.multipaz.securearea.SecureArea
import org.multipaz.storage.Storage
import org.multipaz.trustmanagement.TrustManagerInterface

/** Wallet set-up shared by Validatopia Wallet on Android and iOS. */
object ValidatopiaWallet {
    /** Credential domain for keys that need the device's screen lock (or biometrics) to present. */
    const val DOMAIN_USER_AUTH = "mdoc_user_auth"

    /** Credential domain for keys usable without a screen lock, on devices that have none. */
    const val DOMAIN_NO_USER_AUTH = "mdoc_no_user_auth"

    /** The document types the wallet holds: only the Photo ID. */
    fun createDocumentTypeRepository(): DocumentTypeRepository =
        DocumentTypeRepository().apply { addDocumentType(PhotoID.getDocumentType()) }

    /**
     * The presentment source for proximity presentment. The consent prompt names the verifier
     * when its request is signed by a reader certificate that [readerTrustManager] trusts.
     */
    fun createPresentmentSource(
        documentStore: DocumentStore,
        documentTypeRepository: DocumentTypeRepository,
        readerTrustManager: TrustManagerInterface = ValidatopiaTrust.createReaderTrustManager(),
    ): PresentmentSource = SimplePresentmentSource(
        documentStore = documentStore,
        documentTypeRepository = documentTypeRepository,
        resolveTrustFn = { requester -> ValidatopiaTrust.resolveRequester(requester, readerTrustManager) },
        domainsMdocSignature = listOf(DOMAIN_USER_AUTH, DOMAIN_NO_USER_AUTH),
    )

    /**
     * Provisioning settings: keys that need user authentication when the device has a screen lock
     * to authenticate with (keys can't be created otherwise), else keys that don't.
     *
     * @param deviceSecure whether the device has a passcode, PIN, pattern or password set.
     */
    fun provisioningSettings(deviceSecure: Boolean): DocumentProvisioningSettings =
        DocumentProvisioningSettings().copy(
            requestUserAuth = deviceSecure,
            requestNoUserAuth = !deviceSecure,
            mdocUserAuthDomain = DOMAIN_USER_AUTH,
            mdocNoUserAuthDomain = DOMAIN_NO_USER_AUTH,
        )

    /**
     * The attested wallet back-end for release builds. In the Validatopia container it sits next
     * to the issuer: `/openid4vci` and `/backend` under the same host.
     *
     * @param issuerUrl the issuer's base URL, ending in `/openid4vci`.
     * @param httpClientEngine the platform's HTTP engine.
     * @param secureArea where the device key the back-end authenticates lives.
     * @param storage where the device registration is kept.
     */
    @Throws(CancellationException::class)
    suspend fun createAttestedBackend(
        issuerUrl: String,
        httpClientEngine: HttpClientEngineFactory<*>,
        secureArea: SecureArea,
        storage: Storage,
    ): OpenID4VCIBackend {
        val backendUrl = issuerUrl.removeSuffix("/").substringBeforeLast("/") + "/backend/rpc"
        val client = RpcAuthorizedDeviceClient.connect(
            exceptionMap = RpcExceptionMap.Builder().build(),
            httpClientEngine = httpClientEngine,
            url = backendUrl,
            secureArea = secureArea,
            storage = storage,
        )
        return OpenID4VCIBackendStub(
            endpoint = "openid4vci_backend",
            dispatcher = client.dispatcher,
            notifier = client.notifier,
        )
    }

    /** OpenID4VCI client preferences for redeeming the issuer's offers, as [backend]'s client. */
    @Throws(CancellationException::class)
    suspend fun clientPreferences(backend: OpenID4VCIBackend): OpenID4VCIClientPreferences =
        OpenID4VCIClientPreferences(
            clientId = withContext(RpcAuthClientSession()) { backend.getClientId() },
            // Only pre-authorized offers are used, so no authorization redirect ever happens.
            redirectUrl = "https://localhost/validatopia-wallet/redirect",
            locales = listOf("en-US"),
            signingAlgorithms = listOf(Algorithm.ESP256),
        )
}
