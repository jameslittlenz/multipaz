package org.multipaz.openid4vci.server

import io.ktor.server.application.Application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Deferred
import org.multipaz.openid4vci.admin.AdminAuth
import org.multipaz.openid4vci.request.adminAccountsCreate
import org.multipaz.openid4vci.request.adminAccountsDelete
import org.multipaz.openid4vci.request.adminAccountsList
import org.multipaz.openid4vci.request.adminAccountsResetTotp
import org.multipaz.openid4vci.request.adminActionLog
import org.multipaz.openid4vci.request.adminDeleteRetainedData
import org.multipaz.openid4vci.request.adminIdvAudit
import org.multipaz.openid4vci.request.adminIdvSettings
import org.multipaz.openid4vci.request.adminLogin
import org.multipaz.openid4vci.request.adminLoginTotp
import org.multipaz.openid4vci.request.adminLogout
import org.multipaz.openid4vci.request.adminPersonasList
import org.multipaz.openid4vci.request.adminPersonasUpload
import org.multipaz.openid4vci.request.adminRevealPortrait
import org.multipaz.openid4vci.request.adminSessionInfo
import org.multipaz.openid4vci.request.adminListSessions
import org.multipaz.openid4vci.request.adminSetCredentialStatus
import org.multipaz.openid4vci.request.adminTrustDelete
import org.multipaz.openid4vci.request.adminTrustDownloadTestCsca
import org.multipaz.openid4vci.request.adminTrustList
import org.multipaz.openid4vci.request.adminTrustUpload
import org.multipaz.openid4vci.request.adminUpdateIdvSettings
import org.multipaz.openid4vci.request.authorizeChallenge
import org.multipaz.openid4vci.request.authorizeGet
import org.multipaz.openid4vci.request.authorizePost
import org.multipaz.openid4vci.request.challenge
import org.multipaz.openid4vci.request.credential
import org.multipaz.openid4vci.request.credentialRequest
import org.multipaz.openid4vci.request.finishAuthorization
import org.multipaz.openid4vci.request.identifierList
import org.multipaz.openid4vci.request.idvEvidence
import org.multipaz.openid4vci.request.idvPersona
import org.multipaz.openid4vci.request.idvPersonas
import org.multipaz.openid4vci.request.idvStart
import org.multipaz.openid4vci.request.preauthorizedOffer
import org.multipaz.openid4vci.request.nonce
import org.multipaz.openid4vci.request.openid4VpResponse
import org.multipaz.openid4vci.request.paint
import org.multipaz.openid4vci.request.pushedAuthorizationRequest
import org.multipaz.openid4vci.request.qrCode
import org.multipaz.openid4vci.request.signingCertificate
import org.multipaz.openid4vci.request.statusList
import org.multipaz.openid4vci.request.token
import org.multipaz.openid4vci.request.wellKnownOauthAuthorization
import org.multipaz.openid4vci.request.wellKnownOpenidCredentialIssuer
import org.multipaz.server.common.ServerEnvironment
import org.multipaz.server.request.push
import org.multipaz.server.common.serveResources
import org.multipaz.server.request.certificateAuthority

private const val TAG = "ApplicationExt"

/**
 * Defines server endpoints for HTTP GET and POST.
 */
