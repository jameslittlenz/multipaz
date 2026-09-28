package org.multipaz.samples.validatopia.wallet

import android.app.KeyguardManager
import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.compose.document.DocumentModel
import org.multipaz.crypto.Algorithm
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.presentment.PresentmentSource
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.prompt.PromptModel
import org.multipaz.provisioning.DocumentProvisioningHandler
import org.multipaz.provisioning.DocumentProvisioningSettings
import org.multipaz.provisioning.ProvisioningModel
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackendStub
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.rpc.client.RpcAuthorizedDeviceClient
import org.multipaz.rpc.handler.RpcAuthClientSession
import org.multipaz.rpc.handler.RpcExceptionMap
import org.multipaz.samples.validatopia.shared.idv.DevWalletBackend
import org.multipaz.samples.validatopia.shared.idv.IdvClient
import org.multipaz.samples.validatopia.shared.trust.ValidatopiaTrust
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Platform

/**
 * The wallet's long-lived state, shared by the UI and the NFC presentment services (which may
 * start the process cold, before any activity).
 */
class WalletModel private constructor(
    val storage: Storage,
    val secureArea: SecureArea,
    val documentStore: DocumentStore,
    val documentTypeRepository: DocumentTypeRepository,
    val documentModel: DocumentModel,
    val presentmentSource: PresentmentSource,
    val provisioningModel: ProvisioningModel,
    val promptModel: PromptModel,
    private val settingsTable: StorageTable,
    issuerUrl: String,
    consentAccepted: Boolean,
) {
    private val mutableIssuerUrl = MutableStateFlow(issuerUrl)
    private val mutableConsentAccepted = MutableStateFlow(consentAccepted)
    private val backendLock = Mutex()
    private var backend: Pair<String, OpenID4VCIBackend>? = null

    /** The Validatopia issuer's base URL. */
    val issuerUrl: StateFlow<String> = mutableIssuerUrl.asStateFlow()

    /** Whether the user has accepted the welcome screen's terms. */
    val consentAccepted: StateFlow<Boolean> = mutableConsentAccepted.asStateFlow()

    /** The HTTP client for the issuer. It must not follow redirects (see [ProvisioningModel]). */
    val httpClient = HttpClient(Android) { followRedirects = false }

    /** Persists a new issuer URL. */
    suspend fun setIssuerUrl(url: String) {
        putSetting(KEY_ISSUER_URL, url)
        mutableIssuerUrl.value = url
    }

    /** Records that the user accepted the welcome screen's terms. */
    suspend fun acceptConsent() {
        putSetting(KEY_CONSENT_ACCEPTED, "true")
        mutableConsentAccepted.value = true
    }

    /**
     * The wallet back-end that signs wallet and key attestations: [DevWalletBackend] in debug
     * builds, otherwise the attested Validatopia back-end next to the issuer.
     */
    suspend fun getBackend(): OpenID4VCIBackend = backendLock.withLock {
        val url = issuerUrl.value
        backend?.takeIf { it.first == url }?.second ?: createBackend(url).also { backend = url to it }
    }

    private suspend fun createBackend(issuerUrl: String): OpenID4VCIBackend {
        if (BuildConfig.USE_DEV_ATTESTATION) {
            return DevWalletBackend.create()
        }
        // In the Validatopia container the back-end server sits next to the issuer: /openid4vci
        // and /backend under the same host.
        val backendUrl = issuerUrl.removeSuffix("/").substringBeforeLast("/") + "/backend/rpc"
        val client = RpcAuthorizedDeviceClient.connect(
            exceptionMap = RpcExceptionMap.Builder().build(),
            httpClientEngine = Android,
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

    /** OpenID4VCI client preferences for redeeming the issuer's offers. */
    suspend fun getClientPreferences(): OpenID4VCIClientPreferences {
        val backend = getBackend()
        return OpenID4VCIClientPreferences(
            clientId = withContext(RpcAuthClientSession()) { backend.getClientId() },
            // Only pre-authorized offers are used, so no authorization redirect ever happens.
            redirectUrl = "https://localhost/validatopia-wallet/redirect",
            locales = listOf("en-US"),
            signingAlgorithms = listOf(Algorithm.ESP256),
        )
    }

    /** A client for the issuer's identity-proofing endpoints. */
    suspend fun createIdvClient(): IdvClient = IdvClient(
        issuerUrl = issuerUrl.value,
        httpClient = httpClient,
        backend = getBackend(),
        secureArea = secureArea,
    )

    private suspend fun putSetting(key: String, value: String) {
        val data = value.encodeToByteString()
        if (settingsTable.get(key) == null) {
            settingsTable.insert(key = key, data = data)
        } else {
            settingsTable.update(key = key, data = data)
        }
    }

    companion object {
        private const val KEY_ISSUER_URL = "issuer_url"
        private const val KEY_CONSENT_ACCEPTED = "consent_accepted"

        /** Credential domain for keys that need the screen lock (or biometrics) to present. */
        const val DOMAIN_USER_AUTH = "mdoc_user_auth"

        /** Credential domain for keys usable without the screen lock, on devices without one. */
        const val DOMAIN_NO_USER_AUTH = "mdoc_no_user_auth"

        private val lock = Mutex()
        private var instance: WalletModel? = null

        /** Returns the model, creating it on first use. */
        suspend fun get(context: Context): WalletModel = lock.withLock {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private suspend fun create(context: Context): WalletModel {
            val storage = Platform.nonBackedUpStorage
            val secureArea = Platform.getSecureArea(storage)
            val documentTypeRepository = DocumentTypeRepository().apply {
                addDocumentType(PhotoID.getDocumentType())
            }
            val documentStore = buildDocumentStore(
                storage = storage,
                secureAreaRepository = SecureAreaRepository.Builder().add(secureArea).build(),
            ) {}
            val readerTrustManager = ValidatopiaTrust.createReaderTrustManager()
            val presentmentSource = SimplePresentmentSource(
                documentStore = documentStore,
                documentTypeRepository = documentTypeRepository,
                resolveTrustFn = { requester -> ValidatopiaTrust.resolveRequester(requester, readerTrustManager) },
                domainsMdocSignature = listOf(DOMAIN_USER_AUTH, DOMAIN_NO_USER_AUTH),
            )
            // Keys that require user authentication can only be created with a secure lock screen
            // (an emulator usually has none), so only ask for them when there is one.
            val deviceSecure = context.getSystemService(KeyguardManager::class.java).isDeviceSecure
            val promptModel = AndroidPromptModel.Builder().apply { addCommonDialogs() }.build()
            val provisioningModel = ProvisioningModel(
                documentProvisioningHandler = DocumentProvisioningHandler(
                    secureArea = secureArea,
                    documentStore = documentStore,
                    defaultDocumentProvisioningSettings = DocumentProvisioningSettings().copy(
                        requestUserAuth = deviceSecure,
                        requestNoUserAuth = !deviceSecure,
                        mdocUserAuthDomain = DOMAIN_USER_AUTH,
                        mdocNoUserAuthDomain = DOMAIN_NO_USER_AUTH,
                    ),
                ),
                httpClient = HttpClient(Android) { followRedirects = false },
                promptModel = promptModel,
                authorizationSecureArea = secureArea,
            )
            val settingsTable = storage.getTable(
                StorageTableSpec(name = "ValidatopiaWalletSettings", supportPartitions = false, supportExpiration = false)
            )
            return WalletModel(
                storage = storage,
                secureArea = secureArea,
                documentStore = documentStore,
                documentTypeRepository = documentTypeRepository,
                documentModel = DocumentModel.create(documentStore, documentTypeRepository),
                presentmentSource = presentmentSource,
                provisioningModel = provisioningModel,
                promptModel = promptModel,
                settingsTable = settingsTable,
                issuerUrl = settingsTable.get(KEY_ISSUER_URL)?.decodeToString() ?: BuildConfig.DEFAULT_ISSUER_URL,
                consentAccepted = settingsTable.get(KEY_CONSENT_ACCEPTED)?.decodeToString() == "true",
            )
        }
    }
}
