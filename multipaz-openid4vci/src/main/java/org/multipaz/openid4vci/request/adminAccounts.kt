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
import org.multipaz.openid4vci.admin.AdminAuth
import org.multipaz.rpc.handler.InvalidRequestException

/**
 * Admin-account management (`docs/validatopia/PLAN.md`'s Component F, "Admin accounts": "add or
 * remove admins and reset TOTP").
 */

/** `GET /admin_accounts`: lists admin accounts (never returns password hashes or TOTP secrets). */
suspend fun adminAccountsList(call: ApplicationCall) {
    val accounts = AdminAuth.listAccounts()
    call.respondText(
        text = buildJsonArray {
            for (account in accounts) {
                addJsonObject {
                    put("username", account.username)
                    put("created_at", account.createdAt.toString())
                    put("totp_confirmed", account.totpConfirmed)
                    put("locked", account.locked)
                }
            }
        }.toString(),
        contentType = ContentType.Application.Json
    )
}

/**
 * `POST /admin_accounts`: creates a new admin account. Request: `{"username": "...", "password": "..."}`.
 * The new account has no confirmed TOTP secret; it enrolls one on its first login.
 */
suspend fun adminAccountsCreate(call: ApplicationCall, actor: String) {
    val json = Json.parseToJsonElement(call.receiveText()) as JsonObject
    val username = json["username"]?.jsonPrimitive?.content
        ?: throw InvalidRequestException("missing parameter 'username'")
    val password = (json["password"]?.jsonPrimitive?.content
        ?: throw InvalidRequestException("missing parameter 'password'")).toCharArray()
    val enrollment = try {
        AdminAuth.createAccount(username, password, actor)
    } catch (e: IllegalArgumentException) {
        throw InvalidRequestException(e.message ?: "invalid request")
    } finally {
        password.fill('\u0000')
    }
    respondEnrollment(call, enrollment)
}

/** `POST /admin_accounts_delete`: removes an admin account. Request: `{"username": "..."}`. */
suspend fun adminAccountsDelete(call: ApplicationCall, actor: String) {
    val json = Json.parseToJsonElement(call.receiveText()) as JsonObject
    val username = json["username"]?.jsonPrimitive?.content
        ?: throw InvalidRequestException("missing parameter 'username'")
    try {
        AdminAuth.deleteAccount(username, actor)
    } catch (e: IllegalArgumentException) {
        throw InvalidRequestException(e.message ?: "invalid request")
    }
    call.respondText(
        text = buildJsonObject { put("success", true) }.toString(),
        contentType = ContentType.Application.Json
    )
}

/**
 * `POST /admin_accounts_reset_totp`: regenerates an account's TOTP secret and revokes its
 * sessions; that account must re-enroll TOTP on its next login. Request: `{"username": "..."}`.
 */
suspend fun adminAccountsResetTotp(call: ApplicationCall, actor: String) {
    val json = Json.parseToJsonElement(call.receiveText()) as JsonObject
    val username = json["username"]?.jsonPrimitive?.content
        ?: throw InvalidRequestException("missing parameter 'username'")
    val enrollment = try {
        AdminAuth.resetTotp(username, actor)
    } catch (e: IllegalArgumentException) {
        throw InvalidRequestException(e.message ?: "invalid request")
    }
    respondEnrollment(call, enrollment)
}

private suspend fun respondEnrollment(call: ApplicationCall, enrollment: AdminAuth.Enrollment) {
    call.respondText(
        text = buildJsonObject {
            put("username", enrollment.username)
            put("secret", enrollment.secretBase32)
            put("otpauth_uri", enrollment.provisioningUri)
        }.toString(),
        contentType = ContentType.Application.Json
    )
}
