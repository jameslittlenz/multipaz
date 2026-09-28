package org.multipaz.openid4vci.util

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.storage.StorageTableSpec
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * A short-lived session created by `/idv/start`, binding a subsequent `/idv/evidence` request to
 * the client that started it.
 */
@CborSerializable
data class IdvSession(val clientId: String) {
    companion object {
        private val tableSpec = StorageTableSpec(
            name = "IdvSession",
            supportPartitions = false,
            supportExpiration = true
        )

        suspend fun create(clientId: String, ttl: Duration): String =
            BackendEnvironment.getTable(tableSpec).insert(
                key = null,
                data = ByteString(IdvSession(clientId).toCbor()),
                expiration = Clock.System.now() + ttl
            )

        suspend fun get(sessionId: String): IdvSession? =
            BackendEnvironment.getTable(tableSpec).get(sessionId)?.let { fromCbor(it.toByteArray()) }

        suspend fun consume(sessionId: String) {
            BackendEnvironment.getTable(tableSpec).delete(sessionId)
        }
    }
}
