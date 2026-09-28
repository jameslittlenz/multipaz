package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.openid4vci.admin.AdminAuth
import org.multipaz.openid4vci.admin.LoginResult
import org.multipaz.rpc.handler.InvalidRequestException

/**
 * Admin login/session endpoints (`docs/validatopia/PLAN.md`'s Component E), replacing the single
 * shared `issuance_auth` password cookie with Argon2id-hashed, per-account passwords and
 * mandatory TOTP. Login is two steps because a TOTP code can only be checked once the password is
 * known to be correct (and, for a brand-new account, isn't checked at all — enrollment happens
 * inline; see [AdminAuth.login]).
 */

/**
 * `POST /admin_login`: step 1, password only.
 *
 * Request: `{"username": "...", "password": "..."}`. Response is one of:
 * - `{"status": "totp_required"}` — call `/admin_login_totp` with a code from the authenticator app.
 * - `{"status": "totp_enrollment_required", "secret": "...", "otpauth_uri": "..."}` — the account
 *   has no confirmed authenticator yet; show [secret] for manual entry, then call
 *   `/admin_login_totp` with the first code it produces.
 * - `{"status": "locked", "retry_after_seconds": N}`.
 * - `{"error": "auth_failed"}` (400) — wrong username or password.
 */
suspend fun adminLogin(call: ApplicationCall) {
    val request = Json.parseToJsonElement(call.receiveText()) as JsonObject
    val username = request["username"]?.jsonPrimitive?.content
        ?: throw InvalidRequestException("missing parameter 'username'")
    val password = (request["password"]?.jsonPrimitive?.content
        ?: throw InvalidRequestException("missing parameter 'password'")).toCharArray()
    val clientIp = AdminAuth.clientIp(call)
    val result = try {
        AdminAuth.login(username, password, clientIp)
    } finally {
        password.fill('\u0000')
    }
    respondLoginResult(call, result)
}

/**
 * `POST /admin_login_totp`: step 2, the TOTP code.
 *
 * Request: `{"username": "...", "code": "123456"}`. On success, sets the session cookie and
 * responds `{"status": "ok", "csrf_token": "...", "expires_in": N}`: the CSRF token must be
 * echoed back in the `X-Admin-Csrf` header on every subsequent state-changing admin request.
 */
suspend fun adminLoginTotp(call: ApplicationCall) {
    val request = Json.parseToJsonElement(call.receiveText()) as JsonObject
    val username = request["username"]?.jsonPrimitive?.content
        ?: throw InvalidRequestException("missing parameter 'username'")
    val code = request["code"]?.jsonPrimitive?.content
        ?: throw InvalidRequestException("missing parameter 'code'")
    val result = AdminAuth.completeTotp(username, code, AdminAuth.clientIp(call))
    if (result is LoginResult.Success) {
        AdminAuth.setSessionCookie(call, result)
    }
    respondLoginResult(call, result)
}

/** `POST /admin_logout`: revokes the current session, if any, and clears its cookie. */
suspend fun adminLogout(call: ApplicationCall) {
    AdminAuth.logout(call)
    call.respondText(
        text = buildJsonObject { put("success", true) }.toString(),
        contentType = ContentType.Application.Json
    )
}

private suspend fun respondLoginResult(call: ApplicationCall, result: LoginResult) {
    val (status, body) = when (result) {
        is LoginResult.Success -> HttpStatusCode.OK to buildJsonObject {
            put("status", "ok")
            put("csrf_token", result.csrfToken)
            put("expires_in", result.expiresInSeconds)
        }
        is LoginResult.NeedsTotpCode -> HttpStatusCode.OK to buildJsonObject {
            put("status", "totp_required")
        }
        is LoginResult.NeedsTotpEnrollment -> HttpStatusCode.OK to buildJsonObject {
            put("status", "totp_enrollment_required")
            put("secret", result.secretBase32)
            put("otpauth_uri", result.provisioningUri)
        }
        is LoginResult.LockedOut -> HttpStatusCode.TooManyRequests to buildJsonObject {
            put("status", "locked")
            put("retry_after_seconds", result.retryAfterSeconds)
        }
        LoginResult.InvalidCredentials -> HttpStatusCode.BadRequest to buildJsonObject {
            put("error", "auth_failed")
            put("error_description", "incorrect username, password or code")
        }
    }
    call.respondText(status = status, text = body.toString(), contentType = ContentType.Application.Json)
}
