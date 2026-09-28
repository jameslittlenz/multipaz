package org.multipaz.idv.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.util.encodeBase64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Simple
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.X509Cert
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.idv.backend.csca.ValidatopiaTestCsca
import org.multipaz.idv.backend.face.FakeFaceMatcher
import org.multipaz.idv.backend.persona.PersonaStore
import org.multipaz.idv.lds.Lds
import org.multipaz.idv.mrz.Mrz
import org.multipaz.idv.mrz.MrzSex
import org.multipaz.idv.synthetic.SyntheticPassportFactory
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.openid4vci.credential.CredentialFactoryPhotoId
import org.multipaz.openid4vci.credential.CredentialFactoryRegistry
import org.multipaz.openid4vci.idv.IdentityProofing
import org.multipaz.openid4vci.idv.PassportEvidence
import org.multipaz.openid4vci.idv.toCbor
import org.multipaz.openid4vci.server.configureRouting
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.provisioning.CredentialKeyAttestation
import org.multipaz.provisioning.CredentialMetadata
import org.multipaz.provisioning.Display
import org.multipaz.provisioning.DocumentProvisioningHandler
import org.multipaz.provisioning.KeyBindingInfo
import org.multipaz.provisioning.KeyBindingType
import org.multipaz.provisioning.ProvisioningMetadata
import org.multipaz.provisioning.openid4vci.OpenID4VCI
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.KeyAttestation
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.server.common.ServerConfiguration
import org.multipaz.server.common.ServerEnvironment
import org.multipaz.server.common.installServerEnvironment
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.toBase64Url
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end test for the Validatopia Photo ID issuer: identity proofing (both the passport-evidence
 * and dummy-persona paths) through to a minted `PhotoID` mdoc, using [FakeFaceMatcher] in place of
 * the (deferred) ONNX face matcher.
 *
 * This is the M2 completion bar from `docs/validatopia/PLAN.md`. It does not exercise verifier-side
 * presentment or the use-case selective-disclosure table (Component H) — those need the wallet and
 * verifier apps, which are M4+.
 *
 * The harness mirrors `ProvisioningClientTest`: a `testApplication` running the real openid4vci
 * routing, redeemed through the real `OpenID4VCI` client library for `/token` and `/credential`.
 * `/idv/start` and `/idv/evidence`/`/idv/persona` aren't part of that library (they're
 * Validatopia-specific), so their wallet-attestation and proof-of-possession JWTs are built by hand
 * here, following exactly the checks in `org.multipaz.openid4vci.util.auth`.
 */
class PhotoIdEndToEndTest {
    lateinit var storage: Storage
    lateinit var secureArea: SoftwareSecureArea
    lateinit var secureAreaProvider: SecureAreaProvider<SecureArea>
    lateinit var secureAreaRepository: SecureAreaRepository
    lateinit var documentStore: DocumentStore
    lateinit var documentProvisioningHandler: DocumentProvisioningHandler
    lateinit var credentialFactoryRegistry: CredentialFactoryRegistry

    @Before
    fun setup() = runTest {
        storage = EphemeralStorage()
        secureArea = SoftwareSecureArea.create(storage)
        secureAreaRepository = SecureAreaRepository.Builder().add(secureArea).build()
        secureAreaProvider = SecureAreaProvider<SecureArea>(Dispatchers.Default) { secureArea }
        documentStore = buildDocumentStore(storage, secureAreaRepository) {}
        documentProvisioningHandler = DocumentProvisioningHandler(secureArea, documentStore)
        credentialFactoryRegistry = CredentialFactoryRegistry(listOf(CredentialFactoryPhotoId()))
        credentialFactoryRegistry.initialize()
    }

