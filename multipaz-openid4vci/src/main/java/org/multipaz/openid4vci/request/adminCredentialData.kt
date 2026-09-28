package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.cbor.Cbor
import org.multipaz.openid4vci.admin.AdminActionLogRecord
import org.multipaz.openid4vci.util.IssuanceState
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.rpc.handler.SimpleCipher

/**
 * Retained-passport-data handling for the admin site's "Issued credentials" page
 * (`docs/validatopia/PLAN.md`'s Component F/privacy notes): portraits are hidden unless an admin
 * explicitly reveals one, with every reveal audited, and retained data can be deleted outright.
 */

/**
 * `POST /admin_reveal_portrait`: decrypts the session's retained system-of-record data and
 * returns the portrait JPEG. Every call is audited under [actor]. Request: `{"session_id": "..."}`.
 */
suspend fun adminRevealPortrait(call: ApplicationCall, actor: String) {
    val sessionId = requireSessionId(call)
    val state = IssuanceState.getIssuanceState(sessionId)
    val encrypted = state.systemOfRecordData
    if (encrypted == null) {
        call.respondText(status = HttpStatusCode.NotFound, text = "")
        return
    }
    val cipher = BackendEnvironment.getInterface(SimpleCipher::class)!!
    val systemOfRecordData = Cbor.decode(cipher.decrypt(encrypted.toByteArray()))
    val portrait = systemOfRecordData["core"]["portrait"].asBstr
    AdminActionLogRecord.record(actor, "portrait_revealed", "session_id=$sessionId")
    call.respondBytes(bytes = portrait, contentType = ContentType.Image.JPEG)
}

/**
 * `POST /admin_delete_retained_data`: deletes a session's retained passport data (SOD/DG1/DG2 and
 * the derived claims), keeping already-minted credentials valid but making the session ineligible
 * for a refresh. Request: `{"session_id": "..."}`.
 */
suspend fun adminDeleteRetainedData(call: ApplicationCall, actor: String) {
    val sessionId = requireSessionId(call)
    val state = IssuanceState.getIssuanceState(sessionId)
    state.systemOfRecordData = null
    IssuanceState.updateIssuanceState(sessionId, state, expiration = null)
    AdminActionLogRecord.record(actor, "retained_data_deleted", "session_id=$sessionId")
    call.respondText(
        text = buildJsonObject { put("success", true) }.toString(),
        contentType = ContentType.Application.Json
    )
}

private fun requireSessionId(call: ApplicationCall): String =
    call.request.queryParameters["session_id"]
        ?: throw InvalidRequestException("missing parameter 'session_id'")
