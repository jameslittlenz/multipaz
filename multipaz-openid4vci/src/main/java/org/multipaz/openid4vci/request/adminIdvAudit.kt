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
 * `GET /admin_idv_audit?after=...&limit=...&format=csv`: lists issuance audit entries, as JSON by
 * default or CSV when `format=csv` (for the admin site's "Audit log" export button).
 */
suspend fun adminIdvAudit(call: ApplicationCall) {
    val identityProofing = identityProofingOrNotFound(call) ?: return
    val afterId = call.request.queryParameters["after"]
    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
    val entries = identityProofing.listAudit(afterId, limit)
    if (call.request.queryParameters["format"] == "csv") {
        val csv = buildString {
            append("id,timestamp,method,accepted,nationality,masked_document_number,face_score,session_id,flags\n")
            for (entry in entries) {
                append(csvField(entry.id)); append(',')
                append(entry.timestampEpochSeconds); append(',')
                append(csvField(entry.method)); append(',')
                append(entry.accepted); append(',')
                append(csvField(entry.nationality ?: "")); append(',')
                append(csvField(entry.maskedDocumentNumber ?: "")); append(',')
                append(entry.faceScore?.toString() ?: ""); append(',')
                append(csvField(entry.sessionId ?: "")); append(',')
                append(csvField(entry.flags.joinToString(";")))
                append('\n')
            }
        }
        call.respondText(text = csv, contentType = ContentType("text", "csv"))
        return
    }
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

private fun csvField(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