    @Test
    fun passportEvidenceIssuesPhotoId() = testApplication {
        val serverEnvironment = ServerEnvironment.create(serverConfiguration()) {
            add(CredentialFactoryRegistry::class, credentialFactoryRegistry)
            add(
                IdentityProofing::class,
                PassportIdentityProofing(FakeFaceMatcher(), ValidatopiaTestCsca.getOrCreate(), PersonaStore.EMPTY)
            )
        }
        application {
            installServerEnvironment(serverEnvironment)
            configureRouting(serverEnvironment)
        }
        val httpClient = createClient { followRedirects = false }
        val testCsca = withContext(serverEnvironment.await()) { ValidatopiaTestCsca.getOrCreate() }

        val passport = SyntheticPassportFactory.createPassport(
            documentSignerCertificate = testCsca.documentSignerCertificate,
            documentSignerPrivateKey = testCsca.documentSignerPrivateKey,
            documentSignerSignatureAlgorithm = testCsca.signatureAlgorithm,
            issuingState = "NZL",
            primaryIdentifier = "TRAVELLER",
            secondaryIdentifier = "TEST",
            documentNumber = "PA1234567",
            nationality = "NZL",
            birthDate = LocalDate(1990, 5, 17),
            sex = MrzSex.FEMALE,
            expiryDate = LocalDate(2035, 1, 1),
            portraitBytes = FAKE_JPEG_BYTES,
        )

        withContext(TestBackendEnvironment(httpClient)) {
            val clientId = "urn:uuid:418745b8-78a3-4810-88df-7898aff3ffb4"
            val attestationKey = Crypto.createEcPrivateKey(EcCurve.P256)

            val sessionId = idvStartSession(httpClient, clientId, attestationKey)

            val evidence = PassportEvidence(
                sessionId = sessionId,
                sod = ByteString(passport.sod),
                dg1 = ByteString(passport.dg1),
                dg2 = ByteString(passport.dg2),
                selfie = ByteString(FAKE_JPEG_BYTES),
            )
            val attestationJwt = buildWalletAttestationJwt(clientId, attestationKey.publicKey)
            val popJwt = buildPopJwt(clientId, attestationKey)
            val evidenceResponse = httpClient.post("$BASE_URL/idv/evidence") {
                headers {
                    append("OAuth-Client-Attestation", attestationJwt)
                    append("OAuth-Client-Attestation-PoP", popJwt)
                }
                contentType(ContentType.Application.OctetStream)
                setBody(evidence.toCbor())
            }
            Assert.assertEquals(HttpStatusCode.OK, evidenceResponse.status)
            val offer = jsonOf(evidenceResponse.readRawBytes())["offer"]!!.jsonPrimitive.content

            val credential = redeemOffer(offer)
            assertPhotoIdCredential(credential, passport.dg1, passport.dg2, passport.sod)
        }
    }

    @Test
    fun personaIssuesPhotoId() = testApplication {
        val persona = """
            [{ "id": "p1", "given_name": "Persona", "family_name": "One", "birth_date": "1985-03-12",
               "sex": 2, "nationality": "XVA", "document_number": "VPT000001",
               "expiry_date": "2032-06-01", "portrait": "p1.jpg" }]
        """.trimIndent()
        val personaStore = PersonaStore.fromJson(persona) { FAKE_JPEG_BYTES }

        val serverEnvironment = ServerEnvironment.create(serverConfiguration()) {
            add(CredentialFactoryRegistry::class, credentialFactoryRegistry)
            add(
                IdentityProofing::class,
                PassportIdentityProofing(FakeFaceMatcher(), ValidatopiaTestCsca.getOrCreate(), personaStore)
            )
        }
        application {
            installServerEnvironment(serverEnvironment)
            configureRouting(serverEnvironment)
        }
        val httpClient = createClient { followRedirects = false }

        withContext(TestBackendEnvironment(httpClient)) {
            val clientId = "urn:uuid:418745b8-78a3-4810-88df-7898aff3ffb4"
            val attestationKey = Crypto.createEcPrivateKey(EcCurve.P256)

            var attestationJwt = buildWalletAttestationJwt(clientId, attestationKey.publicKey)
            var popJwt = buildPopJwt(clientId, attestationKey)
            val personasResponse = httpClient.get("$BASE_URL/idv/personas?client_id=$clientId") {
                headers {
                    append("OAuth-Client-Attestation", attestationJwt)
                    append("OAuth-Client-Attestation-PoP", popJwt)
                }
            }
            Assert.assertEquals(HttpStatusCode.OK, personasResponse.status)

            attestationJwt = buildWalletAttestationJwt(clientId, attestationKey.publicKey)
            popJwt = buildPopJwt(clientId, attestationKey)
            val personaResponse = httpClient.post("$BASE_URL/idv/persona") {
                headers {
                    append("Content-Type", "application/json")
                    append("OAuth-Client-Attestation", attestationJwt)
                    append("OAuth-Client-Attestation-PoP", popJwt)
                }
                setBody(buildJsonObject {
                    put("client_id", clientId)
                    put("id", "p1")
                }.toString())
            }
            Assert.assertEquals(HttpStatusCode.OK, personaResponse.status)
            val offer = jsonOf(personaResponse.readRawBytes())["offer"]!!.jsonPrimitive.content

            val credential = redeemOffer(offer)
            Assert.assertTrue(credential is MdocCredential)
            val core = (credential as MdocCredential).issuerNamespaces.data[PhotoID.ISO_23220_2_NAMESPACE]!!
            Assert.assertEquals("Persona", core["given_name"]!!.dataElementValue.asTstr)
            Assert.assertEquals("Validatopia Test Issuance", core["issuing_authority"]!!.dataElementValue.asTstr)
        }
    }

