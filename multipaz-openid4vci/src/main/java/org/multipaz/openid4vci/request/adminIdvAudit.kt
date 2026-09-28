package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `GET /admin_idv_audit?after=...&limit=...`: lists issuance audit entries.
 *
 * CSV export and the admin website page that will display this are Component F, deferred to M3.
 */
suspend fun adminIdvAudit(call: ApplicationCall) {
    val identityProofing = identityProofingOrNotFound(call) ?: return
    val afterId = call.request.queryParameters["after"]
    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
    val entries = identityProofing.listAudit(afterId, limit)
    call.respondText(
        text = buildJsonArray {
            for (entry in entries) {
                addJsonObject {
                    put("id", entry.id)
                    put("timestamp", entry.timestampEpochSeconds)
                    put("method", entry.method)
                    put("accepted", entry.accepted)
                    entry.nationality?.let { put("nationality", it) }
                    entry.maskedDocumentNumber?.let { put("masked_document_number", it) }
                    entry.faceScore?.let { put("face_score", it) }
                    entry.sessionId?.let { put("session_id", it) }
                    putJsonArray("flags") { entry.flags.forEach { add(it) } }
                }
            }
        }.toString(),
        contentType = ContentType.Application.Json
    )
}
