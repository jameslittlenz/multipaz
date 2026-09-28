package org.multipaz.samples.validatopia.shared

import io.ktor.client.HttpClient
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.Algorithm
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.idv.backend.PassportIdentityProofing
import org.multipaz.idv.backend.csca.ValidatopiaTestCsca
import org.multipaz.idv.backend.face.FakeFaceMatcher
import org.multipaz.idv.backend.persona.PersonaStore
import org.multipaz.idv.pa.CscaStore
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.openid4vci.credential.CredentialFactoryPhotoId
import org.multipaz.openid4vci.credential.CredentialFactoryRegistry
import org.multipaz.openid4vci.idv.IdentityProofing
import org.multipaz.openid4vci.server.configureRouting
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.presentment.mdocPresentmentAuthenticateUser
import org.multipaz.presentment.mdocPresentmentGenerateResponse
import org.multipaz.presentment.mdocPresentmentObtainConsent
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.provisioning.CredentialKeyAttestation
import org.multipaz.provisioning.CredentialMetadata
import org.multipaz.provisioning.Display
import org.multipaz.provisioning.DocumentProvisioningHandler
import org.multipaz.provisioning.DocumentProvisioningSettings
import org.multipaz.provisioning.KeyBindingInfo
import org.multipaz.provisioning.KeyBindingType
import org.multipaz.provisioning.ProvisioningMetadata
import org.multipaz.provisioning.openid4vci.OpenID4VCI
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.request.Requester
import org.multipaz.revocation.CachingRevocationChecker
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.samples.validatopia.shared.idv.DevWalletBackend
import org.multipaz.samples.validatopia.shared.idv.IdvClient
import org.multipaz.samples.validatopia.shared.result.CheckOutcome
import org.multipaz.samples.validatopia.shared.result.PhotoIdVerification
import org.multipaz.samples.validatopia.shared.result.PhotoIdVerifier
import org.multipaz.samples.validatopia.shared.trust.ValidatopiaTrust
import org.multipaz.samples.validatopia.shared.usecase.PhotoIdElement
import org.multipaz.samples.validatopia.shared.usecase.PhotoIdUseCase
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.server.common.ServerConfiguration
import org.multipaz.server.common.ServerEnvironment
import org.multipaz.server.common.installServerEnvironment
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.trustmanagement.TrustManagerInterface
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole persona-path demo, in one process: the real Validatopia issuer (with the fixed TEST
 * keys from `multipaz-server-deployment/validatopia-test-keys/`) proofs a persona for the wallet's
 * [IdvClient] and [DevWalletBackend], the offer is redeemed over OpenID4VCI into a wallet
 * [DocumentStore], then every use case is presented with the multipaz presentment code and checked
 * with the verifier's [PhotoIdVerifier].
 *
 * Only the radio (QR/BLE or NFC) is missing: the request and response are handed across directly.
 */
class ValidatopiaRoundTripTest {
    @Test
    fun personaIssuanceThenEveryUseCase() = testApplication {
        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        val documentStore = buildDocumentStore(storage, SecureAreaRepository.Builder().add(secureArea).build()) {}
        startIssuer()
        val httpClient = createClient { followRedirects = false }

        // Wallet: list the test identities and ask for Claudia (NZL passport).
        val idvClient = IdvClient(ISSUER_URL, httpClient, DevWalletBackend.create(), secureArea)
        val personas = idvClient.listPersonas()
        assertEquals(listOf("p1", "p2"), personas.map { it.id })
        val offer = idvClient.requestPersonaOffer("p1")

        // Wallet: redeem the offer over OpenID4VCI.
        withContext(WalletEnvironment(httpClient, secureArea)) {
            redeemOffer(offer, documentStore, secureArea)
        }

        // Wallet: its presentment source trusts the bundled Validatopia reader root.
        val readerTrustManager = ValidatopiaTrust.createReaderTrustManager()
        val presentmentSource = SimplePresentmentSource(
            documentStore = documentStore,
            documentTypeRepository = DocumentTypeRepository().apply { addDocumentType(PhotoID.getDocumentType()) },
            resolveTrustFn = { requester -> ValidatopiaTrust.resolveRequester(requester, readerTrustManager) },
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf(NO_USER_AUTH_DOMAIN),
        )

        val verifier = PhotoIdVerifier(
            issuerTrustManager = ValidatopiaTrust.createIssuerTrustManager(),
            cscaStore = ValidatopiaTrust.createCscaStore(),
            revocationChecker = CachingRevocationChecker(EphemeralStorage(), httpClient),
        )

        for (useCase in PhotoIdUseCase.entries) {
            val (deviceResponse, sessionTranscript) =
                present(useCase, presentmentSource, readerTrustManager)
            val result = verifier.verify(useCase, deviceResponse, sessionTranscript)
            assertSharedExactlyWhatWasRequested(useCase, result)
            assertCredentialIssuerTrusted(result)
            if (useCase == PhotoIdUseCase.CROSS_BORDER) {
                assertCrossBorderPasses(result)
            } else {
                assertNull(result.passportIssuer)
                assertNull(result.dg1Reveals)
            }
        }
    }

