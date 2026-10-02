package org.multipaz.openid4vci.util

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

private const val PAGE_SIZE = 100

/**
 * Deletes [IssuanceState.systemOfRecordData] (for Validatopia: the portrait, SOD, DG1 and DG2
 * from identity proofing) from every issuance session authorized more than [retention] before
 * [now]. Credentials already minted stay valid, but those sessions can no longer refresh them.
 *
 * @return the number of sessions whose data was deleted.
 */
suspend fun purgeRetainedSystemOfRecordData(
    retention: Duration,
    now: Instant = Clock.System.now(),
): Int {
    val cutoff = now - retention
    var purged = 0
    var afterId: String? = null
    while (true) {
        val page = IssuanceState.listIssuanceStates(afterId = afterId, limit = PAGE_SIZE)
        for ((id, state) in page) {
            if (state.systemOfRecordData == null) {
                continue
            }
            // A session holding data but no authorization time can't be aged, so it goes too.
            val authorized = state.authorized
            if (authorized == null || authorized < cutoff) {
                state.systemOfRecordData = null
                IssuanceState.updateIssuanceState(id, state, expiration = null)
                purged++
            }
        }
        if (page.size < PAGE_SIZE) {
            return purged
        }
        afterId = page.last().first
    }
}

/**
 * Deletes the system-of-record data of the issuance session [issuanceStateId], if the session
 * still exists and holds any.
 *
 * @return `true` if data was deleted.
 */
suspend fun deleteSystemOfRecordData(issuanceStateId: String): Boolean {
    val state = try {
        IssuanceState.getIssuanceState(issuanceStateId)
    } catch (_: IllegalStateException) {
        // The session has expired and been removed, and its data with it.
        return false
    }
    if (state.systemOfRecordData == null) {
        return false
    }
    state.systemOfRecordData = null
    IssuanceState.updateIssuanceState(issuanceStateId, state, expiration = null)
    return true
}
