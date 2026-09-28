package org.multipaz.idv.backend.audit

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.storage.StorageTableSpec
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Records every admin action: logins, setting changes, revocations and portrait reveals (see
 * `docs/validatopia/PLAN.md`'s Component C). Admin accounts and login itself are M3 work
 * (Component E); for now this is used by the admin settings endpoint added in M2.
 */
@CborSerializable
data class AdminAuditRecord(
    val timestamp: Instant,
    val action: String,
    val detail: String,
) {
    companion object {
        private val tableSpec = StorageTableSpec(
            name = "AdminAuditLog",
            supportPartitions = false,
            supportExpiration = false
        )

        suspend fun record(action: String, detail: String) {
            val record = AdminAuditRecord(Clock.System.now(), action, detail)
            BackendEnvironment.getTable(tableSpec).insert(
                key = sortableKey(record.timestamp),
                data = ByteString(record.toCbor())
            )
        }

        suspend fun list(afterId: String? = null, limit: Int = 100): List<Pair<String, AdminAuditRecord>> =
            BackendEnvironment.getTable(tableSpec)
                .enumerateWithData(afterKey = afterId, limit = limit)
                .map { (id, data) -> Pair(id, fromCbor(data.toByteArray())) }
    }
}