    @Test
    fun untrustedAnchorsAreFlagged() = testApplication {
        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        val documentStore = buildDocumentStore(storage, SecureAreaRepository.Builder().add(secureArea).build()) {}
        startIssuer()
        val httpClient = createClient { followRedirects = false }
        val offer = IdvClient(ISSUER_URL, httpClient, DevWalletBackend.create(), secureArea).requestPersonaOffer("p2")
        withContext(WalletEnvironment(httpClient, secureArea)) {
            redeemOffer(offer, documentStore, secureArea)
        }
        val presentmentSource = SimplePresentmentSource(
            documentStore = documentStore,
            documentTypeRepository = DocumentTypeRepository().apply { addDocumentType(PhotoID.getDocumentType()) },
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf(NO_USER_AUTH_DOMAIN),
        )
        // A verifier that trusts neither the Validatopia IACA nor the test CSCA.
        val verifier = PhotoIdVerifier(
            issuerTrustManager = ValidatopiaTrust.createReaderTrustManager(),
            cscaStore = CscaStore.from(emptyList()),
            revocationChecker = null,
        )
        val (deviceResponse, sessionTranscript) =
            present(PhotoIdUseCase.CROSS_BORDER, presentmentSource, ValidatopiaTrust.createReaderTrustManager())
        val result = verifier.verify(PhotoIdUseCase.CROSS_BORDER, deviceResponse, sessionTranscript)

        assertEquals(CheckOutcome.FAILED, result.credentialIssuer.checks.first { it.label == "Issuer" }.outcome)
        // The data itself is still intact; only trust is missing.
        assertEquals(CheckOutcome.PASSED, result.credentialIssuer.checks.first { it.label == "Signature and digests" }.outcome)
        assertEquals(CheckOutcome.UNKNOWN, result.credentialIssuer.checks.first { it.label == "Revocation" }.outcome)
        val passportIssuer = assertNotNull(result.passportIssuer)
        assertEquals(CheckOutcome.FAILED, passportIssuer.checks.first { it.label == "Passport issuer" }.outcome)
        assertFalse(assertNotNull(result.passportCheck).authentic)
        assertTrue(result.passportCheck!!.claimsMatch)
    }

    private fun ApplicationTestBuilder.startIssuer() {
        val serverEnvironment = ServerEnvironment.create(
            ServerConfiguration(
                arrayOf(
                    "-param", "base_url=$ISSUER_URL",
                    "-param", "database_engine=ephemeral",
                    "-config", KEYS_CONFIG,
                )
            )
        ) {
            val registry = CredentialFactoryRegistry(listOf(CredentialFactoryPhotoId()))
            registry.initialize()
            add(CredentialFactoryRegistry::class, registry)
            add(
                IdentityProofing::class,
                PassportIdentityProofing(
                    faceMatcher = FakeFaceMatcher(),
                    testCsca = ValidatopiaTestCsca.getOrCreate(),
                    personaStore = PersonaStore.fromJson(PERSONAS) { FAKE_JPEG },
                )
            )
        }
        application {
            installServerEnvironment(serverEnvironment)
            configureRouting(serverEnvironment)
        }
    }

