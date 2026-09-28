package org.multipaz.samples.validatopia.shared.idv

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.util.toBase64Url
import org.multipaz.webtoken.buildJwt
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/** A dummy test identity offered by the issuer's `/idv/personas` endpoint. */
data class Persona(
    val id: String,
    val givenName: String,
    val familyName: String,
)

/** Failure talking to the issuer's `/idv` endpoints. */
open class IdvException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The issuer has dummy (persona) issuance switched off, or doesn't run identity proofing at all. */
class IdvUnavailableException : IdvException("Test identities are not available from this issuer")

/**
 * Client for the Validatopia issuer's identity-proofing endpoints (`docs/validatopia/PLAN.md`,
 * Component D).
 *
 * Every call carries OAuth client attestation (`OAuth-Client-Attestation` plus
 * `OAuth-Client-Attestation-PoP`), exactly like the OpenID4VCI client does for `/par` and `/token`:
 * a fresh key is created in [secureArea] for each call, [backend] attests it as a wallet
 * attestation, and the PoP is signed with it over a fresh `/challenge` nonce. The key is deleted
 * afterwards.
 *
 * @param issuerUrl the issuer's base URL (its `base_url` configuration value). It is also the
 *   PoP audience, so it must match the server's configuration exactly.
 * @param httpClient an HTTP client which does not follow redirects.
 * @param backend the wallet back-end that issues wallet attestations.
 * @param secureArea where to create the per-call attestation keys.
 * @param random source of randomness for the PoP `jti`.
 */
class IdvClient(
    private val issuerUrl: String,
    private val httpClient: HttpClient,
    private val backend: OpenID4VCIBackend,
    private val secureArea: SecureArea,
    private val random: Random = Crypto.secureRandom,
) {
    /**
     * Lists the test identities the issuer offers.
     *
     * @throws IdvUnavailableException if the issuer has test identities switched off.
     * @throws IdvException on any other error.
     */
    @Throws(IdvException::class, CancellationException::class)
    suspend fun listPersonas(): List<Persona> {
        val clientId = backend.getClientId()
        val response = withClientAttestation { attestation, pop ->
            httpClient.get("$issuerUrl/idv/personas") {
                parameter("client_id", clientId)
                headers {
                    append(HEADER_ATTESTATION, attestation)
                    append(HEADER_ATTESTATION_POP, pop)
                }
            }
        }
        val body = checkedBody(response)
        return try {
            Json.parseToJsonElement(body).jsonArray.map { element ->
                val obj = element.jsonObject
                Persona(
                    id = obj.string("id"),
                    givenName = obj.string("given_name"),
                    familyName = obj.string("family_name"),
                )
            }
        } catch (e: IllegalArgumentException) {
            throw IdvException("Malformed persona list from the issuer", e)
        }
    }

    /**
     * Asks the issuer to proof [personaId] and returns the resulting OpenID4VCI credential offer
     * URI, single-use and short-lived, to be redeemed with the provisioning client.
     *
     * @throws IdvUnavailableException if the issuer has test identities switched off.
     * @throws IdvException on any other error.
     */
    @Throws(IdvException::class, CancellationException::class)
    suspend fun requestPersonaOffer(personaId: String): String {
        val clientId = backend.getClientId()
        val response = withClientAttestation { attestation, pop ->
            httpClient.post("$issuerUrl/idv/persona") {
                contentType(ContentType.Application.Json)
                headers {
                    append(HEADER_ATTESTATION, attestation)
                    append(HEADER_ATTESTATION_POP, pop)
                }
                setBody(buildJsonObject {
                    put("client_id", clientId)
                    put("id", personaId)
                }.toString())
            }
        }
        val body = checkedBody(response)
        return try {
            Json.parseToJsonElement(body).jsonObject.string("offer")
        } catch (e: IllegalArgumentException) {
            throw IdvException("Malformed offer response from the issuer", e)
        }
    }

    private suspend fun withClientAttestation(
        block: suspend (attestation: String, pop: String) -> HttpResponse
    ): HttpResponse {
        val keyInfo = secureArea.createKey(
            alias = null,
            createKeySettings = CreateKeySettings(
                nonce = Url(issuerUrl).host.encodeToByteString()
            )
        )
        try {
            val attestation = backend.createJwtWalletAttestation(keyInfo.attestation)
            val challenge = fetchChallenge()
            val pop = buildJwt(
                type = "oauth-client-attestation-pop+jwt",
                key = AsymmetricKey.anonymous(secureArea, keyInfo.alias),
                expiresIn = 5.minutes,
            ) {
                put("iss", backend.getClientId())
                put("aud", issuerUrl)
                put("jti", random.nextBytes(15).toBase64Url())
                put("challenge", challenge)
            }
            return try {
                block(attestation, pop)
            } catch (e: IOException) {
                throw IdvException("Could not reach the issuer at $issuerUrl", e)
            }
        } finally {
            withContext(NonCancellable) {
                secureArea.deleteKey(keyInfo.alias)
            }
        }
    }

    private suspend fun fetchChallenge(): String {
        val response = try {
            httpClient.post("$issuerUrl/challenge")
        } catch (e: IOException) {
            throw IdvException("Could not reach the issuer at $issuerUrl", e)
        }
        val body = checkedBody(response)
        return try {
            Json.parseToJsonElement(body).jsonObject.string("attestation_challenge")
        } catch (e: IllegalArgumentException) {
            throw IdvException("Malformed challenge response from the issuer", e)
        }
    }

    private suspend fun checkedBody(response: HttpResponse): String {
        val body = response.bodyAsText()
        return when (response.status) {
            HttpStatusCode.OK -> body
            HttpStatusCode.NotFound -> throw IdvUnavailableException()
            else -> throw IdvException("The issuer returned ${response.status.value}: ${body.take(MAX_ERROR_LENGTH)}")
        }
    }

    companion object {
        private const val HEADER_ATTESTATION = "OAuth-Client-Attestation"
        private const val HEADER_ATTESTATION_POP = "OAuth-Client-Attestation-PoP"
        private const val MAX_ERROR_LENGTH = 200

        /** Reads a string field, mapping absence or a non-string to [IllegalArgumentException]. */
        private fun JsonObject.string(name: String): String = try {
            this[name]?.jsonPrimitive?.content ?: throw IllegalArgumentException("'$name' missing")
        } catch (e: SerializationException) {
            throw IllegalArgumentException("'$name' malformed", e)
        }
    }
}
