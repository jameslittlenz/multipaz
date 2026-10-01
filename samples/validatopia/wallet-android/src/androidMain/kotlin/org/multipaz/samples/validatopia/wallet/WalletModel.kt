package org.multipaz.samples.validatopia.wallet

import android.app.KeyguardManager
import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.compose.document.DocumentModel
import org.multipaz.digitalcredentials.DigitalCredentials
import org.multipaz.digitalcredentials.getDefault
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
import org.multipaz.samples.validatopia.shared.wallet.CredentialStatusChecker
import org.multipaz.samples.validatopia.shared.wallet.ValidatopiaIssuance
import org.multipaz.samples.validatopia.shared.wallet.ValidatopiaWallet
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Logger
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
    val issuance: ValidatopiaIssuance,
    val promptModel: PromptModel,
    private val settingsTable: StorageTable,
    issuerUrl: String,
    consentAccepted: Boolean,
) {
    private val mutableIssuerUrl = MutableStateFlow(issuerUrl)
    private val mutableConsentAccepted = MutableStateFlow(consentAccepted)
    private val backendLock = Mutex()
    private var backend: Pair<String, OpenID4VCIBackend>? = null

    // Outlives any screen: the documents that come with a Photo ID keep being issued after the
    // wallet has moved on to show the Photo ID.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The Validatopia issuer's base URL. */
    val issuerUrl: StateFlow<String> = mutableIssuerUrl.asStateFlow()

    /** Whether the user has accepted the welcome screen's terms. */
    val consentAccepted: StateFlow<Boolean> = mutableConsentAccepted.asStateFlow()

    /** Checks documents against their issuer's revocation list, for the details screen. */
    val credentialStatusChecker by lazy { CredentialStatusChecker(storage, Android) }

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

    /**
     * Redeems [offers] from identity proofing: the first, the Photo ID, on [provisioningModel],
     * which the provisioning sheet follows; then the rest in the background with [issuance] once
     * the Photo ID is issued.
     */
    suspend fun issueDocuments(offers: List<String>) {
        if (provisioningModel.isActive) {
            return
        }
        val clientPreferences = getClientPreferences()
        val backend = getBackend()
        val photoId = provisioningModel.launchOpenID4VCIProvisioning(
            offerUri = offers.first(),
            clientPreferences = clientPreferences,
            backend = backend,
        )
        scope.launch {
            issuance.issueAfterPhotoId(photoId, offers.drop(1), clientPreferences, backend)
        }
    }

    private var digitalCredentialsStarted = false

    /**
     * Registers the wallet's documents with the platform's W3C Digital Credentials API (Android
     * Credential Manager), and again whenever they change, so websites can ask for them with
     * `navigator.credentials.get()`. Requests arrive in [WalletCredentialManagerPresentmentActivity].
     * Call on the main thread; later calls do nothing.
     */
    fun startDigitalCredentialsExport() {
        if (digitalCredentialsStarted) {
            return
        }
        digitalCredentialsStarted = true
        scope.launch {
            val digitalCredentials = DigitalCredentials.getDefault()
            if (!digitalCredentials.registerAvailable) {
                return@launch
            }
            // Registering on subscription means no change is missed between the two. That first
            // registration is forced: the SDK records what it registered before Credential Manager
            // confirms it, so a registration that failed last time would otherwise be skipped as
            // unchanged.
            documentStore.eventFlow
                .onSubscription { registerDigitalCredentials(digitalCredentials, force = true) }
                .collect { registerDigitalCredentials(digitalCredentials, force = false) }
        }
    }

    private suspend fun registerDigitalCredentials(digitalCredentials: DigitalCredentials, force: Boolean) {
        try {
            digitalCredentials.register(
                documentStore = documentStore,
                documentTypeRepository = documentTypeRepository,
                forceRegistration = force,
            )
        } catch (e: Exception) {
            // Background work: websites just won't see the change until the next registration.
            if (e is CancellationException) throw e
            Logger.w(TAG, "Couldn't register documents with the Digital Credentials API", e)
        }
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
        private const val TAG = "WalletModel"
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
            fun createProvisioningModel() = ProvisioningModel(
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
                provisioningModel = createProvisioningModel(),
                issuance = ValidatopiaIssuance(createProvisioningModel()),
                promptModel = promptModel,
                settingsTable = settingsTable,
                issuerUrl = settingsTable.get(KEY_ISSUER_URL)?.decodeToString() ?: BuildConfig.DEFAULT_ISSUER_URL,
                consentAccepted = settingsTable.get(KEY_CONSENT_ACCEPTED)?.decodeToString() == "true",
            )
        }
    }
}