    private suspend fun redeemOffer(offer: String, documentStore: DocumentStore, secureArea: SecureArea) {
        val provisioningClient = OpenID4VCI.createClientFromOffer(offer, clientPreferences)
        provisioningClient.getAuthorizationChallenges()
        provisioningClient.getKeyBindingChallenge()
        val handler = DocumentProvisioningHandler(
            secureArea = secureArea,
            documentStore = documentStore,
            defaultDocumentProvisioningSettings = DocumentProvisioningSettings().copy(requestUserAuth = false),
        )
        val credentials = handler.getPendingKeyBoundCredentials(
            document = documentStore.createDocument(),
            credentialMetadata = CredentialMetadata(
                display = Display("Validatopia Photo ID"),
                format = CredentialFormat.Mdoc(PhotoID.PHOTO_ID_DOCTYPE),
                keyBindingType = KeyBindingType.Attestation(Algorithm.ES256),
                maxBatchSize = 1,
            ),
            issuerMetadata = ProvisioningMetadata(ISSUER_URL, Display("Validatopia"), mapOf()),
            createKeySettings = CreateKeySettings(),
        )
        val credential = credentials.single()
        val issued = provisioningClient.obtainCredentials(
            KeyBindingInfo.Attestation(
                listOf(CredentialKeyAttestation(credential.identifier, credential.getAttestation()))
            )
        )
        credential.certify(issued.certifications.single().issuerData)
    }

    /** Hands a signed request to the wallet's presentment code and returns its response. */
    private suspend fun present(
        useCase: PhotoIdUseCase,
        source: SimplePresentmentSource,
        readerTrustManager: TrustManagerInterface,
    ): Pair<DeviceResponse, DataItem> {
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            add(Tstr("validatopia-round-trip-${useCase.name}"))
        }
        val sent = useCase.buildRequest(sessionTranscript, ValidatopiaTrust.readerKey())
        val received = DeviceRequest.fromDataItem(Cbor.decode(Cbor.encode(sent.toDataItem())))
        received.verifyReaderAuthentication(sessionTranscript)

        // The consent sheet would name the verifier.
        val requester = Requester(requesterIdentities = received.getRequesterIdentities())
        val trusted = assertNotNull(ValidatopiaTrust.resolveRequester(requester, readerTrustManager))
        assertEquals(ValidatopiaTrust.VERIFIER_DISPLAY_NAME, trusted.trustMetadata.displayName)

