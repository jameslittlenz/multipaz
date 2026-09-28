package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import org.multipaz.openid4vci.util.validateClientAttestation
import org.multipaz.openid4vci.util.validateClientAttestationPoP
import org.multipaz.rpc.handler.InvalidRequestException

/**
 * `GET /idv/personas?client_id=...`: lists the dummy test identities available for issuance.
 *
 * Requires the same `OAuth-Client-Attestation`/`-PoP` headers as `/idv/start`. Returns 404 when
 * dummy issuance is disabled (see `IdvSettingsData.dummyIssuanceEnabled`).
 */
suspend fun idvPersonas(call: ApplicationCall) {
    val identityProofing = identityProofingOrNotFound(call) ?: return
    val clientId = call.request.queryParameters["client_id"]
        ?: throw InvalidRequestException("missing parameter 'client_id'")
    val attestationKey = validateClientAttestation(call.request, clientId)
        ?: throw InvalidRequestException("client attestation required")
    validateClientAttestationPoP(call.request, clientId, attestationKey)

    if (!identityProofing.dummyIssuanceEnabled()) {
        call.respondText(status = HttpStatusCode.NotFound, text = "")
        return
    }
    val personas = identityProofing.listPersonas()
    call.respondText(
        text = buildJsonArray {
            for (persona in personas) {
                addJsonObject {
                    put("id", persona.id)
                    put("given_name", persona.givenName)
                    put("family_name", persona.familyName)
                }
            }
        }.toString(),
        contentType = ContentType.Application.Json
    )
}
