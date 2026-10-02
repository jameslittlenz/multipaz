package org.multipaz.idv.backend.settings

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.openid4vci.idv.IdvSettingsData
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.storage.StorageTableSpec

/**
 * Runtime-editable Validatopia IDV settings (see `docs/validatopia/PLAN.md`'s Component C),
 * stored as a single row so admin edits persist across restarts.
 */
@CborSerializable
data class IdvSettingsRecord(
    val faceMatchThreshold: Double = DEFAULT_FACE_MATCH_THRESHOLD,
    val requireActiveAuth: Boolean = false,
    val acceptUntrustedCsca: Boolean = false,
    val offerTtlSeconds: Long = 300,
    val photoIdValidityDays: Long = 730,
    val dataRetentionDays: Long = 30,
    val dummyIssuanceEnabled: Boolean = true,
    /**
     * Whether `/idv/start` and `/idv/evidence` accept real passports. `null` (the value in rows
     * saved before this setting existed) means off: the passport path stays closed until an admin
     * opens it, since a weak face matcher would let anyone with a chip read impersonate its holder.
     */
    val passportIssuanceEnabled: Boolean? = null,
) {
    companion object {
        // idv_require_active_auth stays off by default: multipaz-idv's Active Auth verifier
        // hasn't been built yet (deferred in M1).

        // SFace cosine similarity. OpenCV suggests 0.363, but the two placeholder persona
        // portraits, different people, score 0.363 against each other. Until the threshold is
        // calibrated on real passport-and-selfie pairs, err towards false rejects.
        const val DEFAULT_FACE_MATCH_THRESHOLD = 0.5

        private val tableSpec = StorageTableSpec(
            name = "IdvSettings",
            supportPartitions = false,
            supportExpiration = false
        )
        private const val KEY = "settings"

        suspend fun get(): IdvSettingsRecord {
            val data = BackendEnvironment.getTable(tableSpec).get(KEY) ?: return IdvSettingsRecord()
            return fromCbor(data.toByteArray())
        }

        suspend fun update(settings: IdvSettingsRecord): IdvSettingsRecord {
            val table = BackendEnvironment.getTable(tableSpec)
            val data = ByteString(settings.toCbor())
            if (table.get(KEY) == null) {
                table.insert(key = KEY, data = data)
            } else {
                table.update(key = KEY, data = data)
            }
            return settings
        }
    }
}

fun IdvSettingsRecord.toData(): IdvSettingsData = IdvSettingsData(
    faceMatchThreshold = faceMatchThreshold,
    requireActiveAuth = requireActiveAuth,
    acceptUntrustedCsca = acceptUntrustedCsca,
    offerTtlSeconds = offerTtlSeconds,
    photoIdValidityDays = photoIdValidityDays,
    dataRetentionDays = dataRetentionDays,
    dummyIssuanceEnabled = dummyIssuanceEnabled,
    passportIssuanceEnabled = passportIssuanceEnabled == true,
)

fun IdvSettingsData.toRecord(): IdvSettingsRecord = IdvSettingsRecord(
    faceMatchThreshold = faceMatchThreshold,
    requireActiveAuth = requireActiveAuth,
    acceptUntrustedCsca = acceptUntrustedCsca,
    offerTtlSeconds = offerTtlSeconds,
    photoIdValidityDays = photoIdValidityDays,
    dataRetentionDays = dataRetentionDays,
    dummyIssuanceEnabled = dummyIssuanceEnabled,
    passportIssuanceEnabled = passportIssuanceEnabled,
)