    private suspend fun idvStartSession(
        httpClient: HttpClient,
        clientId: String,
        attestationKey: EcPrivateKey,
    ): String {
        val attestationJwt = buildWalletAttestationJwt(clientId, attestationKey.publicKey)
        val popJwt = buildPopJwt(clientId, attestationKey)
        val response = httpClient.post("$BASE_URL/idv/start") {
            headers {
                append("Content-Type", "application/json")
                append("OAuth-Client-Attestation", attestationJwt)
                append("OAuth-Client-Attestation-PoP", popJwt)
            }
            setBody(buildJsonObject { put("client_id", clientId) }.toString())
        }
        Assert.assertEquals(HttpStatusCode.OK, response.status)
        return jsonOf(response.readRawBytes())["session_id"]!!.jsonPrimitive.content
    }

    private suspend fun redeemOffer(offer: String): Credential {
        val provisioningClient = OpenID4VCI.createClientFromOffer(
            offerUri = offer,
            clientPreferences = testClientPreferences
        )
        provisioningClient.getAuthorizationChallenges()
        provisioningClient.getKeyBindingChallenge()

        val document = documentStore.createDocument()
        val credential = documentProvisioningHandler.getPendingKeyBoundCredentials(
            document = document,
            credentialMetadata = CredentialMetadata(
                display = Display("test"),
                format = CredentialFormat.Mdoc(PhotoID.PHOTO_ID_DOCTYPE),
                keyBindingType = KeyBindingType.Attestation(Algorithm.ES256),
                maxBatchSize = 1
            ),
            issuerMetadata = ProvisioningMetadata("http://test", Display("test"), mapOf()),
            createKeySettings = CreateKeySettings()
        ).first()

        val credentialData = provisioningClient.obtainCredentials(
            KeyBindingInfo.Attestation(
                listOf(
                    CredentialKeyAttestation(
                        credentialId = credential.identifier,
                        keyAttestation = credential.getAttestation()
                    )
                )
            )
        )
        Assert.assertEquals(1, credentialData.certifications.size)
        credential.certify(credentialData.certifications.first().issuerData)
        return credential
    }

    private fun assertPhotoIdCredential(
        credential: Credential,
        dg1: ByteArray,
        dg2: ByteArray,
        sod: ByteArray,
    ) {
        Assert.assertTrue(credential is MdocCredential)
        val mdoc = credential as MdocCredential
        // IACA root certificate is excluded from x5chain.
        Assert.assertEquals(1, mdoc.issuerCertChain.certificates.size)
        val namespaces = mdoc.issuerNamespaces.data
        val core = namespaces[PhotoID.ISO_23220_2_NAMESPACE]!!
        Assert.assertEquals("TRAVELLER", core["family_name"]!!.dataElementValue.asTstr)
        Assert.assertEquals("TEST", core["given_name"]!!.dataElementValue.asTstr)
        Assert.assertEquals(Simple.TRUE, core["age_over_18"]!!.dataElementValue)
        Assert.assertEquals("XV", core["issuing_country"]!!.dataElementValue.asTstr)
        val datagroups = namespaces[PhotoID.DATAGROUPS_NAMESPACE]!!
        Assert.assertArrayEquals(dg1, (datagroups["dg1"]!!.dataElementValue as Bstr).value)
        Assert.assertArrayEquals(dg2, (datagroups["dg2"]!!.dataElementValue as Bstr).value)
        Assert.assertArrayEquals(sod, (datagroups["sod"]!!.dataElementValue as Bstr).value)
    }

    private fun jsonOf(bytes: ByteArray): JsonObject =
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject

    private fun serverConfiguration() = ServerConfiguration(
        arrayOf(
            "-param", "base_url=$BASE_URL",
            "-param", "database_engine=ephemeral",
            "-param", "use_client_attestation_challenge=false",
        )
    )