fun Application.configureRouting(serverEnvironment: Deferred<ServerEnvironment>) {
    routing {
        push(serverEnvironment)
        // createOnRequest=true so GET /ca/credential_signing (the IACA; see
        // docs/validatopia/PLAN.md) serves immediately on a fresh server rather than 404ing
        // until the first credential has been minted.
        certificateAuthority(createOnRequest = true)
        serveResources()
        get("/authorize") { authorizeGet(call) }
        post("/authorize") { authorizePost(call) }
        post("/authorize_challenge") { authorizeChallenge(call) }
        post("/challenge") { challenge(call) }
        post("/credential_request") { credentialRequest(call) }
        post("/credential") { credential(call) }
        get("/finish_authorization") { finishAuthorization(call) }
        post("/nonce") { nonce(call) }
        post("/openid4vp_response") { openid4VpResponse(call) }
        get("/paint") { paint(call) }
        post("/par") { pushedAuthorizationRequest(call) }
        get("/qr") { qrCode(call) }
        post("/token") { token(call) }
        get("/.well-known/openid-credential-issuer") { wellKnownOpenidCredentialIssuer(call) }
        get("/.well-known/oauth-authorization-server") { wellKnownOauthAuthorization(call) }
        get("/signing_certificate") { signingCertificate(call) }
        post("/preauthorized_offer") { preauthorizedOffer(call) }
        get("/status_list/{bucket}") { statusList(call, call.parameters["bucket"]!!) }
        get("/identifier_list/{bucket}") { identifierList(call, call.parameters["bucket"]!!) }

        // --- Admin login (Component E: Argon2id password + mandatory TOTP, revocable sessions) ---
        post("/admin_login") { adminLogin(call) }
        post("/admin_login_totp") { adminLoginTotp(call) }
        post("/admin_logout") { adminLogout(call) }

        // --- Admin: sessions/credentials (read-only: session cookie, no CSRF needed) ---
        get("/admin_list_sessions") {
            AdminAuth.requireSession(call, requireCsrf = false) ?: return@get
            adminListSessions(call)
        }
        get("/admin_session_info") {
            AdminAuth.requireSession(call, requireCsrf = false) ?: return@get
            adminSessionInfo(call)
        }
        post("/admin_set_credential_status") {
            AdminAuth.requireSession(call, requireCsrf = true) ?: return@post
            adminSetCredentialStatus(call)
        }
        post("/admin_reveal_portrait") {
            val session = AdminAuth.requireSession(call, requireCsrf = true) ?: return@post
            adminRevealPortrait(call, session.username)
        }
        post("/admin_delete_retained_data") {
            val session = AdminAuth.requireSession(call, requireCsrf = true) ?: return@post
            adminDeleteRetainedData(call, session.username)
        }

        // --- Admin: Validatopia IDV settings/audit (Component C/F) ---
        get("/admin_idv_settings") {
            AdminAuth.requireSession(call, requireCsrf = false) ?: return@get
            adminIdvSettings(call)
        }
        post("/admin_idv_settings") {
            AdminAuth.requireSession(call, requireCsrf = true) ?: return@post
            adminUpdateIdvSettings(call)
        }
        get("/admin_idv_audit") {
            AdminAuth.requireSession(call, requireCsrf = false) ?: return@get
            adminIdvAudit(call)
        }
        get("/admin_action_log") {
            AdminAuth.requireSession(call, requireCsrf = false) ?: return@get
            adminActionLog(call)
        }

        // --- Admin: trust store (Component F "Trust") ---
        get("/admin_trust") {
            AdminAuth.requireSession(call, requireCsrf = false) ?: return@get
            adminTrustList(call)
        }
        post("/admin_trust") {
            AdminAuth.requireSession(call, requireCsrf = true) ?: return@post
            adminTrustUpload(call)
        }
        post("/admin_trust_delete") {
            AdminAuth.requireSession(call, requireCsrf = true) ?: return@post
            adminTrustDelete(call)
        }
        get("/admin_trust_test_csca") {
            AdminAuth.requireSession(call, requireCsrf = false) ?: return@get
            adminTrustDownloadTestCsca(call)
        }

        // --- Admin: personas (Component F "Personas") ---
        get("/admin_personas") {
            AdminAuth.requireSession(call, requireCsrf = false) ?: return@get
            adminPersonasList(call)
        }
        post("/admin_personas") {
            AdminAuth.requireSession(call, requireCsrf = true) ?: return@post
            adminPersonasUpload(call)
        }

        // --- Admin: admin accounts (Component F "Admin accounts") ---
        get("/admin_accounts") {
            AdminAuth.requireSession(call, requireCsrf = false) ?: return@get
            adminAccountsList(call)
        }
        post("/admin_accounts") {
            val session = AdminAuth.requireSession(call, requireCsrf = true) ?: return@post
            adminAccountsCreate(call, session.username)
        }
        post("/admin_accounts_delete") {
            val session = AdminAuth.requireSession(call, requireCsrf = true) ?: return@post
            adminAccountsDelete(call, session.username)
        }
        post("/admin_accounts_reset_totp") {
            val session = AdminAuth.requireSession(call, requireCsrf = true) ?: return@post
            adminAccountsResetTotp(call, session.username)
        }

        post("/idv/start") { idvStart(call) }
        post("/idv/evidence") { idvEvidence(call) }
        get("/idv/personas") { idvPersonas(call) }
        post("/idv/persona") { idvPersona(call) }
    }
}
