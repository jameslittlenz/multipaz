package org.multipaz.openid4vci.admin

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.storage.StorageTableSpec
import kotlin.time.Instant

/**
 * A hardened admin account (`docs/validatopia/PLAN.md`'s Component E): an Argon2id password hash
 * plus a mandatory TOTP secret, replacing the single shared `issuance_auth` password cookie.
 *
 * The table key is the username, lowercased, so lookups are case-insensitive without a secondary
 * index. [failedAttempts]/[lockedUntil] implement the increasing-delay lockout; see
 * `AdminAuth.kt`.
 */
@CborSerializable
data class AdminAccountRecord(
    val username: String,
    val passwordSalt: ByteString,
    val passwordHash: ByteString,
    val passwordMemoryKib: Int,
    val passwordIterations: Int,
    val passwordParallelism: Int,
    // Encrypted (via SimpleCipher, the same server key used for IssuanceState.systemOfRecordData)
    // TOTP shared secret. Unlike the password hash this is a symmetric secret that can be replayed
    // directly, so it is encrypted at rest rather than just stored as raw bytes.
    val totpSecretEncrypted: ByteString,
    val totpConfirmed: Boolean,
    val failedAttempts: Int = 0,
    val lockedUntil: Instant? = null,
    val createdAt: Instant,
) {
    companion object {
        private val tableSpec = StorageTableSpec(
            name = "AdminAccounts",
            supportPartitions = false,
            supportExpiration = false
        )

        private fun normalize(username: String) = username.trim().lowercase()

        suspend fun get(username: String): AdminAccountRecord? {
            val data = BackendEnvironment.getTable(tableSpec).get(normalize(username)) ?: return null
            return fromCbor(data.toByteArray())
        }

        suspend fun put(record: AdminAccountRecord) {
            val table = BackendEnvironment.getTable(tableSpec)
            val key = normalize(record.username)
            val data = ByteString(record.toCbor())
            if (table.get(key) == null) {
                table.insert(key = key, data = data)
            } else {
                table.update(key = key, data = data)
            }
        }

        suspend fun delete(username: String): Boolean =
            BackendEnvironment.getTable(tableSpec).delete(normalize(username))

        suspend fun list(): List<AdminAccountRecord> =
            BackendEnvironment.getTable(tableSpec).enumerateWithData()
                .map { (_, data) -> fromCbor(data.toByteArray()) }
                .sortedBy { it.username }

        suspend fun count(): Int = BackendEnvironment.getTable(tableSpec).enumerate().size
    }
}
