package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.multipaz.openid4vci.idv.IdvSettingsData

/**
 * `GET /admin_idv_settings`: returns the current Validatopia IDV settings.
 *
 * The admin website that will edit these through a form is Component F, deferred to M3; for now
 * this and [adminUpdateIdvSettings] are the settings API those forms will call.
 */
suspend fun adminIdvSettings(call: ApplicationCall) {
    val identityProofing = identityProofingOrNotFound(call) ?: return
    call.respondText(
        text = identityProofing.getSettings().toJson().toString(),
        contentType = ContentType.Application.Json
    )
}

/** `POST /admin_idv_settings`: updates the Validatopia IDV settings. */
suspend fun adminUpdateIdvSettings(call: ApplicationCall) {
    val identityProofing = identityProofingOrNotFound(call) ?: return
    val json = Json.parseToJsonElement(call.receiveText()) as JsonObject
    val current = identityProofing.getSettings()
    val updated = IdvSettingsData(
        faceMatchThreshold = json.primitive("face_match_threshold")?.double ?: current.faceMatchThreshold,
        requireActiveAuth = json.primitive("require_active_auth")?.boolean ?: current.requireActiveAuth,
        acceptUntrustedCsca = json.primitive("accept_untrusted_csca")?.boolean ?: current.acceptUntrustedCsca,
        offerTtlSeconds = json.primitive("offer_ttl_seconds")?.long ?: current.offerTtlSeconds,
        photoIdValidityDays = json.primitive("photo_id_validity_days")?.long ?: current.photoIdValidityDays,
        dataRetentionDays = json.primitive("data_retention_days")?.long ?: current.dataRetentionDays,
        dummyIssuanceEnabled = json.primitive("dummy_issuance_enabled")?.boolean ?: current.dummyIssuanceEnabled,
        passportIssuanceEnabled = json.primitive("passport_issuance_enabled")?.boolean
            ?: current.passportIssuanceEnabled,
    )
    val saved = identityProofing.updateSettings(updated)
    call.respondText(
        text = saved.toJson().toString(),
        contentType = ContentType.Application.Json
    )
}

private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

private fun IdvSettingsData.toJson() = buildJsonObject {
    put("face_match_threshold", faceMatchThreshold)
    put("require_active_auth", requireActiveAuth)
    put("accept_untrusted_csca", acceptUntrustedCsca)
    put("offer_ttl_seconds", offerTtlSeconds)
    put("photo_id_validity_days", photoIdValidityDays)
    put("data_retention_days", dataRetentionDays)
    put("dummy_issuance_enabled", dummyIssuanceEnabled)
    put("passport_issuance_enabled", passportIssuanceEnabled)
}