    private suspend fun buildWalletAttestationJwt(clientId: String, attestationPublicKey: EcPublicKey): String {
        val alg = localAttestationPrivateKey.curve.defaultSigningAlgorithmFullySpecified
        val head = buildJsonObject {
            put("typ", "oauth-client-attestation+jwt")
            put("alg", alg.joseAlgorithmIdentifier)
            put("x5c", buildJsonArray {
                add(localAttestationCertificate.encoded.toByteArray().encodeBase64())
            })
        }
        val now = Clock.System.now()
        val payload = buildJsonObject {
            put("iss", localClientId)
            put("sub", clientId)
            put("exp", (now + 5.minutes).epochSeconds)
            put("cnf", buildJsonObject { put("jwk", attestationPublicKey.toJwk()) })
            put("nbf", (now - 1.seconds).epochSeconds)
            put("iat", now.epochSeconds)
        }
        return signJwt(head, payload, localAttestationPrivateKey, alg)
    }

    private suspend fun buildPopJwt(clientId: String, attestationKey: EcPrivateKey): String {
        val alg = attestationKey.curve.defaultSigningAlgorithmFullySpecified
        val head = buildJsonObject {
            put("typ", "oauth-client-attestation-pop+jwt")
            put("alg", alg.joseAlgorithmIdentifier)
        }
        val now = Clock.System.now()
        val payload = buildJsonObject {
            put("iss", clientId)
            put("aud", BASE_URL)
            put("jti", Crypto.secureRandom.nextBytes(12).toBase64Url())
            put("iat", now.epochSeconds)
            put("exp", (now + 5.minutes).epochSeconds)
        }
        return signJwt(head, payload, attestationKey, alg)
    }

    private suspend fun signJwt(header: JsonObject, payload: JsonObject, key: EcPrivateKey, algorithm: Algorithm): String {
        val message = "${header.toString().encodeToByteArray().toBase64Url()}." +
            payload.toString().encodeToByteArray().toBase64Url()
        val signature = Crypto.sign(key, algorithm, message.encodeToByteArray())
        return "$message.${signature.toCoseEncoded().toBase64Url()}"
    }

    object TestBackend : OpenID4VCIBackend {
        override suspend fun getClientId(): String = localClientId

        override suspend fun createJwtClientAssertion(authorizationServerIdentifier: String): String =
            throw UnsupportedOperationException("not used in this test")

        override suspend fun createJwtWalletAttestation(keyAttestation: KeyAttestation): String {
            val alg = localAttestationPrivateKey.curve.defaultSigningAlgorithmFullySpecified
            val head = buildJsonObject {
                put("typ", "oauth-client-attestation+jwt")
                put("alg", alg.joseAlgorithmIdentifier)
                put("x5c", buildJsonArray {
                    add(localAttestationCertificate.encoded.toByteArray().encodeBase64())
                })
            }
            val now = Clock.System.now()
            val payload = buildJsonObject {
                put("iss", localClientId)
                put("sub", testClientPreferences.clientId)
                put("exp", (now + 5.minutes).epochSeconds)
                put("cnf", buildJsonObject { put("jwk", keyAttestation.publicKey.toJwk()) })
                put("nbf", (now - 1.seconds).epochSeconds)
                put("iat", now.epochSeconds)
            }
            val message = "${head.toString().encodeToByteArray().toBase64Url()}." +
                payload.toString().encodeToByteArray().toBase64Url()
            val sig = Crypto.sign(localAttestationPrivateKey, alg, message.encodeToByteArray())
            return "$message.${sig.toCoseEncoded().toBase64Url()}"
        }

        override suspend fun createJwtKeyAttestation(
            credentialKeyAttestations: List<CredentialKeyAttestation>,
            challenge: String,
            userAuthentication: List<String>?,
            keyStorage: List<String>?
        ): String {
            val keyList = credentialKeyAttestations.map { it.keyAttestation.publicKey }
            val alg = localAttestationPrivateKey.curve.defaultSigningAlgorithmFullySpecified
            val head = buildJsonObject {
                put("typ", "key-attestation+jwt")
                put("alg", alg.joseAlgorithmIdentifier)
                put("x5c", buildJsonArray {
                    add(localAttestationCertificate.encoded.toByteArray().encodeBase64())
                })
            }
            val now = Clock.System.now()
            val payload = buildJsonObject {
                put("iss", localClientId)
                put("attested_keys", JsonArray(keyList.map { it.toJwk() }))
                put("nonce", challenge)
                put("nbf", (now - 1.seconds).epochSeconds)
                put("exp", (now + 5.minutes).epochSeconds)
                put("iat", now.epochSeconds)
            }
            val message = "${head.toString().encodeToByteArray().toBase64Url()}." +
                payload.toString().encodeToByteArray().toBase64Url()
            val sig = Crypto.sign(localAttestationPrivateKey, localAttestationPrivateKey.curve.defaultSigningAlgorithm, message.encodeToByteArray())
            return "$message.${sig.toCoseEncoded().toBase64Url()}"
        }
    }

