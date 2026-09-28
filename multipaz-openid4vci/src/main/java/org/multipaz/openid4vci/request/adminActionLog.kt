package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import org.multipaz.openid4vci.admin.AdminActionLogRecord

/**
 * `GET /admin_action_log?after=...&limit=...&format=csv`: lists admin-account actions (logins,
 * lockouts, account/TOTP changes, credential revocation/deletion, portrait reveals). See
 * `AdminActionLog`'s doc comment for how this relates to the Validatopia-specific
 * `admin_idv_audit` log.
 */
suspend fun adminActionLog(call: ApplicationCall) {
    val afterId = call.request.queryParameters["after"]
    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
    val entries = AdminActionLogRecord.list(afterId, limit)
    if (call.request.queryParameters["format"] == "csv") {
        val csv = buildString {
            append("id,timestamp,username,action,detail\n")
            for ((id, entry) in entries) {
                append(csvField(id)); append(',')
                append(entry.timestamp.epochSeconds); append(',')
                append(csvField(entry.username ?: "")); append(',')
                append(csvField(entry.action)); append(',')
                append(csvField(entry.detail))
                append('\n')
            }
        }
        call.respondText(text = csv, contentType = ContentType("text", "csv"))
        return
    }
    call.respondText(
        text = buildJsonArray {
            for ((id, entry) in entries) {
                addJsonObject {
                    put("id", id)
                    put("timestamp", entry.timestamp.epochSeconds)
                    entry.username?.let { put("username", it) }
                    put("action", entry.action)
                    put("detail", entry.detail)
                }
            }
        }.toString(),
        contentType = ContentType.Application.Json
    )
}

private fun csvField(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
