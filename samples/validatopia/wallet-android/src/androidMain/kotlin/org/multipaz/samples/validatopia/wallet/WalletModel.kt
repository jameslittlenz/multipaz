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
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.compose.document.DocumentModel
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.presentment.PresentmentSource
import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.prompt.PromptModel
import org.multipaz.provisioning.DocumentProvisioningHandler
import org.multipaz.provisioning.ProvisioningModel
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.samples.validatopia.shared.idv.DevWalletBackend
import org.multipaz.samples.validatopia.shared.idv.IdvClient
import org.multipaz.samples.validatopia.shared.wallet.ValidatopiaWallet
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

    private suspend fun createBackend(issuerUrl: String): OpenID4VCIBackend =
        if (BuildConfig.USE_DEV_ATTESTATION) {
            DevWalletBackend.create()
        } else {
            ValidatopiaWallet.createAttestedBackend(issuerUrl, Android, secureArea, storage)
        }

    /** OpenID4VCI client preferences for redeeming the issuer's offers. */
    suspend fun getClientPreferences(): OpenID4VCIClientPreferences =
        ValidatopiaWallet.clientPreferences(getBackend())

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

        private val lock = Mutex()
        private var instance: WalletModel? = null

        /** Returns the model, creating it on first use. */
        suspend fun get(context: Context): WalletModel = lock.withLock {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private suspend fun create(context: Context): WalletModel {
            val storage = Platform.nonBackedUpStorage
            val secureArea = Platform.getSecureArea(storage)
            val documentTypeRepository = ValidatopiaWallet.createDocumentTypeRepository()
            val documentStore = buildDocumentStore(
                storage = storage,
                secureAreaRepository = SecureAreaRepository.Builder().add(secureArea).build(),
            ) {}
            val presentmentSource = ValidatopiaWallet.createPresentmentSource(documentStore, documentTypeRepository)
            // Keys that require user authentication can only be created with a secure lock screen
            // (an emulator usually has none), so only ask for them when there is one.
            val deviceSecure = context.getSystemService(KeyguardManager::class.java).isDeviceSecure
            val promptModel = AndroidPromptModel.Builder().apply { addCommonDialogs() }.build()
            val provisioningModel = ProvisioningModel(
                documentProvisioningHandler = DocumentProvisioningHandler(
                    secureArea = secureArea,
                    documentStore = documentStore,
                    defaultDocumentProvisioningSettings = ValidatopiaWallet.provisioningSettings(deviceSecure),
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
