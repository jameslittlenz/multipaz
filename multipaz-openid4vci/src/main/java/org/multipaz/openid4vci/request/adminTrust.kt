package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.openid4vci.idv.TrustedCscaInfo
import org.multipaz.rpc.handler.InvalidRequestException

/**
 * CSCA trust-store management for the admin site's "Trust" page
 * (`docs/validatopia/PLAN.md`'s Component F): "upload CSCA PEMs ...; list them with expiry dates.
 * Download the Validatopia IACA and the test CSCA."
 *
 * Uploading an ICAO master list isn't supported yet — see `CscaStore`'s doc comment and
 * `UploadedCscaStore` in `multipaz-idv-backend`, and the M3 summary.
 */

/** `GET /admin_trust`: lists trusted CSCA certificates. */
suspend fun adminTrustList(call: ApplicationCall) {
    val identityProofing = identityProofingOrNotFound(call) ?: return
    respondCscaList(call, identityProofing.listTrustedCsca())
}

/** `POST /admin_trust`: uploads one or more concatenated PEM-encoded CSCA certificates. */
suspend fun adminTrustUpload(call: ApplicationCall) {
    val identityProofing = identityProofingOrNotFound(call) ?: return
    val json = Json.parseToJsonElement(call.receiveText()) as JsonObject
    val pem = json["pem"]?.jsonPrimitive?.content
        ?: throw InvalidRequestException("missing parameter 'pem'")
    respondCscaList(call, identityProofing.uploadTrustedCsca(pem))
}

/** `POST /admin_trust_delete`: removes an uploaded CSCA certificate. Request: `{"fingerprint": "..."}`. */
suspend fun adminTrustDelete(call: ApplicationCall) {
    val identityProofing = identityProofingOrNotFound(call) ?: return
    val json = Json.parseToJsonElement(call.receiveText()) as JsonObject
    val fingerprint = json["fingerprint"]?.jsonPrimitive?.content
        ?: throw InvalidRequestException("missing parameter 'fingerprint'")
    identityProofing.deleteTrustedCsca(fingerprint)
    respondCscaList(call, identityProofing.listTrustedCsca())
}

/** `GET /admin_trust_test_csca`: downloads the Validatopia Test CSCA certificate, PEM-encoded. */
suspend fun adminTrustDownloadTestCsca(call: ApplicationCall) {
    val identityProofing = identityProofingOrNotFound(call) ?: return
    call.respondText(text = identityProofing.testCscaPem(), contentType = ContentType.Text.Plain)
}

private suspend fun respondCscaList(call: ApplicationCall, list: List<TrustedCscaInfo>) {
    call.respondText(
        text = buildJsonArray {
            for (info in list) {
                addJsonObject {
                    put("fingerprint", info.fingerprintSha256Hex)
                    put("subject", info.subject)
                    put("not_before", info.notBeforeEpochSeconds)
                    put("not_after", info.notAfterEpochSeconds)
                    put("built_in", info.builtIn)
                }
            }
        }.toString(),
        contentType = ContentType.Application.Json
    )
}
