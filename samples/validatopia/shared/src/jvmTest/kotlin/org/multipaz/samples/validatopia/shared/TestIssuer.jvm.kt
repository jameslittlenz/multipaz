package org.multipaz.samples.validatopia.shared

import io.ktor.client.HttpClient
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.multipaz.idv.backend.PassportIdentityProofing
import org.multipaz.idv.backend.csca.ValidatopiaTestCsca
import org.multipaz.idv.backend.face.FakeFaceMatcher
import org.multipaz.idv.backend.persona.PersonaStore
import org.multipaz.openid4vci.credential.CredentialFactoryRegistry
import org.multipaz.openid4vci.credential.ValidatopiaCredentials
import org.multipaz.openid4vci.idv.IdentityProofing
import org.multipaz.openid4vci.server.configureRouting
import org.multipaz.server.common.ServerConfiguration
import org.multipaz.server.common.ServerEnvironment
import org.multipaz.server.common.installServerEnvironment

/** On the JVM the issuer runs in-process, in a Ktor test application. */
actual fun runWithTestIssuer(block: suspend (issuerUrl: String, httpClient: HttpClient) -> Unit) {
    testApplication {
        startIssuer()
        block(ISSUER_URL, createClient { followRedirects = false })
    }
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
        val registry = CredentialFactoryRegistry(ValidatopiaCredentials.createFactories())
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

private const val ISSUER_URL = "http://localhost"

// Tests run with the module directory as the working directory.
private const val KEYS_CONFIG = "../../../multipaz-server-deployment/validatopia-test-keys/validatopia-keys.conf"

// Starts with the JPEG magic bytes; nothing here decodes the portrait as an image. Real
// portraits run to hundreds of KiB, and anything over 64 KiB needs a three-octet ASN.1
// length inside DG2, which the verifier must be able to parse.
private val FAKE_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(100_000) { it.toByte() }

// Mirrors multipaz-server-deployment/docker/init/personas/personas.json.
private val PERSONAS = """
    [{ "id": "p1", "given_name": "Claudia", "family_name": "Hill", "birth_date": "2002-01-01",
       "sex": 2, "nationality": "NZL", "document_number": "AA1234567",
       "expiry_date": "2035-01-01", "portrait": "claudia.jpg" },
     { "id": "p2", "given_name": "Richard", "family_name": "Smyth", "birth_date": "1982-11-02",
       "sex": 1, "nationality": "AUS", "document_number": "ZZ7654321",
       "expiry_date": "2031-11-02", "portrait": "richard.jpg" }]
""".trimIndent()