    inner class TestBackendEnvironment(private val httpClient: HttpClient) : BackendEnvironment {
        override fun <T : Any> getInterface(clazz: KClass<T>): T? {
            return clazz.cast(
                when (clazz) {
                    HttpClient::class -> httpClient
                    OpenID4VCIBackend::class -> TestBackend
                    OpenID4VCIClientPreferences::class -> testClientPreferences
                    SecureAreaProvider::class -> secureAreaProvider
                    else -> return null
                }
            )
        }
    }

    companion object {
        private const val BASE_URL = "http://localhost"

        // Not a real JPEG; only starts with the JPEG magic bytes so `Jp2Decoder.toJpeg` accepts it
        // (DG2 content is otherwise never decoded as an image in `multipaz-idv`, per
        // `SyntheticPassportFactory`'s own doc comment).
        private val FAKE_JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00, 1, 2, 3)

        val testClientPreferences = OpenID4VCIClientPreferences(
            clientId = "urn:uuid:418745b8-78a3-4810-88df-7898aff3ffb4",
            redirectUrl = "https://redirect.example.com",
            locales = listOf("en-US"),
            signingAlgorithms = listOf(Algorithm.ESP256)
        )

        // The same fixed self-signed "wallet attestation" identity already trusted by
        // `trusted_client_attestations`/`trusted_key_attestations` in this module's test
        // `default_configuration.json` (same cert used the same way in `ProvisioningClientTest`).
        private val localAttestationCertificate = X509Cert.fromPem(
            """
                -----BEGIN CERTIFICATE-----
                MIIBxTCCAUugAwIBAgIJAOQTL9qcQopZMAoGCCqGSM49BAMDMDgxNjA0BgNVBAMT
                LXVybjp1dWlkOjYwZjhjMTE3LWI2OTItNGRlOC04ZjdmLTYzNmZmODUyYmFhNjAe
                Fw0yNDA5MjMyMjUxMzFaFw0zNDA5MjMyMjUxMzFaMDgxNjA0BgNVBAMTLXVybjp1
                dWlkOjYwZjhjMTE3LWI2OTItNGRlOC04ZjdmLTYzNmZmODUyYmFhNjB2MBAGByqG
                SM49AgEGBSuBBAAiA2IABN4D7fpNMAv4EtxyschbITpZ6iNH90rGapa6YEO/uhKn
                C6VpPt5RUrJyhbvwAs0edCPthRfIZwfwl5GSEOS0mKGCXzWdRv4GGX/Y0m7EYypo
                x+tzfnRTmoVX3v6OxQiapKMhMB8wHQYDVR0OBBYEFPqAK5EjiQbxFAeWt//DCaWt
                C57aMAoGCCqGSM49BAMDA2gAMGUCMEO01fJKCy+iOTpaVp9LfO7jiXcXksn2BA22
                reiR9ahDRdGNCrH1E3Q2umQAssSQbQIxAIz1FTHbZPcEbA5uE5lCZlRG/DQxlZhk
                /rZrkPyXFhqEgfMnQ45IJ6f8Utlg+4Wiiw==
                -----END CERTIFICATE-----
            """.trimIndent()
        )

        private val localAttestationPrivateKey = EcPrivateKey.fromPem(
            """
            -----BEGIN PRIVATE KEY-----
            ME4CAQAwEAYHKoZIzj0CAQYFK4EEACIENzA1AgEBBDBn7jeRC9u9de3kOkrt9lLT
            Pvd1hflNq1FCgs7D+qbbwz1BQa4XXU0SjsV+R1GjnAY=
            -----END PRIVATE KEY-----
            """.trimIndent(),
            localAttestationCertificate.ecPublicKey
        )

        private val localClientId =
            localAttestationCertificate.subject.components[org.multipaz.asn1.OID.COMMON_NAME.oid]?.value
                ?: throw IllegalStateException("No common name (CN) in certificate's subject")
    }
}
