package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.openid4vci.idv.PassportEvidence
import org.multipaz.openid4vci.idv.fromCbor
import org.multipaz.openid4vci.util.IdvSession
import org.multipaz.openid4vci.util.createIdvOffers
import org.multipaz.openid4vci.util.validateClientAttestation
import org.multipaz.openid4vci.util.validateClientAttestationPoP
import org.multipaz.rpc.handler.InvalidRequestException

// Matches the architecture note in docs/validatopia/PLAN.md: "POST /idv/evidence (client
// attestation + PoP, CBOR <= 2 MB)".
private const val MAX_EVIDENCE_BYTES = 2 * 1024 * 1024

/**
 * `POST /idv/evidence`: submits a passport chip read plus a selfie for identity proofing.
 *
 * Request body: CBOR-encoded [PassportEvidence] (binary, not JSON), with `OAuth-Client-Attestation`
 * /`-PoP` headers for the same client that started the session in `/idv/start`.
 *
 * Response on success: `{"offer": "...", "flags": [...]}`. Response on rejection (HTTP 400):
 * `{"error": "idv_rejected", "flags": [...]}`. The session is single-use either way.
 */
suspend fun idvEvidence(call: ApplicationCall) {
    val identityProofing = identityProofingOrNotFound(call) ?: return
    val body = call.receive<ByteArray>()
    if (body.size > MAX_EVIDENCE_BYTES) {
        throw InvalidRequestException("evidence exceeds the maximum size")
    }
    val evidence = try {
        PassportEvidence.fromCbor(body)
    } catch (e: Exception) {
        throw InvalidRequestException("malformed evidence: ${e.message}")
    }
    val session = IdvSession.get(evidence.sessionId)
        ?: throw InvalidRequestException("unknown or expired idv session")
    val attestationKey = validateClientAttestation(call.request, session.clientId)
        ?: throw InvalidRequestException("client attestation required")
    validateClientAttestationPoP(call.request, session.clientId, attestationKey)
    IdvSession.consume(evidence.sessionId)

    val result = identityProofing.proof(evidence)
    if (!result.accepted) {
        call.respondText(
            status = HttpStatusCode.BadRequest,
            text = buildJsonObject {
                put("error", "idv_rejected")
                put("flags", buildJsonArray { result.flags.forEach { add(it) } })
            }.toString(),
            contentType = ContentType.Application.Json
        )
        return
    }
    val settings = identityProofing.getSettings()
    val offers = createIdvOffers(result, settings.offerTtlSeconds)
    val offer = offers.firstOrNull()
        ?: throw IllegalStateException("No 'photo_id' scoped credential configuration is registered")
    call.respondText(
        text = buildJsonObject {
            put("offer", offer)
            put("flags", buildJsonArray { result.flags.forEach { add(it) } })
        }.toString(),
        contentType = ContentType.Application.Json
    )
}
