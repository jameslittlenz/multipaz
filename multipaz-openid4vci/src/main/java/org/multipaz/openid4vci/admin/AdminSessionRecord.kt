package org.multipaz.openid4vci.admin

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.Crypto
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.toBase64Url
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A server-side, revocable admin session (`docs/validatopia/PLAN.md`'s Component E: "Sessions are
 * server-side and revocable"). The cookie carries only the opaque [id] returned by [create]; the
 * session itself, including the [csrfToken] issued alongside it, lives here so it can be looked up
 * and deleted (logout, lockout-triggered revocation, account removal) independent of the cookie.
 *
 * The table isn't partitioned by username because the cookie value is the only thing available
 * when validating a session — there's no username to partition on until after the lookup.
 * [revokeAllForUser] instead does a bounded scan; this is a small admin-only table, not one that
 * needs to scale past a handful of concurrent sessions per account.
 */
@CborSerializable
data class AdminSessionRecord(
    val username: String,
    val csrfToken: String,
    val createdAt: Instant,
) {
    companion object {
        private val tableSpec = StorageTableSpec(
            name = "AdminSessions",
            supportPartitions = false,
            supportExpiration = true
        )

        suspend fun create(username: String, ttlSeconds: Long): Pair<String, AdminSessionRecord> {
            val record = AdminSessionRecord(
                username = username,
                csrfToken = Crypto.secureRandom.nextBytes(24).toBase64Url(),
                createdAt = Clock.System.now(),
            )
            val sessionId = BackendEnvironment.getTable(tableSpec).insert(
                key = null,
                data = ByteString(record.toCbor()),
                expiration = record.createdAt + ttlSeconds.seconds
            )
            return Pair(sessionId, record)
        }

        suspend fun get(sessionId: String): AdminSessionRecord? {
            val data = BackendEnvironment.getTable(tableSpec).get(sessionId) ?: return null
            return fromCbor(data.toByteArray())
        }

        suspend fun revoke(sessionId: String) {
            BackendEnvironment.getTable(tableSpec).delete(sessionId)
        }

        suspend fun revokeAllForUser(username: String) {
            val table = BackendEnvironment.getTable(tableSpec)
            val normalized = username.trim().lowercase()
            for ((id, data) in table.enumerateWithData()) {
                if (fromCbor(data.toByteArray()).username == normalized) {
                    table.delete(id)
                }
            }
        }
    }
}
