package org.multipaz.idv.backend.audit

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.openid4vci.idv.IdvAuditEntry
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.storage.StorageTableSpec
import kotlin.time.Instant

/** How issuance was attempted, for [IssuanceAuditRecord.method]. */
enum class IssuanceMethod { PASSPORT, PERSONA }

/**
 * One row of the issuance audit log: never contains the selfie or the raw document number (see
 * `docs/validatopia/PLAN.md`'s Component C and privacy notes in Component E).
 */
@CborSerializable
data class IssuanceAuditRecord(
    val timestamp: Instant,
    // The name() of an IssuanceMethod value ("PASSPORT" or "PERSONA"); stored as a plain String
    // since @CborSerializable classes in this codebase don't embed enum-typed fields directly.
    val method: String,
    val accepted: Boolean,
    val nationality: String?,
    val maskedDocumentNumber: String?,
    val faceScore: Double?,
    val flags: List<String>,
    val sessionId: String?,
) {
    companion object {
        private val tableSpec = StorageTableSpec(
            name = "IssuanceAudit",
            supportPartitions = false,
            supportExpiration = false
        )

        suspend fun record(record: IssuanceAuditRecord): String =
            BackendEnvironment.getTable(tableSpec).insert(
                key = sortableKey(record.timestamp),
                data = ByteString(record.toCbor())
            )

        suspend fun list(afterId: String? = null, limit: Int = 100): List<Pair<String, IssuanceAuditRecord>> =
            BackendEnvironment.getTable(tableSpec)
                .enumerateWithData(afterKey = afterId, limit = limit)
                .map { (id, data) -> Pair(id, fromCbor(data.toByteArray())) }

        /** Masks all but the last 4 characters of a document number, for audit logging. */
        fun mask(documentNumber: String): String =
            if (documentNumber.length <= 4) {
                "*".repeat(documentNumber.length)
            } else {
                "*".repeat(documentNumber.length - 4) + documentNumber.takeLast(4)
            }
    }
}

fun IssuanceAuditRecord.toEntry(id: String): IdvAuditEntry = IdvAuditEntry(
    id = id,
    timestampEpochSeconds = timestamp.epochSeconds,
    method = method,
    accepted = accepted,
    nationality = nationality,
    maskedDocumentNumber = maskedDocumentNumber,
    faceScore = faceScore,
    flags = flags,
    sessionId = sessionId,
)
