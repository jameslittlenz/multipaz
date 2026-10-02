package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.openid4vci.util.IdvSession
import org.multipaz.openid4vci.util.validateClientAttestation
import org.multipaz.openid4vci.util.validateClientAttestationPoP
import org.multipaz.rpc.handler.InvalidRequestException
import kotlin.time.Duration.Companion.minutes

private const val IDV_SESSION_TTL_MINUTES = 10L

/**
 * `POST /idv/start`: begins a Validatopia identity-proofing session for an attested wallet.
 *
 * Request: `{"client_id": "..."}`, with `OAuth-Client-Attestation`/`-PoP` headers (see
 * `org.multipaz.openid4vci.util.auth`). Response: `{"session_id": "..."}`.
 *
 * The session id must be included in the [org.multipaz.openid4vci.idv.PassportEvidence] posted
 * to `/idv/evidence`. Active Authentication verification (which would otherwise use a nonce from
 * this endpoint) is deferred: `aa/ActiveAuthVerifier.kt` wasn't built in M1.
 *
 * Returns 404 while passport issuance is switched off in the admin settings.
 */
suspend fun idvStart(call: ApplicationCall) {
    passportProofingOrNotFound(call) ?: return
    val json = Json.parseToJsonElement(call.receiveText()) as JsonObject
    val clientId = json["client_id"]?.jsonPrimitive?.content
        ?: throw InvalidRequestException("missing parameter 'client_id'")
    val attestationKey = validateClientAttestation(call.request, clientId)
        ?: throw InvalidRequestException("client attestation required")
    validateClientAttestationPoP(call.request, clientId, attestationKey)
    val sessionId = IdvSession.create(clientId, IDV_SESSION_TTL_MINUTES.minutes)
    call.respondText(
        text = buildJsonObject { put("session_id", sessionId) }.toString(),
        contentType = ContentType.Application.Json
    )
}
