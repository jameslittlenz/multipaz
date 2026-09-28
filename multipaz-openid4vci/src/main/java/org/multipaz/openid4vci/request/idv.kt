package org.multipaz.openid4vci.request

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import org.multipaz.openid4vci.idv.IdentityProofing
import org.multipaz.rpc.backend.BackendEnvironment

/**
 * Returns the registered [IdentityProofing], or responds 404 and returns `null` if this server
 * profile doesn't have identity proofing configured (i.e. isn't running the Validatopia profile).
 *
 * Every `/idv` and `/admin_idv_*` handler starts with this, per
 * `docs/validatopia/PLAN.md`'s Component D: "They return 404 when IDV is disabled."
 */
suspend fun identityProofingOrNotFound(call: ApplicationCall): IdentityProofing? {
    val identityProofing = BackendEnvironment.getInterface(IdentityProofing::class)
    if (identityProofing == null) {
        call.respondText(status = HttpStatusCode.NotFound, text = "")
    }
    return identityProofing
}
