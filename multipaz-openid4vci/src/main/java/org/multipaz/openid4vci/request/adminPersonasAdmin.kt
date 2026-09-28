package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.openid4vci.idv.PersonaSummary
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.util.fromBase64

/**
 * Persona-store upload for the admin site's "Personas" page (`docs/validatopia/PLAN.md`'s
 * Component F): "upload/replace personas.json + portraits, preview them". Listing them for
 * preview reuses [PersonaSummary] from the public `/idv/personas` endpoint's DTO.
 *
 * Request: `{"personas_json": "<raw personas.json text>", "portraits": {"<filename>": "<base64 JPEG>", ...}}`.
 * Portraits travel as base64 JSON (not multipart) to keep this endpoint consistent with every
 * other admin endpoint's plain-JSON request bodies; fine at the scale of a handful of demo
 * portraits.
 */
suspend fun adminPersonasUpload(call: ApplicationCall) {
    val identityProofing = identityProofingOrNotFound(call) ?: return
    val json = Json.parseToJsonElement(call.receiveText()) as JsonObject
    val personasJson = json["personas_json"]?.jsonPrimitive?.content
        ?: throw InvalidRequestException("missing parameter 'personas_json'")
    val portraitsJson = json["portraits"]?.jsonObject
        ?: throw InvalidRequestException("missing parameter 'portraits'")
    val portraits = portraitsJson.entries.associate { (filename, value) ->
        filename to try {
            value.jsonPrimitive.content.fromBase64()
        } catch (e: IllegalArgumentException) {
            throw InvalidRequestException("portrait '$filename' is not valid base64: ${e.message}")
        }
    }
    val summaries = identityProofing.uploadPersonas(personasJson, portraits)
    respondPersonaSummaries(call, summaries)
}

/** `GET /admin_personas`: lists the current personas (same summary as `/idv/personas`, unauthenticated attestation not required here since this is behind an admin session). */
suspend fun adminPersonasList(call: ApplicationCall) {
    val identityProofing = identityProofingOrNotFound(call) ?: return
    respondPersonaSummaries(call, identityProofing.listPersonas())
}

private suspend fun respondPersonaSummaries(call: ApplicationCall, personas: List<PersonaSummary>) {
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
