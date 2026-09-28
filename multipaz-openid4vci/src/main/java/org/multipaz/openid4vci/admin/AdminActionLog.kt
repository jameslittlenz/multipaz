package org.multipaz.openid4vci.admin

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.Crypto
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.toBase64Url
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Records every admin-account action: logins (success, failure, lockout), logouts, account
 * creation/removal, TOTP resets, credential revocation/deletion and portrait reveals.
 *
 * This is separate from `IssuanceAuditRecord`/`AdminAuditRecord` in `multipaz-idv-backend`, which
 * cover Validatopia-specific identity-proofing and settings actions: those two modules are only
 * usable from `multipaz-idv-backend` (idv-backend depends on this module, not the other way
 * around), while admin accounts/sessions are core `multipaz-openid4vci` infrastructure used by
 * every server profile. The admin website's audit log page displays both.
 */
@CborSerializable
data class AdminActionLogRecord(
    val timestamp: Instant,
    val username: String?,
    val action: String,
    val detail: String,
) {
    companion object {
        private val tableSpec = StorageTableSpec(
            name = "AdminActionLog",
            supportPartitions = false,
            supportExpiration = false
        )

        suspend fun record(username: String?, action: String, detail: String) {
            val record = AdminActionLogRecord(Clock.System.now(), username, action, detail)
            BackendEnvironment.getTable(tableSpec).insert(
                key = sortableKey(record.timestamp),
                data = ByteString(record.toCbor())
            )
        }

        suspend fun list(afterId: String? = null, limit: Int = 100): List<Pair<String, AdminActionLogRecord>> =
            BackendEnvironment.getTable(tableSpec)
                .enumerateWithData(afterKey = afterId, limit = limit)
                .map { (id, data) -> Pair(id, fromCbor(data.toByteArray())) }

        // Mirrors org.multipaz.idv.backend.audit.sortableKey: a key that sorts lexicographically
        // in timestamp order, since StorageTable.enumerate's ordering is lexicographic-by-key.
        private fun sortableKey(timestamp: Instant): String {
            val millis = timestamp.toEpochMilliseconds().coerceAtLeast(0)
            val randomSuffix = Crypto.secureRandom.nextBytes(4).toBase64Url()
            return "${millis.toString().padStart(20, '0')}-$randomSuffix"
        }
    }
}
