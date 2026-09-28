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
import org.multipaz.openid4vci.util.createIdvOffers
import org.multipaz.openid4vci.util.validateClientAttestation
import org.multipaz.openid4vci.util.validateClientAttestationPoP
import org.multipaz.rpc.handler.InvalidRequestException

/**
 * `POST /idv/persona`: issues a Photo ID for a dummy test identity, following the same offer path
 * as `/idv/evidence`.
 *
 * Request: `{"client_id": "...", "id": "..."}`, with the same attestation headers as `/idv/start`.
 * Response: `{"offer": "..."}`.
 */
suspend fun idvPersona(call: ApplicationCall) {
    val identityProofing = identityProofingOrNotFound(call) ?: return
    val json = Json.parseToJsonElement(call.receiveText()) as JsonObject
    val clientId = json["client_id"]?.jsonPrimitive?.content
        ?: throw InvalidRequestException("missing parameter 'client_id'")
    val personaId = json["id"]?.jsonPrimitive?.content
        ?: throw InvalidRequestException("missing parameter 'id'")
    val attestationKey = validateClientAttestation(call.request, clientId)
        ?: throw InvalidRequestException("client attestation required")
    validateClientAttestationPoP(call.request, clientId, attestationKey)

    val result = identityProofing.proofPersona(personaId)
    val settings = identityProofing.getSettings()
    val offers = createIdvOffers(result, settings.offerTtlSeconds)
    val offer = offers.firstOrNull()
        ?: throw IllegalStateException("No 'photo_id' scoped credential configuration is registered")
    call.respondText(
        text = buildJsonObject { put("offer", offer) }.toString(),
        contentType = ContentType.Application.Json
    )
}