        val selection = mdocPresentmentObtainConsent(deviceRequest = received, source = source)
        val response = withContext(mdocPresentmentAuthenticateUser(selection)) {
            mdocPresentmentGenerateResponse(
                selection = selection,
                deviceRequest = received,
                eReaderKey = null,
                sessionTranscript = sessionTranscript,
                source = source,
            )
        }
        val wire = DeviceResponse.fromDataItem(Cbor.decode(Cbor.encode(response.deviceResponse.toDataItem())))
        return wire to sessionTranscript
    }

    private fun assertSharedExactlyWhatWasRequested(useCase: PhotoIdUseCase, result: PhotoIdVerification) {
        assertEquals(
            useCase.requested.map { it.element }.toSet(),
            result.disclosed.map { it.element }.toSet(),
            "Disclosed elements for ${useCase.name}",
        )
        for (claim in result.disclosed) {
            val requested = useCase.requested.first { it.element == claim.element }
            assertEquals(requested.intentToRetain, claim.intentToRetain)
        }
        assertTrue(result.notShared.none { it.wasRequested }, "Nothing requested was withheld for ${useCase.name}")
        assertTrue(result.notShared.isNotEmpty(), "Every use case leaves something unshared")
    }

    private fun assertCredentialIssuerTrusted(result: PhotoIdVerification) {
        val checks = result.credentialIssuer.checks.associateBy { it.label }
        assertEquals(CheckOutcome.WARNING, checks.getValue("Issuer").outcome, checks.getValue("Issuer").detail)
        assertTrue(checks.getValue("Issuer").detail.contains("TEST"))
        assertEquals(CheckOutcome.PASSED, checks.getValue("Signature and digests").outcome, checks.getValue("Signature and digests").detail)
        assertEquals(CheckOutcome.PASSED, checks.getValue("Validity").outcome)
        assertEquals(CheckOutcome.PASSED, checks.getValue("Revocation").outcome, checks.getValue("Revocation").detail)
    }

    private fun assertCrossBorderPasses(result: PhotoIdVerification) {
        val passportCheck = assertNotNull(result.passportCheck)
        assertTrue(passportCheck.authentic, "Passive authentication: ${passportCheck.passiveAuthentication}")
        assertTrue(passportCheck.claimsMatch, "DG1 comparisons: ${passportCheck.comparisons}")
        assertEquals("NZL", passportCheck.mrz!!.issuingState)
        assertTrue(passportCheck.comparisons.all { it.matches == true }, "${passportCheck.comparisons}")
        assertContentEquals(FAKE_JPEG, passportCheck.faceImage)
        val passportIssuer = assertNotNull(result.passportIssuer)
        assertTrue(passportIssuer.checks.none { it.outcome == CheckOutcome.FAILED }, "${passportIssuer.checks}")
        assertEquals(CheckOutcome.WARNING, passportIssuer.overall)
        val reveals = assertNotNull(result.dg1Reveals)
        assertEquals("CLAUDIA HILL", reveals.first { it.label == "Full name" }.value)
        assertTrue(result.valueOf(PhotoIdElement(PhotoID.ISO_23220_2_NAMESPACE, "portrait")) != null)
        // The Photo ID's own (Validatopia) number is never part of the border request.
        assertTrue(result.notShared.any { it.element.identifier == "document_number" })
    }

    private inner class WalletEnvironment(
        private val httpClient: HttpClient,
        secureArea: SecureArea,
    ) : BackendEnvironment {
        private val backend = DevWalletBackend.create()
        private val secureAreaProvider = SecureAreaProvider(Dispatchers.Default) { secureArea }

        override fun <T : Any> getInterface(clazz: KClass<T>): T? {
            val value: Any = when (clazz) {
                HttpClient::class -> httpClient
                OpenID4VCIBackend::class -> backend
                OpenID4VCIClientPreferences::class -> clientPreferences
                SecureAreaProvider::class -> secureAreaProvider
                else -> return null
            }
            return clazz.cast(value)
        }
    }

    companion object {
        private const val ISSUER_URL = "http://localhost"
        private const val NO_USER_AUTH_DOMAIN = "mdoc_no_user_auth"

        // Tests run with the module directory as the working directory.
        private const val KEYS_CONFIG = "../../../multipaz-server-deployment/validatopia-test-keys/validatopia-keys.conf"

        private val clientPreferences = OpenID4VCIClientPreferences(
            clientId = DevWalletBackend.CLIENT_ID,
            redirectUrl = "https://redirect.example.com",
            locales = listOf("en-US"),
            signingAlgorithms = listOf(Algorithm.ESP256),
        )

        // Starts with the JPEG magic bytes; nothing here decodes the portrait as an image. Real
        // portraits run to hundreds of KiB, and anything over 64 KiB needs a three-octet ASN.1
        // length inside DG2, which the verifier must be able to parse.
        private val FAKE_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(100_000) { it.toByte() }

        // Mirrors multipaz-server-deployment/docker/init/personas/personas.json.
        private val PERSONAS = """
            [{ "id": "p1", "given_name": "Claudia", "family_name": "Hill", "birth_date": "2002-01-01",
               "sex": 2, "nationality": "NZL", "document_number": "AA1234567",
               "expiry_date": "2035-01-01", "portrait": "p1.jpg" },
             { "id": "p2", "given_name": "Kai", "family_name": "Sorensen", "birth_date": "1988-11-02",
               "sex": 1, "nationality": "XVA", "document_number": "XVP000002",
               "expiry_date": "2031-11-02", "portrait": "p2.jpg" }]
        """.trimIndent()
    }
}
